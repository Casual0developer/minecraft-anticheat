package dev.anticheat.listeners;

import dev.anticheat.AntiCheatPlugin;
import dev.anticheat.managers.BanManager;
import dev.anticheat.managers.ViolationManager;
import dev.anticheat.managers.WhitelistManager;
import dev.anticheat.utils.GroundUtil;
import dev.anticheat.utils.McMath;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Firework;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Vehicle;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ElytraFlightListener — physics-accurate elytra simulation.
 *
 * Every gliding tick, vanilla computes the new velocity from the old velocity
 * and the look direction (LivingEntity#updateFallFlyingMovement), then moves
 * the player by exactly that velocity.  The client sends its position every
 * tick, so each tick's movement can be predicted from the previous tick's
 * actual movement and compared.  Starting from real movement every tick means
 * errors never build up — no drift over a long flight, no guessed start speed.
 *
 * Checks:
 *   ElytraFlight[Speed]  — horizontal speed above the physics prediction
 *   ElytraFlight[Lift]   — rising / falling slower than the physics prediction
 *   ElytraFlight[Packet] — started gliding while standing on the ground
 *
 * Speed and Lift accumulate the per-tick excess in a slowly-decaying buffer,
 * so a cheat that adds even ~0.02 b/t every tick (hover, cruise control) is
 * caught within a second or two, while legit rounding (~0.0001) never builds up.
 *
 * Rocket boosts use the exact FireworkRocketEntity formula while a rocket is
 * attached to the player (tracked from launch, not guessed from right-clicks),
 * plus the player's ping afterwards since boosted movement is still in flight.
 *
 * Skipped ticks (movement becomes the new baseline, nothing is judged): the
 * first glide tick, anything touching the ground, water/lava/cobweb/powder
 * snow/climbables, overlapping another entity (pushing), grace periods, and
 * very slow movement (Bukkit merges moves under 1/16 block into the next event).
 */
public class ElytraFlightListener implements Listener {

    private final AntiCheatPlugin plugin;
    private final BanManager bm;
    private final ViolationManager vm;
    private final WhitelistManager wm;

    private static class ElytraState {
        int vl = 0;
        int packetElytraVL = 0;

        /** Previous gliding tick's movement = the velocity carried into this tick. */
        boolean hasBaseline = false;
        double prevDx, prevDy, prevDz;

        /** Accumulated excess over physics (blocks), decaying per checked tick. */
        double hBuffer = 0;
        double vBuffer = 0;

        /** Rockets attached to this player (removed once dead). */
        final List<Firework> rockets = new ArrayList<>();
        long lastRocketAliveMs = 0;
    }

    private final Map<UUID, ElytraState> state = new ConcurrentHashMap<>();

    // Vanilla constants (LivingEntity / FireworkRocketEntity)
    private static final double DRAG_H            = (double) 0.99F;
    private static final double DRAG_V            = (double) 0.98F;
    private static final double DEFAULT_GRAVITY   = 0.08;
    private static final double SLOW_FALL_GRAVITY = 0.01;

    /** Per-tick excess ignored — MC float/trig rounding is ~0.0001. */
    private static final double TICK_TOLERANCE = 0.004;
    /** Buffer decay per checked tick, so isolated blips fade out. */
    private static final double BUFFER_DECAY   = 0.98;
    /** Accumulated excess (blocks) that raises a flag. */
    private static final double BUFFER_LIMIT   = 0.35;
    /** Below this previous-tick speed, Bukkit may have merged two ticks into one event. */
    private static final double MIN_SIM_SPEED  = 0.1;
    /** Extra time after a rocket dies during which boosted packets may still arrive. */
    private static final long   ROCKET_TAIL_MS = 150;

    private static final int BAN_VL           = 3;
    private static final int PACKET_ELYTRA_VL = 3;

    public ElytraFlightListener(AntiCheatPlugin plugin) {
        this.plugin = plugin;
        this.bm = plugin.getBanManager();
        this.vm = plugin.getViolationManager();
        this.wm = plugin.getWhitelistManager();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Per-tick physics check
    // ─────────────────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!plugin.getConfig().getBoolean("checks.elytraflight", true)) return;
        // No TPS kill-switch: each packet is one client tick of client physics.
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        if (isExempt(player)) { state.remove(id); return; }

        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null || from.getWorld() != to.getWorld()) return;

        ElytraState s = state.computeIfAbsent(id, k -> new ElytraState());
        double dx = to.getX() - from.getX();
        double dy = to.getY() - from.getY();
        double dz = to.getZ() - from.getZ();

        if (!player.isGliding()) {
            s.hasBaseline = false;
            s.hBuffer = 0;
            s.vBuffer = 0;
            return;
        }

        if (!canSimulate(player, s, from, to)) {
            setBaseline(s, dx, dy, dz);
            return;
        }

        float yaw = to.getYaw();
        float pitch = to.getPitch();

        double[] plain = glide(s.prevDx, s.prevDy, s.prevDz, yaw, pitch, gravity(player, s.prevDy));
        double predH = Math.hypot(plain[0], plain[2]);
        double predV = plain[1];

        boolean boosting = isBoosting(player, s);
        if (boosting) {
            // Steady boost: this tick's move = glide(boost(last move)).  The
            // unboosted prediction stays allowed for the first/last boost tick.
            double[] b = rocketBoost(s.prevDx, s.prevDy, s.prevDz, yaw, pitch);
            double[] bg = glide(b[0], b[1], b[2], yaw, pitch, gravity(player, b[1]));
            predH = Math.max(predH, Math.hypot(bg[0], bg[2]));
            predV = Math.max(predV, bg[1]);
        }

        double actH = Math.hypot(dx, dz);
        double excessH = actH - predH;
        double excessV = dy - predV;
        s.hBuffer = Math.max(0, s.hBuffer * BUFFER_DECAY + excessH - TICK_TOLERANCE);
        s.vBuffer = Math.max(0, s.vBuffer * BUFFER_DECAY + excessV - TICK_TOLERANCE);

        if (excessH > TICK_TOLERANCE || excessV > TICK_TOLERANCE) {
            debugLog(player, "h=" + fmt(actH) + " exp=" + fmt(predH) + " dy=" + fmt(dy) + " exp=" + fmt(predV)
                    + (boosting ? " rocket" : "") + " buf=" + fmt(s.hBuffer) + "/" + fmt(s.vBuffer));
        }

        if (s.hBuffer > BUFFER_LIMIT) {
            flag(player, id, "ElytraFlight[Speed] h=" + fmt(actH) + " expected<=" + fmt(predH)
                    + " (+" + fmt(s.hBuffer) + "b over physics)");
            s.hBuffer = 0;
        }
        if (s.vBuffer > BUFFER_LIMIT) {
            flag(player, id, "ElytraFlight[Lift] dy=" + fmt(dy) + " expected<=" + fmt(predV)
                    + " (+" + fmt(s.vBuffer) + "b over physics)");
            s.vBuffer = 0;
        }

        setBaseline(s, dx, dy, dz);
    }

    private boolean canSimulate(Player player, ElytraState s, Location from, Location to) {
        if (!s.hasBaseline) return false;
        UUID id = player.getUniqueId();
        if (vm.hasDamageGrace(id) || vm.hasTeleportGrace(id) || vm.hasJoinGrace(id)) return false;
        // Touching the ground (landing, skimming): collisions change velocity.
        // Server-side only — a client "on ground" claim ends the glide anyway.
        if (GroundUtil.touchingGround(player, from) || GroundUtil.touchingGround(player, to)) return false;
        if (player.isInWater() || player.isSwimming() || isInSlowingBlock(to)) return false;
        // Bukkit only fires a move event past 1/16 block, so below this speed a
        // tick may have been merged into this event.
        double prevSpeed = Math.sqrt(s.prevDx * s.prevDx + s.prevDy * s.prevDy + s.prevDz * s.prevDz);
        if (prevSpeed < MIN_SIM_SPEED) return false;
        // Overlapping mobs/players/vehicles push the player a little each tick.
        return to.getWorld().getNearbyEntities(player.getBoundingBox().expand(0.2),
                e -> e != player && (e instanceof LivingEntity || e instanceof Vehicle)).isEmpty();
    }

    private void setBaseline(ElytraState s, double dx, double dy, double dz) {
        s.prevDx = dx;
        s.prevDy = dy;
        s.prevDz = dz;
        s.hasBaseline = true;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Vanilla physics
    // ─────────────────────────────────────────────────────────────────────────

    /** One tick of LivingEntity#updateFallFlyingMovement. Returns the new velocity = this tick's move. */
    private static double[] glide(double vx, double vy, double vz, float yaw, float pitch, double gravity) {
        float pitchRad = pitch * 0.017453292F;
        double[] look = McMath.lookVector(yaw, pitch);
        double horizLook = Math.sqrt(look[0] * look[0] + look[2] * look[2]);
        double horizVel = Math.sqrt(vx * vx + vz * vz);
        double cosSq = Math.cos(pitchRad);
        cosSq *= cosSq;

        vy += gravity * (-1.0 + cosSq * 0.75);
        if (vy < 0.0 && horizLook > 0.0) {            // diving: vertical speed → horizontal
            double d = vy * -0.1 * cosSq;
            vx += look[0] * d / horizLook;
            vy += d;
            vz += look[2] * d / horizLook;
        }
        if (pitchRad < 0.0F && horizLook > 0.0) {     // pulling up: horizontal speed → lift
            double d = horizVel * (double) (-McMath.sin(pitchRad)) * 0.04;
            vx += -look[0] * d / horizLook;
            vy += d * 3.2;
            vz += -look[2] * d / horizLook;
        }
        if (horizLook > 0.0) {                         // steer toward the look direction
            vx += (look[0] / horizLook * horizVel - vx) * 0.1;
            vz += (look[2] / horizLook * horizVel - vz) * 0.1;
        }
        return new double[]{ vx * DRAG_H, vy * DRAG_V, vz * DRAG_H };
    }

    /** One tick of an attached firework rocket's boost (FireworkRocketEntity#tick). */
    private static double[] rocketBoost(double vx, double vy, double vz, float yaw, float pitch) {
        double[] look = McMath.lookVector(yaw, pitch);
        return new double[]{
                vx + look[0] * 0.1 + (look[0] * 1.5 - vx) * 0.5,
                vy + look[1] * 0.1 + (look[1] * 1.5 - vy) * 0.5,
                vz + look[2] * 0.1 + (look[2] * 1.5 - vz) * 0.5
        };
    }

    /** Effective gravity: gravity attribute, capped at 0.01 by Slow Falling while descending. */
    private static double gravity(Player player, double vy) {
        double g = DEFAULT_GRAVITY;
        try {
            AttributeInstance inst = player.getAttribute(Attribute.GENERIC_GRAVITY);
            if (inst != null) g = inst.getValue();
        } catch (Throwable ignored) {}
        if (vy <= 0.0 && player.hasPotionEffect(PotionEffectType.SLOW_FALLING)) g = Math.min(g, SLOW_FALL_GRAVITY);
        return g;
    }

    private boolean isBoosting(Player player, ElytraState s) {
        long now = System.currentTimeMillis();
        s.rockets.removeIf(fw -> !fw.isValid());
        if (!s.rockets.isEmpty()) {
            s.lastRocketAliveMs = now;
            return true;
        }
        return now - s.lastRocketAliveMs <= Math.max(0, player.getPing()) + ROCKET_TAIL_MS;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Rockets and glide start
    // ─────────────────────────────────────────────────────────────────────────

    /** Tracks rockets actually attached to a gliding player (replaces "any right-click = firework"). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRocketLaunch(ProjectileLaunchEvent event) {
        if (!(event.getEntity() instanceof Firework fw)) return;
        Player player = null;
        if (fw.getAttachedTo() instanceof Player attached) {
            player = attached;
        } else if (fw.getShooter() instanceof Player shooter && shooter.isGliding() && !fw.isShotAtAngle()) {
            player = shooter;
        }
        if (player == null) return;
        ElytraState s = state.computeIfAbsent(player.getUniqueId(), k -> new ElytraState());
        s.rockets.add(fw);
        s.lastRocketAliveMs = System.currentTimeMillis();
    }

    /**
     * Vanilla only lets you start gliding while airborne.  The server trusts the
     * client's "airborne" claim for that, so check for real ground under the feet.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGlideToggle(EntityToggleGlideEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (!event.isGliding()) return;
        if (!plugin.getConfig().getBoolean("checks.elytraflight", true)) return;
        if (isExempt(player)) return;

        UUID id = player.getUniqueId();
        ElytraState s = state.computeIfAbsent(id, k -> new ElytraState());
        s.hasBaseline = false; // first glide tick carries non-glide velocity

        if (GroundUtil.touchingGround(player, player.getLocation())) {
            s.packetElytraVL++;
            debugLog(player, "glide started on the ground vl=" + s.packetElytraVL + "/" + PACKET_ELYTRA_VL);
            if (s.packetElytraVL >= PACKET_ELYTRA_VL) {
                flag(player, id, "ElytraFlight[Packet] started gliding while on the ground");
                s.packetElytraVL = 0;
            }
        } else {
            s.packetElytraVL = Math.max(0, s.packetElytraVL - 1);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private boolean isInSlowingBlock(Location loc) {
        for (double oy : new double[]{0.0, 1.0}) {
            Material m = loc.clone().add(0, oy, 0).getBlock().getType();
            if (m == Material.COBWEB || m == Material.POWDER_SNOW || m == Material.LAVA
                    || m == Material.WATER || m == Material.BUBBLE_COLUMN || m == Material.LADDER
                    || m == Material.VINE || m == Material.SCAFFOLDING || m.name().endsWith("_VINES")
                    || m.name().endsWith("_VINES_PLANT") || m == Material.SWEET_BERRY_BUSH) return true;
        }
        return false;
    }

    private void flag(Player player, UUID id, String reason) {
        ElytraState s = state.get(id);
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

    public void removePlayer(UUID id) { state.remove(id); }

    /** Drops the physics baseline (a teleport breaks it), keeping VL and attached rockets. */
    public void resetMotion(UUID id) {
        ElytraState old = state.remove(id);
        if (old == null) return;
        ElytraState s = new ElytraState();
        s.vl = old.vl;
        s.packetElytraVL = old.packetElytraVL;
        s.rockets.addAll(old.rockets);
        s.lastRocketAliveMs = old.lastRocketAliveMs;
        state.put(id, s);
    }

    private void debugLog(Player player, String msg) {
        if (plugin.getConfig().getBoolean("debug.enabled", false)) {
            plugin.getLogger().info("[DEBUG][ElytraFlight] " + player.getName() + " » " + msg);
        }
    }

    private String fmt(double d) { return String.format("%.3f", d); }
}
