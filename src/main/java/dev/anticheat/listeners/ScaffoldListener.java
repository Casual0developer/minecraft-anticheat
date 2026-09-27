package dev.anticheat.listeners;

import dev.anticheat.AntiCheatPlugin;
import dev.anticheat.managers.BanManager;
import dev.anticheat.managers.ViolationManager;
import dev.anticheat.utils.GroundUtil;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * ScaffoldListener — three independent sub-checks:
 *
 * ① Scaffold[AbnormalPitch]  (VL-based)
 *   Catches scaffold mods that use NO server-side rotation (silent-rotate or
 *   no-rotate mode).  When the mod places blocks without rotating the player's
 *   head, the server-side pitch stays near 0° (horizontal) or negative
 *   (upward) — impossible for legitimate bridging, which requires ~30–75°
 *   downward pitch to click the target face.
 *   Requires PITCH_STREAK consecutive scaffold placements below the threshold.
 *
 * ② Scaffold[RotationSnap]   (VL-based)  ← primary detector for Wurst Walk
 *   Catches scaffold mods that DO send server-side rotation packets (e.g.
 *   Wurst scaffold "Walk").  These mods rotate the player's pitch from its
 *   natural value (~0–15°) to the target angle (~60–80°) in a single tick,
 *   place the block, then immediately snap back.  On the server this appears
 *   as a PlayerMoveEvent with |Δpitch| > SNAP_DEGREES, followed within
 *   SNAP_PLACE_MS by a scaffold-pattern BlockPlaceEvent.
 *
 *   Why this is safe: a human who rapidly swings their mouse 40°+ and then
 *   immediately places a foot-level block is already unusual.  Doing it for
 *   SNAP_STREAK consecutive scaffold placements while sprinting is not
 *   physically reproducible without automation.
 *
 * ③ Scaffold[PlacementRate]  (VL-based)
 *   Catches any scaffold mod regardless of rotation mode by tracking how many
 *   scaffold-pattern blocks are placed within a rolling window.
 *   God-bridge (fastest legit technique) peaks at ~4–5 b/s = 6–7 in 1 500 ms.
 *   We flag at RATE_THRESHOLD (8) inside RATE_WINDOW_MS (1 500 ms).
 *
 * False-positive guards applied before any sub-check:
 *   • Creative / Spectator / flying / gliding / dead / vehicle / bypass perm / whitelist
 *   • VM grace:   join, teleport, damage
 *   • Elytra grace:  2 000 ms after last gliding tick
 *   • Liquid grace:  1 500 ms after leaving water / lava
 *   • Solidity guard: only full-solid walkable blocks are tracked
 *   • Y-level guard:  placed block must be at or below foot level (≤ 2 below)
 *   • Proximity guard: placed block must be ≤ 3.0 blocks horizontally from player
 *   • Speed guard:   player must be moving (≥ 0.10 b/event)
 *   • Movement-direction filter: block must be in the forward 90° arc of velocity
 *     (prevents wall-building FP: running north + placing east → dot ≤ 0 → excluded)
 *   • Air-tick guard: player must be on/near ground (≤ 3 air ticks)
 *   • High-ping skip: AbnormalPitch skipped above 250 ms; RotationSnap + Rate still run
 *   • Sneak relaxation: pitch threshold eased 5° for sneak-bridge
 */
public class ScaffoldListener implements Listener {

    private final AntiCheatPlugin plugin;
    private final ViolationManager vm;
    private final BanManager bm;

    // ── Position tracker ──────────────────────────────────────────────────────
    private final Map<UUID, Location> lastLocation = new HashMap<>();
    private final Map<UUID, Long>     lastMoveMs   = new HashMap<>();

    // ── RotationSnap tracking ─────────────────────────────────────────────────
    /** Pitch value at the most recent PlayerMoveEvent. */
    private final Map<UUID, Float> lastPitch     = new HashMap<>();
    /**
     * Epoch ms of the most recent move event where |Δpitch| > SNAP_DEGREES.
     * Scaffold mods rotate to the target angle in one tick; humans move their
     * mouse gradually across several events.
     */
    private final Map<UUID, Long>  pitchSnapMs   = new HashMap<>();
    /** Consecutive scaffold placements that each coincided with a recent pitch snap. */
    private final Map<UUID, Integer> rotSnapStreak = new HashMap<>();

    // ── AbnormalPitch streak ──────────────────────────────────────────────────
    private final Map<UUID, Integer> pitchStreak = new HashMap<>();

    // ── PlacementRate rolling window ──────────────────────────────────────────
    private final Map<UUID, Deque<Long>> placeTimes = new HashMap<>();

    // ── Elytra / liquid grace timestamps ─────────────────────────────────────
    private final Map<UUID, Long> lastGlidingMs = new HashMap<>();
    private final Map<UUID, Long> lastLiquidMs  = new HashMap<>();

