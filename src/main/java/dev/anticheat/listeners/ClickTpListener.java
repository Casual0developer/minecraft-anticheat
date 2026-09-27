package dev.anticheat.listeners;

import dev.anticheat.AntiCheatPlugin;
import dev.anticheat.managers.BanManager;
import dev.anticheat.managers.ViolationManager;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerMoveEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Detects "Click TP" / blink / position-warp cheats.
 *
 * ── Why click-event detection is not enough ─────────────────────────────────
 * Modern click-tp cheats (Wurst, Astro, etc.) DO NOT send an interact packet
 * for the far block.  The cheat just runs:
 *
 *     player.setPos(target.x, target.y, target.z);
 *     player.setDeltaMovement(0, 0, 0);
 *
 * No use-item packet is sent.  The server learns about the warp via the next
 * client position packet, which it treats as ordinary movement (the vanilla
 * "moved too quickly!" check has a 100-blocks² / ~10 block hole that hides
 * 3–9 block click-tp warps).  The only artefact is therefore a single
 * PlayerMoveEvent with an impossible-for-keyboard-input delta.
 *
 * ── Domain separation ───────────────────────────────────────────────────────
 * All move-based layers operate on the HORIZONTAL (XZ) delta only.  Vertical-
 * dominant movement (|Δy| > horizontal Δ) is skipped — that's fly-hack / jump
 * / fall territory, handled by FlyListener.  Layers also require a BURST
 * pattern (slow previous tick → fast current tick) so sustained fast horizontal
 * movement (a fly hacker cruising) does not trip this listener — it's left
 * to FlyListener / SpeedListener.
 *
 * ── Detection layers ────────────────────────────────────────────────────────
 *
 *  L1 — HORIZONTAL BLINK BURST.  Horizontal delta over {@code BLINK_THRESHOLD}
 *       (default 2.5 b) AND previous horizontal delta was low (slow → fast
 *       in a single tick).  Catches every 3+ block click-tp from walking
 *       or standing.
 *
 *  L2 — FROM-REST BURST.  Going from a near-stationary horizontal delta
 *       (< 0.5 b/t, i.e. walking or standing) to > {@code FROM_REST_TRIGGER}
 *       (default 1.5 b/t) in a single move event is physically impossible
 *       from keyboard input — vanilla per-tick acceleration tops out at
 *       ~0.42 b/t (jump take-off).  Catches sub-threshold warps from a
 *       walking or stationary player.
 *
 *  L3 — HORIZONTAL ACCELERATION SPIKE.  A horizontal delta increase of more
 *       than {@code ACCEL_TRIGGER} (default 2.0 b/t) in a single tick is
 *       impossible regardless of starting speed: rocket-boosted elytra
 *       adds at most ~1.5 b/t per firework; ice / sprint-jump accelerate
 *       by ≤ 0.5 b/t; slime / wind / mace / explosion knockback is
 *       teleport-grace exempt.
 *
 *  L4 — RAW INTERACT DISTANCE.  Backstop for older cheats that send an
 *       interact for a far block without warping position.
 *
 * ── Server-validated exemptions ONLY ────────────────────────────────────────
 * Every exemption uses a state that the cheat client cannot fake without the
 * server knowing — no isGliding / isSwimming / isRiptiding / isInsideVehicle
 * exemptions (those are client-controlled and would create a free bypass):
 *
 *   • anticheat.bypass permission     • Whitelist
 *   • Spectator gamemode              • Creative gamemode (server-set)
 *   • Dead                            • Join grace
 *   • Damage grace                    • Teleport grace
 *     (set by actual server-side       (set ONLY by PlayerTeleportEvent —
 *      damage)                          covers /tp /tpa /tpahere /rtp /home
 *                                       /warp /spawn, ender pearl, chorus
 *                                       fruit, nether/end portal, bed,
 *                                       riptide, wind charge / mace burst,
 *                                       explosion knockback, respawn, etc.)
 */
public class ClickTpListener implements Listener {

    private final AntiCheatPlugin plugin;
    private final ViolationManager vm;
    private final BanManager bm;

    // ── L1: Hard threshold (single-move delta) ──────────────────────────────
    /**
     * Per-move delta above this is always a teleport.
     * Legitimate per-packet maximums in vanilla 1.21:
     *   walk 0.21, sprint 0.28, sprint-jump 0.6, ice ~1.0, elytra cruise ~2.0
     * 2.5 sits above every legit source EXCEPT the rocket-boosted elytra
     * peak (~4 b/t); rocket peaks are caught only when starting from rest,
     * which they don't — they ramp from elytra-cruise speed.
     */
    private static final double DEFAULT_BLINK_THRESHOLD = 2.5;

