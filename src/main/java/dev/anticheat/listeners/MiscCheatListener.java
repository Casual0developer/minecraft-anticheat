package dev.anticheat.listeners;

import dev.anticheat.AntiCheatPlugin;
import dev.anticheat.managers.BanManager;
import dev.anticheat.managers.ViolationManager;
import dev.anticheat.managers.WhitelistManager;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerBedLeaveEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * MiscCheatListener — three small, zero-false-positive checks:
 *
 * ① ZeroHungerSprint
 *   Vanilla disables sprinting the instant hunger reaches 0.  A movement packet
 *   with isSprinting=true while foodLevel==0 is server-side impossible without
 *   a hacked client.  Requires 2 consecutive flagging ticks to avoid any
 *   1-tick server-event ordering edge case.
 *
 * ② SleepMovement
 *   While a player is in a bed (player.isSleeping()==true) the vanilla client
 *   locks all movement input.  A position delta while sleeping is a forged
 *   packet.  500 ms grace on bed entry covers the animation slide-in.
 *
 */
public class MiscCheatListener implements Listener {

    private final AntiCheatPlugin  plugin;
    private final BanManager       bm;
    private final ViolationManager vm;
    private final WhitelistManager wm;

    // ── ① ZeroHungerSprint ────────────────────────────────────────────────────
    /** Consecutive ticks where isSprinting && hunger==0 was true. Ban at 2. */
    private final Map<UUID, Integer> hungerSprintVL = new HashMap<>();

    // ── ② SleepMovement ───────────────────────────────────────────────────────
    /** Wall-clock time (ms) when the player entered the bed. */
    private final Map<UUID, Long> bedEnterMs = new HashMap<>();

    /** Bed-entry animation grace before we start checking movement. */
    private static final long SLEEP_GRACE_MS = 500L;

    // ─────────────────────────────────────────────────────────────────────────

    public MiscCheatListener(AntiCheatPlugin plugin) {
        this.plugin = plugin;
        this.bm     = plugin.getBanManager();
        this.vm     = plugin.getViolationManager();
        this.wm     = plugin.getWhitelistManager();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PlayerMoveEvent — all three checks share this handler
    // ─────────────────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!plugin.getConfig().getBoolean("checks.misc", true)) return;
        // No TPS kill-switch: per-packet movement isn't changed by server lag.
        Player   player = event.getPlayer();
        UUID     id     = player.getUniqueId();
        Location from   = event.getFrom();
        Location to     = event.getTo();
        if (to == null) return;

        if (isExempt(player)) {
            reset(id);
            return;
        }

        checkHungerSprint(player, id);
        checkSleepMovement(player, id, from, to);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ① ZeroHungerSprint
    // ─────────────────────────────────────────────────────────────────────────

    private void checkHungerSprint(Player player, UUID id) {
        if (player.isSprinting() && player.getFoodLevel() == 0) {
            int vl = hungerSprintVL.getOrDefault(id, 0) + 1;
            hungerSprintVL.put(id, vl);
            debugLog(player, "ZeroHungerSprint VL=" + vl);
            if (vl >= 2) {
                hungerSprintVL.remove(id);
                bm.banPlayer(player, "ZeroHungerSprint");
            }
        } else {
            hungerSprintVL.remove(id);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ② SleepMovement — track bed enter / leave
    // ─────────────────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBedEnter(PlayerBedEnterEvent event) {
        // Only record if the enter was fully successful
        if (event.getBedEnterResult() != PlayerBedEnterEvent.BedEnterResult.OK) return;
        bedEnterMs.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBedLeave(PlayerBedLeaveEvent event) {
        bedEnterMs.remove(event.getPlayer().getUniqueId());
    }

    private void checkSleepMovement(Player player, UUID id, Location from, Location to) {
        if (!player.isSleeping()) {
            bedEnterMs.remove(id);
            return;
        }

        Long enterMs = bedEnterMs.get(id);
        if (enterMs == null) return;

        // Grace window: animation slide-in takes ~400 ms
        if (System.currentTimeMillis() - enterMs < SLEEP_GRACE_MS) return;

        double dx = Math.abs(to.getX() - from.getX());
        double dy = Math.abs(to.getY() - from.getY());
        double dz = Math.abs(to.getZ() - from.getZ());

        if (dx > 0.01 || dy > 0.01 || dz > 0.01) {
            debugLog(player, "SleepMovement dx=" + fmt(dx) + " dy=" + fmt(dy) + " dz=" + fmt(dz));
            bedEnterMs.remove(id);
            bm.banPlayer(player, "SleepMovement");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ─────────────────────────────────────────────────────────────────────────
    // Cleanup
    // ─────────────────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        reset(event.getPlayer().getUniqueId());
    }

    public void removePlayer(UUID id) {
        reset(id);
    }

    private void reset(UUID id) {
        hungerSprintVL.remove(id);
        bedEnterMs.remove(id);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private boolean isExempt(Player player) {
        GameMode gm = player.getGameMode();
        return player.hasPermission("anticheat.bypass")
                || wm.isWhitelisted(player.getUniqueId())
                || gm == GameMode.CREATIVE
                || gm == GameMode.SPECTATOR
                || player.isDead()
                || vm.hasJoinGrace(player.getUniqueId())
                || vm.hasTeleportGrace(player.getUniqueId());
    }

    private void debugLog(Player player, String msg) {
        if (plugin.getConfig().getBoolean("debug.enabled", false)) {
            plugin.getLogger().info("[DEBUG][Misc] " + player.getName() + " » " + msg);
        }
    }

    private String fmt(double d) { return String.format("%.3f", d); }
}