    // ── Thresholds ────────────────────────────────────────────────────────────

    /**
     * Bukkit pitch: -90 = straight up, 0 = horizontal, 90 = straight down.
     * Legit bridging: ~30–75° downward.  We flag below 20°: anything near
     * horizontal.  Raised from 10° → 20° to also catch scaffold mods that
     * apply a tiny downward tilt (< 20°) to barely avoid the old threshold.
     * Used by AbnormalPitch (silent-rotate / no-rotate scaffold).
     */
    private static final float  PITCH_FLAG_DEGREES = 20.0f;
    /** Threshold relaxation when player is sneaking (sneak-bridge needs less pitch). */
    private static final float  PITCH_SNEAK_BONUS  = 5.0f;
    /** Consecutive scaffold placements at abnormal pitch before VL is added. */
    private static final int    PITCH_STREAK       = 3;

    /**
     * Pitch delta (degrees) between consecutive move events considered a "snap".
     * Wurst scaffold rotates 50–80° in one tick.  We use 25° so we still catch
     * rotations from a partially-downward starting pitch (e.g. player at 35°
     * rotating to 70° = delta 35° would miss a 40° threshold).
     * SNAP_STREAK = 2 prevents a single fast mouse-flick from false-flagging.
     */
    private static final float  SNAP_DEGREES       = 25.0f;
    /**
     * Max ms between a pitch snap and a scaffold placement for the snap to count.
     * One tick = 50 ms; 200 ms gives a 4-tick window for packet-ordering variance.
     */
    private static final long   SNAP_PLACE_MS      = 200L;
    /** Consecutive snap-coincident scaffold placements before VL is added. */
    private static final int    SNAP_STREAK        = 2;

    /**
     * Max scaffold-pattern blocks in RATE_WINDOW_MS before PlacementRate flags.
     * God-bridge peak ≈ 4–5 b/s = 5–6 in 1 200 ms.  We flag at 7.
     */
    private static final int    RATE_THRESHOLD     = 7;
    /** Rolling window for the placement-rate sub-check (ms). */
    private static final long   RATE_WINDOW_MS     = 1_200L;

    /** Minimum horizontal speed (blocks/event) to be "moving while placing". */
    private static final double MIN_MOVE_SPEED     = 0.10;
    /** Max horizontal distance from player centre to placed-block centre. */
    private static final double MAX_PLACE_RADIUS   = 3.0;
    /** Y blocks below foot level still in scaffold range. */
    private static final int    MAX_Y_BELOW        = 2;

    /** Grace after leaving liquid (ms). */
    private static final long   LIQUID_GRACE_MS    = 1_500L;
    /** Grace after last gliding tick (ms). */
    private static final long   ELYTRA_GRACE_MS    = 2_000L;
    /** Ping ceiling for AbnormalPitch (RotationSnap and PlacementRate still run). */
    private static final int    MAX_PING_FOR_PITCH = 250;

    public ScaffoldListener(AntiCheatPlugin plugin) {
        this.plugin = plugin;
        this.vm     = plugin.getViolationManager();
        this.bm     = plugin.getBanManager();
    }

