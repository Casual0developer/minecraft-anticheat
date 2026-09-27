package dev.anticheat.listeners;

import dev.anticheat.AntiCheatPlugin;
import dev.anticheat.managers.BanManager;
import dev.anticheat.managers.ViolationManager;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Detects "Criticals" cheats — modules that force every attack to register as
 * a critical hit (1.5× base damage) even when vanilla wouldn't allow it.
 *
 * ── Vanilla crit conditions (server-validated) ──────────────────────────────
 * In vanilla, a critical hit only applies when ALL of the following hold:
 *   • player is airborne (falling)
 *   • not in a vehicle
 *   • not in water (swimming / submerged)
 *   • not blinded
 *   • not climbing (ladder / vine / scaffolding / etc.)
 *
 * If a crit lands while NONE of those allow it, the cheat overrode the
 * server check.
 *
 * ── Detection ───────────────────────────────────────────────────────────────
 * Bukkit doesn't expose an isCritical() flag on EntityDamageByEntityEvent,
 * but the BASE damage on a crit is multiplied by 1.5.  We compute the
 * attacker's expected base damage from {@link Attribute#GENERIC_ATTACK_DAMAGE}
 * plus any Sharpness bonus on the held weapon, and compare it to the event's
 * BASE modifier.  A ratio in [1.4, 1.6] is a crit.
 *
 * On a confirmed crit we look at the attacker's airborne state (via a 1-tick
 * scheduler that tracks the last time each player was server-airborne) plus
 * the other vanilla conditions.  If none match, flag.
 *
 * A 5-tick (250 ms) grace is applied to "recently airborne" so the moment a
 * player lands isn't false-flagged as on-ground when the server actually
 * computed the crit a tick earlier.
 */
public class CritListener implements Listener {

    private final AntiCheatPlugin plugin;
    private final ViolationManager vm;
    private final BanManager bm;

    /** Lower / upper ratio of (event BASE damage) / (expected base) for a hit
     *  to count as a critical.  Vanilla crit factor is exactly 1.5 — the band
     *  absorbs floating-point and edge effects. */
    private static final double CRIT_RATIO_LOW  = 1.4;
    private static final double CRIT_RATIO_HIGH = 1.6;

    /** Ticks since last airborne under which we still allow a crit
     *  (covers the moment a player lands but the server's crit calc fired
     *  one tick earlier mid-air). */
    private static final int RECENTLY_AIRBORNE_TICKS = 5;

    /** Rate-limit (ms) between flags. */
    private static final long FLAG_COOLDOWN_MS = 200;

    private final Map<UUID, Integer> critVL       = new HashMap<>();
    private final Map<UUID, Long>    lastFlagTime = new HashMap<>();
    /** Ticks since the player was last server-airborne. */
    private final Map<UUID, Integer> ticksSinceAirborne = new HashMap<>();

    private BukkitTask tickTask;

