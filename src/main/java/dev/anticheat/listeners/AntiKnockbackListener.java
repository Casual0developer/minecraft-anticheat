package dev.anticheat.listeners;

import dev.anticheat.AntiCheatPlugin;
import dev.anticheat.managers.BanManager;
import dev.anticheat.managers.ViolationManager;
import dev.anticheat.managers.WhitelistManager;
import dev.anticheat.utils.GroundUtil;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffectType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * AntiKnockbackListener — detects knockback cancellation hacks.
 *
 * Every melee hit applies a horizontal velocity burst to the victim.
 * Vanilla physics require the victim to move at least MIN_DISPLACEMENT blocks
 * in some XZ direction within WINDOW_TICKS_BASE ticks after the hit.
 *
 * Detection approach:
 *  1. onDamage records the hit with a sequence number and schedules evaluation.
 *  2. onMove ONLY accumulates XZ displacement — no evaluation.
 *  3. The scheduled task always fires and evaluates, regardless of movement.
 *     This is critical: AntiKB players send zero position updates, so relying
 *     on PlayerMoveEvent would only fire when they rotate their camera.
 *  4. Each record carries a sequence number so rapid multi-hit scenarios don't
 *     cause the wrong record to be evaluated by an earlier scheduler task.
 *
 * False-positive guards (at record time AND re-checked at evaluation time):
 *  • Sneak at an edge      — sneaking doesn't reduce knockback, but it stops the
 *                            player being pushed off a block edge; skipped only
 *                            when there's no ground in the knockback direction
 *  • High ping             — window is extended by the victim's ping (up to 8t)
 *                            so their knockback movement has time to arrive
 *  • Walk-back after hit   — the furthest displacement during the window counts,
 *                            not the final net, so knocked-then-returned is legit
 *  • Solid wall nearby     — wall in KB direction absorbs the push
 *  • Resistance potion     — legitimately reduces knockback
 *  • In water / climbable  — velocity dampened by medium
 *  • Same XZ position      — direction undefined, skip
 *  • KB enchant > 10       — admin item, too extreme to measure reliably
 *  • Teleport / join grace — skip
 *  • Creative / Spectator  — exempt
 *  • Two consecutive hits  — vl >= 2 before ban, absorbs single-measurement noise
 */
public class AntiKnockbackListener implements Listener {

    private final AntiCheatPlugin  plugin;
    private final BanManager       bm;
    private final ViolationManager vm;
    private final WhitelistManager wm;

    // ── Per-hit tracking record ───────────────────────────────────────────────

    private static final class KBRecord {
        final long   seq;        // sequence number — matches hitSeq at record time
        final double dirX;       // normalised XZ knockback direction
        final double dirZ;
        final boolean edgeHold;  // sneaking at an edge in the KB direction when hit
        double       accumX = 0; // XZ displacement accumulated since hit
        double       accumZ = 0;
        double       maxDisp = 0; // furthest the accumulated displacement got during the window

        KBRecord(long seq, double dirX, double dirZ, boolean edgeHold) {
            this.seq      = seq;
            this.dirX     = dirX;
            this.dirZ     = dirZ;
            this.edgeHold = edgeHold;
        }
    }

    /** Active pending record per player (latest hit only). */
    private final Map<UUID, KBRecord> pending = new HashMap<>();

    /**
     * Hit sequence counter per player.
     * Incremented on every new hit so that an earlier scheduler task can detect
     * that a newer hit has overwritten the record and bail out immediately.
     */
    private final Map<UUID, Long> hitSeq = new HashMap<>();

    /**
     * Consecutive zero-KB hits.  Incremented when a window closes with
     * displacement below MIN_DISPLACEMENT.  Reset on any legit hit.
     * Ban fires at VL >= 2 so a single bad measurement doesn't instant-ban.
     */
    private final Map<UUID, Integer> kbVL = new HashMap<>();

    // ── Constants ─────────────────────────────────────────────────────────────

    /**
     * Base window in ticks before evaluation fires (no KB enchant).
     * Increased to 10 ticks (500ms) to be lenient for lag/ping and velocity jitter.
     * Previous 8t caused false flags on 150ms ping.
     */
    private static final long   WINDOW_TICKS_BASE   = 10L;