    // ── L2: From-rest burst ─────────────────────────────────────────────────
    /** "Effectively stationary" cutoff. Walk = 0.21 b/t, sprint = 0.28 b/t. */
    private static final double FROM_REST_PREV_MAX = 0.5;
    /** Delta size that the previous-stationary player could not have produced. */
    private static final double FROM_REST_TRIGGER  = 1.5;

    // ── L3: Acceleration spike ──────────────────────────────────────────────
    /** Max legitimate one-tick acceleration: ~0.5 b/t (sprint-jump). */
    private static final double ACCEL_TRIGGER = 2.0;

    // ── L4: Interact-distance backstop ──────────────────────────────────────
    private static final double MAX_INTERACT_DISTANCE          = 6.0;
    private static final double MAX_INTERACT_DISTANCE_CREATIVE = 7.0;

    /** Per-flag VL accounting per player. */
    private final Map<UUID, Integer> clickTpVL = new HashMap<>();

    /** Rate-limit (ms) between flags per player. */
    private static final long FLAG_COOLDOWN_MS = 150;
    private final Map<UUID, Long> lastFlagTime = new HashMap<>();

    /** Last accepted move delta per player — used for L2/L3 input-style checks. */
    private final Map<UUID, Double> prevDelta = new HashMap<>();

    public ClickTpListener(AntiCheatPlugin plugin) {
        this.plugin = plugin;
        this.vm = plugin.getViolationManager();
        this.bm = plugin.getBanManager();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Move-based detection (L1 / L2 / L3) — HORIZONTAL deltas only.
    //
    // Vertical-dominant moves (fly hacks, jump bursts, falling) are NOT this
    // listener's job — they're handled by FlyListener.  Restricting ClickTp
    // to the horizontal plane stops fly-hack movement from being miscategorised
    // as a click-tp flag.
    //
    // Additionally every layer requires a BURST pattern (previous delta was
    // small, current delta is large) — sustained fast movement (e.g. a fly
    // hacker cruising horizontally at 3 b/t every tick) does NOT trip these
    // layers and is left to FlyListener / SpeedListener.
    // ─────────────────────────────────────────────────────────────────────────
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!plugin.getConfig().getBoolean("checks.clicktp", true)) return;
        // No TPS kill-switch: per-packet movement isn't changed by server lag.
        if (event.getTo() == null) return;

        Player player = event.getPlayer();
        UUID id = player.getUniqueId();

        Location from = event.getFrom();
        Location to   = event.getTo();
        if (from.getWorld() == null || to.getWorld() == null
                || !from.getWorld().equals(to.getWorld())) return;

        // Horizontal-only delta (XZ plane). Y movement is handled by FlyListener.
        double dx = to.getX() - from.getX();
        double dy = to.getY() - from.getY();
        double dz = to.getZ() - from.getZ();
        double hDelta = Math.sqrt(dx * dx + dz * dz);

        // Skip vertical-dominant moves — those are fly / jump / fall territory.
        // A click-tp targets a block to stand on, so it's always horizontally
        // dominant (XZ travel ≫ Y travel).  Fly hacks are the opposite — they
        // mostly travel in Y.  Splitting by axis-dominance cleanly separates
        // the two domains.
        if (Math.abs(dy) > hDelta) {
            prevDelta.put(id, hDelta); // still update history so the next tick is accurate
            return;
        }

        double prev = prevDelta.getOrDefault(id, 0.0);
        // Always update — even when exempt — so the next non-exempt tick has
        // an accurate "previous" value.
        prevDelta.put(id, hDelta);

        if (isExempt(player)) return;

        // Server-validated ground check on BOTH endpoints.  A click-tp lands
        // the player on TOP of the clicked block — both `from` and `to` are
        // grounded.  Fly hacks toggle ground→air and stay airborne, and the
        // first tick of a fly-cheat keybind tap is exactly that ground→air
        // transition — the case that was tripping L2/L3 before.  Anything
        // that isn't ground→ground is left to FlyListener.
        if (!serverOnGround(from) || !serverOnGround(to)) return;

        // Rate-limit
        long nowMs = System.currentTimeMillis();
        Long lastFlag = lastFlagTime.get(id);
        if (lastFlag != null && nowMs - lastFlag < FLAG_COOLDOWN_MS) return;

