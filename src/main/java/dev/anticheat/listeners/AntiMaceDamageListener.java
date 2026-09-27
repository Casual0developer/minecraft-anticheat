package dev.anticheat.listeners;

import dev.anticheat.AntiCheatPlugin;
import dev.anticheat.managers.BanManager;
import dev.anticheat.managers.ViolationManager;
import dev.anticheat.managers.WhitelistManager;
import dev.anticheat.utils.GroundUtil;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.potion.PotionEffectType;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class AntiMaceDamageListener implements Listener {

    private final AntiCheatPlugin plugin;
    private final BanManager bm;
    private final ViolationManager vm;
    private final WhitelistManager wm;

    private static class MaceState {
        int vl = 0;
        int debugVl = 0;
        double fallStartY = Double.MIN_VALUE;
        double maxFallDistance = 0;
        double prevY = Double.MIN_VALUE;
        boolean wasOnGround = true;
        int airTicks = 0;
    }

    private final Map<UUID, MaceState> state = new ConcurrentHashMap<>();

    private static final double BASE_MACE_DAMAGE = 6.0;
    private static final double FALL_BONUS_PER_BLOCK = 1.0;
    private static final double FALL_BONUS_THRESHOLD = 3.0;
    private static final int BAN_VL = 3;
    private static final int DEBUG_VL = 5;

    public AntiMaceDamageListener(AntiCheatPlugin plugin) {
        this.plugin = plugin;
        this.bm = plugin.getBanManager();
        this.vm = plugin.getViolationManager();
        this.wm = plugin.getWhitelistManager();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!plugin.getConfig().getBoolean("checks.mace", true)) return;
        // No TPS kill-switch: per-packet movement isn't changed by server lag.
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        MaceState s = state.computeIfAbsent(id, k -> new MaceState());

        double y = player.getLocation().getY();

        if (GroundUtil.isOnGround(player, player.getLocation()) || player.isFlying()
                || player.isInsideVehicle() || player.isGliding()) {
            s.fallStartY = Double.MIN_VALUE;
            s.maxFallDistance = 0;
            s.wasOnGround = true;
            s.airTicks = 0;
            s.prevY = y;
            return;
        }

        if (s.wasOnGround) {
            s.fallStartY = y;
            s.maxFallDistance = 0;
            s.wasOnGround = false;
        }

        if (s.fallStartY != Double.MIN_VALUE && y < s.fallStartY) {
            s.maxFallDistance = Math.max(s.maxFallDistance, s.fallStartY - y);
        } else if (y > s.fallStartY + 0.5) {
            s.fallStartY = y;
            s.maxFallDistance = 0;
        }

        s.airTicks++;
        s.prevY = y;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        // Melee only — Thorns procs report the Thorns wearer as the damager.
        if (event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) return;
        if (!(event.getDamager() instanceof Player attacker)) return;
        if (!(event.getEntity() instanceof LivingEntity victim)) return;

        UUID id = attacker.getUniqueId();
        if (isExempt(attacker)) return;

        if (attacker.getInventory().getItemInMainHand().getType() != Material.MACE) return;

        MaceState s = state.get(id);
        double fallDistance = s != null ? s.maxFallDistance : 0;

        boolean isCritical = !attacker.isOnGround()
                && !GroundUtil.touchingGround(attacker, attacker.getLocation())
                && attacker.getFallDistance() > 0;
        double maxDamage = calculateMaxMaceDamage(attacker, fallDistance, isCritical);
        double actualDamage = event.getDamage();
        double rawBase = event.getDamage(org.bukkit.event.entity.EntityDamageEvent.DamageModifier.BASE);

        boolean flagged = false;
        String reason = "";

        if (actualDamage > maxDamage + 2.0) {
            flagged = true;
            reason = "MaceDamage[Impossible] dmg=" + fmt(actualDamage) + " max=" + fmt(maxDamage);
        }

        if (rawBase > 50.0) {
            flagged = true;
            reason = "MaceDamage[Base] rawBase=" + fmt(rawBase) + " (legit max ~6+)";
        }

        double hardness = victim instanceof Player pv ? 0.0 : (victim.getMaxHealth() > 0 ? 0.0 : 0.0);
        if (actualDamage > 500 && fallDistance < 5) {
            flagged = true;
            reason = "MaceDamage[NoFall] dmg=" + fmt(actualDamage) + " but only fell " + fmt(fallDistance) + "b";
        }

        if (flagged) {
            if (s == null) {
                s = new MaceState();
                state.put(id, s);
            }
            s.vl++;
            s.debugVl++;

            debugLog(attacker, reason + " vl=" + s.vl + "/" + BAN_VL);

            if (plugin.getConfig().getBoolean("debug.enabled", false) && s.debugVl >= DEBUG_VL) {
                debugLog(attacker, "MaceDamage[Debug] CONSISTENT CHEATING DETECTED - " + reason);
                s.debugVl = 0;
            }

            if (s.vl >= BAN_VL) {
                state.remove(id);
                bm.banPlayer(attacker, reason);
            }
        } else if (s != null) {
            s.vl = Math.max(0, s.vl - 1);
            s.debugVl = Math.max(0, s.debugVl - 1);
        }

        if (s != null) {
            s.fallStartY = Double.MIN_VALUE;
            s.maxFallDistance = 0;
        }
    }

    private double calculateMaxMaceDamage(Player player, double fallDistance, boolean critical) {
        double damage = BASE_MACE_DAMAGE;

        if (fallDistance > FALL_BONUS_THRESHOLD) {
            double fallBonus = (fallDistance - FALL_BONUS_THRESHOLD) * FALL_BONUS_PER_BLOCK;
            damage += fallBonus;
        }

        var held = player.getInventory().getItemInMainHand();
        if (held != null && held.hasItemMeta()) {
            int density = held.getEnchantmentLevel(Enchantment.DENSITY);
            int windburst = held.getEnchantmentLevel(Enchantment.WIND_BURST);

            if (density > 0) {
                damage += density;
            }

            if (windburst > 0) {
                double windBurstLaunch = 0.9 * windburst;
                double windFallBonus = Math.max(0, windBurstLaunch - FALL_BONUS_THRESHOLD) * FALL_BONUS_PER_BLOCK;
                damage += windFallBonus;
            }
        }

        var strength = player.getPotionEffect(PotionEffectType.STRENGTH);
        if (strength != null) {
            damage += (strength.getAmplifier() + 1) * 3.0;
        }

        var haste = player.getPotionEffect(PotionEffectType.HASTE);

        if (critical) {
            damage *= 1.5;
        }

        return damage;
    }

    private boolean isExempt(Player player) {
        GameMode gm = player.getGameMode();
        return player.hasPermission("anticheat.bypass")
                || wm.isWhitelisted(player.getUniqueId())
                || gm == GameMode.CREATIVE
                || gm == GameMode.SPECTATOR
                || player.isDead()
                || vm.hasJoinGrace(player.getUniqueId());
    }

    public void removePlayer(UUID id) {
        state.remove(id);
    }

    /** Restarts fall tracking, keeping VL. */
    public void resetMotion(UUID id) {
        MaceState old = state.remove(id);
        if (old == null) return;
        MaceState s = new MaceState();
        s.vl = old.vl;
        s.debugVl = old.debugVl;
        state.put(id, s);
    }

    private void debugLog(Player player, String msg) {
        if (plugin.getConfig().getBoolean("debug.enabled", false)) {
            plugin.getLogger().info("[DEBUG][MaceDamage] " + player.getName() + " » " + msg);
        }
    }

    private String fmt(double d) { return String.format("%.1f", d); }
}