    /** Extra ticks per Knockback enchant level (KB I → 12t, KB II → 16t). */
    private static final long   WINDOW_TICKS_PER_KB = 4L;

    /** Ping counted toward extending the window (400 ms → +8 ticks max). */
    private static final int    MAX_PING_MS_FOR_WINDOW = 400;

    /** Consecutive zero-knockback readings before a ban. */
    private static final int    ZERO_VL_THRESHOLD = 2;

    /**
     * Skip entirely for KB level above this — admin/creative items launch
     * the player so far that packets won't arrive within any sane window.
     */
    private static final int    KB_SKIP_THRESHOLD   = 10;

    /**
     * Minimum XZ displacement expected after a hit.
     * Vanilla base KB pushes ~0.35 blocks net over 8 ticks.
     * Relaxed for velocity false flags: now 0.12/0.18 (lenient) vs previous strict 0.28/0.32 which flagged legit lag.
     * Configurable via settings.antikb-min-displacement to tighten if needed.
     */
    private static final double DEFAULT_MIN_DISPLACEMENT    = 0.12;
    private static final double DEFAULT_PARTIAL_DISPLACEMENT = 0.18;
    private static final int DEFAULT_PARTIAL_VL_THRESHOLD  = 2;

    // ─────────────────────────────────────────────────────────────────────────

    public AntiKnockbackListener(AntiCheatPlugin plugin) {
        this.plugin = plugin;
        this.bm     = plugin.getBanManager();
        this.vm     = plugin.getViolationManager();
        this.wm     = plugin.getWhitelistManager();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Record hit — schedule evaluation regardless of player movement
    // ─────────────────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!plugin.getConfig().getBoolean("checks.antiknockback", true)) return;
        if (dev.anticheat.utils.TPSUtil.isSeverelyLagging()) return;
        // Melee only — this measures melee knockback.  Thorns procs report the
        // Thorns wearer as the "attacker", which isn't a knockback hit to measure.
        if (event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) return;
        if (!(event.getEntity()  instanceof Player      victim))   return;
        if (!(event.getDamager() instanceof LivingEntity attacker)) return;
        if (event.getFinalDamage() <= 0) return; // absorbed entirely — no knockback

        UUID id = victim.getUniqueId();
        if (isExempt(victim)) return;

        // Sneaking is NOT skipped here (it used to be, which let any AntiKB
        // cheat just hold shift).  Sneak-at-an-edge is handled at evaluation.

        // Water / climbable dampens velocity
        if (victim.isInWater() || isOnClimbable(victim)) return;

        // Cobwebs absorb all knockback — skip
        if (isInCobweb(victim)) return;

        // Resistance legitimately reduces knockback
        if (victim.hasPotionEffect(PotionEffectType.RESISTANCE)) return;

        // KB enchant on attacker's weapon
        int kbLevel = 0;
        if (attacker instanceof Player attackerPlayer) {
            ItemStack weapon = attackerPlayer.getInventory().getItemInMainHand();
            if (weapon != null && weapon.containsEnchantment(Enchantment.KNOCKBACK)) {
                kbLevel = weapon.getEnchantmentLevel(Enchantment.KNOCKBACK);
            }
        }
        // Admin/creative item — velocity too extreme to measure
        if (kbLevel > KB_SKIP_THRESHOLD) {
            debugLog(victim, "KB level " + kbLevel + " > " + KB_SKIP_THRESHOLD + " — skipping");
            return;
        }

        Location vLoc = victim.getLocation();
        Location aLoc = attacker.getLocation();
        double dx  = vLoc.getX() - aLoc.getX();
        double dz  = vLoc.getZ() - aLoc.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.01) return; // same XZ position — direction undefined

        // Assign a sequence number so we can detect stale scheduler tasks
        long seq = hitSeq.getOrDefault(id, 0L) + 1;
        hitSeq.put(id, seq);

        boolean edgeHold = isSneakEdgeHold(victim, dx / len, dz / len);
        KBRecord rec = new KBRecord(seq, dx / len, dz / len, edgeHold);
        pending.put(id, rec);