    public CritListener(AntiCheatPlugin plugin) {
        this.plugin = plugin;
        this.vm = plugin.getViolationManager();
        this.bm = plugin.getBanManager();

        // 1-tick scheduler that tracks airborne state regardless of
        // PlayerMoveEvent firing.
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickAirborne, 1L, 1L);
    }

    private void tickAirborne() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID id = p.getUniqueId();
            if (serverOnGround(p.getLocation())) {
                ticksSinceAirborne.put(id,
                        ticksSinceAirborne.getOrDefault(id, 0) + 1);
            } else {
                ticksSinceAirborne.put(id, 0);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (!plugin.getConfig().getBoolean("checks.crit", true)) return;
        if (dev.anticheat.utils.TPSUtil.isSeverelyLagging()) return;
        if (event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) return;
        if (!(event.getDamager() instanceof Player attacker)) return;
        if (!(event.getEntity() instanceof LivingEntity)) return;
        if (isExempt(attacker)) return;

        UUID id = attacker.getUniqueId();
        long now = System.currentTimeMillis();
        Long lastFlag = lastFlagTime.get(id);
        if (lastFlag != null && now - lastFlag < FLAG_COOLDOWN_MS) return;

        // ── Determine whether this hit was a critical ──────────────────────
        double actualBase = event.getDamage(EntityDamageEvent.DamageModifier.BASE);
        double expectedBase = computeExpectedBase(attacker);
        if (expectedBase <= 0) return;
        double ratio = actualBase / expectedBase;
        if (ratio < CRIT_RATIO_LOW || ratio > CRIT_RATIO_HIGH) return;

        // ── Crit confirmed — check vanilla eligibility ─────────────────────
        if (canLegitimatelyCrit(attacker)) return;

        // No vanilla condition allows this crit → cheat
        lastFlagTime.put(id, now);

        int vl = critVL.getOrDefault(id, 0) + 1;
        critVL.put(id, vl);
        int vlThreshold = plugin.getConfig().getInt("settings.crit-vl-threshold", 1);

        if (plugin.getConfig().getBoolean("debug.enabled", false)) {
            plugin.getLogger().info("[DEBUG][Crit] " + attacker.getName()
                    + " on-ground crit base=" + String.format("%.2f", actualBase)
                    + " expected=" + String.format("%.2f", expectedBase)
                    + " ratio=" + String.format("%.2f", ratio)
                    + " VL=" + vl + "/" + vlThreshold);
        }

        if (vl >= vlThreshold) {
            critVL.remove(id);
            bm.banPlayer(attacker, "Crit on-ground ratio=" + String.format("%.2f", ratio));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Expected BASE damage if the hit were NOT a critical.  This is the
     * attacker's GENERIC_ATTACK_DAMAGE (includes weapon attack-damage modifier
     * and strength/weakness potion effects) plus the Sharpness enchant bonus
     * (which is added as a flat damage outside the attribute system).
     *
     * Smite / Bane of Arthropods are deliberately ignored — they only apply
     * against specific mob types and would complicate the ratio without
     * adding meaningful detection accuracy.
     */
    private double computeExpectedBase(Player attacker) {
        AttributeInstance attr = attacker.getAttribute(Attribute.GENERIC_ATTACK_DAMAGE);
        if (attr == null) return -1.0;
        double base = attr.getValue();

        ItemStack weapon = attacker.getInventory().getItemInMainHand();
        if (weapon != null) {
            int sharp = weapon.getEnchantmentLevel(Enchantment.SHARPNESS);
            if (sharp > 0) {
                // Sharpness 1 = +1.0 dmg, +0.5 per additional level
                base += 0.5 * (sharp + 1);
            }
        }
        return base;
    }

    private boolean canLegitimatelyCrit(Player player) {
        UUID id = player.getUniqueId();
        int ticksGround = ticksSinceAirborne.getOrDefault(id, Integer.MAX_VALUE);

        // Airborne now OR within RECENTLY_AIRBORNE_TICKS = could crit
        boolean airborne = ticksGround < RECENTLY_AIRBORNE_TICKS;

        if (!airborne) return false;
        if (player.isInsideVehicle()) return false;
        if (player.isInWater()) return false;
        if (player.hasPotionEffect(PotionEffectType.BLINDNESS)) return false;

        Material atFeet = player.getLocation().getBlock().getType();
        if (isClimbable(atFeet)) return false;

        return true;
    }

    private boolean isClimbable(Material m) {
        return m == Material.LADDER || m == Material.VINE || m == Material.SCAFFOLDING
            || m == Material.TWISTING_VINES || m == Material.TWISTING_VINES_PLANT
            || m == Material.WEEPING_VINES  || m == Material.WEEPING_VINES_PLANT
            || m == Material.CAVE_VINES     || m == Material.CAVE_VINES_PLANT;
    }

    private boolean serverOnGround(Location loc) {
        World w = loc.getWorld();
        if (w == null) return false;
        double halfW = 0.3;
        int y = (int) Math.floor(loc.getY() - 0.02);
        double[][] corners = {
            {-halfW, -halfW}, { halfW, -halfW},
            {-halfW,  halfW}, { halfW,  halfW},
            { 0,      0    },
        };
        for (double[] c : corners) {
            Material m = w.getBlockAt(
                (int) Math.floor(loc.getX() + c[0]),
                y,
                (int) Math.floor(loc.getZ() + c[1])
            ).getType();
            if (m.isSolid()) return true;
        }
        return false;
    }

    private boolean isExempt(Player player) {
        UUID id = player.getUniqueId();
        GameMode gm = player.getGameMode();
        return player.hasPermission("anticheat.bypass")
            || plugin.getWhitelistManager().isWhitelisted(id)
            || gm == GameMode.SPECTATOR
            || gm == GameMode.CREATIVE
            || player.isDead()
            || vm.hasJoinGrace(id)
            || vm.hasTeleportGrace(id);
    }

    public void removePlayer(UUID id) {
        critVL.remove(id);
        lastFlagTime.remove(id);
        ticksSinceAirborne.remove(id);
    }
}