    // ── Position / pitch tracking ─────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        GameMode gm = player.getGameMode();
        if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR) return;

        UUID id  = player.getUniqueId();
        long now = System.currentTimeMillis();

        // ── Elytra / liquid grace timestamps ──────────────────────────────────
        if (player.isGliding()) lastGlidingMs.put(id, now);
        Material feet = player.getLocation().getBlock().getType();
        if (player.isInWater()
                || feet == Material.LAVA
                || feet == Material.KELP
                || feet == Material.KELP_PLANT
                || feet == Material.SEAGRASS
                || feet == Material.TALL_SEAGRASS
                || feet == Material.BUBBLE_COLUMN) {
            lastLiquidMs.put(id, now);
        }

        // ── RotationSnap pitch-delta detection ────────────────────────────────
        float currPitch = event.getTo().getPitch();
        Float prevPitch = lastPitch.get(id);
        if (prevPitch != null && Math.abs(currPitch - prevPitch) >= SNAP_DEGREES) {
            // Pitch changed by ≥ 40° in a single move packet — characteristic of
            // scaffold mods that rotate to the target face and back in one tick.
            pitchSnapMs.put(id, now);
        }
        lastPitch.put(id, currPitch);

        // ── Position (used for velocity approximation in isScaffoldPattern) ───
        lastLocation.put(id, event.getFrom().clone());
        lastMoveMs.put(id, now);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        removePlayer(event.getPlayer().getUniqueId());
    }

    // ── Core check ────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!plugin.getConfig().getBoolean("checks.scaffold", true)) return;
        if (dev.anticheat.utils.TPSUtil.isSeverelyLagging()) return;
        Player player = event.getPlayer();
        UUID   id     = player.getUniqueId();

        if (isExempt(player)) { resetStreaks(id); return; }

        if (vm.hasJoinGrace(id) || vm.hasTeleportGrace(id) || vm.hasDamageGrace(id)) {
            resetStreaks(id);
            return;
        }

        long now = System.currentTimeMillis();

        Long glideTime  = lastGlidingMs.get(id);
        Long liquidTime = lastLiquidMs.get(id);
        if ((glideTime  != null && now - glideTime  < ELYTRA_GRACE_MS)
         || (liquidTime != null && now - liquidTime < LIQUID_GRACE_MS)) {
            resetStreaks(id);
            return;
        }

        Block    placed    = event.getBlockPlaced();
        Location playerLoc = player.getLocation();

        if (!isScaffoldPattern(player, id, placed, playerLoc, now)) {
            pitchStreak.remove(id);
            rotSnapStreak.remove(id);
            return;
        }

        // ── Sub-check A: AbnormalPitch (no-rotate / silent-rotate scaffold) ───
        checkAbnormalPitch(player, id, playerLoc);

        // ── Sub-check B: RotationSnap (full-rotate scaffold, e.g. Wurst Walk) ─
        checkRotationSnap(player, id, now);

        // ── Sub-check C: PlacementRate (all scaffold types) ───────────────────
        checkPlacementRate(player, id, now);
    }

    // ── Scaffold-pattern detection ────────────────────────────────────────────

    private boolean isScaffoldPattern(Player player, UUID id, Block placed,
                                      Location playerLoc, long now) {

        // Only full-solid walkable blocks.  Decorative / passable / special blocks
        // (signs, torches, rails, buttons, scaffolding, dripstone) can be placed
        // at unusual angles legitimately.
        Material mat = placed.getType();
        if (!mat.isSolid() || mat.isAir()) return false;
        if (mat == Material.SCAFFOLDING || mat == Material.POINTED_DRIPSTONE) return false;

        // Block must be at or below foot level (classic scaffold bridge pattern).
        double footY   = playerLoc.getY();
        double placedY = placed.getY();
        if (placedY > footY || footY - placedY > MAX_Y_BELOW) return false;

        // Block must be close horizontally (not a distant wall or terrain fill).
        double dx = placed.getX() + 0.5 - playerLoc.getX();
        double dz = placed.getZ() + 0.5 - playerLoc.getZ();
        if (dx * dx + dz * dz > MAX_PLACE_RADIUS * MAX_PLACE_RADIUS) return false;

        // Player must be moving.
        double[] vel   = horizontalVelocity(id, playerLoc, now);
        double   speed = Math.sqrt(vel[0] * vel[0] + vel[1] * vel[1]);
        if (speed < MIN_MOVE_SPEED) return false;

        // Direction filter — eliminate the wall-building false-positive without
        // breaking scaffold detection when the player has stepped slightly past
        // the block's centre (which makes dz negative and dot < 0).
        //
        // Rule: the placed block must NOT be more than 1.5 blocks BEHIND the
        // player in the movement direction.  "Behind" = negative dot product.
        // Blocks directly below (hDist < 0.8) are always allowed — they are the
        // airborne-scaffold case where dx ≈ dz ≈ 0.
        //
        // Wall-building FP:  running north (vel_z < 0) + placing east (dz ≈ 0)
        //   → dot = vel_x*1 + vel_z*0 = 0 → excluded iff hDist ≥ 0.8.
        // Wurst stepping past block centre: vel_z > 0, block slightly behind
        //   (dz = −0.2) → dot = small negative, but hDist ≈ 0.2–0.5 < 0.8
        //   → allowed.
        double hDist = Math.sqrt(dx * dx + dz * dz);
        double dot   = vel[0] * dx + vel[1] * dz;
        if (dot < -speed * 1.5 && hDist >= 0.8) return false;

        // Player must be near the ground.  Allow up to 5 air ticks so scaffold
        // that fires 2–3 ticks after the player steps off the edge is still caught.
        // Client claim OR server-seen ground, so a fake "airborne" claim doesn't skip the check.
        if (!player.isOnGround() && !GroundUtil.touchingGround(player, playerLoc)
                && vm.getAirTicks(id) > 5) return false;

        return true;
    }

    // ── Sub-check A: AbnormalPitch ────────────────────────────────────────────

    private void checkAbnormalPitch(Player player, UUID id, Location playerLoc) {
        if (player.getPing() > MAX_PING_FOR_PITCH) {
            pitchStreak.remove(id);
            return;
        }

        float threshold = player.isSneaking()
                ? PITCH_FLAG_DEGREES - PITCH_SNEAK_BONUS   // 5°
                : PITCH_FLAG_DEGREES;                      // 10°

        float pitch = playerLoc.getPitch();

        if (pitch < threshold) {
            int streak = pitchStreak.merge(id, 1, Integer::sum);
            debugLog(player, "AbnormalPitch streak=" + streak + "/" + PITCH_STREAK
                    + " pitch=" + fmt(pitch));
            if (streak >= PITCH_STREAK) {
                pitchStreak.put(id, 0);
                flag(player, id, "Scaffold[AbnormalPitch]", 1);
            }
        } else {
            pitchStreak.remove(id);
        }
    }

    // ── Sub-check B: RotationSnap ─────────────────────────────────────────────

    /**
     * Wurst scaffold Walk rotates the player's head to the target block face
     * (~60–80° downward) in a single Move packet, places the block, then
     * immediately snaps back.  Each block placement is preceded by a
     * |Δpitch| ≥ SNAP_DEGREES event within SNAP_PLACE_MS.
     *
     * A human who quickly flicks their mouse 40°+ and immediately places a
     * foot-level block could trigger this once; requiring SNAP_STREAK (2)
     * consecutive snap-coincident placements eliminates that edge case.
     */
    private void checkRotationSnap(Player player, UUID id, long now) {
        Long snapTime = pitchSnapMs.get(id);
        if (snapTime != null && now - snapTime <= SNAP_PLACE_MS) {
            // This scaffold placement was preceded by a rapid pitch change.
            int streak = rotSnapStreak.merge(id, 1, Integer::sum);
            debugLog(player, "RotationSnap streak=" + streak + "/" + SNAP_STREAK
                    + " snapAge=" + (now - snapTime) + "ms");
            if (streak >= SNAP_STREAK) {
                rotSnapStreak.put(id, 0);
                flag(player, id, "Scaffold[RotationSnap]", 1);
            }
        } else {
            // No recent snap → clear streak so isolated mouse-flicks don't accumulate.
            rotSnapStreak.remove(id);
        }
    }

    // ── Sub-check C: PlacementRate ────────────────────────────────────────────

    private void checkPlacementRate(Player player, UUID id, long now) {
        Deque<Long> times = placeTimes.computeIfAbsent(id, k -> new ArrayDeque<>());
        times.addLast(now);
        while (!times.isEmpty() && now - times.peekFirst() > RATE_WINDOW_MS) {
            times.pollFirst();
        }
        int count = times.size();
        debugLog(player, "PlacementRate count=" + count + "/" + RATE_THRESHOLD
                + " window=" + RATE_WINDOW_MS + "ms");
        if (count >= RATE_THRESHOLD) {
            times.clear();
            flag(player, id, "Scaffold[PlacementRate]", 1);
        }
    }

    // ── Flag ──────────────────────────────────────────────────────────────────

    private void flag(Player player, UUID id, String checkName, int amount) {
        vm.incrementScaffoldVL(id, amount);
        int vl        = vm.getScaffoldVL(id);
        int threshold = plugin.getConfig().getInt("settings.scaffold-vl-threshold", 5);
        debugLog(player, checkName + " VL=" + vl + "/" + threshold);
        if (vl >= threshold) {
            vm.remove(id);
            removePlayer(id);
            bm.banPlayer(player, checkName);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private boolean isExempt(Player player) {
        GameMode gm = player.getGameMode();
        return player.hasPermission("anticheat.bypass")
                || plugin.getWhitelistManager().isWhitelisted(player.getUniqueId())
                || gm == GameMode.CREATIVE
                || gm == GameMode.SPECTATOR
                || player.isDead()
                || player.isInsideVehicle()
                || player.isFlying()
                || player.isGliding();
    }

    private double[] horizontalVelocity(UUID id, Location current, long now) {
        Location last   = lastLocation.get(id);
        Long     lastMs = lastMoveMs.get(id);
        if (last == null || lastMs == null
                || now - lastMs > 300
                || !last.getWorld().equals(current.getWorld())) {
            return new double[]{0.0, 0.0};
        }
        return new double[]{current.getX() - last.getX(), current.getZ() - last.getZ()};
    }

    private void resetStreaks(UUID id) {
        pitchStreak.remove(id);
        rotSnapStreak.remove(id);
    }

    /** Called by PlayerStateListener on quit / kick / teleport / respawn. */
    public void removePlayer(UUID id) {
        lastLocation.remove(id);
        lastMoveMs.remove(id);
        lastPitch.remove(id);
        pitchSnapMs.remove(id);
        rotSnapStreak.remove(id);
        pitchStreak.remove(id);
        placeTimes.remove(id);
        lastGlidingMs.remove(id);
        lastLiquidMs.remove(id);
    }

    private void debugLog(Player player, String msg) {
        if (plugin.getConfig().getBoolean("debug.enabled", false)) {
            plugin.getLogger().info("[DEBUG][Scaffold] " + player.getName() + " » " + msg);
        }
    }

    private String fmt(double d) { return String.format("%.2f", d); }
}