        // Extend the window by the victim's ping so a laggy player's knockback
        // movement has arrived before we measure it.
        long pingTicks = Math.min(Math.max(victim.getPing(), 0), MAX_PING_MS_FOR_WINDOW) / 50;
        long windowTicks = WINDOW_TICKS_BASE + (long) kbLevel * WINDOW_TICKS_PER_KB + pingTicks;
        debugLog(victim, "hit recorded seq=" + seq + " dir=(" + f(dx / len) + "," + f(dz / len)
                + ") kbLevel=" + kbLevel + " ping+" + pingTicks + "t window=" + windowTicks + "t"
                + (edgeHold ? " edgeHold" : ""));

        // Schedule evaluation.  Fires even if the player sends zero movement
        // packets — the key fix for standing-still AntiKB detection.
        //
        // NOTE: damageGrace is intentionally NOT re-checked at evaluation time.
        // It's active for 10 ticks after every hit — re-checking it here would
        // silently skip every single evaluation.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            // Sequence guard: if a newer hit came in, this task is stale — bail
            Long currentSeq = hitSeq.get(id);
            if (currentSeq == null || currentSeq != seq) return;

            KBRecord r = pending.remove(id);
            if (r == null) return; // player quit during window

            Player p = Bukkit.getPlayer(id);
            if (p == null || !p.isOnline() || p.isDead()) return;

