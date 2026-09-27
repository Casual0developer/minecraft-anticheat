package dev.anticheat.managers;

import dev.anticheat.AntiCheatPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ViolationManager {

    private final AntiCheatPlugin plugin;

    /** Fly violation level per player */
    private final Map<UUID, Integer> flyVL   = new ConcurrentHashMap<>();

    /** Reach violation level per player */
    private final Map<UUID, Integer> reachVL = new ConcurrentHashMap<>();

    /** Speed violation level per player */
    private final Map<UUID, Integer> speedVL = new ConcurrentHashMap<>();

    /** Scaffold violation level per player */
    private final Map<UUID, Integer> scaffoldVL = new ConcurrentHashMap<>();

    /**
     * Grace ticks remaining per player.
     * These now decrement on a scheduler every server tick —
     * NOT inside PlayerMoveEvent — so they expire even for stationary players.
     */
    private final Map<UUID, Integer> jumpGrace     = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> damageGrace   = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> teleportGrace = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> joinGrace     = new ConcurrentHashMap<>();

    /** Previous Y position for motion tracking */
    private final Map<UUID, Double> prevY = new ConcurrentHashMap<>();

    /** Consecutive airborne ticks */
    private final Map<UUID, Integer> airticks = new ConcurrentHashMap<>();

    /** NoFall tracking: whether player was on ground last tick */
    private final Map<UUID, Boolean> wasOnGround = new ConcurrentHashMap<>();

    /** Last known safe (grounded) location per player — used for rubberband punishment. */
    private final Map<UUID, Location> lastSafeLocation = new ConcurrentHashMap<>();

    private final BukkitTask graceTick;
    private final BukkitTask decayTask;

    public ViolationManager(AntiCheatPlugin plugin) {
        this.plugin = plugin;

        // ── Grace tick: runs every server tick (20/s) ──────────────────────
        // Fixed: was previously called only inside PlayerMoveEvent, meaning
        // stationary players would never have their grace period expire.
        graceTick = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            Set<UUID> active = new java.util.HashSet<>();
            active.addAll(jumpGrace.keySet());
            active.addAll(damageGrace.keySet());
            active.addAll(teleportGrace.keySet());
            active.addAll(joinGrace.keySet());
            for (UUID id : active) {
                tickDown(jumpGrace,     id);
                tickDown(damageGrace,   id);
                tickDown(teleportGrace, id);
                tickDown(joinGrace,     id);
            }
        }, 1L, 1L);

        // ── VL decay: periodically reduce fly/reach/speed VL ──────────────
        int decayInterval = plugin.getConfig().getInt("settings.vl-decay-ticks", 40);
        decayTask = Bukkit.getScheduler().runTaskTimer(plugin, this::decayAll, decayInterval, decayInterval);
    }

    // ── Grace helpers ───────────────────────────────────────────────────────

    public void giveJumpGrace(UUID id) {
        jumpGrace.put(id, plugin.getConfig().getInt("settings.jump-grace-ticks", 6));
    }

    public void giveDamageGrace(UUID id) {
        damageGrace.put(id, plugin.getConfig().getInt("settings.damage-grace-ticks", 10));
    }

    public void giveTeleportGrace(UUID id) {
        teleportGrace.put(id, plugin.getConfig().getInt("settings.teleport-grace-ticks", 20));
    }

    /** Give a precise number of teleport-grace ticks, bypassing the config default.
     *  Used when the required duration is calculated from physics (e.g. Wind Burst). */
    public void giveTeleportGraceTicks(UUID id, int ticks) {
        // Only upgrade — never shorten an already-active longer grace
        int current = teleportGrace.getOrDefault(id, 0);
        if (ticks > current) teleportGrace.put(id, ticks);
    }

    public void giveJoinGrace(UUID id) {
        joinGrace.put(id, plugin.getConfig().getInt("settings.join-grace-ticks", 60));
    }

    private void tickDown(Map<UUID, Integer> map, UUID id) {
        Integer v = map.get(id);
        if (v != null) {
            if (v <= 1) map.remove(id);
            else map.put(id, v - 1);
        }
    }

    public boolean hasJumpGrace(UUID id)     { return jumpGrace.containsKey(id); }
    public boolean hasDamageGrace(UUID id)   { return damageGrace.containsKey(id); }
    public boolean hasTeleportGrace(UUID id) { return teleportGrace.containsKey(id); }
    public boolean hasJoinGrace(UUID id)     { return joinGrace.containsKey(id); }
    // ── Fly VL ─────────────────────────────────────────────────────────────

    public int getFlyVL(UUID id)                     { return flyVL.getOrDefault(id, 0); }
    public void incrementFlyVL(UUID id, int amount)  { flyVL.put(id, getFlyVL(id) + amount); }
    public void resetFlyVL(UUID id)                  { flyVL.remove(id); }

    // ── Reach VL ────────────────────────────────────────────────────────────

    public int getReachVL(UUID id)                    { return reachVL.getOrDefault(id, 0); }
    public void incrementReachVL(UUID id, int amount) { reachVL.put(id, getReachVL(id) + amount); }
    public void resetReachVL(UUID id)                 { reachVL.remove(id); }

    // ── Speed VL ─────────────────────────────────────────────────────────────

    public int getSpeedVL(UUID id)                    { return speedVL.getOrDefault(id, 0); }
    public void incrementSpeedVL(UUID id, int amount) { speedVL.put(id, getSpeedVL(id) + amount); }
    public void resetSpeedVL(UUID id)                 { speedVL.remove(id); }

    // ── Scaffold VL ───────────────────────────────────────────────────────────

    public int  getScaffoldVL(UUID id)                     { return scaffoldVL.getOrDefault(id, 0); }
    public void incrementScaffoldVL(UUID id, int amount)   { scaffoldVL.put(id, getScaffoldVL(id) + amount); }
    public void resetScaffoldVL(UUID id)                   { scaffoldVL.remove(id); }

    // ── Air-tick tracking ──────────────────────────────────────────────────

    public int  getAirTicks(UUID id)  { return airticks.getOrDefault(id, 0); }
    public void incrementAirTicks(UUID id) { airticks.put(id, getAirTicks(id) + 1); }
    public void resetAirTicks(UUID id)     { airticks.remove(id); }

    // ── Y tracking ─────────────────────────────────────────────────────────

    public Double getPrevY(UUID id)             { return prevY.get(id); }
    public void   setPrevY(UUID id, double y)   { prevY.put(id, y); }

    // ── Ground tracking ────────────────────────────────────────────────────

    public boolean getWasOnGround(UUID id)            { return wasOnGround.getOrDefault(id, true); }
    public void    setWasOnGround(UUID id, boolean v) { wasOnGround.put(id, v); }

    // ── Safe location (rubberband) ──────────────────────────────────────────

    public Location getLastSafeLocation(UUID id)           { return lastSafeLocation.get(id); }
    public void     setLastSafeLocation(UUID id, Location l) {
        if (l != null) lastSafeLocation.put(id, l.clone());
    }

    // ── Full cleanup on quit / ban ─────────────────────────────────────────

    public void remove(UUID id) {
        flyVL.remove(id);
        reachVL.remove(id);
        speedVL.remove(id);
        scaffoldVL.remove(id);

        jumpGrace.remove(id);
        damageGrace.remove(id);
        teleportGrace.remove(id);
        joinGrace.remove(id);
        prevY.remove(id);
        airticks.remove(id);
        wasOnGround.remove(id);
        lastSafeLocation.remove(id);
    }

    /** Full shutdown cleanup — cancel tasks, clear all maps. */
    public void cleanup() {
        if (graceTick != null) graceTick.cancel();
        if (decayTask != null) decayTask.cancel();
        flyVL.clear();
        reachVL.clear();
        speedVL.clear();
        scaffoldVL.clear();
        prevY.clear();
        airticks.clear();
        wasOnGround.clear();
    }

    // ── Decay logic ────────────────────────────────────────────────────────

    private void decayAll() {
        decay(flyVL);
        decay(reachVL);
        decay(speedVL);
        decay(scaffoldVL);
    }

    private void decay(Map<UUID, Integer> map) {
        for (UUID id : map.keySet()) {
            int v = map.getOrDefault(id, 0);
            if (v > 0) map.put(id, v - 1);
            else map.remove(id);
        }
    }
}
