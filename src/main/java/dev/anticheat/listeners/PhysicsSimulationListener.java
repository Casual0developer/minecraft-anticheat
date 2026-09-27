package dev.anticheat.listeners;

import dev.anticheat.AntiCheatPlugin;
import dev.anticheat.managers.BanManager;
import dev.anticheat.managers.ViolationManager;
import dev.anticheat.managers.WhitelistManager;
import dev.anticheat.utils.GroundUtil;
import dev.anticheat.utils.Momentum;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.potion.PotionEffectType;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PhysicsSimulationListener — NEW unified simulation for Speed, Flight, Jesus, BoatFly
 * with full ice + attribute + potion + enchant awareness.
 *
 * Goals:
 *  - Speed: accounts for GENERIC_MOVEMENT_SPEED attribute, sprint, speed potion, soul speed, depth strider, dolphin, honey/slime, and ICE friction (ICE 1.4x, PACKED 1.7x, BLUE 2.8x, FROSTED 1.4x)
 *  - Flight: accurate gravity (0.08) + drag (0.98) vs expected, with levitation/slow-falling/elytra/wind-burst grace aware
 *  - Jesus: detects water-walk without frost walker / boat / riptide / dolphin; allows legitimate shallow / lily / frosted ice
 *  - BoatFly: ice-aware (boats on blue ice legit 1.2-1.5 b/t) + vertical cap with launcher grace
 */
public class PhysicsSimulationListener implements Listener {

    private final AntiCheatPlugin plugin;
    private final BanManager bm;
    private final ViolationManager vm;
    private final WhitelistManager wm;

    private static class SimState {
        double prevX = Double.NaN, prevZ = Double.NaN, prevY = Double.NaN;
        double prevDy = 0;
        int airTicks = 0;
        int speedVL = 0, flightVL = 0, jesusVL = 0, boatVL = 0;
        int jesusAirTicks = 0;
        long lastSpeedFlag = 0, lastFlightFlag = 0;
        int graceTicks = 0;
        /** Legit horizontal momentum carried between ticks (elytra landings, ice, knockback). */
        final Momentum momentum = new Momentum();
    }

    private final Map<UUID, SimState> state = new ConcurrentHashMap<>();

    public PhysicsSimulationListener(AntiCheatPlugin plugin) {
        this.plugin = plugin;
        this.bm = plugin.getBanManager();
        this.vm = plugin.getViolationManager();
        this.wm = plugin.getWhitelistManager();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!plugin.getConfig().getBoolean("checks.physics-simulation", true)) return;
        // No TPS kill-switch: per-packet movement isn't changed by server lag.
        Location to = event.getTo();
        Location from = event.getFrom();
        if (to == null) return;
        Player p = event.getPlayer();
        UUID id = p.getUniqueId();
        if (isExempt(p)) { state.remove(id); return; }

        SimState s = state.computeIfAbsent(id, k -> new SimState());
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        double dy = to.getY() - from.getY();
        double hDist = Math.hypot(dx, dz);

        // Grace movement (launches, teleports) is legit — carry its speed forward.
        if (vm.hasJoinGrace(id) || vm.hasTeleportGrace(id)) { s.momentum.step(p, from, to, hDist, true); return; }
        if (s.graceTicks > 0) {
            s.graceTicks--; s.prevX = to.getX(); s.prevZ = to.getZ(); s.prevY = to.getY();
            s.momentum.step(p, from, to, hDist, true);
            return;
        }

        // Ignore micro head movements
        if (hDist < 0.001 && Math.abs(dy) < 0.001) { s.momentum.step(p, from, to, hDist, false); return; }

        // --- Ground / Ice / Attribute aware speed simulation ---
        checkSpeed(p, id, s, from, to, hDist, dx, dz);

        // --- Flight simulation (vertical) ---
        checkFlight(p, id, s, from, to, dy);

        // --- Jesus (water walk) ---
        checkJesus(p, id, s, to, hDist);

