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

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * ImpossibleSprintListener — detects sprinting while in states that make
 * sprinting server-impossible.
 *
 * Vanilla Minecraft disables sprinting the instant any of these become true:
 *   • isBlocking  — drawing a bow, raising a shield
 *   • isSneaking  — crouching
 *   • isHandRaised — eating, drinking, using a potion
 *
 * A sprinting player who is ALSO in any of these states is sending forged
 * sprint packets — a modified client.
 *
 * False-positive fixes:
 *   • Golden apple / food eating: when a player rapidly right-clicks to eat,
 *     then releases while still sprinting, there can be brief moments where
 *     the sprint packet races ahead of the hand-raise state. We require
 *     the state to persist for MIN_VIOLATION_TICKS (3 ticks = 150ms) before
 *     counting it as a violation. A legitimate eat-and-sprint lasts < 150ms;
 *     a real ImpossibleSprint hack lasts the entire time.
 *   • 2-second cooldown between flags prevents rapid VL accumulation from
 *     borderline cases.
 *
 * Detection: persist for 3 ticks → flag. VL_THRESHOLD flags within cooldown
 * window = ban.
 */
public class ImpossibleSprintListener implements Listener {

    private final AntiCheatPlugin plugin;
    private final BanManager bm;
    private final ViolationManager vm;
    private final WhitelistManager wm;

    private final Map<UUID, Integer> vl = new HashMap<>();
    /** Consecutive ticks the impossible state has persisted. */
    private final Map<UUID, Integer> persistTicks = new HashMap<>();
    /** Wall-clock time (ms) of the last flag — 2s cooldown between flags. */
    private final Map<UUID, Long> lastFlagMs = new HashMap<>();

    private static final int DEFAULT_BAN_VL = 2;
    private static final int MIN_VIOLATION_TICKS = 5;
    private static final long FLAG_COOLDOWN_MS = 500L;

    public ImpossibleSprintListener(AntiCheatPlugin plugin) {
        this.plugin = plugin;
        this.bm = plugin.getBanManager();
        this.vm = plugin.getViolationManager();
        this.wm = plugin.getWhitelistManager();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!plugin.getConfig().getBoolean("checks.impossiblesprint", true)) return;
        // No TPS kill-switch: sprint/sneak state comes from the client's own packets.
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();

        if (!player.isSprinting()) {
            reset(id);
            return;
        }

        if (isExempt(player)) {
            reset(id);
            return;
        }

        boolean blocking = player.isBlocking();
        boolean sneaking = player.isSneaking();
        boolean handRaised = player.isHandRaised();

        if (!blocking && !sneaking && !handRaised) {
            persistTicks.remove(id);
            return;
        }

        Location from = event.getFrom();
        Location to = event.getTo();
        double hSpeed = 0;
        if (to != null && from != null) {
            double dx = to.getX() - from.getX();
            double dz = to.getZ() - from.getZ();
            hSpeed = Math.sqrt(dx * dx + dz * dz);
        }

        if (handRaised && !blocking && !sneaking) {
            persistTicks.remove(id);
            return;
        }

        if (blocking && hSpeed < 0.01) {
            persistTicks.remove(id);
            return;
        }

        if (sneaking && hSpeed < 0.10) {
            persistTicks.remove(id);
            return;
        }

        int ticks = persistTicks.merge(id, 1, Integer::sum);

        if (ticks < MIN_VIOLATION_TICKS) return;

        long now = System.currentTimeMillis();
        Long lastFlag = lastFlagMs.get(id);
        if (lastFlag != null && now - lastFlag < FLAG_COOLDOWN_MS) return;

        lastFlagMs.put(id, now);

        int banVL = plugin.getConfig().getInt("settings.impossiblesprint-vl-threshold", DEFAULT_BAN_VL);
        int currentVL = vl.merge(id, 1, Integer::sum);
        debugLog(player, "ImpossibleSprint — sprinting while "
                + (blocking ? "blocking" : sneaking ? "sneaking" : "hand raised")
                + " persist=" + ticks + "ticks vl=" + currentVL + "/" + banVL);

        if (currentVL >= banVL) {
            reset(id);
            bm.banPlayer(player, "ImpossibleSprint");
        }
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
                || vm.hasJoinGrace(player.getUniqueId())
                || vm.hasTeleportGrace(player.getUniqueId());
    }

    private void reset(UUID id) {
        vl.remove(id);
        persistTicks.remove(id);
        lastFlagMs.remove(id);
    }

    public void removePlayer(UUID id) {
        reset(id);
    }

    /** Drops the in-progress streak, keeping VL and flag cooldown. */
    public void resetMotion(UUID id) {
        persistTicks.remove(id);
    }

    private void debugLog(Player player, String msg) {
        if (plugin.getConfig().getBoolean("debug.enabled", false)) {
            plugin.getLogger().info("[DEBUG][ImpossibleSprint] " + player.getName() + " » " + msg);
        }
    }
}
