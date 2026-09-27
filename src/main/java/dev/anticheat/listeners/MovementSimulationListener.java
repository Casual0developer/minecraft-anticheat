package dev.anticheat.listeners;

import dev.anticheat.AntiCheatPlugin;
import dev.anticheat.managers.BanManager;
import dev.anticheat.managers.ViolationManager;
import dev.anticheat.managers.WhitelistManager;
import dev.anticheat.utils.GroundUtil;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class MovementSimulationListener implements Listener {

    private final AntiCheatPlugin plugin;
    private final BanManager bm;
    private final ViolationManager vm;
    private final WhitelistManager wm;

    private static class MoveState {
        int vl = 0;
        int stepVL = 0;
        int spiderVL = 0;
        int invWalkVL = 0;
        int spiderSimTicks = 0;
        int spiderSimVL = 0;
        int glideTicks = 0;
        int glideVL = 0;
        double prevDy = Double.MIN_VALUE;
        int levNotRisingTicks = 0;
        int levTooFastTicks = 0;
        int levVL = 0;

        double prevY = Double.MIN_VALUE;
        boolean prevOnGround = true;
        int airTicks = 0;
        double groundY = Double.MIN_VALUE;
        int jumpGrace = 0;
        double lastGroundY = Double.MIN_VALUE;
        int stepHeight = 0;
        boolean wasStepping = false;
        int climbTicks = 0;
        double verticalStack = 0;
        int consistentClimb = 0;
        int slimeBounceTicks = 0;

        // Grace period (in ticks) after leaving the ground via a legitimate jump.
        // Spider checks are suppressed while this is > 0, preventing false flags
        // when a player jumps next to a tall wall (the wall makes isAgainstWall()
        // true even though the upward motion is from vanilla jump physics).
        int jumpGraceTicks = 0;

        // Cumulative Y gained since the last ground contact, used to distinguish
        // a normal jump arc (≤ ~1.25 b) from a genuine spider-climb.
        double totalAirRise = 0;

        // How many consecutive ticks the server has reported onGround == true.
        // Bukkit's isOnGround() occasionally flickers false for one tick on flat
        // ground, which would silently increment airTicks.  We require this to
        // be < 3 before Spider[Fast] can treat the player as genuinely airborne,
        // so standing/walking next to a wall never satisfies the check.
        int groundedTicks = 0;
    }

    private final Map<UUID, MoveState> state = new ConcurrentHashMap<>();

    private static final int BAN_VL = 2;

    public MovementSimulationListener(AntiCheatPlugin plugin) {
        this.plugin = plugin;
        this.bm = plugin.getBanManager();
        this.vm = plugin.getViolationManager();
        this.wm = plugin.getWhitelistManager();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!plugin.getConfig().getBoolean("checks.movement-simulation", true)) return;
        // No TPS kill-switch: per-packet movement isn't changed by server lag.
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();

        if (isExempt(player)) { state.remove(id); return; }

        Location to = event.getTo();
        Location from = event.getFrom();
        if (to == null || from == null) return;

        MoveState s = state.computeIfAbsent(id, k -> new MoveState());

        double deltaY = to.getY() - from.getY();
        double deltaX = to.getX() - from.getX();
        double deltaZ = to.getZ() - from.getZ();
        double hSpeed = Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);
        boolean onGround = GroundUtil.isOnGround(player, to);

        if (s.prevY == Double.MIN_VALUE) {
            s.prevY = from.getY();
            s.prevOnGround = onGround;
            s.groundY = from.getY();
            s.lastGroundY = from.getY();
            return;
        }

        int airTicksBefore = s.airTicks;

        if (onGround && !s.prevOnGround) {
            // Just landed — reset all climb state so a mid-air accumulation that
            // crossed the 3-tick threshold cannot bleed into the next jump.
            s.groundY = to.getY();
            s.airTicks = 0;
            s.stepHeight = 0;
            s.climbTicks = 0;
            s.verticalStack = 0;
            s.consistentClimb = 0;
            s.totalAirRise = 0;
            s.groundedTicks = 1;
            s.glideTicks = 0;
            // Brief grace so the first couple of ticks after landing (where the
            // player may still register as "against wall") are not counted.
            s.jumpGraceTicks = Math.max(s.jumpGraceTicks, 2);
        } else if (onGround) {
            s.airTicks = 0;
            s.totalAirRise = 0;
            s.groundedTicks++;
            s.glideTicks = 0;
            if (s.jumpGraceTicks > 0) s.jumpGraceTicks--;
        } else {
            // First tick off the ground → begin a jump-grace window.
            // 10 ticks covers the entire rising phase of a max-height vanilla jump
            // (~0.42 + 0.35 + 0.28 + … ≈ 1.25 b over ~6–7 ticks), with margin.
            if (s.prevOnGround) {
                s.jumpGraceTicks = 10;
                s.totalAirRise = 0;
                s.glideTicks = 0;
            }
            s.groundedTicks = 0;
            s.airTicks++;
            if (deltaY > 0) s.totalAirRise += deltaY;
            if (s.jumpGraceTicks > 0) s.jumpGraceTicks--;
        }

        // --- Step check ---
        // Vanilla auto-steps up to the step_height attribute (0.6 b by default)
        // without leaving the ground, so stairs/slabs (0.5), soul sand, paths,
        // snow layers etc. are all legitimate ground-to-ground rises.  Only flag:
        //  • ground → ground rises above step_height (instant/vanilla-mode Step), or
        //  • landings that gain more than step_height within ≤3 air ticks
        //    (packet Step: +0.42, +0.75, +1.0 onGround).  A real jump can't
        //    touch down while still rising, and step-assist on landing needs a
        //    falling tick, which a jump only reaches after ~6 ticks.
        if (onGround && deltaY > 0.001) {
            double maxStep = getStepHeight(player) + 0.01;
            // Use deltaY for ground → ground so a stale lastGroundY (e.g. after a
            // teleport) can't inflate the rise.
            double yGain = s.prevOnGround ? deltaY : to.getY() - s.lastGroundY;
            boolean tooFast = s.prevOnGround || airTicksBefore <= 3;
            if (yGain > maxStep && yGain < 1.5 && tooFast && hSpeed > 0.05) {
                s.stepVL++;
                debugLog(player, "Step height=" + String.format("%.3f", yGain)
                        + " hSpeed=" + String.format("%.3f", hSpeed)
                        + " vl=" + s.stepVL + "/" + BAN_VL);
                if (s.stepVL >= BAN_VL) {
                    flag(player, id, "Step walked up " + String.format("%.3f", yGain) + "b without jumping");
                    s.stepVL = 0;
                }
            } else {
                s.stepVL = Math.max(0, s.stepVL - 1);
            }
            s.lastGroundY = to.getY();
        }

        if (isOnSlimeBlock(player) && deltaY > 0) {
            s.slimeBounceTicks = 10;
        }
        if (s.slimeBounceTicks > 0) s.slimeBounceTicks--;

        // --- Spider (slow) check ---
        // Skip entirely while inside a jump-grace window or while totalAirRise
        // is still within the vanilla jump envelope (~1.26 b).  This prevents
        // a player who jumps next to a tall wall from being flagged because
        // isAgainstWall() is true throughout the entire legitimate jump arc.
        if (!onGround && deltaY > 0.001 && hSpeed < 0.08 && !isInClimbable(player)
                && !isOnSlimeBlock(player) && s.slimeBounceTicks == 0
                && s.jumpGraceTicks == 0 && s.totalAirRise > 1.30) {
            boolean againstWall = isAgainstWall(player);

            if (againstWall && !s.prevOnGround) {
                s.climbTicks++;
                s.verticalStack += deltaY;
                if (s.climbTicks >= 3) {
                    s.spiderVL++;
                    debugLog(player, "Spider climb=" + s.climbTicks + "ticks total="
                            + String.format("%.3f", s.verticalStack) + "b vl=" + s.spiderVL + "/" + BAN_VL);
                    if (s.spiderVL >= BAN_VL) {
                        flag(player, id, "Spider climbed " + String.format("%.3f", s.verticalStack) + "b vertically");
                        s.spiderVL = 0;
                    }
                    s.climbTicks = 0;
                    s.verticalStack = 0;
                }
            } else if (!againstWall || s.prevOnGround) {
                s.climbTicks = Math.max(0, s.climbTicks - 2);
                s.verticalStack = 0;
            }
        } else if (deltaY <= 0.001 || s.jumpGraceTicks > 0) {
            // Decaying: also drain during grace so stale counts don't linger.
            s.climbTicks = Math.max(0, s.climbTicks - 2);
            s.verticalStack = 0;
        }

        // --- Spider[Fast] check ---
        // Same grace conditions as the slow check.  airTicks > 8 already rules
        // out most jump scenarios, but combining it with jumpGraceTicks == 0,
        // totalAirRise > 1.30, and groundedTicks == 0 (player left the ground
        // this air-phase, not a Bukkit isOnGround() flicker while standing)
        // prevents false flags when standing next to 1/2/3-block structures.
        if (deltaY > 0.1 && !onGround && !isInClimbable(player) && hSpeed < 0.08
                && isAgainstWall(player) && s.airTicks > 8 && s.slimeBounceTicks == 0
                && s.jumpGraceTicks == 0 && s.totalAirRise > 1.30 && s.groundedTicks == 0) {
            s.consistentClimb++;
            if (s.consistentClimb >= 3) {
                s.spiderVL++;
                debugLog(player, "Spider[Fast] dY=" + String.format("%.3f", deltaY)
                        + " climb=" + s.consistentClimb + " vl=" + s.spiderVL + "/" + BAN_VL);
                if (s.spiderVL >= BAN_VL) {
                    flag(player, id, "Spider[Fast] fast vertical climb");
                    s.spiderVL = 0;
                }
                s.consistentClimb = 0;
            }
        } else {
            s.consistentClimb = Math.max(0, s.consistentClimb - 1);
        }

        if (onGround) {
            s.lastGroundY = to.getY();
        }

        // --- InventoryWalk via Movement Simulation (detects moving while inventory GUI open) ---
        // Uses shared InventoryWalkListener state but via movement-sim physics (hSpeed)
        // Offhand (F swap) is exempt because it doesn't keep inventory open (invOpen false)
        try {
            if (plugin.getInventoryWalkListener().isInventoryOpen(id)) {
                if (hSpeed > 0.08 && !player.isDead() && !vm.hasJoinGrace(id)) {
                    s.invWalkVL++;
                    debugLog(player, "MoveSim[InvWalk] hSpeed=" + String.format("%.3f", hSpeed) + " vl=" + s.invWalkVL + "/3");
                    if (s.invWalkVL >= 3) {
                        flag(player, id, "InventoryWalk[Sim] moving " + String.format("%.3f", hSpeed) + "b/t with inventory open");
                        s.invWalkVL = 0;
                    }
                } else {
                    s.invWalkVL = Math.max(0, s.invWalkVL - 1);
                }
            } else {
                s.invWalkVL = 0;
            }
        } catch (Throwable ignored) {}

        // --- Spider Simulation (NEW — explicit spider added to movement simulation) ---
        // Simulates vanilla wall-climb: spider should be 0.12±0.05 b/t vertical while pressed to wall, no jumping, no climbable
        // Accounts for ice (friction 0.98→0.99 on ice, slightly faster) and speed attribute (sprinting)
        // Previous spider checks were threshold-based; this is tick-perfect simulation
        if (!onGround && isAgainstWall(player) && !isInClimbable(player) && !isOnSlimeBlock(player)
                && s.jumpGraceTicks == 0 && s.slimeBounceTicks == 0 && !isOnIce(to) ) {
            // Valid spider window: must be airborne >4 ticks, totalAirRise >1.0 (beyond jump), and vertical in spider range
            boolean inSpiderDy = deltaY > 0.07 && deltaY < 0.38;
            boolean lowHorizontal = hSpeed < 0.14;
            // Ice increases max horizontal slightly, but spider requires low horizontal
            if (isOnIce(to)) lowHorizontal = hSpeed < 0.18;
            if (inSpiderDy && lowHorizontal && s.airTicks > 4 && s.totalAirRise > 1.0 && s.groundedTicks == 0) {
                s.spiderSimTicks++;
                // Spider climbs at ~0.12, so 6 ticks = 0.72 blocks — simulate expected vs actual
                if (s.spiderSimTicks >= 6) {
                    s.spiderSimVL++;
                    debugLog(player, "MoveSim[Spider] simTicks=" + s.spiderSimTicks + " dY=" + String.format("%.3f", deltaY) + " stack=" + String.format("%.3f", s.verticalStack+s.totalAirRise) + " vl=" + s.spiderSimVL + "/2");
                    int thresh = plugin.getConfig().getInt("settings.spider-sim-vl", 2);
                    if (s.spiderSimVL >= thresh) {
                        flag(player, id, "Spider[Sim] climbed " + String.format("%.2f", s.verticalStack + s.totalAirRise) + "b on wall (sim)");
                        s.spiderSimVL = 0;
                    }
                    s.spiderSimTicks = 0;
                }
            } else {
                s.spiderSimTicks = Math.max(0, s.spiderSimTicks - 1);
            }
        } else {
            s.spiderSimTicks = Math.max(0, s.spiderSimTicks - 2);
            if (onGround) s.spiderSimVL = Math.max(0, s.spiderSimVL - 1);
        }

        // --- Glide Simulation (WURST) — improved ---
        // Wurst glide: stable slow-fall -0.125 / -0.062 / -0.08 vs vanilla accelerating -0.08→-0.78
        // Detect via 3 complementary signals: slow-fall band, sim vs expected, and Wurst-exact velocities
        if (!onGround && s.airTicks > 10 && s.jumpGraceTicks == 0 && s.slimeBounceTicks == 0
                && !isInClimbable(player) && !isOnSlimeBlock(player) && !isOnHoney(to) && !isInPowderSnow(to)
                && !player.hasPotionEffect(PotionEffectType.SLOW_FALLING)
                && !player.hasPotionEffect(PotionEffectType.LEVITATION)
                && !player.isGliding() && !player.isSwimming() && !isInCobweb(player)
                && !player.isInsideVehicle() && !isInLiquidLev(player)) {
            boolean isSlowFall = deltaY < -0.008 && deltaY > -0.19 && deltaY > s.prevDy - 0.025;
            double expectedDy = s.prevDy != Double.MIN_VALUE ? s.prevDy * 0.98 - 0.08 : -0.08;
            boolean isSimGlide = s.prevDy != Double.MIN_VALUE && s.airTicks > 12 && dyFasterThanExpected(deltaY, expectedDy);
            // Wurst exact glide velocities (tested against Wurst 7.45 glide modes)
            boolean isWurstExact = Math.abs(deltaY + 0.125) < 0.012 || Math.abs(deltaY + 0.062) < 0.010
                    || Math.abs(deltaY + 0.08) < 0.015 || Math.abs(deltaY + 0.05) < 0.010 || Math.abs(deltaY + 0.15) < 0.015;
            boolean movingHorizontally = hSpeed > 0.05 && hSpeed < 0.65;
            boolean stableDy = s.prevDy != Double.MIN_VALUE && Math.abs(deltaY - s.prevDy) < 0.025;
            if ((isSlowFall && movingHorizontally) || isSimGlide || (isWurstExact && movingHorizontally && s.airTicks > 8) || (stableDy && isSlowFall && s.airTicks > 10)) {
                s.glideTicks++;
                int need = isWurstExact ? 14 : 20;
                if (s.glideTicks >= need) {
                    s.glideVL++;
                    debugLog(player, "MoveSim[Glide] dy=" + String.format("%.3f", deltaY) + " exp=" + String.format("%.3f", expectedDy) + " wurst=" + isWurstExact + " stable=" + stableDy + " ticks=" + s.glideTicks + " vl=" + s.glideVL + "/2");
                    int thresh = plugin.getConfig().getInt("settings.glide-sim-vl", 2);
                    if (s.glideVL >= thresh) {
                        String type = isWurstExact ? "Wurst" : "Sim";
                        flag(player, id, "Glide[" + type + "] falling " + String.format("%.3f", deltaY) + "b/t (slow-fall)");
                        s.glideVL = 0;
                    }
                    s.glideTicks = 0;
                }
            } else {
                s.glideTicks = Math.max(0, s.glideTicks - 2);
            }
        } else {
            s.glideTicks = Math.max(0, s.glideTicks - 2);
            if (onGround) s.glideVL = Math.max(0, s.glideVL - 1);
        }

        // --- Levitation Simulation (MOVED from LevitationSimulationListener) ---
        // Now lives in MovementSim — LevitationSimulationListener is dormant when movement-sim is enabled
        if (plugin.getConfig().getBoolean("checks.levitation-simulation", true)) {
        PotionEffect lev = player.getPotionEffect(PotionEffectType.LEVITATION);
        if (lev != null && !isExempt(player) && !vm.hasJoinGrace(id) && !vm.hasTeleportGrace(id)) {
            int amp = lev.getAmplifier();
            double expectedLev = 0.05 * (amp + 1);
            boolean hasBlockAbove = hasBlockAbove(player, to);
            boolean inCobwebLev = isInCobweb(player);
            boolean inPowderLev = isInPowderSnow(to);
            boolean onWaterLev = isOnWater(to) || isInWaterLev(player, to);
            boolean onHoneyLev = isOnHoney(to);
            boolean onSlimeLev = isOnSlimeBlock(player);
            boolean inLiquidLev = isInLiquidLev(player);
            boolean climbLev = isInClimbable(player);
            boolean obstructed = hasBlockAbove || inCobwebLev || inPowderLev || onWaterLev || onHoneyLev || onSlimeLev || inLiquidLev || climbLev;
            if (!obstructed) {
                boolean onGroundLev = isOnGroundLev(player, to);
                double levThresh = expectedLev - 0.025;
                if (onGroundLev) levThresh = Math.max(levThresh, 0.02);
                // Not rising
                if (expectedLev > 0.02 && deltaY < levThresh) {
                    s.levNotRisingTicks++;
                    int need = onGroundLev ? 8 : 12;
                    if (s.levNotRisingTicks >= need) {
                        s.levVL++;
                        debugLog(player, "MoveSim[Levitation-NotRising] amp=" + (amp+1) + " exp=" + String.format("%.3f", expectedLev) + " dy=" + String.format("%.3f", deltaY) + " vl=" + s.levVL);
                        int thresh = plugin.getConfig().getInt("settings.levitation-sim-vl", 2);
                        if (s.levVL >= thresh) {
                            flag(player, id, "Levitation[NotRising] has levitation " + (amp+1) + " but not rising (dy=" + String.format("%.3f", deltaY) + ")");
                            s.levVL = 0;
                        }
                        s.levNotRisingTicks = 0;
                    }
                } else {
                    s.levNotRisingTicks = Math.max(0, s.levNotRisingTicks - 2);
                }
                // Too fast
                double maxAllowedLev = expectedLev + 0.14 + (amp > 10 ? amp * 0.012 : 0);
                if (deltaY > maxAllowedLev) {
                    s.levTooFastTicks++;
                    if (s.levTooFastTicks >= 6) {
                        s.levVL++;
                        debugLog(player, "MoveSim[Levitation-TooFast] amp=" + (amp+1) + " max=" + String.format("%.3f", maxAllowedLev) + " dy=" + String.format("%.3f", deltaY) + " vl=" + s.levVL);
                        int thresh = plugin.getConfig().getInt("settings.levitation-sim-vl", 2);
                        if (s.levVL >= thresh) {
                            flag(player, id, "Levitation[TooFast] dy=" + String.format("%.3f", deltaY) + " > max " + String.format("%.3f", maxAllowedLev) + " for amp " + (amp+1));
                            s.levVL = 0;
                        }
                        s.levTooFastTicks = 0;
                    }
                } else {
                    s.levTooFastTicks = Math.max(0, s.levTooFastTicks - 1);
                }
            } else {
                s.levNotRisingTicks = 0;
                s.levTooFastTicks = Math.max(0, s.levTooFastTicks - 1);
            }
        } else if (lev == null) {
            s.levNotRisingTicks = 0;
            s.levTooFastTicks = 0;
        }
        } // levitation-simulation enabled
        if (!plugin.getConfig().getBoolean("checks.levitation-simulation", true)) {
            s.levNotRisingTicks = 0;
            s.levTooFastTicks = 0;
        }

        s.prevY = to.getY();
        s.prevOnGround = onGround;
        s.prevDy = deltaY;
    }

    private double getStepHeight(Player player) {
        try {
            AttributeInstance inst = player.getAttribute(Attribute.GENERIC_STEP_HEIGHT);
            if (inst != null) return inst.getValue(); // default 0.6
        } catch (Throwable ignored) {}
        try {
            // Newer name STEP_HEIGHT
            AttributeInstance inst2 = player.getAttribute(Attribute.valueOf("STEP_HEIGHT"));
            if (inst2 != null) return inst2.getValue();
        } catch (Throwable ignored) {}
        return 0.6;
    }

    private boolean isOnSlimeBlock(Player player) {
        Location loc = player.getLocation();
        for (double dy = -0.1; dy >= -1.5; dy -= 0.3) {
            if (loc.clone().add(0, dy, 0).getBlock().getType() == Material.SLIME_BLOCK) {
                return true;
            }
        }
        return false;
    }

    private boolean isOnIce(Location loc) {
        Block b = loc.clone().subtract(0, 0.4, 0).getBlock();
        Material m = b.getType();
        if (m == Material.ICE || m == Material.PACKED_ICE || m == Material.BLUE_ICE || m == Material.FROSTED_ICE) return true;
        Block b2 = loc.clone().subtract(0, 1.0, 0).getBlock();
        Material m2 = b2.getType();
        return m2 == Material.BLUE_ICE || m2 == Material.PACKED_ICE;
    }

    private boolean isOnHoney(Location loc) {
        for (double dy = -0.1; dy >= -0.7; dy -= 0.30) {
            if (loc.clone().add(0, dy, 0).getBlock().getType() == Material.HONEY_BLOCK) return true;
        }
        return false;
    }

    private boolean isInPowderSnow(Location loc) {
        if (loc.getBlock().getType() == Material.POWDER_SNOW) return true;
        if (loc.clone().add(0, 0.5, 0).getBlock().getType() == Material.POWDER_SNOW) return true;
        if (loc.clone().add(0, 1.0, 0).getBlock().getType() == Material.POWDER_SNOW) return true;
        return false;
    }

    private boolean isInCobweb(Player player) {
        Location loc = player.getLocation();
        double px = loc.getX(), py = loc.getY(), pz = loc.getZ();
        for (double ox = -0.3; ox <= 0.3; ox += 0.3)
            for (double oz = -0.3; oz <= 0.3; oz += 0.3)
                for (double oy = 0; oy <= 1.5; oy += 0.5)
                    if (loc.getWorld().getBlockAt((int)Math.floor(px+ox),(int)Math.floor(py+oy),(int)Math.floor(pz+oz)).getType() == Material.COBWEB) return true;
        return false;
    }

    private boolean hasBlockAbove(Player p, Location loc) {
        for (double dy = 1.7; dy <= 2.2; dy += 0.3) {
            Block b = loc.clone().add(0, dy, 0).getBlock();
            Material m = b.getType();
            if (m.isSolid()) return true;
            try { if (!b.isPassable()) return true; } catch (Throwable ignored) {}
            if (m.name().endsWith("_SLAB") || m.name().endsWith("_STAIRS") || m.name().endsWith("_TRAPDOOR")) return true;
        }
        Block eye = p.getEyeLocation().getBlock();
        if (eye.getType().isSolid()) return true;
        try { if (!eye.isPassable()) return true; } catch (Throwable ignored) {}
        return false;
    }

    private boolean isOnWater(Location loc) {
        Block b = loc.clone().subtract(0, 0.3, 0).getBlock();
        return b.getType() == Material.WATER || b.getType() == Material.BUBBLE_COLUMN;
    }

    private boolean isInWaterLev(Player p, Location loc) {
        return p.isInWater() || p.isSwimming() || loc.getBlock().getType() == Material.WATER || p.getEyeLocation().getBlock().getType() == Material.WATER;
    }

    private boolean isInLiquidLev(Player p) { return p.isInWater() || p.isSwimming() || p.getLocation().getBlock().getType() == Material.LAVA; }

    private boolean isOnGroundLev(Player p, Location loc) {
        if (GroundUtil.isOnGround(p, loc)) return true;
        Block b = loc.clone().subtract(0, 0.25, 0).getBlock();
        if (b.getType().isSolid()) return true;
        Block b2 = loc.clone().subtract(0, 0.65, 0).getBlock();
        return b2.getType().isSolid();
    }

    private boolean dyFasterThanExpected(double actual, double expected) {
        // Glide falls slower, so actual (e.g. -0.08) > expected (e.g. -0.45) by >0.28
        return actual > expected + 0.28;
    }

    private boolean isAgainstWall(Player player) {
        Location loc = player.getLocation();
        double px = loc.getX(), py = loc.getY(), pz = loc.getZ();

        // Player hitbox is 0.6 wide; the edge of the player is 0.3 from centre.
        // We probe at 0.31 + 0.69 = 1.0 block away from centre in each cardinal
        // direction so that only blocks the player is genuinely pressed against
        // register as "wall".  Using exactly floor(centre ± 1) is too coarse —
        // it catches blocks one full block away that the player can never touch.
        //
        // Height: scan only 0.1 – 1.7 b (feet to just below head-top).
        // This deliberately excludes h = 0 (floor/step blocks) and anything
        // above head height (ceilings, 3-block roofs), which are not climbable
        // surfaces and were causing false Spider[Fast] flags.
        double[] offsets = {0.31, 0.65}; // two radial samples per direction
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

        for (int[] d : dirs) {
            for (double r : offsets) {
                double bx = px + d[0] * r;
                double bz = pz + d[1] * r;
                // Check at knee (0.3) and chest (1.2) height — two samples that
                // cover the body without touching the floor or the ceiling zone.
                for (double h : new double[]{0.3, 1.2}) {
                    Block b = loc.getWorld().getBlockAt(
                            (int) Math.floor(bx),
                            (int) Math.floor(py + h),
                            (int) Math.floor(bz));
                    if (b.getType().isSolid()) return true;
                }
            }
        }
        return false;
    }

    private boolean isInClimbable(Player player) {
        var block = player.getLocation().getBlock().getType();
        if (block == Material.LADDER || block == Material.VINE || block == Material.SCAFFOLDING) return true;
        if (block.name().contains("VINES") || block.name().contains("KELP")) return true;
        if (block.name().contains("WEEPING") || block.name().contains("TWISTING")) return true;
        if (block.name().contains("CAVE")) return true;
        var below = player.getLocation().clone().add(0, -0.5, 0).getBlock().getType();
        return below == Material.LADDER || below == Material.VINE;
    }

    private void flag(Player player, UUID id, String reason) {
        MoveState s = state.get(id);
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
                || player.isFlying() || player.isGliding()
                || player.isClimbing() || player.isSwimming()
                || player.isInWater() || isInClimbable(player)
                || vm.hasJoinGrace(player.getUniqueId());
    }

    public void removePlayer(UUID id) { state.remove(id); }

    /** Fresh motion state, carrying every sub-check's VL over. */
    public void resetMotion(UUID id) {
        MoveState old = state.remove(id);
        if (old == null) return;
        MoveState s = new MoveState();
        s.vl = old.vl;
        s.stepVL = old.stepVL;
        s.spiderVL = old.spiderVL;
        s.invWalkVL = old.invWalkVL;
        s.spiderSimVL = old.spiderSimVL;
        s.glideVL = old.glideVL;
        s.levVL = old.levVL;
        state.put(id, s);
    }

    private void debugLog(Player player, String msg) {
        if (plugin.getConfig().getBoolean("debug.enabled", false)) {
            plugin.getLogger().info("[DEBUG][MoveSim] " + player.getName() + " » " + msg);
        }
    }
}