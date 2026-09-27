package dev.anticheat.listeners;

import dev.anticheat.AntiCheatPlugin;
import dev.anticheat.managers.BanManager;
import dev.anticheat.managers.ViolationManager;
import dev.anticheat.managers.WhitelistManager;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class AntiTimerListener implements Listener {

    private final AntiCheatPlugin plugin;
    private final BanManager bm;
    private final ViolationManager vm;
    private final WhitelistManager wm;

    private static class TimerState {
        int vl = 0;
        int packetsThisWindow = 0;
        long windowStartMs = 0;
        long lastPacketMs = 0;
        long[] recentIntervals = new long[20];
        int intervalIdx = 0;
        int intervalCount = 0;
        double avgInterval = 50.0;
        int fastWindows = 0;
        int consistentFast = 0;
        double distanceThisWindow = 0;
        double prevX = Double.MIN_VALUE;
        double prevZ = Double.MIN_VALUE;
        int speedVL = 0;
    }

    private final Map<UUID, TimerState> state = new ConcurrentHashMap<>();
    private BukkitTask tickTask;

    private static final int BAN_VL = 2;
    private static final long WINDOW_MS = 505L;
    private static final double FAST_PACKETS_PER_SEC = 24.5;
    private static final int CONSISTENT_FAST_THRESHOLD = 2;
    private static final double MIN_AVG_INTERVAL = 46;
    private static final double FAST_SPEED_THRESHOLD = 7;

    public AntiTimerListener(AntiCheatPlugin plugin) {
        this.plugin = plugin;
        this.bm = plugin.getBanManager();
        this.vm = plugin.getViolationManager();
        this.wm = plugin.getWhitelistManager();
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickAll, 10L, 10L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();

        if (isExempt(player)) { state.remove(id); return; }
        if (event.getFrom().getX() == event.getTo().getX()
            && event.getFrom().getY() == event.getTo().getY()
            && event.getFrom().getZ() == event.getTo().getZ()) return;

        long now = System.currentTimeMillis();
        TimerState s = state.computeIfAbsent(id, k -> new TimerState());

        if (s.windowStartMs == 0) {
            s.windowStartMs = now;
            s.prevX = event.getTo().getX();
            s.prevZ = event.getTo().getZ();
        }

        double dx = event.getTo().getX() - s.prevX;
        double dz = event.getTo().getZ() - s.prevZ;
        s.distanceThisWindow += Math.sqrt(dx * dx + dz * dz);
        s.prevX = event.getTo().getX();
        s.prevZ = event.getTo().getZ();

        if (s.lastPacketMs > 0) {
            long interval = now - s.lastPacketMs;
            if (interval > 0 && interval < 200) {
                s.recentIntervals[s.intervalIdx] = interval;
                s.intervalIdx = (s.intervalIdx + 1) % s.recentIntervals.length;
                if (s.intervalCount < s.recentIntervals.length) s.intervalCount++;
            }
        }
        s.lastPacketMs = now;
        s.packetsThisWindow++;

        if (now - s.windowStartMs >= WINDOW_MS) {
            checkWindow(s, player, id, now);
            s.packetsThisWindow = 0;
            s.windowStartMs = now;
            s.distanceThisWindow = 0;
        }
    }

    private void checkWindow(TimerState s, Player player, UUID id, long now) {
        // TPS-aware: scale thresholds when server is lagging (packets batch together)
        double tpsFactor = dev.anticheat.utils.TPSUtil.lagCompensationFactor();
        double fastThreshold = FAST_PACKETS_PER_SEC * tpsFactor;
        double speedThreshold = FAST_SPEED_THRESHOLD * tpsFactor;
        double intervalThreshold = MIN_AVG_INTERVAL / tpsFactor;

        double windowSec = (now - s.windowStartMs) / 1000.0;
        if (windowSec < 0.1) windowSec = 0.5;
        double packetsPerSec = s.packetsThisWindow / windowSec;
        double speedPerSec = s.distanceThisWindow / windowSec;

        if (packetsPerSec >= fastThreshold) {
            s.fastWindows++;
            s.consistentFast++;
            debugLog(player, "Timer[Fast] pps=" + String.format("%.0f", packetsPerSec)
                    + " consistent=" + s.consistentFast);
            if (s.consistentFast >= CONSISTENT_FAST_THRESHOLD) {
                flag(player, id, "Timer[Fast] " + String.format("%.0f", packetsPerSec) + " packets/sec");
                s.consistentFast = 0;
                s.fastWindows = 0;
            }
        } else {
            s.consistentFast = Math.max(0, s.consistentFast - 1);
        }

        if (speedPerSec >= speedThreshold && s.packetsThisWindow > 8) {
            s.speedVL++;
            debugLog(player, "Timer[Speed] speed=" + String.format("%.1f", speedPerSec)
                    + " b/s vl=" + s.speedVL + "/" + BAN_VL);
            if (s.speedVL >= BAN_VL) {
                flag(player, id, "Timer[Speed] moving " + String.format("%.1f", speedPerSec) + " b/s");
                s.speedVL = 0;
            }
        } else {
            s.speedVL = Math.max(0, s.speedVL - 1);
        }

        if (s.intervalCount >= 8) {
            long sum = 0;
            for (int i = 0; i < s.intervalCount; i++) sum += s.recentIntervals[i];
            s.avgInterval = (double) sum / s.intervalCount;

            if (s.avgInterval < intervalThreshold && s.packetsThisWindow > 10) {
                s.vl++;
                debugLog(player, "Timer[Interval] avg=" + String.format("%.1f", s.avgInterval)
                        + "ms vl=" + s.vl + "/" + BAN_VL);
                if (s.vl >= BAN_VL) {
                    flag(player, id, "Timer[Interval] avg " + String.format("%.1f", s.avgInterval) + "ms");
                    s.vl = 0;
                }
            } else {
                s.vl = Math.max(0, s.vl - 1);
            }
            s.intervalCount = 0;
            s.intervalIdx = 0;
        }
    }

    private void tickAll() {
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, TimerState> entry : new ConcurrentHashMap<>(state).entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !player.isOnline()) {
                state.remove(entry.getKey());
                continue;
            }
            TimerState s = entry.getValue();
            if (now - s.windowStartMs >= WINDOW_MS && s.packetsThisWindow > 0) {
                checkWindow(s, player, entry.getKey(), now);
                s.packetsThisWindow = 0;
                s.windowStartMs = now;
                s.distanceThisWindow = 0;
            }
        }
    }

    private void flag(Player player, UUID id, String reason) {
        TimerState s = state.get(id);
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

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        state.remove(event.getPlayer().getUniqueId());
    }

    public void removePlayer(UUID id) { state.remove(id); }

    /** Starts a fresh measuring window (a teleport would count as distance), keeping VLs. */
    public void resetMotion(UUID id) {
        TimerState old = state.remove(id);
        if (old == null) return;
        TimerState s = new TimerState();
        s.vl = old.vl;
        s.speedVL = old.speedVL;
        s.consistentFast = old.consistentFast;
        state.put(id, s);
    }

    private void debugLog(Player player, String msg) {
        if (plugin.getConfig().getBoolean("debug.enabled", false)) {
            plugin.getLogger().info("[DEBUG][AntiTimer] " + player.getName() + " » " + msg);
        }
    }
}