        s.prevX = to.getX(); s.prevZ = to.getZ(); s.prevY = to.getY(); s.prevDy = dy;
        if (isOnGround(p, to)) s.airTicks = 0; else s.airTicks++;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBoatMove(VehicleMoveEvent event) {
        if (!(event.getVehicle() instanceof Boat boat)) return;
        if (boat.getPassengers().isEmpty()) return;
        for (var e : boat.getPassengers()) if (e instanceof Player p) {
            UUID id = p.getUniqueId();
            if (isExempt(p)) continue;
            if (vm.hasTeleportGrace(id) || vm.hasJoinGrace(id)) continue;
            SimState s = state.computeIfAbsent(id, k -> new SimState());
            Location from = event.getFrom();
            Location to = event.getTo();
            double dx = to.getX() - from.getX();
            double dz = to.getZ() - from.getZ();
            double dy = to.getY() - from.getY();
            double h = Math.hypot(dx, dz);
            // Ice-aware boat speed
            double iceMult = getBoatIceMultiplier(to);
            double baseBoatMax = 0.4 + (iceMult * 0.9); // 0.4 normal water, ~1.3 blue ice + 0.9 =1.3? actually base 0.4 +2.8*0.9≈2.9 on blue ice
            if (iceMult > 1.5) baseBoatMax = 2.9; // blue ice cap

            // Vertical boat fly (should be ~0)
            if (dy > 0.12 && !isBoatOnWaterOrIce(to)) {
                s.boatVL++;
                if (s.boatVL >= 3) { s.boatVL = 0; debug(p, "BoatFlySim vertical dy=" + fmt(dy)); bm.banPlayer(p, "BoatFly[Sim] dy=" + fmt(dy)); }
            } else if (h > baseBoatMax + 0.35) {
                s.boatVL++;
                if (s.boatVL >= 4) { s.boatVL = 0; debug(p, "BoatFlySim h=" + fmt(h)+" max="+fmt(baseBoatMax)); bm.banPlayer(p, "BoatFly[Sim] h=" + fmt(h)); }
            } else {
                s.boatVL = Math.max(0, s.boatVL-1);
            }
        }
    }

    // ── Speed ─────────────────────────────────────────────────────────────
    private void checkSpeed(Player p, UUID id, SimState s, Location from, Location to, double hDist, double dx, double dz) {
        // Skip if in liquid / vehicle / elytra (those have own physics) or
        // knockback.  Those ticks are trusted: their speed is carried forward
        // as momentum, so the land → jump → re-deploy of elytra bouncing fits.
        boolean trusted = p.isInsideVehicle() || p.isGliding() || p.isSwimming() || p.isInWater()
                || vm.hasDamageGrace(id);
        double carriedMax = s.momentum.step(p, from, to, hDist, trusted);
        if (trusted) return;

        // Momentum bleeding off from a legit burst (e.g. elytra bouncing:
        // land → jump → re-deploy) under vanilla friction.
        double tol = plugin.getConfig().getDouble("settings.speed-sim-tolerance", 0.07);
        double max = Math.max(computeMaxHorizontal(p, to, from), carriedMax * 1.03 + tol);
        if (hDist > max) {
            long now = System.currentTimeMillis();
            // Grace for velocity (knockback, wind, riptide, lunge)
            if (now - s.lastSpeedFlag < 350) return;
            s.lastSpeedFlag = now;
            s.speedVL++;
            debug(p, "SpeedSim hDist=" + fmt(hDist) + " max=" + fmt(max) + " vl=" + s.speedVL + " ice=" + fmt(getIceMultiplier(to)) + " attr=" + fmt(getAttributeFactor(p)));
            int thresh = plugin.getConfig().getInt("settings.speed-sim-vl", 3);
            if (s.speedVL >= thresh) { s.speedVL = 0; bm.banPlayer(p, "Speed[Sim] " + fmt(hDist) + ">" + fmt(max)); }
        } else {
            s.speedVL = Math.max(0, s.speedVL-1);
        }
    }