        // Thresholds are tuned for a normal sprinter.  Scale them by how much
        // faster this player can accelerate (Speed potions / speed attribute):
        // with Speed 255 a legit sprint covers ~6.6 blocks in its first tick.
        double speedFactor = speedFactor(player);
        double blinkThreshold = plugin.getConfig().getDouble(
                "settings.clicktp-blink-threshold", DEFAULT_BLINK_THRESHOLD) * speedFactor;
        double fromRestTrigger = plugin.getConfig().getDouble(
                "settings.clicktp-fromrest-trigger", FROM_REST_TRIGGER) * speedFactor;
        double accelTrigger = plugin.getConfig().getDouble(
                "settings.clicktp-accel-trigger", ACCEL_TRIGGER) * speedFactor;

        // The maximum previous-tick horizontal velocity at which we treat a
        // big jump as a teleport burst.  Above this the player was already
        // moving fast on their own (fly hack cruise, ice-sprinting, etc.) and
        // the spike is the *other* listeners' problem, not ours.
        // 0.6 = above sprint-jump peak (~0.6) but below sustained fly speed.
        final double BURST_PREV_MAX = blinkThreshold * 0.6;

        // ── L1 — Horizontal blink burst ────────────────────────────────────
        // A sudden jump from a slow horizontal velocity to a large one.
        // Sustained fast horizontal movement (fly hack cruising) doesn't
        // trip this — only single-tick warps from walking/standing speed do.
        if (hDelta > blinkThreshold && prev < BURST_PREV_MAX) {
            flag(player, "ClickTp[Blink] h=" + fmt(hDelta)
                    + " prev=" + fmt(prev)
                    + " threshold=" + fmt(blinkThreshold), 3);
            lastFlagTime.put(id, nowMs);
            return;
        }

        // ── L2 — From-rest burst ───────────────────────────────────────────
        // Stationary / walking player suddenly jumps a chunky horizontal
        // distance.  Keyboard input can't ramp from ~0 to > 1.5 b/t in one
        // tick — vanilla max accel from rest is ~0.42 b/t (jump take-off).
        if (prev < FROM_REST_PREV_MAX && hDelta > fromRestTrigger) {
            flag(player, "ClickTp[FromRest] prev=" + fmt(prev)
                    + " h=" + fmt(hDelta), 3);
            lastFlagTime.put(id, nowMs);
            return;
        }

        // ── L3 — Horizontal acceleration spike ─────────────────────────────
        // A single-tick horizontal velocity jump above ACCEL_TRIGGER blocks
        // is unreachable from any legit source: rocket-firework boost adds
        // ~1.5 b/t; ice / sprint-jump accelerate by ≤ 0.5 b/t; knockback /
        // wind / mace are teleport-grace exempt.
        if (hDelta - prev > accelTrigger) {
            flag(player, "ClickTp[Accel] prev=" + fmt(prev)
                    + " h=" + fmt(hDelta)
                    + " jump=" + fmt(hDelta - prev), 2);
            lastFlagTime.put(id, nowMs);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // L4 — Raw interact-distance (cheats that don't warp position)
    // ─────────────────────────────────────────────────────────────────────────
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.LEFT_CLICK_BLOCK && action != Action.RIGHT_CLICK_BLOCK) return;

        Player player = event.getPlayer();
        Block  block  = event.getClickedBlock();
        if (block == null) return;
        if (isExempt(player)) return;

        Location eye = player.getEyeLocation();
        Location target = block.getLocation().add(0.5, 0.5, 0.5);
        checkInteractDistance(player, eye.distance(target));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteractAtEntity(PlayerInteractAtEntityEvent event) {
        Player player = event.getPlayer();
        Entity entity = event.getRightClicked();
        if (isExempt(player)) return;

        Location eye    = player.getEyeLocation();
        Location target = entity.getLocation();
        checkInteractDistance(player, eye.distance(target));
    }

