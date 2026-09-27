package dev.anticheat.listeners;

import dev.anticheat.AntiCheatPlugin;
import dev.anticheat.managers.BanManager;
import dev.anticheat.managers.ViolationManager;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.potion.PotionEffectType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Detects "VClip" — vertical-teleport cheats.
 *
 * Inspected Astro source (VClipModule.java) — on toggle it runs:
 *
 *     player.setPos(x, y ± dist, z);
 *     player.setDeltaMovement(dx, 0, dz);
 *
 * with {@code dist} 1–50 blocks (default 5).  Vanilla "moved too quickly"
 * rejects only deltas of ~10+ blocks, so a 5-block vclip slips through.
 *
 * ── Two complementary layers ────────────────────────────────────────────────
 *
 *  L1 — RAW Δy MAGNITUDE.  Vanilla per-move vertical Δ caps at ~1.95 b/t
 *       (terminal-velocity fall) downward and ~0.42 b/t (jump take-off) up.
 *       Anything above {@link #DEFAULT_DY_TRIGGER} (2.0 b) in a single move
 *       event is a teleport.  This is what catches Astro's "5 blocks up
 *       through open air" case — no walls required.
 *
 *  L2 — THROUGH-WALL.  For smaller vclips (Δy 1.0–2.0) that DO pass through
 *       solid blocks, the scanned column has at least one solid+occluding
 *       block between the player's old head and new feet — vanilla physics
 *       can't cross those.
 *
 * Server-initiated teleports (commands, pearls, portals, bed, plugin tp)
 * fire PlayerTeleportEvent, NOT PlayerMoveEvent, so they never reach this
 * handler.  No additional exemption is needed for them.
 */
public class VClipListener implements Listener {

    private final AntiCheatPlugin plugin;
    private final ViolationManager vm;
    private final BanManager bm;

    /** Upward single-move Δy above this is always a teleport.  Vanilla max
     *  upward Δy per move is ~0.42 (jump take-off); 1.0 is safely above
     *  every legitimate vertical move (jump-boost / levitation / slime /
     *  wind / mace / riptide / pearl are all teleport-grace exempt). */
    private static final double DEFAULT_UP_TRIGGER   = 1.0;

    /** Downward single-move Δy below this (negative) is always a teleport.
     *  Vanilla terminal fall ≈ 1.95 b/t — sustained falls never exceed it,
     *  so anything below 2.0 (-2.0 Δy) in one move is a vclip. */
    private static final double DEFAULT_DOWN_TRIGGER = 2.0;

    /** Min |Δy| to bother scanning for the through-wall path check.  1.0
     *  catches sub-trigger downward vclips that pass through a single
     *  floor block (which a normal 1-block fall through air doesn't, so
     *  no false positive). */
    private static final double THROUGH_WALL_MIN_DY = 1.1;

    /** Rate-limit (ms) between flags. */
    private static final long FLAG_COOLDOWN_MS = 200;

    private final Map<UUID, Integer> vclipVL      = new HashMap<>();
    private final Map<UUID, Long>    lastFlagTime = new HashMap<>();

    /**
     * Deferred-flag state.  When a large Δy move arrives we don't immediately
     * flag — we stash the reason here and wait to see whether the NEXT move
     * is also large.  Two large moves in a row = sustained cheat-fly (let
     * FlyListener handle).  Large followed by small = single-packet vclip,
     * confirmed → flag now.
     */
    private final Map<UUID, String> pendingFlag = new HashMap<>();

    /**
     * Counts CONSECUTIVE large-Δy moves per player.  Only the FIRST large
     * move (count == 1) can produce a pending flag; subsequent large moves
     * clear pending and don't set a new one, so the eventual transition
     * back to small Δy (e.g. cheat-fly stopping and gravity taking over)
     * doesn't confirm a phantom pending.
     */
    private final Map<UUID, Integer> consecutiveBigDy = new HashMap<>();

    public VClipListener(AntiCheatPlugin plugin) {
        this.plugin = plugin;
        this.vm = plugin.getViolationManager();
        this.bm = plugin.getBanManager();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!plugin.getConfig().getBoolean("checks.vclip", true)) return;
        // No TPS kill-switch: per-packet movement isn't changed by server lag.
        if (event.getTo() == null) return;

        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        if (isExempt(player)) {
            pendingFlag.remove(id);
            return;
        }

        Location from = event.getFrom();
        Location to   = event.getTo();
        if (to.getWorld() == null || from.getWorld() == null
                || !to.getWorld().equals(from.getWorld())) {
            pendingFlag.remove(id);
            return;
        }

        double dy = to.getY() - from.getY();
        double absDy = Math.abs(dy);

        double upTrigger = plugin.getConfig().getDouble(
                "settings.vclip-up-trigger", DEFAULT_UP_TRIGGER);
        double downTrigger = plugin.getConfig().getDouble(
                "settings.vclip-down-trigger", DEFAULT_DOWN_TRIGGER);

        // Asymmetric "big" check — upward is far stricter because vanilla
        // upward Δy caps at 0.42 (jump take-off), while downward routinely
        // reaches 1.95 (terminal fall).
        boolean bigDy = dy > 0 ? dy > upTrigger : -dy > downTrigger;

        // ── Update consecutive-big counter ─────────────────────────────────
        // During sustained cheat fly each tick has bigDy, count > 1, so we
        // never set a fresh pending.  When fly stops and Δy drops back to
        // small, there's no pending to confirm — no false flag.
        int prevBigCount = consecutiveBigDy.getOrDefault(id, 0);
        int bigCount = bigDy ? prevBigCount + 1 : 0;
        consecutiveBigDy.put(id, bigCount);

        // ── Deferred-flag confirmation pass ────────────────────────────────
        String pending = pendingFlag.get(id);
        if (pending != null) {
            if (bigDy) {
                // Two large Δy moves in a row → sustained cheat fly, not
                // a single-packet vclip.  Cancel pending.
                pendingFlag.remove(id);
            } else {
                // Previous tick was the FIRST large move (small → large),
                // and now we're back to small → confirmed single-packet
                // teleport.
                flag(player, pending);
                pendingFlag.remove(id);
                return;
            }
        }

        if (bigDy) {
            // Only the FIRST large-Δy move (after a stretch of small) can be
            // a pending vclip.  Subsequent large moves are sustained fly.
            if (bigCount > 1) return;
            pendingFlag.put(id, "VClip dy=" + String.format("%.3f", dy)
                    + " (above " + (dy > 0 ? upTrigger : downTrigger) + "b cap)");
            return;
        }

        // ── L2 — through-wall path check (catches sub-trigger vclips) ──────
        if (absDy < THROUGH_WALL_MIN_DY) return;

        World w = to.getWorld();
        int x = to.getBlockX();
        int z = to.getBlockZ();
        int fromFeet = (int) Math.floor(from.getY());
        int toFeet   = (int) Math.floor(to.getY());

        int low  = Math.min(fromFeet, toFeet);
        int high = Math.max(fromFeet, toFeet);

        // Blocks the player's body strictly traversed: from low+2 (above
        // their original head) up to high-1 (below their final feet).
        for (int by = low + 2; by <= high - 1; by++) {
            Material m = w.getBlockAt(x, by, z).getType();
            if (m.isSolid() && m.isOccluding()) {
                // Through-wall is unambiguous — flag immediately, no defer.
                flag(player, "VClip dy=" + String.format("%.3f", dy)
                        + " through " + m + " @y=" + by);
                return;
            }
        }
    }

    private void flag(Player player, String reason) {
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();
        Long lastFlag = lastFlagTime.get(id);
        if (lastFlag != null && now - lastFlag < FLAG_COOLDOWN_MS) return;
        lastFlagTime.put(id, now);

        int vl = vclipVL.getOrDefault(id, 0) + 2; // hard signal, 2 VL per flag
        vclipVL.put(id, vl);
        int vlThreshold = plugin.getConfig().getInt("settings.vclip-vl-threshold", 2);

        if (plugin.getConfig().getBoolean("debug.enabled", false)) {
            plugin.getLogger().info("[DEBUG][VClip] " + player.getName() + " " + reason
                    + " VL=" + vl + "/" + vlThreshold);
        }

        if (vl >= vlThreshold) {
            vclipVL.remove(id);
            bm.banPlayer(player, reason);
        }
    }

    private boolean isExempt(Player player) {
        UUID id = player.getUniqueId();
        GameMode gm = player.getGameMode();
        return player.hasPermission("anticheat.bypass")
            || plugin.getWhitelistManager().isWhitelisted(id)
            || gm == GameMode.SPECTATOR
            || gm == GameMode.CREATIVE
            || player.isFlying()
            || player.isGliding()                                      // elytra (server-validated)
            || player.hasPotionEffect(PotionEffectType.LEVITATION)     // server-validated buff
            || player.isDead()
            || vm.hasJoinGrace(id)
            || vm.hasTeleportGrace(id);
    }

    public void removePlayer(UUID id) {
        vclipVL.remove(id);
        lastFlagTime.remove(id);
        pendingFlag.remove(id);
        consecutiveBigDy.remove(id);
    }
}
