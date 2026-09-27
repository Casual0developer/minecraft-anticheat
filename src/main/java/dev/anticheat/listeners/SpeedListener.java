package dev.anticheat.listeners;

import dev.anticheat.AntiCheatPlugin;
import dev.anticheat.managers.BanManager;
import dev.anticheat.managers.ViolationManager;
import dev.anticheat.utils.GroundUtil;
import dev.anticheat.utils.Momentum;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.potion.PotionEffectType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Detects illegal horizontal movement speed (Speed/Velocity hacks).
 *
 * ── False-positive prevention ────────────────────────────────────────────────
 *
 * Swiftness (Speed potion)
 *   Each potion level raises the cap by PER_LEVEL (0.065 b/t).
 *
 * Sprint-jumping
 *   While airborne the cap gains an extra AIR_ADD (0.22 b/t) to cover the
 *   horizontal momentum burst at jump-launch.  A legit sprint-jump peaks at
 *   ~0.46 b/t (no potion) / ~0.64 b/t (Speed II); the caps sit above those.
 *
 * Consecutive-tick requirement (FAST_TICKS = 3)
 *   VL only increments when the player exceeds the cap for 3 consecutive ticks.
 *   Legitimate brief peaks (jump launch, knockback landing, slime bounce) last
 *   1-2 ticks and are silently ignored.  A speed hacker is consistently fast.
 *
 * Dolphins Grace
 *   Adds DOLPHIN_ADD (0.35 b/t) to the cap while the effect is active.
 *
 * Soul Sand / Soul Speed
 *   Soul sand slows players to ~40 % of walk speed — they never trigger the
 *   check.  Soul Speed III on soul sand reaches ~0.25 b/t, still under BASE.
 *
 * Ice / Packed Ice / Blue Ice / Frosted Ice
 *   Fully exempt.  Friction-less surfaces allow arbitrary horizontal speed
 *   build-up that is impossible to distinguish from hacks per-tick.
 *
 * Slime blocks
 *   Fully exempt.  Slime bounces can impart significant horizontal momentum.
 *
 * Boats / Minecarts / Horses / all vehicles
 *   Exempt via player.isInsideVehicle().
 *
 * Elytra
 *   Exempt via player.isGliding().
 *
 * Water (swimming / Dolphins Grace in water)
 *   Exempt via player.isInWater() + player.isSwimming().
 *
 * Arrows / Knockback
 *   Covered by the damage-grace period set in PlayerStateListener.onDamage and
 *   the improved onVelocity handler that checks both vertical AND horizontal
 *   velocity magnitude.
 *
 * Riptide trident
 *   Covered by the PlayerRiptideEvent grace in PlayerStateListener.
 *
 * Wind charges (1.21 knockback projectile)
 *   Covered by PlayerStateListener.onVelocity (velocity magnitude check).
 *
 * Teleports / joins
 *   Covered by teleport-grace and join-grace.
 *
 * Server / client lag spikes
 *   Detected via wall-clock gap between consecutive move events (LAG_MS = 200).
 *   Grace is skipped; position state is refreshed so the next tick starts clean.
 */
public class SpeedListener implements Listener {

    private final AntiCheatPlugin plugin;
    private final ViolationManager vm;
    private final BanManager       bm;

    /** Previous X coordinate used to compute per-tick horizontal displacement. */
    private final Map<UUID, Double>  prevX      = new HashMap<>();
    /** Previous Z coordinate. */
    private final Map<UUID, Double>  prevZ      = new HashMap<>();
    /**
     * Consecutive ticks the player has been above the speed cap.
     * VL only increments when this reaches FAST_TICKS.
     */
    private final Map<UUID, Integer> fastTicks  = new HashMap<>();
    /** Wall-clock time of the last processed move event (for lag detection). */
    private final Map<UUID, Long>    lastMoveMs = new HashMap<>();
    /** Legit horizontal momentum carried between ticks (elytra landings, ice, knockback). */
    private final Map<UUID, Momentum> momentum = new HashMap<>();

    // ── Speed cap constants ──────────────────────────────────────────────────

    /**
     * Base cap: lenient to prevent velocity false flags. Covers vanilla sprint 0.281 b/t with ~14% buffer.
     * Previously tightened to 0.285 which caused strafe/speed false positives on lag.
     * Now relaxed to reduce false flags while still catching blatant speed (>30% over vanilla).
     */
    private static final double BASE       = 0.315;