    private double computeMaxHorizontal(Player p, Location to, Location from) {
        double base = 0.287; // vanilla walk 0.2, sprint 0.28, sprint-jump 0.38-0.46, we start 0.287
        // Attribute scaling - GENERIC_MOVEMENT_SPEED default 0.1
        double attrFactor = getAttributeFactor(p); // 1.0 normal, 2.0 if attribute doubled
        base *= attrFactor;

        // Sprint
        if (p.isSprinting()) base *= 1.30;

        // Speed potion
        if (p.hasPotionEffect(PotionEffectType.SPEED)) {
            int amp = p.getPotionEffect(PotionEffectType.SPEED).getAmplifier()+1;
            base *= (1 + 0.2 * amp);
        }

        // Soul speed on soul sand/soil
        double soulMult = getSoulSpeedMultiplier(p, to);
        base *= soulMult;

        // Depth strider / Dolphin only in water, already exempt, so ignore here

        // Ice multiplier
        double ice = getIceMultiplier(to);
        base *= ice;

        // Honey / soul sand slow handled via soulMult/ice, but if on honey block, cap is low
        Material below = to.clone().subtract(0,0.2,0).getBlock().getType();
        if (below == Material.HONEY_BLOCK) base *= 0.4;
        if (below == Material.SOUL_SAND && soulMult == 1.0) base *= 0.5; // soul sand without enchant is slow

        // Jump boost airborne bonus
        if (!isOnGround(p, to)) base += 0.08;

        // Tolerance + slime/hay etc. Scaffolding?
        double tol = plugin.getConfig().getDouble("settings.speed-sim-tolerance", 0.07);
        return base + tol;
    }

