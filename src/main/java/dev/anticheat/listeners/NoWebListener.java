package dev.anticheat.listeners;

import dev.anticheat.AntiCheatPlugin;
import dev.anticheat.managers.BanManager;
import dev.anticheat.managers.ViolationManager;
import dev.anticheat.managers.WhitelistManager;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * NoWebListener — detects bypassing cobweb slowdown.
 *
 * Vanilla cobweb dampens ALL movement to ~0.05 b/t (XZ) and ~0.03 b/t (Y).
 * It also prevents jumping entirely. A NoWeb hack either lets the player
 * move at normal speed or jump inside the cobweb.
 *
 * Two sub-checks:
 *   ① Speed — horizontal speed exceeds MAX_WEB_SPEED while in cobweb
 *   ② Jump — any upward Δy > 0.0 while in cobweb (vanilla: jump = impossible)
 *
 * Detection: VL-based. Exceeding speed OR jumping increments VL.
 * BAN_VL consecutive flags = ban.
 */
public class NoWebListener implements Listener {

    private final AntiCheatPlugin plugin;
    private final BanManager bm;
    private final ViolationManager vm;
    private final WhitelistManager wm;

    private final Map<UUID, Integer> vl = new HashMap<>();
    private final Map<UUID, Double> prevX = new HashMap<>();
    private final Map<UUID, Double> prevZ = new HashMap<>();
    private final Map<UUID, Double> prevY = new HashMap<>();

    private static final double DEFAULT_MAX_WEB_SPEED = 0.10;
    private static final int DEFAULT_BAN_VL = 3;

    public NoWebListener(AntiCheatPlugin plugin) {
        this.plugin = plugin;
        this.bm = plugin.getBanManager();
        this.vm = plugin.getViolationManager();
        this.wm = plugin.getWhitelistManager();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!plugin.getConfig().getBoolean("checks.noweb", true)) return;
        // No TPS kill-switch: per-packet movement isn't changed by server lag.
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();

        if (isExempt(player)) {
            reset(id);
            return;
        }

        if (!isInCobweb(player)) {
            reset(id);
            return;
        }

        Location to = event.getTo();
        if (to == null) return;

        Double px = prevX.get(id);
        Double pz = prevZ.get(id);
        Double py = prevY.get(id);
        prevX.put(id, to.getX());
        prevZ.put(id, to.getZ());
        prevY.put(id, to.getY());

        if (px == null || pz == null || py == null) return;

        double dx = to.getX() - px;
        double dz = to.getZ() - pz;
        double deltaY = to.getY() - py;
        double speed = Math.sqrt(dx * dx + dz * dz);

        double maxSpeed = plugin.getConfig().getDouble("settings.noweb-max-speed", DEFAULT_MAX_WEB_SPEED);
        int banVL = plugin.getConfig().getInt("settings.noweb-vl-threshold", DEFAULT_BAN_VL);

        boolean violation = false;
        String detail = "";

        if (speed > maxSpeed) {
            violation = true;
            detail = "speed=" + String.format("%.4f", speed) + " max=" + maxSpeed;
        } else if (deltaY > 0.01) {
            violation = true;
            detail = "jump dY=" + String.format("%.4f", deltaY);
        }

        if (!violation) {
            vl.remove(id);
            return;
        }

        int currentVL = vl.merge(id, 1, Integer::sum);
        debugLog(player, "NoWeb — " + detail + " vl=" + currentVL + "/" + banVL);

        if (currentVL >= banVL) {
            vl.remove(id);
            bm.banPlayer(player, "NoWeb " + detail);
        }
    }

    private boolean isInCobweb(Player player) {
        Location loc = player.getLocation();
        Block feet = loc.getBlock();
        Block head = loc.clone().add(0, 1.0, 0).getBlock();
        return feet.getType() == Material.COBWEB || head.getType() == Material.COBWEB;
    }

    private boolean isExempt(Player player) {
        GameMode gm = player.getGameMode();
        return player.hasPermission("anticheat.bypass")
                || wm.isWhitelisted(player.getUniqueId())
                || gm == GameMode.CREATIVE
                || gm == GameMode.SPECTATOR
                || player.isDead()
                || player.isInsideVehicle()
                || player.isFlying()
                || player.getAllowFlight()
                || player.isGliding()
                || vm.hasJoinGrace(player.getUniqueId())
                || vm.hasTeleportGrace(player.getUniqueId());
    }

    private void reset(UUID id) {
        vl.remove(id);
        prevX.remove(id);
        prevZ.remove(id);
        prevY.remove(id);
    }

    public void removePlayer(UUID id) {
        reset(id);
    }

    private void debugLog(Player player, String msg) {
        if (plugin.getConfig().getBoolean("debug.enabled", false)) {
            plugin.getLogger().info("[DEBUG][NoWeb] " + player.getName() + " » " + msg);
        }
    }
}