            GameMode gm = p.getGameMode();
            if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR) return;
            if (p.hasPermission("anticheat.bypass") || wm.isWhitelisted(id)) return;

            // Re-check these at evaluation time — state can change within the window
            if (r.edgeHold || isSneakEdgeHold(p, r.dirX, r.dirZ)) {
                debugLog(p, "sneaking at an edge in KB direction — skip");
                return;
            }
            if (p.isInWater() || isOnClimbable(p))     return;
            if (p.hasPotionEffect(PotionEffectType.RESISTANCE)) return;

            evaluateWindow(p, id, r);
        }, windowTicks);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Accumulate displacement each tick — evaluation done by scheduler above
    // ─────────────────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        UUID     id   = event.getPlayer().getUniqueId();
        Location from = event.getFrom();
        Location to   = event.getTo();
        if (to == null) return;

        KBRecord rec = pending.get(id);
        if (rec == null) return;

        // Only accumulate actual XZ position change, not rotation-only packets
        rec.accumX += to.getX() - from.getX();
        rec.accumZ += to.getZ() - from.getZ();
        rec.maxDisp = Math.max(rec.maxDisp, Math.sqrt(rec.accumX * rec.accumX + rec.accumZ * rec.accumZ));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Evaluate at window close
    // ─────────────────────────────────────────────────────────────────────────

    private void evaluateWindow(Player player, UUID id, KBRecord rec) {
        double minDisp = plugin.getConfig().getDouble("settings.antikb-min-displacement", DEFAULT_MIN_DISPLACEMENT);
        double partialDisp = plugin.getConfig().getDouble("settings.antikb-partial-displacement", DEFAULT_PARTIAL_DISPLACEMENT);
        int partialThresh = plugin.getConfig().getInt("settings.antikb-partial-vl", DEFAULT_PARTIAL_VL_THRESHOLD);
        // Furthest point reached, not the final net: a player knocked back who
        // then sprints back toward the attacker can end the window near zero.
        double disp = rec.maxDisp;
        double net  = Math.sqrt(rec.accumX * rec.accumX + rec.accumZ * rec.accumZ);
        debugLog(player, "KB eval disp=" + f(disp) + " net=" + f(net) + " min=" + f(minDisp) + " partial=" + f(partialDisp));

        if (isInCobweb(player)) {
            debugLog(player, "disp=" + f(disp) + " in cobweb — skip");
            kbVL.remove(id);
            return;
        }

        // Wall in KB direction absorbs push — not a hack
        if (hasSolidWall(player.getLocation(), rec.dirX, rec.dirZ)) {
            debugLog(player, "disp=" + f(disp) + " wall in KB dir — skip");
            kbVL.remove(id);
            return;
        }

        if (disp >= minDisp) {
            if (disp < partialDisp) {
                int pvl = kbVL.getOrDefault(id, 0) + 1;
                kbVL.put(id, pvl);
                debugLog(player, "AntiKnockback[Partial] disp=" + f(disp) + " vl=" + pvl + "/" + partialThresh + " (10-20% reduction)");
                if (pvl >= partialThresh) {
                    kbVL.remove(id);
                    hitSeq.remove(id);
                    bm.banPlayer(player, "AntiKnockback[Partial] reduced KB over " + partialThresh + " hits");
                }
            } else {
                // Fully legit knockback (>= partial threshold) — reset
                if (kbVL.containsKey(id)) debugLog(player, "KB legit disp=" + f(disp) + " >= " + f(partialDisp) + " — reset");
                kbVL.remove(id);
            }
            return;
        }

        int vl = kbVL.getOrDefault(id, 0) + 1;
        kbVL.put(id, vl);
        debugLog(player, "AntiKnockback[Zero] VL=" + vl + "/" + ZERO_VL_THRESHOLD + " disp=" + f(disp) + " (<" + f(minDisp) + ")");

        // Two in a row — one reading can be a lag spike that delayed the
        // victim's movement packets past the window.
        if (vl >= ZERO_VL_THRESHOLD) {
            kbVL.remove(id);
            hitSeq.remove(id);
            bm.banPlayer(player, "AntiKnockback[Zero]");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * True when the player is sneaking on the ground and there's no ground
     * half a block further in the knockback direction.  Vanilla stops a
     * sneaking player from being pushed off an edge, so their knockback
     * legitimately comes out near zero there.
     */
    private boolean isSneakEdgeHold(Player player, double dirX, double dirZ) {
        if (!player.isSneaking()) return false;
        Location loc = player.getLocation();
        if (!GroundUtil.touchingGround(player, loc)) return false; // edge-hold only works on the ground
        return !GroundUtil.touchingGround(player, loc.clone().add(dirX * 0.5, 0, dirZ * 0.5));
    }

    /**
     * Checks for a solid block at feet or head height within 1.5 blocks in the
     * knockback direction. Starts at 0.2 blocks (not 0.4) to catch players who
     * are pressed very close to a wall when hit.
     */
    private boolean hasSolidWall(Location loc, double dirX, double dirZ) {
        for (double d = 0.2; d <= 1.5; d += 0.3) {
            int bx = (int) Math.floor(loc.getX() + dirX * d);
            int by = loc.getBlockY();
            int bz = (int) Math.floor(loc.getZ() + dirZ * d);
            if (loc.getWorld().getBlockAt(bx, by,     bz).getType().isSolid()) return true;
            if (loc.getWorld().getBlockAt(bx, by + 1, bz).getType().isSolid()) return true;
        }
        return false;
    }

    private boolean isInCobweb(Player player) {
        var block = player.getLocation().getBlock().getType();
        var blockAbove = player.getLocation().clone().add(0, 1, 0).getBlock().getType();
        return block == org.bukkit.Material.COBWEB || blockAbove == org.bukkit.Material.COBWEB;
    }

    private boolean isOnClimbable(Player player) {
        return switch (player.getLocation().getBlock().getType()) {
            case LADDER, VINE, SCAFFOLDING,
                 TWISTING_VINES, TWISTING_VINES_PLANT,
                 WEEPING_VINES,  WEEPING_VINES_PLANT,
                 CAVE_VINES,     CAVE_VINES_PLANT -> true;
            default -> false;
        };
    }

    private boolean isExempt(Player player) {
        GameMode gm = player.getGameMode();
        return player.hasPermission("anticheat.bypass")
                || wm.isWhitelisted(player.getUniqueId())
                || gm == GameMode.CREATIVE
                || gm == GameMode.SPECTATOR
                || player.isDead()
                || player.isInsideVehicle()
                || vm.hasJoinGrace(player.getUniqueId())
                || vm.hasTeleportGrace(player.getUniqueId());
        // damageGrace excluded — getting hit IS the trigger for this check
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Cleanup
    // ─────────────────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        removePlayer(event.getPlayer().getUniqueId());
    }

    public void removePlayer(UUID id) {
        pending.remove(id);
        kbVL.remove(id);
        hitSeq.remove(id);
    }

    /** Drops the in-flight hit (a teleport breaks its displacement), keeping VL. */
    public void resetMotion(UUID id) {
        pending.remove(id);
    }

    private void debugLog(Player player, String msg) {
        if (plugin.getConfig().getBoolean("debug.enabled", false))
            plugin.getLogger().info("[DEBUG][AntiKB] " + player.getName() + " » " + msg);
    }

    private String f(double d) { return String.format("%.3f", d); }
}