    private void checkInteractDistance(Player player, double distance) {
        UUID id = player.getUniqueId();
        long nowMs = System.currentTimeMillis();
        Long lastFlag = lastFlagTime.get(id);
        if (lastFlag != null && nowMs - lastFlag < FLAG_COOLDOWN_MS) return;

        double maxDist = player.getGameMode() == GameMode.CREATIVE
                ? MAX_INTERACT_DISTANCE_CREATIVE : MAX_INTERACT_DISTANCE;
        maxDist += plugin.getConfig().getDouble("settings.clicktp-lag-buffer", 0.0);

        AttributeInstance blockRange = player.getAttribute(Attribute.PLAYER_BLOCK_INTERACTION_RANGE);
        if (blockRange != null) {
            double attrRange = blockRange.getValue();
            if (attrRange > 4.9) {
                maxDist += (attrRange - 4.9);
            }
        }

        var speedEffect = player.getPotionEffect(org.bukkit.potion.PotionEffectType.SPEED);
        if (speedEffect != null) {
            double speedMultiplier = 1.0 + (speedEffect.getAmplifier() + 1) * 0.2;
            maxDist *= speedMultiplier;
        }

        if (distance > maxDist) {
            flag(player, "ClickTp[Distance] dist=" + fmt(distance) + " max=" + fmt(maxDist), 3);
            lastFlagTime.put(id, nowMs);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Flag / exemption / cleanup
    // ─────────────────────────────────────────────────────────────────────────
    private void flag(Player player, String reason, int vlIncrease) {
        UUID id = player.getUniqueId();
        int vl = clickTpVL.getOrDefault(id, 0) + vlIncrease;
        clickTpVL.put(id, vl);

        int threshold = plugin.getConfig().getInt("settings.clicktp-vl-threshold", 3);
        debugLog(player, reason + " VL=" + vl + "/" + threshold);

        if (vl >= threshold) {
            clickTpVL.remove(id);
            bm.banPlayer(player, reason);
        }
    }

    /** Called by PlayerStateListener on quit to avoid memory leaks. */
    public void removePlayer(UUID id) {
        clickTpVL.remove(id);
        lastFlagTime.remove(id);
        prevDelta.remove(id);
    }

    /** Drops the previous-move baseline, keeping VL. */
    public void resetMotion(UUID id) {
        prevDelta.remove(id);
    }

    /**
     * Only server-validated states are exempted.  No client-controlled flags
     * (isGliding, isSwimming, isRiptiding, isInsideVehicle) — those are
     * spoofable and would create a free bypass.
     *
     * {@code isFlying()} IS server-validated: the player can only enter the
     * flying state if the server has set {@code allowFlight = true}, which
     * happens only via creative gamemode or an explicit plugin/permission
     * grant.  A cheat client cannot fake flight on its own — the server
     * rejects the flight packet.  Flying produces large per-move deltas
     * (sprint-fly ~1.5 b/t, takeoff from hover trips the from-rest layer)
     * which is what was causing the false flags for /fly users.
     */
    private boolean isExempt(Player player) {
        UUID id = player.getUniqueId();
        GameMode gm = player.getGameMode();
        return player.hasPermission("anticheat.bypass")
                || plugin.getWhitelistManager().isWhitelisted(id)
                || gm == GameMode.SPECTATOR
                || gm == GameMode.CREATIVE
                || player.isFlying()
                || player.isDead()
                || vm.hasJoinGrace(id)
                || vm.hasDamageGrace(id)
                || vm.hasTeleportGrace(id);
    }

    /**
     * How many times faster than a normal sprinter (0.13 b/t per tick of
     * acceleration) this player can accelerate, from the movement-speed
     * attribute — which includes Speed potions and the sprint bonus.  Never
     * below 1, so Slowness doesn't tighten the thresholds.
     */
    private double speedFactor(Player player) {
        double speed = 0.1;
        try {
            AttributeInstance inst = player.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED);
            if (inst != null) speed = inst.getValue();
        } catch (Throwable ignored) {}
        // The sprint modifier is already in the attribute while the server sees
        // the player sprinting; otherwise assume they are (upper bound).
        double accel = player.isSprinting() ? speed : speed * 1.3;
        return Math.max(1.0, accel / 0.13);
    }

    /**
     * Server-validated on-ground check.  Samples the 4 corners of the player
     * hitbox just below feet level and returns true if any rests on a solid
     * block.  This is read from the world directly and cannot be spoofed by
     * the client (unlike {@code Player.isOnGround()}, which is the
     * client-reported flag).
     */
    private boolean serverOnGround(Location loc) {
        World w = loc.getWorld();
        if (w == null) return false;
        double halfW = 0.3;            // player hitbox half-width
        int y = (int) Math.floor(loc.getY() - 0.02);
        double[][] corners = {
            {-halfW, -halfW}, { halfW, -halfW},
            {-halfW,  halfW}, { halfW,  halfW},
            { 0,      0    },
        };
        for (double[] c : corners) {
            Material m = w.getBlockAt(
                (int) Math.floor(loc.getX() + c[0]),
                y,
                (int) Math.floor(loc.getZ() + c[1])
            ).getType();
            if (m.isSolid()) return true;
        }
        return false;
    }

    private void debugLog(Player player, String msg) {
        if (plugin.getConfig().getBoolean("debug.enabled", false)) {
            plugin.getLogger().info("[DEBUG][ClickTp] " + player.getName() + " » " + msg);
        }
    }

    private String fmt(double d) { return String.format("%.3f", d); }
}
