package dev.anticheat.listeners;

import dev.anticheat.AntiCheatPlugin;
import dev.anticheat.managers.BanManager;
import dev.anticheat.managers.ViolationManager;
import dev.anticheat.utils.GroundUtil;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class FlyListener implements Listener {

    private final AntiCheatPlugin plugin;
    private final ViolationManager vm;
    private final BanManager bm;

    private static class FlightState {
        // Move-event state
        double prevDeltaY = Double.MIN_VALUE;
        int hoverTicks = 0;
        int upTicks = 0;
        int slimeGrace = 0;
        double lastSlimeY = Double.MIN_VALUE;
        int gravityStreak = 0;
        boolean wasGliding = false;
        boolean wasInLiquid = false;
        long lastMoveMs = 0;
        int bounceTicks = 0;
        boolean fellRecently = false;
        // HFlight state
        int hflightTicks = 0;
        int hflightVL = 0;
        long lastHFFlag = 0;
        double lastTickY = Double.MIN_VALUE;
        double lastTickX = Double.MIN_VALUE;
        double lastTickZ = Double.MIN_VALUE;
        double prevDy = Double.MIN_VALUE;
        int airborneTicks = 0;
    }

    private final Map<UUID, FlightState> state = new ConcurrentHashMap<>();
    private BukkitTask hfTask;

    private static final double GRAVITY = 0.08;
    private static final double DRAG = 0.98;
    private static final double MAX_JUMP_VEL = 0.42;
    private static final double HOVER_THRESHOLD = 0.028; // relaxed from 0.015 to reduce velocity false flags
    private static final int HOVER_TICKS_MAX = 5; // 3→5
    private static final int HOVER_MIN_AIRTICKS = 8; // 6→8
    private static final int MAX_AIR_TICKS = 32; // 25→32
    private static final int SLIME_GRACE_TICKS = 24;
    private static final long LAG_SPIKE_MS = 250; // 200→250

    private static final double HF_ANTI_GRAV_TOL = 0.08;
    private static final double HF_TERMINAL_LOW = -2.1;
    private static final double HF_TERMINAL_HIGH = -1.7;
    private static final double HF_FLAT_DY_MAX = 0.08;
    private static final double HF_MIN_H_DELTA = 0.10;
    private static final int HF_TICK_THRESHOLD = 6;
    private static final long HF_FLAG_COOLDOWN = 600;
    private static final int HF_AIRBORNE_WARMUP = 5;

    public FlyListener(AntiCheatPlugin plugin) {
        this.plugin = plugin;
        this.vm = plugin.getViolationManager();
        this.bm = plugin.getBanManager();
        hfTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickHFlight, 1L, 1L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!plugin.getConfig().getBoolean("checks.fly", true)) return;
        // No TPS kill-switch: this compares the client's own per-packet movement,
        // which server lag doesn't change, and the lag-spike guard below already
        // resets on packet gaps.  Turning it off let lag machines disable it.
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        if (isExempt(player)) { resetState(id, player.getLocation().getY()); return; }

        Location to = event.getTo();
        if (to == null) return;

        FlightState s = state.computeIfAbsent(id, k -> new FlightState());

        double prevY = vm.getPrevY(id) != null ? vm.getPrevY(id) : to.getY();
        double deltaY = to.getY() - prevY;
        vm.setPrevY(id, to.getY());

        boolean onGround = isOnGround(player, to);
        boolean inLiquid = isInLiquid(player);
        boolean onClimbable = isOnClimbable(player);
        boolean isGliding = player.isGliding();
        boolean hasLev = player.hasPotionEffect(PotionEffectType.LEVITATION);
        boolean hasSlowFall = player.hasPotionEffect(PotionEffectType.SLOW_FALLING);
        boolean onHoney = isOnOrNearHoney(player, to);
        boolean inSticky = isInStickyBlock(player, to);
        boolean hadGround = vm.getWasOnGround(id);
        vm.setWasOnGround(id, onGround);

        long nowMs = System.currentTimeMillis();
        if (s.lastMoveMs > 0 && (nowMs - s.lastMoveMs) > LAG_SPIKE_MS) {
            hardResetState(id, to.getY());
            debugLog(player, "LagSpike gap=" + (nowMs - s.lastMoveMs) + "ms");
            return;
        }
        s.lastMoveMs = nowMs;

        boolean nearSlime = isSlimeNearby(player, to)
                || isSlimeNearby(player, event.getFrom());

        // --- Slime/bounce grace grants (must happen before any early-return or decrement) ---
        if (!onGround && deltaY > 0.3 && nearSlime) {
            s.slimeGrace = 60;
            s.lastSlimeY = to.getY();
            debugLog(player, "SlimeBounce (high) +60 grace");
        }
        if (hadGround && !onGround && deltaY > 0.1) {
            vm.giveJumpGrace(id);
            s.upTicks = 0;
            s.prevDeltaY = Double.MIN_VALUE;
            if (nearSlime) {
                s.slimeGrace = SLIME_GRACE_TICKS;
                debugLog(player, "SlimeBounce (launch) +" + SLIME_GRACE_TICKS + " grace");
            }
        }
        if (!hadGround && onGround && nearSlime) {
            s.slimeGrace = SLIME_GRACE_TICKS;
            s.lastSlimeY = to.getY(); // anchor for nearRecentSlime on next frame
            debugLog(player, "SlimeBounce (landing) +" + SLIME_GRACE_TICKS + " grace");
        }
        // Even if we didn't land, record slime proximity while falling so the bounce
        // frame can use nearRecentSlime even if the player is already above scan range.
        if (nearSlime && deltaY < 0) {
            s.lastSlimeY = to.getY();
        }

        // Detect mid-air bounce: falling fast then suddenly moving up.
        // prevDeltaY == MIN_VALUE after a TP or landing reset — treat as a valid fall.
        boolean prevWasFalling = (s.prevDeltaY == Double.MIN_VALUE) || (s.prevDeltaY < -0.3);
        // Use lastSlimeY as a fallback: if we recorded a slime bounce position recently
        // and the player is still within 5 blocks of it vertically, honour the bounce.
        boolean nearRecentSlime = nearSlime
                || (s.lastSlimeY != Double.MIN_VALUE && Math.abs(to.getY() - s.lastSlimeY) < 5.0);
        if (deltaY > 0.3 && prevWasFalling && nearRecentSlime) {
            s.bounceTicks = 15;
            s.fellRecently = false;
            s.slimeGrace = Math.max(s.slimeGrace, SLIME_GRACE_TICKS);
            s.lastSlimeY = to.getY(); // track current apex so subsequent ticks stay exempt
            debugLog(player, "SlimeBounce mid-air detected, grace=" + s.bounceTicks + "/" + s.slimeGrace);
        }
        if (deltaY < -0.5) {
            s.fellRecently = true;
            // Don't clear lastSlimeY here — the nearSlime+deltaY<0 block above
            // already refreshed it if slime is in range. Clearing it would undo that.
        }

        if (onGround || inLiquid || onClimbable || isGliding || hasLev || hasSlowFall) {
            // resetState intentionally preserves slimeGrace/bounceTicks so the
            // next move event (the bounce frame) still has grace available.
            // Only hard-clear slime state when firmly on non-bounce ground.
            if (onGround && !nearSlime) {
                s.slimeGrace = 0;
                s.bounceTicks = 0;
                s.fellRecently = false;
            }
            resetState(id, to.getY());
            return;
        }

        if (s.wasInLiquid && !inLiquid && !onGround) {
            resetState(id, to.getY());
            debugLog(player, "WaterExit");
            return;
        }

        s.wasGliding = isGliding;
        s.wasInLiquid = inLiquid;

        if (onHoney || inSticky) { s.prevDeltaY = deltaY; return; }

        vm.incrementAirTicks(id);
        int airTicks = vm.getAirTicks(id);

        // Decrement bounce ticks *after* grants so the current tick is still covered
        if (s.bounceTicks > 0) s.bounceTicks--;

        // Decrement slime grace and skip all checks while it is active
        if (s.slimeGrace > 0) {
            s.slimeGrace--;
            s.upTicks = 0;
            s.prevDeltaY = deltaY;
            s.gravityStreak = 0;
            s.hoverTicks = 0;
            debugLog(player, "SlimeGrace remaining=" + s.slimeGrace);
            return;
        }

        if (vm.hasJumpGrace(id) || vm.hasJoinGrace(id)) {
            s.prevDeltaY = deltaY;
            return;
        }

        double maxUpVel = plugin.getConfig().getDouble("settings.max-upward-velocity", MAX_JUMP_VEL);
        if (player.hasPotionEffect(PotionEffectType.JUMP_BOOST)) {
            int amp = player.getPotionEffect(PotionEffectType.JUMP_BOOST).getAmplifier() + 1;
            maxUpVel += amp * 0.15;
        }

        if (!vm.hasTeleportGrace(id) && deltaY > maxUpVel + 0.18 && !nearSlime && s.bounceTicks == 0) {
            flag(player, id, "Fly[UpVelocity] dY=" + fmt(deltaY) + " max=" + fmt(maxUpVel), 2);
        }

        if (s.prevDeltaY != Double.MIN_VALUE && airTicks > 5) {
            double expectedDY = (s.prevDeltaY * DRAG) - GRAVITY;
            if (deltaY > expectedDY + 0.20 && deltaY > 0) {
                s.gravityStreak++;
                if (s.gravityStreak >= 3) {
                    flag(player, id, "Fly[GravityViolation] dY=" + fmt(deltaY) + " expected<=" + fmt(expectedDY + 0.18), 2);
                }
            } else {
                s.gravityStreak = 0;
            }
        }
        s.prevDeltaY = deltaY;

        if (airTicks >= HOVER_MIN_AIRTICKS && Math.abs(deltaY) < HOVER_THRESHOLD) {
            s.hoverTicks++;
            if (s.hoverTicks > HOVER_TICKS_MAX) flag(player, id, "Fly[Hover] hoverTicks=" + s.hoverTicks, 2);
        } else {
            s.hoverTicks = 0;
        }

        if (airTicks > MAX_AIR_TICKS && deltaY > 0.04) {
            flag(player, id, "Fly[UpPostApex] airTick=" + airTicks + " dY=" + fmt(deltaY), 2);
        }

        if (deltaY > 0.08) {
            if (vm.hasTeleportGrace(id) || s.bounceTicks > 0) {
                s.upTicks = 0;
            } else {
                s.upTicks++;
                int upThreshold = 8;
                if (player.hasPotionEffect(PotionEffectType.JUMP_BOOST)) {
                    int amp = player.getPotionEffect(PotionEffectType.JUMP_BOOST).getAmplifier() + 1;
                    upThreshold += amp * 2;
                }
                if (s.upTicks >= upThreshold) {
                    flag(player, id, "Fly[ConsecUpTicks] upTicks=" + s.upTicks + "/" + upThreshold, 2);
                }
            }
        } else {
            s.upTicks = 0;
        }

        if (airTicks > 40 && Math.abs(deltaY) < 0.001) {
            flag(player, id, "Fly[StaticHover] airTicks=" + airTicks, 2);
        }
    }

    private void tickHFlight() {
        // Main thread only: this reads blocks/entities and can ban (kick), which
        // Bukkit forbids off-thread.  It used to run on a parallelStream.
        for (Player player : Bukkit.getOnlinePlayers()) checkHFlight(player);
    }

    private void checkHFlight(Player player) {
        UUID id = player.getUniqueId();
        if (isExemptHF(player)) { resetHFState(id); return; }

        Location loc = player.getLocation();
        if (loc.getWorld() == null) { resetHFState(id); return; }
        if (isInCobwebHF(player)) { resetHFState(id); return; }
        if (isInPowderSnow(loc)) { resetHFState(id); return; } // powder snow slow fall is not HFlight

        FlightState s = state.computeIfAbsent(id, k -> new FlightState());

        if (serverOnGroundHF(loc, player)) {
            s.hflightTicks = 0; s.airborneTicks = 0;
            s.lastTickY = loc.getY(); s.lastTickX = loc.getX(); s.lastTickZ = loc.getZ();
            return;
        }

        if (hasLegitFlightContext(player, loc)) {
            s.hflightTicks = 0; s.airborneTicks = 0;
            s.lastTickY = loc.getY(); s.lastTickX = loc.getX(); s.lastTickZ = loc.getZ();
            return;
        }

        s.airborneTicks++;

        if (s.airborneTicks < HF_AIRBORNE_WARMUP) {
            s.hflightTicks = 0;
            s.lastTickY = loc.getY(); s.lastTickX = loc.getX(); s.lastTickZ = loc.getZ();
            if (s.lastTickY != Double.MIN_VALUE) s.prevDy = loc.getY() - s.lastTickY;
            return;
        }

        if (s.lastTickY == Double.MIN_VALUE) {
            s.lastTickY = loc.getY(); s.lastTickX = loc.getX(); s.lastTickZ = loc.getZ();
            return;
        }

        double dy = loc.getY() - s.lastTickY;
        double dx = loc.getX() - s.lastTickX;
        double dz = loc.getZ() - s.lastTickZ;
        double hDelta = Math.sqrt(dx * dx + dz * dz);
        s.lastTickY = loc.getY(); s.lastTickX = loc.getX(); s.lastTickZ = loc.getZ();

        boolean qualifying = (hDelta >= HF_MIN_H_DELTA && Math.abs(dy) <= HF_FLAT_DY_MAX);

        if (!qualifying && s.prevDy != Double.MIN_VALUE
                && !(dy >= HF_TERMINAL_LOW && dy <= HF_TERMINAL_HIGH)
                && dy > -0.5) {
            double expectedDy = s.prevDy - GRAVITY;
            if (dy > expectedDy + HF_ANTI_GRAV_TOL) qualifying = true;
        }
        s.prevDy = dy;

        if (!qualifying) { s.hflightTicks = 0; return; }

        s.hflightTicks++;
        if (s.hflightTicks < HF_TICK_THRESHOLD) return;

        long now = System.currentTimeMillis();
        if (s.lastHFFlag > 0 && now - s.lastHFFlag < HF_FLAG_COOLDOWN) return;
        s.lastHFFlag = now;

        s.hflightVL++;
        int vlThreshold = plugin.getConfig().getInt("settings.hflight-vl-threshold", 3);

        if (plugin.getConfig().getBoolean("debug.enabled", false)) {
            plugin.getLogger().info("[DEBUG][Fly][HFlight] " + player.getName()
                    + " ticks=" + s.hflightTicks + " h=" + String.format("%.3f", hDelta)
                    + " dy=" + String.format("%.3f", dy) + " VL=" + s.hflightVL + "/" + vlThreshold);
        }

        s.hflightTicks = 0;
        if (s.hflightVL >= vlThreshold) {
            s.hflightVL = 0;
            bm.banPlayer(player, "Fly[HFlight] sustained-airborne ticks=" + s.hflightTicks);
        }
    }

    private boolean serverOnGroundHF(Location loc, Player player) {
        if (loc.getWorld() == null) return false;
        double halfW = 0.3;
        // Check slightly below feet with extra slop for small blocks like end rods (1 block tall but isSolid=false)
        int y = (int) Math.floor(loc.getY() - 0.02);
        double[][] corners = {{-halfW,-halfW},{halfW,-halfW},{-halfW,halfW},{halfW,halfW},{0,0}};
        for (double[] c : corners) {
            Block b = loc.getWorld().getBlockAt(
                (int)Math.floor(loc.getX()+c[0]), y, (int)Math.floor(loc.getZ()+c[1]));
            if (isSupportiveBlock(b)) return true;
            // Powder snow with leather boots counts as ground
            if (b.getType() == Material.POWDER_SNOW && hasLeatherBoots(player)) return true;
        }
        for (double[] c : corners) {
            Block b = loc.getWorld().getBlockAt(
                (int)Math.floor(loc.getX()+c[0]), y, (int)Math.floor(loc.getZ()+c[1]));
            if (isClimbableMat(b.getType())) return true;
        }
        // Also check one block lower for end rods / chains where player is on top of tall narrow block
        // Player's feet Y may be ~0.5 above block top if standing on top of end rod (rod height 15/16)
        // Already covered by y = floor(Y-0.02), but double check y-1 for hanging
        for (double[] c : corners) {
            Block b = loc.getWorld().getBlockAt(
                (int)Math.floor(loc.getX()+c[0]), y-1, (int)Math.floor(loc.getZ()+c[1]));
            Material m = b.getType();
            if (m == Material.END_ROD || m == Material.LIGHTNING_ROD || m.name().endsWith("_FENCE") || m.name().endsWith("_WALL")) {
                // Check distance to block top
                double blockTop = b.getY() + 1.0;
                // For end rod, top is at +1, player standing at blockTop (~1.0). Already y would be blockTop level? Might be off.
                // Keep as fallback
            }
        }
        return false;
    }

    private boolean isSupportiveBlock(Block b) {
        Material m = b.getType();
        if (m.isAir()) return false;
        // Solid blocks fully support
        if (m.isSolid()) return true;
        // Non-solid but has collision and can be stood on: end rods, chains, fences, walls, etc.
        // Use isPassable check: end rods etc. are not passable, while air-like is passable
        try {
            if (!b.isPassable()) return true;
        } catch (Throwable ignored) {}
        // Explicit small supportive blocks
        return switch (m) {
            case END_ROD, LIGHTNING_ROD, CHAIN -> true;
            default -> {
                String n = m.name();
                if (n.endsWith("_FENCE") || n.endsWith("_FENCE_GATE") || n.endsWith("_WALL") || n.endsWith("_PANE") || n.equals("IRON_BARS")) yield true;
                if (n.endsWith("TRAPDOOR") || n.endsWith("SLAB") || n.endsWith("STAIRS") || n.equals("SCAFFOLDING")) yield true;
                yield false;
            }
        };
    }

    private boolean hasLeatherBoots(Player player) {
        var boots = player.getInventory().getBoots();
        return boots != null && boots.getType() == Material.LEATHER_BOOTS;
    }

    private boolean hasLegitFlightContext(Player player, Location to) {
        if (player.isInWater() || player.isInsideVehicle() || isStandingOnBoat(player)) return true;
        if (player.hasPotionEffect(PotionEffectType.LEVITATION)) return true;
        if (player.hasPotionEffect(PotionEffectType.SLOW_FALLING)) return true;
        Block feet = to.getBlock();
        Block waist = feet.getRelative(org.bukkit.block.BlockFace.UP);
        Block below = feet.getRelative(org.bukkit.block.BlockFace.DOWN);
        Material fm = feet.getType(), wm = waist.getType(), bm = below.getType();
        if (fm == Material.LAVA || wm == Material.LAVA) return true;
        if (isClimbableMat(fm) || isClimbableMat(wm)) return true;
        if (fm == Material.WATER || wm == Material.WATER || fm == Material.BUBBLE_COLUMN || wm == Material.BUBBLE_COLUMN) return true;
        if (fm == Material.COBWEB || wm == Material.COBWEB) return true;
        if (fm == Material.POWDER_SNOW || wm == Material.POWDER_SNOW) return true; // falling through powder snow is slow fall, not flight
        if (bm == Material.HONEY_BLOCK || bm == Material.WATER || bm == Material.LAVA) return true;
        if (bm == Material.POWDER_SNOW) return true; // standing/falling on powder snow (with or without boots, it's legit vertical)
        // Check up to 2.5 blocks below for slime/bounce blocks — mirrors isSlimeNearby range
        for (double offset : new double[]{0.1, 0.6, 1.1, 1.6, 2.1, 2.5}) {
            Material mat = to.clone().subtract(0, offset, 0).getBlock().getType();
            if (mat == Material.SLIME_BLOCK || mat.name().endsWith("_BED")) return true;
            if (mat == Material.POWDER_SNOW) return true; // powder snow column below also legitimizes
        }
        // Also check if inside powder snow via world scan (like cobweb)
        if (isInPowderSnow(to)) return true;
        return false;
    }

    private boolean isInPowderSnow(Location to) {
        if (to.getWorld() == null) return false;
        double px = to.getX(), py = to.getY(), pz = to.getZ();
        int minX = (int) Math.floor(px - 0.3), maxX = (int) Math.floor(px + 0.3);
        int minZ = (int) Math.floor(pz - 0.3), maxZ = (int) Math.floor(pz + 0.3);
        int minY = (int) Math.floor(py), maxY = (int) Math.floor(py + 1.0);
        for (int x = minX; x <= maxX; x++)
            for (int z = minZ; z <= maxZ; z++)
                for (int y = minY; y <= maxY; y++)
                    if (to.getWorld().getBlockAt(x, y, z).getType() == Material.POWDER_SNOW) return true;
        return false;
    }

    private boolean isClimbableMat(Material m) {
        return m == Material.LADDER || m == Material.VINE || m == Material.SCAFFOLDING
            || m == Material.TWISTING_VINES || m == Material.TWISTING_VINES_PLANT
            || m == Material.WEEPING_VINES || m == Material.WEEPING_VINES_PLANT
            || m == Material.CAVE_VINES || m == Material.CAVE_VINES_PLANT;
    }

    private boolean isExemptHF(Player player) {
        UUID id = player.getUniqueId();
        GameMode gm = player.getGameMode();
        return player.hasPermission("anticheat.bypass")
            || plugin.getWhitelistManager().isWhitelisted(id)
            || gm == GameMode.SPECTATOR || gm == GameMode.CREATIVE
            || player.isFlying() || player.isGliding() || player.isDead()
            || player.isInsideVehicle() || isStandingOnBoat(player)
            || player.isClimbing()
            || vm.hasJoinGrace(id) || vm.hasDamageGrace(id) || vm.hasTeleportGrace(id);
    }

    private boolean isInCobwebHF(Player player) {
        Location loc = player.getLocation();
        double px = loc.getX(), py = loc.getY(), pz = loc.getZ();
        int minX = (int) Math.floor(px - 0.3), maxX = (int) Math.floor(px + 0.3);
        int minZ = (int) Math.floor(pz - 0.3), maxZ = (int) Math.floor(pz + 0.3);
        int minY = (int) Math.floor(py), maxY = (int) Math.floor(py + 1.8);
        for (int x = minX; x <= maxX; x++)
            for (int z = minZ; z <= maxZ; z++)
                for (int y = minY; y <= maxY; y++)
                    if (loc.getWorld().getBlockAt(x, y, z).getType() == Material.COBWEB) return true;
        return false;
    }

    private boolean isStandingOnBoat(Player player) {
        Location loc = player.getLocation();
        for (Entity e : loc.getWorld().getNearbyEntities(loc, 1.5, 2.0, 1.5)) {
            if (e instanceof Boat && loc.getY() - e.getLocation().getY() >= 0.0
                    && loc.getY() - e.getLocation().getY() <= 1.5) return true;
        }
        return false;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (isExempt(player)) return;
        Block block = event.getBlock();
        Location loc = player.getLocation();
        double dx = Math.abs((block.getX() + 0.5) - loc.getX());
        double dz = Math.abs((block.getZ() + 0.5) - loc.getZ());
        double dy = loc.getY() - (block.getY() + 1.0);
        if (dx <= 1.5 && dz <= 1.5 && dy >= -0.5 && dy <= 2.5) {
            vm.giveJumpGrace(player.getUniqueId());
            resetState(player.getUniqueId(), loc.getY());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        if (isExempt(player)) return;
        Block placed = event.getBlockPlaced();
        if (placed.getY() >= player.getEyeLocation().getY() - 0.5) {
            UUID id = player.getUniqueId();
            vm.giveJumpGrace(id);
            FlightState s = state.get(id);
            if (s != null) { s.prevDeltaY = Double.MIN_VALUE; s.gravityStreak = 0; s.upTicks = 0; }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getClickedBlock() == null) return;
        Material m = event.getClickedBlock().getType();
        if (!m.name().endsWith("_TRAPDOOR") && !m.name().endsWith("_FENCE_GATE")) return;
        Player player = event.getPlayer();
        if (isExempt(player)) return;
        Block b = event.getClickedBlock();
        Location loc = player.getLocation();
        double dx = Math.abs((b.getX() + 0.5) - loc.getX());
        double dy = Math.abs((b.getY() + 0.5) - loc.getY());
        double dz = Math.abs((b.getZ() + 0.5) - loc.getZ());
        if (dx <= 1.5 && dy <= 2.0 && dz <= 1.5) {
            FlightState s = state.get(player.getUniqueId());
            if (s != null) { s.prevDeltaY = Double.MIN_VALUE; s.gravityStreak = 0; s.upTicks = 0; }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onFlightToggle(PlayerToggleFlightEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        if (isExempt(player)) return;
        if (event.isFlying()) {
            event.setCancelled(true);
            flag(player, id, "Fly[ToggleFlight]", 2);
        }
    }

    private void flag(Player player, UUID id, String checkName, int vlAmount) {
        vm.incrementFlyVL(id, vlAmount);
        int vl = vm.getFlyVL(id);
        String mode = plugin.getConfig().getString("punishment.mode", "temp");
        int threshold = "rubberband".equals(mode) ? 2 : plugin.getConfig().getInt("settings.fly-vl-threshold", 2);
        debugLog(player, checkName + " VL=" + vl + "/" + threshold);
        if (vl >= threshold) { vm.remove(id); removePlayer(id); bm.banPlayer(player, checkName); }
    }

    private void resetState(UUID id, double y) {
        vm.resetAirTicks(id);
        vm.setPrevY(id, y);
        FlightState s = state.get(id);
        if (s != null) {
            s.prevDeltaY = Double.MIN_VALUE; s.hoverTicks = 0; s.upTicks = 0;
            // Do NOT zero slimeGrace, bounceTicks, or fellRecently here.
            // The landing frame fires resetState just before the bounce frame,
            // and teleport-grace resets fire while the player is still falling.
            // These fields are cleared only when they naturally expire or on full remove.
            s.gravityStreak = 0; s.lastMoveMs = 0;
        }
    }

    private void hardResetState(UUID id, double y) {
        vm.resetAirTicks(id);
        vm.setPrevY(id, y);
        FlightState s = state.get(id);
        if (s != null) {
            s.prevDeltaY = Double.MIN_VALUE; s.hoverTicks = 0; s.upTicks = 0;
            s.slimeGrace = 0; s.bounceTicks = 0; s.fellRecently = false;
            s.gravityStreak = 0; s.lastMoveMs = 0;
        }
    }

    private void resetHFState(UUID id) {
        FlightState s = state.get(id);
        if (s != null) {
            s.hflightTicks = 0; s.prevDy = Double.MIN_VALUE;
            s.lastTickY = Double.MIN_VALUE; s.airborneTicks = 0;
        }
    }

    public void removePlayer(UUID id) {
        state.remove(id);
    }

    /** Fresh motion state, keeping the HFlight VL so a teleport/launch can't launder it. */
    public void resetMotion(UUID id) {
        FlightState old = state.remove(id);
        if (old == null) return;
        FlightState s = new FlightState();
        s.hflightVL = old.hflightVL;
        s.lastHFFlag = old.lastHFFlag;
        state.put(id, s);
    }

    private boolean isExempt(Player player) {
        GameMode gm = player.getGameMode();
        return player.hasPermission("anticheat.bypass")
                || plugin.getWhitelistManager().isWhitelisted(player.getUniqueId())
                || gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR
                || player.getAllowFlight() || player.isFlying() || player.isDead()
                || player.isInsideVehicle() || vm.hasJoinGrace(player.getUniqueId());
    }

    private boolean isOnGround(Player player, Location loc) {
        if (GroundUtil.isOnGround(player, loc)) return true;
        Block below = loc.clone().subtract(0, 0.1, 0).getBlock();
        if (below.getType().isSolid()) return true;
        Block lower = loc.clone().subtract(0, 0.6, 0).getBlock();
        return lower.getType().isSolid();
    }

    private boolean isInLiquid(Player player) {
        if (player.isInWater() || player.isSwimming()) return true;
        Material feet = player.getLocation().getBlock().getType();
        Material head = player.getEyeLocation().getBlock().getType();
        return feet == Material.LAVA || head == Material.LAVA || feet == Material.BUBBLE_COLUMN || head == Material.BUBBLE_COLUMN;
    }

    private boolean isOnClimbable(Player player) {
        Material m = player.getLocation().getBlock().getType();
        return switch (m) {
            case LADDER, VINE, SCAFFOLDING, TWISTING_VINES, TWISTING_VINES_PLANT,
                 WEEPING_VINES, WEEPING_VINES_PLANT, CAVE_VINES, CAVE_VINES_PLANT,
                 KELP_PLANT -> true;
            default -> false;
        };
    }

    private boolean isSlimeNearby(Player player, Location to) {
        // Check up to 2.5 blocks below — high-velocity bounces can lift the player
        // well above the slime block on the very first move event after the bounce.
        for (double offset : new double[]{0.1, 0.6, 1.1, 1.6, 2.1, 2.5}) {
            if (isBounceBlock(to.clone().subtract(0, offset, 0).getBlock().getType())) return true;
        }
        return false;
    }

    private boolean isBounceBlock(Material m) {
        return m == Material.SLIME_BLOCK || m.name().endsWith("_BED");
    }

    private boolean isOnOrNearHoney(Player player, Location to) {
        Block feet = to.getBlock();
        Block below = to.clone().subtract(0, 0.1, 0).getBlock();
        Block lower = to.clone().subtract(0, 0.6, 0).getBlock();
        Block n = to.clone().add(0, 0, 1).getBlock();
        Block s = to.clone().add(0, 0, -1).getBlock();
        Block e = to.clone().add(1, 0, 0).getBlock();
        Block w = to.clone().add(-1, 0, 0).getBlock();
        Material h = Material.HONEY_BLOCK;
        return feet.getType() == h || below.getType() == h || lower.getType() == h
            || n.getType() == h || s.getType() == h || e.getType() == h || w.getType() == h;
    }

    private boolean isInStickyBlock(Player player, Location to) {
        Material feet = to.getBlock().getType();
        Material upper = to.clone().add(0, 0.9, 0).getBlock().getType();
        return feet == Material.COBWEB || upper == Material.COBWEB
            || feet == Material.POWDER_SNOW || upper == Material.POWDER_SNOW;
    }

    private void debugLog(Player player, String msg) {
        if (plugin.getConfig().getBoolean("debug.enabled", false)) {
            plugin.getLogger().info("[DEBUG][Fly] " + player.getName() + " » " + msg);
        }
    }

    private String fmt(double d) { return String.format("%.4f", d); }
}