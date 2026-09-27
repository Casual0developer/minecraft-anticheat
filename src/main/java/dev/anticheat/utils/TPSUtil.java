package dev.anticheat.utils;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * TPSUtil — TPS-aware guard to reduce false positives during lag.
 *
 * Measures TPS itself from the last ~3 seconds of server ticks, instead of
 * reading Paper's 1-minute average by reflection on every call.  The short
 * window means checks that pause during lag come back within a few seconds
 * of the lag ending, rather than staying off for up to a minute.
 */
public final class TPSUtil {

    private TPSUtil() {}

    /** Ticks in the measuring window (~3 s at 20 TPS). */
    private static final int WINDOW_TICKS = 60;

    private static final long[] tickNanos = new long[WINDOW_TICKS + 1];
    private static int nextIdx = 0;
    private static int filled = 0;
    private static volatile double tps = 20.0;
    private static BukkitTask task;

    /** Starts the per-tick sampler. Call from onEnable. */
    public static void start(Plugin plugin) {
        stop();
        nextIdx = 0;
        filled = 0;
        tps = 20.0;
        task = Bukkit.getScheduler().runTaskTimer(plugin, TPSUtil::sample, 1L, 1L);
    }

    /** Stops the sampler. Call from onDisable. */
    public static void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private static void sample() {
        long now = System.nanoTime();
        tickNanos[nextIdx] = now;
        nextIdx = (nextIdx + 1) % tickNanos.length;
        if (filled < tickNanos.length) filled++;
        if (filled < 2) return;

        long oldest = tickNanos[filled < tickNanos.length ? 0 : nextIdx];
        long elapsed = now - oldest;
        if (elapsed <= 0) return;
        tps = Math.min(20.0, (filled - 1) * 1_000_000_000.0 / elapsed);
    }

    /** Returns TPS over the last ~3 seconds, capped to 20. Returns 20 until the sampler has run. */
    public static double getTPS() {
        return tps;
    }

    public static boolean isLagging() {
        return getTPS() < 18.5;
    }

    public static boolean isSeverelyLagging() {
        return getTPS() < 16.0;
    }

    /**
     * Returns a lag-compensation multiplier (1.0 = no lag, >1.0 = scale up thresholds).
     * Used to make checks more lenient during TPS drops.
     */
    public static double lagCompensationFactor() {
        double tps = getTPS();
        if (tps >= 19.8) return 1.0;
        if (tps >= 18.0) return 1.15;
        if (tps >= 16.0) return 1.35;
        return 1.6;
    }
}