    private double getAttributeFactor(Player p) {
        try {
            AttributeInstance inst = p.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED);
            if (inst != null) {
                double val = inst.getValue(); // default 0.1
                if (val <= 0) return 1.0;
                return val / 0.1;
            }
        } catch (Throwable ignored) {}
        try {
            // Newer name MOVEMENT_SPEED
            AttributeInstance inst2 = p.getAttribute(Attribute.valueOf("MOVEMENT_SPEED"));
            if (inst2 != null) return inst2.getValue() / 0.1;
        } catch (Throwable ignored) {}
        return 1.0;
    }

    private double getIceMultiplier(Location loc) {
        // Only for simulation — check precise ice under feet with small offset
        // Previous 2.85 for BLUE allowed 70x speed before flag; now reduced to realistic vanilla caps
        for (double dy : new double[]{0.1, 0.4, 0.7, 1.0}) {
            Block b = loc.clone().subtract(0, dy, 0).getBlock();
            Material m = b.getType();
            double mult = switch (m) {
                case ICE -> 1.28;
                case PACKED_ICE -> 1.45;
                case BLUE_ICE -> 1.85;
                case FROSTED_ICE -> 1.28;
                default -> 0;
            };
            if (mult > 0) return mult;
        }
        // Edge case: player on boundary of blue/packed ice block
        for (double ox = -0.3; ox <= 0.3; ox+=0.3)
            for (double oz = -0.3; oz <= 0.3; oz+=0.3) {
                Block b = loc.clone().add(ox, -0.4, oz).getBlock();
                if (b.getType() == Material.BLUE_ICE) return 1.65;
                if (b.getType() == Material.PACKED_ICE) return 1.35;
            }
        return 1.0;
    }

    private double getBoatIceMultiplier(Location loc) {
        // Boat on ice is legit fast — keep higher but simulation-only
        for (double dy : new double[]{0.4, 0.8}) {
            Block b = loc.clone().subtract(0, dy, 0).getBlock();
            Material m = b.getType();
            if (m == Material.BLUE_ICE) return 2.2;
            if (m == Material.PACKED_ICE) return 1.6;
            if (m == Material.ICE) return 1.35;
        }
        return 1.0;
    }

    private double getSoulSpeedMultiplier(Player p, Location loc) {
        Block below = loc.clone().subtract(0,0.3,0).getBlock();
        Material m = below.getType();
        if (m != Material.SOUL_SAND && m != Material.SOUL_SOIL) return 1.0;
        // Check boots for soul speed
        var boots = p.getInventory().getBoots();
        if (boots == null) return 1.0;
        int lvl = 0;
        try { lvl = boots.getEnchantmentLevel(Enchantment.SOUL_SPEED); } catch (Throwable ignored) {}
        if (lvl <= 0) {
            // Try via namespaced
            try {
                for (var e : boots.getEnchantments().entrySet()) if (e.getKey().getKey().toString().toLowerCase().contains("soul_speed")) lvl = e.getValue();
            } catch (Throwable ignored) {}
        }
        if (lvl <= 0) return 1.0;
        // Soul speed 1: 1.3x, 2:1.6x, 3:2.0x
        return 1.0 + lvl * 0.33;
    }

    // ── Flight ─────────────────────────────────────────────────────────────
    private void checkFlight(Player p, UUID id, SimState s, Location from, Location to, double dy) {
        if (p.isFlying() || p.isGliding() || p.isInsideVehicle()) return;
        if (p.hasPotionEffect(PotionEffectType.LEVITATION) || p.hasPotionEffect(PotionEffectType.SLOW_FALLING)) return;
        if (isOnClimbable(p)) return;
        if (isInLiquid(p)) return;
        if (isOnSlime(to) || isOnHoney(to)) return;
        // Powder snow handled via exemption
        if (isInPowderSnow(to)) return;

        double expected = s.prevDy * 0.98 - 0.08;
        // If just jumped, allow 0.42
        if (s.airTicks < 3 && dy > 0.32) {
            double jumpBoost = 0;
            if (p.hasPotionEffect(PotionEffectType.JUMP_BOOST)) jumpBoost = (p.getPotionEffect(PotionEffectType.JUMP_BOOST).getAmplifier()+1)*0.1;
            double maxJump = 0.42 + jumpBoost + getAttributeFactor(p)*0.02;
            if (dy > maxJump + 0.12) {
                s.flightVL++;
                if (s.flightVL >= 3) { s.flightVL=0; debug(p,"FlightSim jump dy="+fmt(dy)); bm.banPlayer(p,"Flight[Sim] dy="+fmt(dy)); }
            }
            return;
        }
        // Gravity violation
        if (s.airTicks > 5 && dy > expected + 0.16) {
            long now = System.currentTimeMillis();
            if (now - s.lastFlightFlag < 400) return;
            s.lastFlightFlag = now;
            s.flightVL++;
            debug(p,"FlightSim gravity dy="+fmt(dy)+" exp="+fmt(expected)+" vl="+s.flightVL);
            if (s.flightVL >= 4) { s.flightVL=0; bm.banPlayer(p,"Flight[Sim] gravity"); }
        } else {
            s.flightVL = Math.max(0, s.flightVL-1);
        }
    }

    // ── Jesus ─────────────────────────────────────────────────────────────
    private void checkJesus(Player p, UUID id, SimState s, Location loc, double hDist) {
        if (p.isFlying() || p.isGliding() || p.isInsideVehicle() || p.isSwimming() || p.isInWater()) return;
        if (p.hasPotionEffect(PotionEffectType.WATER_BREATHING) && false) return; // not relevant
        // Frost walker enchant allows jesus
        var boots = p.getInventory().getBoots();
        if (boots != null) {
            try { if (boots.getEnchantmentLevel(Enchantment.FROST_WALKER) > 0) return; } catch (Throwable ignored) {}
            try { for (var e: boots.getEnchantments().entrySet()) if (e.getKey().getKey().toString().toLowerCase().contains("frost")) return; } catch (Throwable ignored) {}
        }
        // Check if above water with air below feet but water 0.2-0.5 below
        Block feet = loc.getBlock();
        if (feet.getType() == Material.WATER) return; // inside water not jesus
        Block below = loc.clone().subtract(0,0.3,0).getBlock();
        Block below2 = loc.clone().subtract(0,0.8,0).getBlock();
        boolean waterBelow = below.getType() == Material.WATER || below.getType() == Material.BUBBLE_COLUMN || below2.getType() == Material.WATER;
        if (!waterBelow) { s.jesusAirTicks = 0; return; }
        // Check depth: if water depth >1, it's deep water, jesus is moving on surface
        // Must be within 0.1-0.4 above water surface and moving horizontally
        double y = loc.getY();
        double waterY = below.getY() + 1; // top of water
        if (below.getType() != Material.WATER) waterY = below2.getY() + 1;
        double dyAbove = y - waterY;
        if (dyAbove < -0.3 || dyAbove > 0.45) { s.jesusAirTicks = 0; return; }
        // Must have solid water surface, not lily/slab above
        if (feet.getType().isSolid() || feet.getType() == Material.LILY_PAD) { s.jesusAirTicks=0; return; }

        if (hDist > 0.14) {
            s.jesusAirTicks++;
            if (s.jesusAirTicks >= 8) {
                s.jesusVL++;
                debug(p,"JesusSim hDist="+fmt(hDist)+" dyAbove="+fmt(dyAbove)+" vl="+s.jesusVL);
                int thresh = plugin.getConfig().getInt("settings.jesus-vl", 3);
                if (s.jesusVL >= thresh) { s.jesusVL=0; s.jesusAirTicks=0; bm.banPlayer(p,"Jesus[Sim] walking on water"); }
            }
        } else {
            s.jesusAirTicks = Math.max(0, s.jesusAirTicks-1);
            s.jesusVL = Math.max(0, s.jesusVL-1);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────
    private boolean isExempt(Player p) {
        GameMode gm = p.getGameMode();
        return p.hasPermission("anticheat.bypass") || wm.isWhitelisted(p.getUniqueId())
                || gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR || p.isDead()
                || p.isInsideVehicle() && !(p.getVehicle() instanceof Boat) // boats handled separately
                || vm.hasJoinGrace(p.getUniqueId());
    }

    private boolean isOnGround(Player p, Location loc) {
        if (GroundUtil.isOnGround(p, loc)) return true;
        Block b = loc.clone().subtract(0,0.25,0).getBlock();
        if (b.getType().isSolid()) return true;
        Block b2 = loc.clone().subtract(0,0.65,0).getBlock();
        return b2.getType().isSolid();
    }
    private boolean isOnClimbable(Player p) {
        Material m = p.getLocation().getBlock().getType();
        return switch (m) {
            case LADDER, VINE, SCAFFOLDING, TWISTING_VINES, TWISTING_VINES_PLANT, WEEPING_VINES, WEEPING_VINES_PLANT, CAVE_VINES, CAVE_VINES_PLANT -> true;
            default -> m.name().contains("KELP");
        };
    }
    private boolean isInLiquid(Player p) { return p.isInWater() || p.isSwimming() || p.getLocation().getBlock().getType()==Material.LAVA || p.getEyeLocation().getBlock().getType()==Material.LAVA; }
    private boolean isOnSlime(Location loc) { for(double dy=-0.1; dy>=-1.0; dy-=0.3) if (loc.clone().add(0,dy,0).getBlock().getType()==Material.SLIME_BLOCK) return true; return false; }
    private boolean isOnHoney(Location loc) {
        for(double dy=-0.1; dy>=-0.7; dy-=0.3) if (loc.clone().add(0,dy,0).getBlock().getType()==Material.HONEY_BLOCK) return true;
        return false;
    }
    private boolean isInPowderSnow(Location loc) {
        if (loc.getBlock().getType()==Material.POWDER_SNOW) return true;
        if (loc.clone().add(0,0.2,0).getBlock().getType()==Material.POWDER_SNOW) return true;
        return false;
    }
    private boolean isBoatOnWaterOrIce(Location loc) {
        Block below = loc.clone().subtract(0,0.4,0).getBlock();
        Material m = below.getType();
        return m==Material.WATER || m==Material.BUBBLE_COLUMN || m.isSolid() || m.name().contains("ICE");
    }

    private void debug(Player p, String msg) {
        if (plugin.getConfig().getBoolean("debug.enabled", false)) plugin.getLogger().info("[DEBUG][Sim] " + p.getName() + " » " + msg);
    }
    private String fmt(double d){ return String.format("%.3f", d); }

    public void removePlayer(UUID id){ state.remove(id); }

    /** Fresh motion baseline, carrying the VLs and flag cooldowns over. */
    public void resetMotion(UUID id) {
        SimState old = state.remove(id);
        if (old == null) return;
        SimState s = new SimState();
        s.speedVL = old.speedVL;
        s.flightVL = old.flightVL;
        s.jesusVL = old.jesusVL;
        s.boatVL = old.boatVL;
        s.lastSpeedFlag = old.lastSpeedFlag;
        s.lastFlightFlag = old.lastFlightFlag;
        state.put(id, s);
    }
}
