package dev.anticheat.listeners;

import dev.anticheat.AntiCheatPlugin;
import dev.anticheat.managers.BanManager;
import dev.anticheat.managers.ViolationManager;
import dev.anticheat.managers.WhitelistManager;
import org.bukkit.GameMode;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class TriggerBotListener implements Listener {

    private final AntiCheatPlugin plugin;
    private final BanManager bm;
    private final ViolationManager vm;
    private final WhitelistManager wm;

    private static class TriggerState {
        int vl = 0;
        long lastAttackMs = 0;
        long[] recentIntervals = new long[10];
        int intervalIndex = 0;
        int intervalCount = 0;
        long lastTargetAcquiredMs = 0;
        long[] reactionTimes = new long[10];
        int reactionIndex = 0;
        int reactionCount = 0;
        int perfectTimingStreak = 0;
        int maxAttackRateStreak = 0;
    }

    private final Map<UUID, TriggerState> state = new ConcurrentHashMap<>();

    private static final int BAN_VL = 3;
    private static final long PERFECT_TIMING_THRESHOLD_MS = 2;
    private static final int PERFECT_STREAK_THRESHOLD = 5;
    private static final long MIN_HUMAN_INTERVAL_MS = 80;
    private static final int MAX_RATE_STREAK = 8;
    private static final long REACTION_VARIANCE_THRESHOLD_MS = 10;
    private static final int REACTION_SAMPLES_NEEDED = 6;

    public TriggerBotListener(AntiCheatPlugin plugin) {
        this.plugin = plugin;
        this.bm = plugin.getBanManager();
        this.vm = plugin.getViolationManager();
        this.wm = plugin.getWhitelistManager();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (!plugin.getConfig().getBoolean("checks.triggerbot", true)) return;
        if (dev.anticheat.utils.TPSUtil.isSeverelyLagging()) return;
        // Melee only — Thorns procs report the Thorns wearer as the damager.
        if (event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) return;
        if (!(event.getDamager() instanceof Player attacker)) return;
        if (!(event.getEntity() instanceof LivingEntity)) return;

        UUID id = attacker.getUniqueId();
        if (isExempt(attacker)) return;

        long now = System.currentTimeMillis();
        TriggerState s = state.computeIfAbsent(id, k -> new TriggerState());

        if (s.lastAttackMs > 0) {
            long interval = now - s.lastAttackMs;

            s.recentIntervals[s.intervalIndex] = interval;
            s.intervalIndex = (s.intervalIndex + 1) % s.recentIntervals.length;
            if (s.intervalCount < s.recentIntervals.length) s.intervalCount++;

            if (s.intervalCount >= 4) {
                checkPerfectTiming(s, interval, attacker, id);
                checkInhumanConsistency(s, attacker, id);
                checkMaxRateStreak(s, interval, attacker, id);
            }
        }

        s.lastAttackMs = now;
    }

    private void checkPerfectTiming(TriggerState s, long interval, Player attacker, UUID id) {
        boolean isPerfect = false;
        for (int i = 0; i < s.intervalCount; i++) {
            if (Math.abs(s.recentIntervals[i] - interval) <= PERFECT_TIMING_THRESHOLD_MS) {
                int similarCount = 0;
                for (int j = 0; j < s.intervalCount; j++) {
                    if (Math.abs(s.recentIntervals[j] - interval) <= PERFECT_TIMING_THRESHOLD_MS) {
                        similarCount++;
                    }
                }
                if (similarCount >= 3) {
                    isPerfect = true;
                    break;
                }
            }
        }

        if (isPerfect) {
            s.perfectTimingStreak++;
            if (s.perfectTimingStreak >= PERFECT_STREAK_THRESHOLD) {
                flag(attacker, id, "TriggerBot[PerfectTiming] streak=" + s.perfectTimingStreak + " interval=" + interval + "ms");
                s.perfectTimingStreak = 0;
            }
        } else {
            s.perfectTimingStreak = Math.max(0, s.perfectTimingStreak - 1);
        }
    }

    private void checkInhumanConsistency(TriggerState s, Player attacker, UUID id) {
        if (s.intervalCount < REACTION_SAMPLES_NEEDED) return;

        long sum = 0;
        for (int i = 0; i < s.intervalCount; i++) sum += s.recentIntervals[i];
        double avg = (double) sum / s.intervalCount;

        double varianceSum = 0;
        for (int i = 0; i < s.intervalCount; i++) {
            varianceSum += Math.pow(s.recentIntervals[i] - avg, 2);
        }
        double variance = varianceSum / s.intervalCount;
        double stdDev = Math.sqrt(variance);

        if (stdDev < REACTION_VARIANCE_THRESHOLD_MS && avg > MIN_HUMAN_INTERVAL_MS) {
            flag(attacker, id, "TriggerBot[Consistency] stdDev=" + String.format("%.1f", stdDev) + "ms avg=" + String.format("%.0f", avg) + "ms");
        }
    }

    private void checkMaxRateStreak(TriggerState s, long interval, Player attacker, UUID id) {
        if (interval >= MIN_HUMAN_INTERVAL_MS - 10 && interval <= MIN_HUMAN_INTERVAL_MS + 20) {
            s.maxAttackRateStreak++;
            if (s.maxAttackRateStreak >= MAX_RATE_STREAK) {
                flag(attacker, id, "TriggerBot[MaxRate] streak=" + s.maxAttackRateStreak + " interval=" + interval + "ms");
                s.maxAttackRateStreak = 0;
            }
        } else {
            s.maxAttackRateStreak = Math.max(0, s.maxAttackRateStreak - 2);
        }
    }

    private void flag(Player player, UUID id, String reason) {
        TriggerState s = state.get(id);
        if (s == null) return;
        s.vl++;
        debugLog(player, reason + " vl=" + s.vl + "/" + BAN_VL);
        if (s.vl >= BAN_VL) {
            state.remove(id);
            bm.banPlayer(player, reason);
        }
    }

    private boolean isExempt(Player player) {
        GameMode gm = player.getGameMode();
        return player.hasPermission("anticheat.bypass")
                || wm.isWhitelisted(player.getUniqueId())
                || gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR
                || player.isDead() || player.isInsideVehicle()
                || vm.hasJoinGrace(player.getUniqueId());
    }

    public void removePlayer(UUID id) { state.remove(id); }

    private void debugLog(Player player, String msg) {
        if (plugin.getConfig().getBoolean("debug.enabled", false)) {
            plugin.getLogger().info("[DEBUG][TriggerBot] " + player.getName() + " » " + msg);
        }
    }
}
