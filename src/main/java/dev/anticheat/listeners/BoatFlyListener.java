package dev.anticheat.listeners;

import dev.anticheat.AntiCheatPlugin;
import dev.anticheat.managers.BanManager;
import dev.anticheat.managers.ViolationManager;
import dev.anticheat.managers.WhitelistManager;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Levelled;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.entity.Shulker;
import org.bukkit.util.BoundingBox;
import org.bukkit.event.Listener;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class BoatFlyListener implements Listener {

    private final AntiCheatPlugin plugin;
    private final BanManager bm;
    private final ViolationManager vm;
    private final WhitelistManager wm;

    private static class BoatState {
        int vl = 0;
        int airTicks = 0;
        int hoverTicks = 0;
        double prevY = Double.MIN_VALUE;
        double prevX = Double.MIN_VALUE;
        double prevZ = Double.MIN_VALUE;
        // Gravity check
        double prevDy = 0;
        boolean prevAirborne = false;
        int gravityTicks = 0;
        // Speed check (1-second windows)
        double vBound = -1;        // physics upper bound on boat speed at window start (-1 = unknown)
        long windowStartMs = 0;
        double windowDist = 0;
        double windowMaxFriction = 0;
        boolean windowFlow = false;
        int overWindows = 0;
    }

    private final Map<UUID, BoatState> state = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> launcherGrace = new ConcurrentHashMap<>();
    private BukkitTask tickTask;

    private static final double MAX_UP_SPEED = 0.1;
    private static final double MAX_AIR_SPEED = 1.0;
    private static final int MAX_AIR_TICKS = 12; // increased from 5 to 12 to allow driving off 1-2 block ledges without false flag
    private static final int HOVER_TICKS = 6; // increased from 3 to 6 to avoid ledge-edge micro-hovers
    private static final double HOVER_BAND = 0.02;
    private static final int BAN_VL = 2;
    private static final int LEDGE_GRACE_TICKS = 14; // ~0.7s grace after leaving ground to cover fall off ledge

    /** A boat in the air gains this much downward speed every tick (Boat#floatBoat: -0.04F). */
    private static final double BOAT_GRAVITY = (double) 0.04F;
    /** Slack on the gravity prediction per tick. */
    private static final double GRAVITY_TOLERANCE = 0.01;
    /** Consecutive defying ticks before a flag (absorbs two packets landing in one server tick). */
    private static final int GRAVITY_TICKS = 3;

    // Vanilla boat drive (Boat#floatBoat / #controlBoat): each tick velocity is
    // multiplied by the friction for where the boat is (water/air 0.9, land =
    // block friction: ice 0.98, blue ice 0.989), then paddling adds ≤ 0.04.
    // Top speed = 0.04 / (1 - friction): 0.4 b/t on water, 2.0 packed ice, 3.64 blue ice.
    private static final double BOAT_ACCEL = (double) 0.04F;
    /** Water current push per tick (Entity#updateFluidHeightAndDoFluidPushing, water scale 0.014). */
    private static final double FLOW_PUSH = 0.014;
    /** Distance is judged per window, so merged/missing packets just add up. */
    private static final long BOAT_WINDOW_MS = 1000;
    private static final double BOAT_SPEED_MULT = 1.04;
    private static final double BOAT_SPEED_SLACK = 0.4;   // blocks per window
    /** Consecutive over-limit windows before a flag. */
    private static final int BOAT_SPEED_WINDOWS = 2;

    public BoatFlyListener(AntiCheatPlugin plugin) {
        this.plugin = plugin;
        this.bm = plugin.getBanManager();
        this.vm = plugin.getViolationManager();
        this.wm = plugin.getWhitelistManager();
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickAll, 1L, 1L);
    }

    private void tickAll() {
        tickGrace();
        // Main thread only — reads blocks and can ban (kick), which Bukkit forbids off-thread.
        for (Player player : Bukkit.getOnlinePlayers()) checkPlayer(player);
    }

    public void resetGrace(UUID id, int ticks) {
        launcherGrace.put(id, ticks);
    }

    private void tickGrace() {
        launcherGrace.replaceAll((k, v) -> v - 1);
        launcherGrace.entrySet().removeIf(e -> e.getValue() <= 0);
    }

    private void checkPlayer(Player player) {
        if (!plugin.getConfig().getBoolean("checks.boatfly", true)) return;
        if (dev.anticheat.utils.TPSUtil.isSeverelyLagging()) return;
        // Decrement launcher grace (also ticked globally via tickAll)
        Integer lg = launcherGrace.get(player.getUniqueId());
        if (lg != null && lg > 0) return; // boat launcher grace - skip all boat checks
        UUID id = player.getUniqueId();
        if (!player.isInsideVehicle() || !(player.getVehicle() instanceof Boat)) {
            state.remove(id); return;
        }
        if (isExempt(player)) { state.remove(id); return; }

        BoatState s = state.computeIfAbsent(id, k -> new BoatState());
        Entity boat = player.getVehicle();
        Location loc = boat.getLocation();

        if (s.prevY == Double.MIN_VALUE) {
            s.prevY = loc.getY(); s.prevX = loc.getX(); s.prevZ = loc.getZ();
            return;
        }

        double deltaY = loc.getY() - s.prevY;
        double dx = loc.getX() - s.prevX;
        double dz = loc.getZ() - s.prevZ;
        double hSpeed = Math.sqrt(dx * dx + dz * dz);
        s.prevY = loc.getY(); s.prevX = loc.getX(); s.prevZ = loc.getZ();

        // ── Speed (water, ice, land, air) ───────────────────────────────────
        checkBoatSpeed(player, id, s, boat, hSpeed);

        // ── Gravity ─────────────────────────────────────────────────────────
        // A boat in the air speeds up downward by 0.04 every tick, whatever
        // effects its rider has (Levitation doesn't lift boats).  Rising,
        // hovering or sinking at a steady rate in mid-air is impossible.  The
        // older checks below only catch rises over 0.1 b/t or 12+ airborne
        // ticks, so a slow levitation-speed rise (~0.045 b/t) slipped past.
        boolean airborne = !isBoatSupported(boat);
        if (airborne && s.prevAirborne) {
            double maxDy = s.prevDy - BOAT_GRAVITY + GRAVITY_TOLERANCE;
            if (deltaY > maxDy) {
                s.gravityTicks++;
                debugLog(player, "BoatFly[Gravity] dY=" + fmt(deltaY) + " expected<=" + fmt(maxDy)
                        + " ticks=" + s.gravityTicks + "/" + GRAVITY_TICKS);
                if (s.gravityTicks >= GRAVITY_TICKS) {
                    s.gravityTicks = 0;
                    flag(player, id, "BoatFly[Gravity] dY=" + fmt(deltaY) + " expected<=" + fmt(maxDy));
                }
            } else {
                s.gravityTicks = Math.max(0, s.gravityTicks - 1);
            }
        } else {
            s.gravityTicks = 0;
        }
        s.prevDy = deltaY;
        s.prevAirborne = airborne;

        boolean onGround = isBoatOnGround(loc);

        if (onGround) { s.airTicks = 0; s.hoverTicks = 0; return; }
        s.airTicks++;

        // Ledge grace: when boat just drove off a ledge it's falling (negative deltaY)
        // Do not apply hover/airborne checks during the initial fall — only upward cheat.
        boolean isFalling = deltaY < -0.04;
        boolean inLedgeGrace = s.airTicks <= LEDGE_GRACE_TICKS && isFalling;

        if (deltaY > MAX_UP_SPEED) {
            flag(player, id, "BoatFly[Up] dY=" + fmt(deltaY));
        } else if (!inLedgeGrace && s.airTicks > MAX_AIR_TICKS) {
            flag(player, id, "BoatFly[Airborne] airTicks=" + s.airTicks);
        } else if (!inLedgeGrace && hSpeed > MAX_AIR_SPEED) {
            flag(player, id, "BoatFly[Speed] h=" + fmt(hSpeed));
        } else if (!inLedgeGrace && Math.abs(deltaY) < HOVER_BAND && s.airTicks > 3) {
            // Only count hover when truly hovering (near-zero vertical) while airborne and not falling
            // Require longer sustained hover to avoid ledge micro-hovers
            s.hoverTicks++;
            if (s.hoverTicks >= HOVER_TICKS) flag(player, id, "BoatFly[Hover] ticks=" + s.hoverTicks);
        } else {
            if (!inLedgeGrace || Math.abs(deltaY) >= HOVER_BAND) {
                // decay hover slowly during grace if not actually hovering
                if (s.hoverTicks > 0) s.hoverTicks = Math.max(0, s.hoverTicks - 1);
            }
        }
    }

    private boolean isBoatOnGround(Location loc) {
        if (loc.getWorld() == null) return false;
        // Check center and 4 corners of boat (approx 1.4 width) slightly below hull
        double yCheck = loc.getY() - 0.35; // boat hull offset
        double half = 0.7;
        double[][] points = {{0,0},{half,0},{-half,0},{0,half},{0,-half},{half,half},{-half,half},{half,-half},{-half,-half}};
        for (double[] p : points) {
            Block b = loc.getWorld().getBlockAt(
                    (int)Math.floor(loc.getX() + p[0]),
                    (int)Math.floor(yCheck),
                    (int)Math.floor(loc.getZ() + p[1]));
            Material m = b.getType();
            if (m.isSolid() || m == Material.WATER || m == Material.LAVA) return true;
            // second check 0.5 lower to handle half-slabs / boat slightly above ground
            Block b2 = loc.getWorld().getBlockAt(
                    (int)Math.floor(loc.getX() + p[0]),
                    (int)Math.floor(yCheck - 0.4),
                    (int)Math.floor(loc.getZ() + p[1]));
            Material m2 = b2.getType();
            if (m2.isSolid()) return true;
        }
        // Also check directly below boat location at 0.1 and 0.5 as before for water
        Block below = loc.clone().subtract(0, 0.5, 0).getBlock();
        if (below.getType() == Material.WATER || below.getType() == Material.LAVA) return true;
        if (loc.getBlock().getType() == Material.WATER || loc.getBlock().getType() == Material.LAVA) return true;
        return false;
    }

    /**
     * Vanilla boat speed.  Each client tick: speed = last speed × friction +
     * paddle (≤ 0.04) + water current (≤ 0.014 in flowing water), so on still
     * water a boat tops out at 0.4 b/t.  Previously nothing checked boat speed
     * on water at all (BoatFly treats water as ground; PhysicsSim allowed 1.65).
     *
     * Judged as distance per ~1 s window against the most vanilla physics can
     * cover in that time (by wall clock, 20 client ticks/s), starting from a
     * physics upper bound on the speed at the window start.  Sampling once per
     * server tick means packets sometimes merge or go missing; over a window
     * they simply add up.  Wall-clock timing also catches boat timer cheats.
     */
    private void checkBoatSpeed(Player player, UUID id, BoatState s, Entity boat, double hSpeed) {
        double[] surface = boatSurface(boat.getWorld(), boat.getBoundingBox());
        long now = System.currentTimeMillis();

        // The rider was just hit / blasted (the boat gets knocked too): start over.
        if (vm.hasDamageGrace(id)) { s.vBound = -1; s.overWindows = 0; return; }

        if (s.vBound < 0) {
            // Unknown speed: assume up to top speed for this surface (lenient seed).
            s.vBound = Math.max(hSpeed, steadySpeed(surface[0], BOAT_ACCEL + surface[1]));
            startWindow(s, now);
            return;
        }

        s.windowDist += hSpeed;
        s.windowMaxFriction = Math.max(s.windowMaxFriction, surface[0]);
        s.windowFlow |= surface[1] > 0;
        if (now - s.windowStartMs < BOAT_WINDOW_MS) return;

        double f = Math.min(s.windowMaxFriction, 0.995);
        double accel = BOAT_ACCEL + (s.windowFlow ? FLOW_PUSH : 0);
        double ticks = (now - s.windowStartMs) / 50.0 + 1;          // client ticks, +1 for jitter
        double steady = steadySpeed(f, accel);
        double fn = Math.pow(f, ticks);
        // Σ of v_k over the window, v_k = vBound·f^k + steady·(1 − f^k)
        double maxDist = steady * ticks + (s.vBound - steady) * f * (1 - fn) / (1 - f);
        double cap = maxDist * BOAT_SPEED_MULT + BOAT_SPEED_SLACK;

        if (s.windowDist > cap) {
            s.overWindows++;
            debugLog(player, "BoatSpeed dist=" + fmt(s.windowDist) + " max=" + fmt(cap) + " in "
                    + (now - s.windowStartMs) + "ms friction=" + fmt(f) + " windows=" + s.overWindows + "/" + BOAT_SPEED_WINDOWS);
            if (s.overWindows >= BOAT_SPEED_WINDOWS) {
                s.overWindows = 0;
                flag(player, id, "BoatSpeed " + fmt(s.windowDist) + "b in " + (now - s.windowStartMs)
                        + "ms, max " + fmt(cap) + (f <= 0.9 ? " (water/air)" : " (friction " + fmt(f) + ")"));
            }
        } else {
            s.overWindows = 0;
        }
        s.vBound = s.vBound * fn + steady * (1 - fn);
        startWindow(s, now);
    }

    private static double steadySpeed(double friction, double accel) {
        return accel / (1 - Math.min(friction, 0.995));
    }

    private static void startWindow(BoatState s, long now) {
        s.windowStartMs = now;
        s.windowDist = 0;
        s.windowMaxFriction = 0;
        s.windowFlow = false;
    }

    /**
     * Where a boat hull is, as {friction, current push}.  In a fluid → 0.9
     * (water; under-water is 0.45, so 0.9 is the upper bound), plus the
     * current push if any of that water is flowing.  On land → the slipperiest
     * block under the hull (Boat#getGroundFriction averages them; max is the
     * upper bound).  Neither → in the air, 0.9.
     */
    private double[] boatSurface(World w, BoundingBox hull) {
        BoundingBox probe = hull.clone().expand(0, 0.1, 0, 0, 0, 0);
        int x0 = (int) Math.floor(probe.getMinX()), x1 = (int) Math.floor(probe.getMaxX());
        int z0 = (int) Math.floor(probe.getMinZ()), z1 = (int) Math.floor(probe.getMaxZ());
        int y0 = (int) Math.floor(probe.getMinY()) - 1, y1 = (int) Math.floor(probe.getMaxY());
        boolean inFluid = false, flowing = false;
        double land = 0;
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                for (int y = y0; y <= y1; y++) {
                    Block b = w.getBlockAt(x, y, z);
                    Material m = b.getType();
                    if (m.isAir()) continue;
                    if (isFluid(b)) {
                        if (new BoundingBox(x, y, z, x + 1, y + 1, z + 1).overlaps(probe)) {
                            inFluid = true;
                            if (b.getBlockData() instanceof Levelled lv && lv.getLevel() != 0) flowing = true;
                        }
                        continue;
                    }
                    for (BoundingBox shape : b.getCollisionShape().getBoundingBoxes()) {
                        if (shape.shift(x, y, z).overlaps(probe)) {
                            land = Math.max(land, blockFriction(m));
                            break;
                        }
                    }
                }
            }
        }
        if (inFluid) return new double[]{ 0.9, flowing ? FLOW_PUSH : 0 };
        return new double[]{ land > 0 ? land : 0.9, 0 };
    }

    private static double blockFriction(Material m) {
        return switch (m) {
            case ICE, PACKED_ICE, FROSTED_ICE -> 0.98;
            case BLUE_ICE -> 0.989;
            case SLIME_BLOCK -> 0.8;
            default -> 0.6;
        };
    }

    /**
     * Precise support test for the gravity check: is the boat's hull resting
     * on a block (collision shape), in / on a fluid, or on another boat,
     * minecart or shulker?  (isBoatOnGround above is deliberately generous —
     * it counts ground up to 0.75 below — so it can't tell "in the air".)
     */
    private boolean isBoatSupported(Entity boat) {
        World w = boat.getWorld();
        BoundingBox probe = boat.getBoundingBox().expand(0, 0.05, 0, 0, 0, 0); // 0.05 below the hull
        int x0 = (int) Math.floor(probe.getMinX()), x1 = (int) Math.floor(probe.getMaxX());
        int z0 = (int) Math.floor(probe.getMinZ()), z1 = (int) Math.floor(probe.getMaxZ());
        int y0 = (int) Math.floor(probe.getMinY()) - 1, y1 = (int) Math.floor(probe.getMaxY());
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                for (int y = y0; y <= y1; y++) {
                    Block b = w.getBlockAt(x, y, z);
                    Material m = b.getType();
                    if (m.isAir()) continue;
                    if (isFluid(b)) {
                        if (new BoundingBox(x, y, z, x + 1, y + 1, z + 1).overlaps(probe)) return true;
                        continue;
                    }
                    for (BoundingBox shape : b.getCollisionShape().getBoundingBoxes()) {
                        if (shape.shift(x, y, z).overlaps(probe)) return true;
                    }
                }
            }
        }
        return !w.getNearbyEntities(probe, e -> e != boat && !boat.getPassengers().contains(e)
                && (e instanceof Boat || e instanceof Minecart || e instanceof Shulker)).isEmpty();
    }

    private boolean isFluid(Block b) {
        Material m = b.getType();
        if (m == Material.WATER || m == Material.LAVA || m == Material.BUBBLE_COLUMN
                || m == Material.KELP || m == Material.KELP_PLANT
                || m == Material.SEAGRASS || m == Material.TALL_SEAGRASS) return true;
        return b.getBlockData() instanceof Waterlogged wl && wl.isWaterlogged();
    }

    private void flag(Player player, UUID id, String reason) {
        BoatState s = state.get(id);
        if (s == null) return;
        s.vl++;
        debugLog(player, reason + " vl=" + s.vl + "/" + BAN_VL);
        if (s.vl >= BAN_VL) { state.remove(id); bm.banPlayer(player, reason); }
    }

    private boolean isExempt(Player player) {
        GameMode gm = player.getGameMode();
        return player.hasPermission("anticheat.bypass")
                || wm.isWhitelisted(player.getUniqueId())
                || gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR
                || player.isDead()
                || vm.hasJoinGrace(player.getUniqueId())
                || vm.hasTeleportGrace(player.getUniqueId());
    }

    public void removePlayer(UUID id) { state.remove(id); launcherGrace.remove(id); }

    /** Fresh position baseline, keeping VL so a re-mount can't launder it. */
    public void resetMotion(UUID id) {
        launcherGrace.remove(id);
        BoatState old = state.remove(id);
        if (old == null) return;
        BoatState s = new BoatState();
        s.vl = old.vl;
        state.put(id, s);
    }

    private void debugLog(Player player, String msg) {
        if (plugin.getConfig().getBoolean("debug.enabled", false)) {
            plugin.getLogger().info("[DEBUG][BoatFly] " + player.getName() + " » " + msg);
        }
    }

    private String fmt(double d) { return String.format("%.4f", d); }
}
