package dev.anticheat.listeners;

import dev.anticheat.AntiCheatPlugin;
import dev.anticheat.managers.BanManager;
import dev.anticheat.managers.ViolationManager;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class ReachListener implements Listener {

    private final AntiCheatPlugin plugin;
    private final ViolationManager vm;
    private final BanManager bm;

    // Rate-limit flags per player to avoid spam from lag bursts
    private final Map<UUID, Long> lastFlagTime = new HashMap<>();

    // Vanilla reach: survival = 3.0, creative = 5.0
    private static final double MAX_SURVIVAL_REACH = 3.2;  // 3.0 + 0.2 lag buffer
    private static final double MAX_CREATIVE_REACH = 5.2;
    private static final double INSTANT_BAN_REACH  = 6.5;  // blatant — instant ban
                                                            // raised from 5.5: max allowed reach with full buffers
                                                            // (3.2 + 0.6 ping + 0.75 spear = 4.55), so 6.5 gives
                                                            // ~2 blocks of headroom against lag/desync
    private static final long   FLAG_COOLDOWN_MS   = 100;  // 100ms between flags — allows fast VL accumulation on fluctuating reach (e.g. 5.0→4.6→5.0→4.1)
    /**
     * Extra reach allowance when the attacker is holding a spear or trident.
     * Spears/tridents are long-shaft weapons; their melee hit-registration
     * can legitimately occur slightly further from the player's eye than a
     * sword or axe would.  This does NOT exempt them — a reach hacker using
     * a spear still gets banned once they exceed vanilla + this buffer.
     */
    private static final double SPEAR_REACH_BONUS  = 0.75;

    public ReachListener(AntiCheatPlugin plugin) {
        this.plugin = plugin;
        this.vm = plugin.getViolationManager();
        this.bm = plugin.getBanManager();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (!plugin.getConfig().getBoolean("checks.reach", true)) return;
        if (dev.anticheat.utils.TPSUtil.isSeverelyLagging()) return;
        // Melee only.  Thorns damage reports the Thorns WEARER as the damager, so
        // without this, shooting a Thorns wearer from range got THEM a Reach ban.
        if (event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) return;
        if (!(event.getDamager() instanceof Player attacker)) return;
        Entity victim = event.getEntity();
        UUID id = attacker.getUniqueId();

        if (isExempt(attacker)) return;
        if (!(victim instanceof LivingEntity)) return;

        // Rate-limit flags
        long now = System.currentTimeMillis();
        Long lastFlag = lastFlagTime.get(id);
        if (lastFlag != null && now - lastFlag < FLAG_COOLDOWN_MS) return;

        // ── Distance calculation ───────────────────────────────────────────
        double distance = getEyeToHitboxDistance(attacker, victim);

        double maxReach = attacker.getGameMode() == GameMode.CREATIVE
                ? MAX_CREATIVE_REACH : MAX_SURVIVAL_REACH;

        // Static config buffer (operator-tunable, default 0)
        double configBuffer = plugin.getConfig().getDouble("settings.reach-lag-buffer", 0.0);
        maxReach += configBuffer;

        // Dynamic ping compensation.
        // When a chasing attacker has latency their client interpolates the
        // victim's position ahead of where the server thinks it is.  A
        // legitimate hit at the edge of reach on a sprinting-away victim can
        // therefore register slightly over the cap server-side.
        //
        // Formula: ping_ms / 1000 * 4.0 b/s (conservative sprint estimate).
        //   50 ms →  0.20 b     150 ms →  0.60 b
        //  100 ms →  0.40 b     200 ms → capped at 150 ms (0.60 b)
        //
        // Cap at 150 ms so high-ping players cannot exploit this to reach hack.
        int pingMs = Math.min(attacker.getPing(), 150);
        double pingBuffer = (pingMs / 1000.0) * 4.0;
        maxReach += pingBuffer;

        // Spear / trident bonus — long-shaft weapons register hits slightly
        // further from the eye than short weapons.  Not an exemption: a reach
        // hacker using a spear still gets banned once they exceed this cap.
        if (isHoldingSpear(attacker)) {
            maxReach += SPEAR_REACH_BONUS;
            debugLog(attacker, "SpearBonus applied, newMax=" + fmt(maxReach));
        }

        // ── Attribute compensation ────────────────────────────────────────
        // PLAYER_ENTITY_INTERACTION_RANGE allows players to legitimately hit
        // entities further away.  Servers with admin attributes (range 50, etc.)
        // would otherwise false-flag every hit.  We read the actual attribute
        // and add it on top of vanilla reach.
        AttributeInstance interactionRange = attacker.getAttribute(Attribute.PLAYER_ENTITY_INTERACTION_RANGE);
        if (interactionRange != null) {
            double attrRange = interactionRange.getValue();
            if (attrRange > 3.0) {
                maxReach += (attrRange - 3.0);
                debugLog(attacker, "AttributeRange=" + fmt(attrRange) + "b, newMax=" + fmt(maxReach));
            }
        }

        // Small fast-moving entity buffer.  Chickens (0.4 × 0.7), bats,
        // silverfish, vexes etc. shift erratically server-side vs the
        // client's interpolated position — even legitimate spam-clicks
        // routinely fall just outside the strict reach cap.  Add 0.6 b
        // when the victim's smallest hitbox dimension is under 0.5 b.
        // Not a real bypass: a reach hacker chasing a chicken at distance
        // > 3.8 b is still caught (banned at INSTANT_BAN_REACH = 6.5 b too).
        double minDim = Math.min(victim.getWidth(), victim.getHeight());
        if (minDim < 0.5) {
            maxReach += 0.6;
            debugLog(attacker, "SmallEntity bonus applied (" + victim.getType()
                    + " minDim=" + fmt(minDim) + ") newMax=" + fmt(maxReach));
        }

        if (distance <= maxReach) return;

        event.setCancelled(true);
        lastFlagTime.put(id, now);
        // excess relative to vanilla (not including buffers) for clear logging
        double vanilla = attacker.getGameMode() == GameMode.CREATIVE
                ? MAX_CREATIVE_REACH : MAX_SURVIVAL_REACH;
        double excess = distance - vanilla;

        // ── Instant ban for blatant reach ─────────────────────────────────
        double instantBan = INSTANT_BAN_REACH;
        if (interactionRange != null) {
            double attrRange = interactionRange.getValue();
            if (attrRange > 3.0) instantBan += (attrRange - 3.0);
        }
        if (distance > instantBan) {
            debugLog(attacker, "Reach[Blatant] dist=" + fmt(distance) + " (instant ban)");
            vm.resetReachVL(id);
            removePlayer(id);
            bm.banPlayer(attacker, "Reach[Blatant] dist=" + fmt(distance));
            return;
        }

        // ── VL-based flagging ─────────────────────────────────────────────
        // Fixed: now uses ViolationManager instead of a local map,
        // so /anticheat vl can display it and decay applies correctly.
        // Flag harder the further over reach the player is
        int vlIncrease = excess > 1.0 ? 3 : excess > 0.5 ? 2 : 1;
        vm.incrementReachVL(id, vlIncrease);
        int vl = vm.getReachVL(id);

        int threshold = plugin.getConfig().getInt("settings.reach-vl-threshold", 4);
        debugLog(attacker, "Reach[Flag] dist=" + fmt(distance) + " max=" + fmt(maxReach)
                + " excess=" + fmt(excess) + " VL=" + vl + "/" + threshold);

        if (vl >= threshold) {
            vm.resetReachVL(id);
            removePlayer(id);
            bm.banPlayer(attacker, "Reach dist=" + fmt(distance));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Eye-to-hitbox distance (most accurate method available in Bukkit)
    // ─────────────────────────────────────────────────────────────────────────
    private double getEyeToHitboxDistance(Player attacker, Entity victim) {
        Location eye  = attacker.getEyeLocation();
        Location vLoc = victim.getLocation();

        double height = victim.getHeight();
        double halfW  = victim.getWidth() / 2.0;

        double nearX = clamp(eye.getX(), vLoc.getX() - halfW, vLoc.getX() + halfW);
        double nearY = clamp(eye.getY(), vLoc.getY(),         vLoc.getY() + height);
        double nearZ = clamp(eye.getZ(), vLoc.getZ() - halfW, vLoc.getZ() + halfW);

        double dx = eye.getX() - nearX;
        double dy = eye.getY() - nearY;
        double dz = eye.getZ() - nearZ;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private double clamp(double val, double min, double max) {
        return Math.max(min, Math.min(max, val));
    }

    /**
     * Returns true when the attacker is swinging a spear-class weapon.  Detects:
     *  • Material.TRIDENT — vanilla trident (longest vanilla melee weapon)
     *  • Any item whose Material name contains "SPEAR" — covers vanilla spears
     *
     * Main hand only — melee attacks always use the main hand, so a trident in
     * the off-hand must not extend a sword's reach.  The item's display name is
     * NOT checked: anyone can rename a sword "Spear" in an anvil.
     */
    private boolean isHoldingSpear(Player player) {
        return isSpearItem(player.getInventory().getItemInMainHand());
    }

    private boolean isSpearItem(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return false;
        // Vanilla trident
        if (item.getType() == Material.TRIDENT) return true;
        // Vanilla spear materials (WOODEN_SPEAR, IRON_SPEAR, …)
        return item.getType().name().contains("SPEAR");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Cleanup
    // ─────────────────────────────────────────────────────────────────────────

    /** Called by PlayerStateListener on quit to avoid memory leaks. */
    public void removePlayer(UUID id) {
        lastFlagTime.remove(id);
    }

    private boolean isExempt(Player player) {
        return player.hasPermission("anticheat.bypass")
                || plugin.getWhitelistManager().isWhitelisted(player.getUniqueId())
                || player.getGameMode() == GameMode.SPECTATOR
                || player.isDead()
                || player.isInsideVehicle()                    // horse/boat: elevated eye throws off 3D distance
                || vm.hasJoinGrace(player.getUniqueId())
                || vm.hasTeleportGrace(player.getUniqueId()); // attacker just ender-pearled — position is off
    }

    private void debugLog(Player player, String msg) {
        if (plugin.getConfig().getBoolean("debug.enabled", false)) {
            plugin.getLogger().info("[DEBUG][Reach] " + player.getName() + " » " + msg);
        }
    }

    private String fmt(double d) { return String.format("%.3f", d); }
}