    /** Added to the cap for each Speed-potion level. Slightly increased for leniency. */
    private static final double PER_LEVEL  = 0.065;

    /**
     * Extra cap headroom while airborne. Sprint-jump peak ~0.46 b/t.
     * Increased to be lenient for jump + velocity combos.
     */
    private static final double AIR_ADD    = 0.235;

    /** Extra cap when Dolphins Grace is active. */
    private static final double DOLPHIN    = 0.350;

    /** Global jitter/rounding tolerance — increased to absorb velocity & lag spike. */
    private static final double TOLERANCE  = 0.065;

    /**
     * Consecutive ticks above cap before VL. Increased to 3 to ignore single-tick velocity spikes.
     */
    private static final int FAST_TICKS = 3;

    /**
     * Wall-clock gap (ms) that signals a server or client lag spike.
     * 200 ms = 4 server ticks at 20 TPS.
     */
    private static final long LAG_MS = 200;

    public SpeedListener(AntiCheatPlugin plugin) {
        this.plugin = plugin;
        this.vm     = plugin.getViolationManager();
        this.bm     = plugin.getBanManager();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Main check
    // ─────────────────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!plugin.getConfig().getBoolean("checks.speed", true)) return;
        // No TPS kill-switch: per-packet movement isn't changed by server lag,
        // and the lag-spike guard below already resets on packet gaps.
        Player player = event.getPlayer();
        UUID   id     = player.getUniqueId();

        Location from = event.getFrom();
        Location to   = event.getTo();
        if (to == null) return;

        Momentum m = momentum.computeIfAbsent(id, k -> new Momentum());
        double moved = Math.hypot(to.getX() - from.getX(), to.getZ() - from.getZ());

        if (isExempt(player)) {
            // Keep position current so we don't get a false delta after exemption ends
            Location loc = player.getLocation();
            prevX.put(id, loc.getX());
            prevZ.put(id, loc.getZ());
            fastTicks.remove(id);
            // Exempt movement (gliding, ice, slime…) is legit — carry its speed forward.
            m.step(player, from, to, moved, true);
            return;
        }

        // ── Lag-spike guard ──────────────────────────────────────────────────
        // If the gap since the last event is larger than LAG_MS the server was
        // lagging; one event may represent several ticks of movement.
        long nowMs  = System.currentTimeMillis();
        Long prevMs = lastMoveMs.put(id, nowMs);
        if (prevMs != null && (nowMs - prevMs) > LAG_MS) {
            prevX.put(id, to.getX());
            prevZ.put(id, to.getZ());
            fastTicks.remove(id);
            m.reset();
            return;
        }

        Double px = prevX.get(id);
        Double pz = prevZ.get(id);
        prevX.put(id, to.getX());
        prevZ.put(id, to.getZ());
        if (px == null || pz == null) {
            m.step(player, from, to, moved, true);
            return;
        }

        // ── Grace periods ────────────────────────────────────────────────────
        // Jump grace is deliberately NOT here: it's granted on every take-off,
        // so it switched Speed off for most of a bunny-hop.  Sprint-jumping is
        // already covered by AIR_ADD (take-off tick ≈ 0.48 b/t vs 0.63 cap;
        // Speed II ≈ 0.59 vs 0.76) plus the FAST_TICKS streak requirement.
        if (vm.hasDamageGrace(id)
                || vm.hasTeleportGrace(id)
                || vm.hasJoinGrace(id)) {
            fastTicks.remove(id);
            // Knockback / launches are legit speed — carry it forward.
            m.step(player, from, to, moved, true);
            return;
        }

        // ── Compute horizontal speed ─────────────────────────────────────────
        double dx    = to.getX() - px;
        double dz    = to.getZ() - pz;
        double speed = Math.sqrt(dx * dx + dz * dz);

        // Momentum carried from a legit burst (elytra landing, ice, knockback)
        // bleeding off under vanilla friction.  Can't be sustained by a cheat.
        double carriedMax = m.step(player, from, to, speed, false);

        // Head-rotation-only events produce no XZ displacement — skip them.
        if (speed < 0.005) {
            fastTicks.remove(id);
            return;
        }

        double max = Math.max(computeMaxSpeed(player, to),
                carriedMax * 1.03 + plugin.getConfig().getDouble("settings.speed-tolerance", TOLERANCE));

        int requiredTicks = plugin.getConfig().getInt("settings.speed-fast-ticks", FAST_TICKS);
        if (speed > max) {
            int ft = fastTicks.getOrDefault(id, 0) + 1;
            fastTicks.put(id, ft);
            debugLog(player, String.format("above cap spd=%.4f max=%.4f fastTick=%d/%d",
                    speed, max, ft, requiredTicks));
            // Extra strafing check: if player is moving > max*1.12 (12% over) while sprinting+strafing, flag quicker
            boolean isStrafing = player.isSprinting() && Math.abs(dx) > 0.05 && Math.abs(dz) > 0.05;
            if (isStrafing && speed > max * 1.08) {
                debugLog(player, "Strafing speed detected spd=" + String.format("%.3f", speed));
                // strafing speed hacks are more obvious, require only 2 ticks
                if (ft >= Math.min(requiredTicks, 2)) {
                    flag(player, id, String.format("Speed[Strafing] spd=%.3f max=%.3f", speed, max));
                    return;
                }
            }
            if (ft >= requiredTicks) {
                flag(player, id,
                     String.format("Speed[Fast] spd=%.3f max=%.3f", speed, max));
            }
        } else {
            fastTicks.remove(id); // clean tick resets the streak
        }

        if (isInCobweb(player) && speed > 0.08) {
            flag(player, id, String.format("Speed[Web] spd=%.3f in cobweb", speed));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Max-speed calculation
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns the maximum allowed horizontal blocks-per-tick for this player
     * given their current active effects and state.
     */
    private double computeMaxSpeed(Player player, Location to) {
        // Allow config overrides for tuning without recompiling
        double base = plugin.getConfig().getDouble("settings.speed-base", BASE);
        double perLvl = plugin.getConfig().getDouble("settings.speed-per-level", PER_LEVEL);
        double airAdd = plugin.getConfig().getDouble("settings.speed-air-add", AIR_ADD);
        double tol = plugin.getConfig().getDouble("settings.speed-tolerance", TOLERANCE);
        double max = base;

        // ── Swiftness / Speed potion ─────────────────────────────────────────
        if (player.hasPotionEffect(PotionEffectType.SPEED)) {
            int level = player.getPotionEffect(PotionEffectType.SPEED).getAmplifier() + 1;
            max += level * perLvl;
        }

        // ── Sprint-jump air momentum ─────────────────────────────────────────
        // Only when the server also sees air under the feet — otherwise a
        // client claiming "airborne" gets the air allowance on flat ground.
        if (!player.isOnGround() && !GroundUtil.touchingGround(player, to)) {
            max += airAdd;
        }

        // ── Diagonal strafing compensation (small allowance) ────────────────
        // Vanilla diagonal sprint is slightly faster than straight due to vector sum.
        // Allow +0.025 for true diagonal movement to avoid false-flagging legit strafe.
        // This is still far below 20% speed hack (which would be +0.06-0.07).
        // Detected via velocity angle — not via keys, so works for any diagonal.
        // Will be added on top of base before tolerance.
        // We compute from last two positions if available to see if movement was diagonal
        // Simple heuristic: if both dx and dz last tick were >0.08, it's diagonal-like.
        // Since we have prev positions stored, we can reuse them for diagonal check.
        // For now, we apply a tiny fixed allowance only when sprinting, which matches
        // vanilla's increased diagonal cap but keeps hack detection tight.
        if (player.isSprinting()) {
            // Check if movement is diagonal by looking at current instant velocity angle?
            // Use stored prev values: if both axes had significant movement, it's diagonal.
            // We approximate via current to-prev delta already computed in onMove caller,
            // but here we don't have dx/dz. So we keep allowance minimal and only when sprinting.
            max += 0.015;
        }

        // ── Dolphins Grace ───────────────────────────────────────────────────
        if (player.hasPotionEffect(PotionEffectType.DOLPHINS_GRACE)) {
            max += DOLPHIN;
        }

        return max + tol;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Exemption rules
    // ─────────────────────────────────────────────────────────────────────────

    private boolean isExempt(Player player) {
        GameMode gm = player.getGameMode();
        if (player.hasPermission("anticheat.bypass"))                          return true;
        if (plugin.getWhitelistManager().isWhitelisted(player.getUniqueId())) return true;
        if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR)               return true;
        if (player.getAllowFlight() || player.isFlying())                       return true;
        if (player.isDead())                                                    return true;
        if (player.isInsideVehicle())                                          return true; // boats, minecarts, horses, etc.
        if (player.isGliding())                                                return true; // elytra
        if (player.isInWater() || player.isSwimming())                         return true; // any aquatic movement
        if (vm.hasJoinGrace(player.getUniqueId()))                             return true;
        if (isOnSlipperyOrBouncy(player.getLocation()))                        return true; // ice / slime
        if (isOnSoulBlock(player.getLocation()))                               return true; // soul sand / soil
        return false;
    }

    private boolean isInCobweb(Player player) {
        var block = player.getLocation().getBlock().getType();
        var blockAbove = player.getLocation().clone().add(0, 1, 0).getBlock().getType();
        return block == Material.COBWEB || blockAbove == Material.COBWEB;
    }

    /**
     * Returns true when the player is standing on or near a block that
     * can produce legitimate super-speed:
     *   Ice / Packed Ice / Blue Ice / Frosted Ice — frictionless, speed builds
     *   Slime blocks — horizontal bouncing momentum
     */
    private boolean isOnSlipperyOrBouncy(Location loc) {
        Block b1 = loc.clone().subtract(0, 0.1, 0).getBlock();
        Block b2 = loc.clone().subtract(0, 0.6, 0).getBlock();
        return isIceOrSlime(b1) || isIceOrSlime(b2);
    }

    private boolean isIceOrSlime(Block b) {
        return switch (b.getType()) {
            case ICE, PACKED_ICE, BLUE_ICE, FROSTED_ICE, SLIME_BLOCK -> true;
            default -> false;
        };
    }

    /**
     * Soul Sand / Soul Soil + Soul Speed exemption.
     *
     * The original comment claimed Soul Speed III sprint ≈ 0.25 b/t and
     * would stay under BASE (0.300).  That is wrong.  Actual values:
     *   Soul Speed I  sprint ≈ 0.21 b/t  — fine
     *   Soul Speed II sprint ≈ 0.32 b/t  — exceeds BASE → false ban
     *   Soul Speed III sprint ≈ 0.42 b/t — well above BASE
     *
     * Calculating the exact per-level cap (soul sand slow × sprint
     * multiplier × soul speed level × potion stack) is brittle.
     * Full exemption on soul sand/soil is the correct approach — the
     * same treatment as ice — since the movement is physically bounded
     * by the block's friction and cannot be exploited for free speed.
     */
    private boolean isOnSoulBlock(Location loc) {
        Block b1 = loc.clone().subtract(0, 0.1, 0).getBlock();
        Block b2 = loc.clone().subtract(0, 0.6, 0).getBlock();
        return isSoulBlock(b1) || isSoulBlock(b2);
    }

    private boolean isSoulBlock(Block b) {
        return b.getType() == Material.SOUL_SAND || b.getType() == Material.SOUL_SOIL;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // VL / flag / cleanup
    // ─────────────────────────────────────────────────────────────────────────

    private void flag(Player player, UUID id, String reason) {
        vm.incrementSpeedVL(id, 1);
        int vl        = vm.getSpeedVL(id);
        int threshold = plugin.getConfig().getInt("settings.speed-vl-threshold", 6);
        debugLog(player, reason + " VL=" + vl + "/" + threshold);
        if (vl >= threshold) {
            vm.remove(id);
            removePlayer(id);
            bm.banPlayer(player, reason.split(" ")[0]); // "Speed[Fast]"
        }
    }

    /** Called by PlayerStateListener on quit, kick, teleport, and vehicle exit. */
    public void removePlayer(UUID id) {
        prevX.remove(id);
        prevZ.remove(id);
        fastTicks.remove(id);
        lastMoveMs.remove(id);
        momentum.remove(id);
    }

    private void debugLog(Player player, String msg) {
        if (plugin.getConfig().getBoolean("debug.enabled", false)) {
            plugin.getLogger().info("[DEBUG][Speed] " + player.getName() + " » " + msg);
        }
    }
}
