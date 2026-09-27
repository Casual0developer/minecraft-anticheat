package dev.anticheat.listeners;

import dev.anticheat.AntiCheatPlugin;
import dev.anticheat.managers.BanManager;
import dev.anticheat.managers.ViolationManager;
import dev.anticheat.managers.WhitelistManager;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * FastLadderListener — detects climbing ladders/vines faster than vanilla allows.
 *
 * Vanilla upward climb speed: ~0.1175 b/t (fixed, unaffected by Speed/Jump Boost).
 * Detection threshold: 0.25 b/t — more than double vanilla, generous enough to
 * absorb minor lag-induced packet batching while still catching any meaningful
 * fast-ladder cheat.
 *
 * Uses a local VL counter (ban at 3) rather than instant ban to absorb the rare
 * edge case where a single lag spike bundles two ticks of legitimate movement
 * into one packet.  A real fast-ladder user exceeds the threshold on every
 * climbing tick, so they hit VL 3 within 3 consecutive flagged ticks.
 */
public class FastLadderListener implements Listener {

    private final AntiCheatPlugin  plugin;
    private final BanManager       bm;
    private final ViolationManager vm;
    private final WhitelistManager wm;

    /** Per-player violation accumulator — incremented on fast climb, reset otherwise. */
    private final Map<UUID, Integer> vl = new HashMap<>();

    /** Vanilla ladder climb speed is ~0.1175 b/t.  Threshold is ~2× that. */
    private static final double MAX_CLIMB_SPEED = 0.25;
    private static final int    BAN_VL          = 3;

    public FastLadderListener(AntiCheatPlugin plugin) {
        this.plugin = plugin;
        this.bm     = plugin.getBanManager();
        this.vm     = plugin.getViolationManager();
        this.wm     = plugin.getWhitelistManager();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!plugin.getConfig().getBoolean("checks.fastladder", true)) return;
        // No TPS kill-switch: per-packet movement isn't changed by server lag.
        Player   player = event.getPlayer();
        UUID     id     = player.getUniqueId();
        Location from   = event.getFrom();
        Location to     = event.getTo();
        if (to == null) return;

        // ── Not climbing — reset VL and bail ─────────────────────────────────
        if (!player.isClimbing()) {
            vl.remove(id);
            return;
        }

        if (isExempt(player)) {
            vl.remove(id);
            return;
        }

        double deltaY = to.getY() - from.getY();

        // Only care about upward movement (downward fast-ladder is far less
        // impactful and has more false positives from fall + ladder contact)
        if (deltaY <= 0) {
            vl.remove(id);
            return;
        }

        if (deltaY <= MAX_CLIMB_SPEED) {
            // Legitimate climb tick — decay VL
            if (vl.getOrDefault(id, 0) > 0)
                vl.merge(id, -1, Integer::sum);
            return;
        }

        // Fast climb detected
        int currentVL = vl.merge(id, 1, Integer::sum);
        debugLog(player, "FastLadder — deltaY=" + String.format("%.4f", deltaY)
                + " vl=" + currentVL);

        if (currentVL >= BAN_VL) {
            vl.remove(id);
            bm.banPlayer(player, "FastLadder");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Exemption check
    // ─────────────────────────────────────────────────────────────────────────

    private boolean isExempt(Player player) {
        GameMode gm = player.getGameMode();
        return player.hasPermission("anticheat.bypass")
                || wm.isWhitelisted(player.getUniqueId())
                || gm == GameMode.CREATIVE
                || gm == GameMode.SPECTATOR
                || player.isDead()
                || player.isInsideVehicle()
                || vm.hasJoinGrace(player.getUniqueId())
                || vm.hasTeleportGrace(player.getUniqueId())
                || vm.hasDamageGrace(player.getUniqueId());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Cleanup
    // ─────────────────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        removePlayer(event.getPlayer().getUniqueId());
    }

    public void removePlayer(UUID id) {
        vl.remove(id);
    }

    private void debugLog(Player player, String msg) {
        if (plugin.getConfig().getBoolean("debug.enabled", false))
            plugin.getLogger().info("[DEBUG][FastLadder] " + player.getName() + " » " + msg);
    }
}
