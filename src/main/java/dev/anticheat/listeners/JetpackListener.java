package dev.anticheat.listeners;

import dev.anticheat.AntiCheatPlugin;
import dev.anticheat.managers.BanManager;
import dev.anticheat.managers.ViolationManager;
import dev.anticheat.managers.WhitelistManager;
import dev.anticheat.utils.GroundUtil;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class JetpackListener implements Listener {

    private final AntiCheatPlugin plugin;
    private final BanManager bm;
    private final ViolationManager vm;
    private final WhitelistManager wm;

    private static class JetpackState {
        int vl = 0;
        int jetTicks = 0;
        int hoverTicks = 0;
        int airTicks = 0;
        double lastY = Double.MIN_VALUE;
        double prevDy = 0;
    }

    private final Map<UUID, JetpackState> state = new ConcurrentHashMap<>();
    private BukkitTask tickTask;

    private static final double MIN_UP_DY = 0.08;
    private static final int ASCENT_TICKS = 5;
    private static final double HOVER_BAND = 0.03;
    private static final int HOVER_TICKS = 6;
    private static final int BAN_VL = 2;

    public JetpackListener(AntiCheatPlugin plugin) {
        this.plugin = plugin;
        this.bm = plugin.getBanManager();
        this.vm = plugin.getViolationManager();
        this.wm = plugin.getWhitelistManager();
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickAll, 1L, 1L);
    }

    private void tickAll() {
        // Main thread only — reads blocks and can ban (kick), which Bukkit forbids off-thread.
        for (Player player : Bukkit.getOnlinePlayers()) checkPlayer(player);
    }

    private void checkPlayer(Player player) {
        if (!plugin.getConfig().getBoolean("checks.jetpack", true)) return;
        if (dev.anticheat.utils.TPSUtil.isSeverelyLagging()) return;
        UUID id = player.getUniqueId();
        if (isExempt(player)) { state.remove(id); return; }

        JetpackState s = state.computeIfAbsent(id, k -> new JetpackState());
        Location loc = player.getLocation();

        // Robust ground check: vanilla isOnGround is unreliable on partial blocks like snow layers
        if (isOnGroundProper(player, loc) || player.isInWater() || player.isSwimming()
                || player.isInsideVehicle() || isInCobweb(player) || isOnClimbable(player)
                || isOnSlimeBlock(player) || isOnSnowBlock(player) || isInPowderSnow(loc)) { state.remove(id); return; }

        if (s.lastY == Double.MIN_VALUE) { s.lastY = loc.getY(); return; }

        double dy = loc.getY() - s.lastY;
        s.lastY = loc.getY();
        s.airTicks++;

        if (dy >= MIN_UP_DY && dy > s.prevDy * 0.8 && !wasRecentlyOnSlime(player) && dy < 0.5) {
            s.jetTicks++;
            if (s.jetTicks >= ASCENT_TICKS) {
                flag(player, id, "Jetpack[Ascent] consecutive=" + s.jetTicks + " dy=" + fmt(dy));
                s.jetTicks = 0;
                return;
            }
        } else {
            s.jetTicks = 0;
        }
        s.prevDy = dy;

        if (s.airTicks > 5 && Math.abs(dy) < HOVER_BAND) {
            s.hoverTicks++;
            if (s.hoverTicks >= HOVER_TICKS) {
                flag(player, id, "Jetpack[Hover] hoverTicks=" + s.hoverTicks + " airTicks=" + s.airTicks);
                s.hoverTicks = 0;
            }
        } else {
            s.hoverTicks = 0;
        }
    }

    private boolean isInCobweb(Player player) {
        Location loc = player.getLocation();
        double px = loc.getX(), py = loc.getY(), pz = loc.getZ();
        for (double ox = -0.3; ox <= 0.3; ox += 0.3) {
            for (double oz = -0.3; oz <= 0.3; oz += 0.3) {
                for (double oy = 0; oy <= 1.5; oy += 0.5) {
                    if (loc.getWorld().getBlockAt(
                            (int)Math.floor(px + ox), (int)Math.floor(py + oy), (int)Math.floor(pz + oz))
                            .getType() == org.bukkit.Material.COBWEB) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean isOnSlimeBlock(Player player) {
        Location loc = player.getLocation();
        for (double dy = -0.1; dy >= -1.5; dy -= 0.3) {
            if (loc.clone().add(0, dy, 0).getBlock().getType() == org.bukkit.Material.SLIME_BLOCK) {
                return true;
            }
        }
        return false;
    }

    private boolean wasRecentlyOnSlime(Player player) {
        Location loc = player.getLocation();
        for (double dy = -0.5; dy >= -3.0; dy -= 0.5) {
            if (loc.clone().add(0, dy, 0).getBlock().getType() == org.bukkit.Material.SLIME_BLOCK) {
                return true;
            }
        }
        return false;
    }

    private boolean isOnClimbable(Player player) {
        var block = player.getLocation().getBlock().getType();
        return block == Material.LADDER || block == Material.VINE
            || block == Material.SCAFFOLDING
            || block.name().contains("VINES") || block.name().contains("KELP")
            || block == Material.TWISTING_VINES || block == Material.WEEPING_VINES
            || block == Material.CAVE_VINES;
    }

    private boolean isOnGroundProper(Player player, Location loc) {
        if (GroundUtil.isOnGround(player, loc)) return true;
        for (double dy = -0.1; dy >= -0.7; dy -= 0.30) {
            Block b = loc.clone().add(0, dy, 0).getBlock();
            Material m = b.getType();
            if (m.isAir()) continue;
            if (m.isSolid()) return true;
            if (m == Material.SNOW || m == Material.SNOW_BLOCK) return true;
            if (m == Material.POWDER_SNOW) {
                var boots = player.getInventory().getBoots();
                boolean hasLeather = boots != null && boots.getType() == Material.LEATHER_BOOTS;
                // With leather boots powder snow is ground; without boots, treat as powder snow slow-fall (exempt via isInPowderSnow)
                if (hasLeather) return true;
                continue;
            }
            if (m == Material.HONEY_BLOCK || m == Material.SLIME_BLOCK) return true;
            try { if (!b.isPassable()) return true; } catch (Throwable ignored) {}
            if (m == Material.END_ROD || m == Material.CHAIN) return true;
            String n = m.name();
            if (n.endsWith("_FENCE") || n.endsWith("_WALL") || n.endsWith("_SLAB") || n.endsWith("_STAIRS")
                    || n.endsWith("_TRAPDOOR") || n.equals("SCAFFOLDING") || n.equals("IRON_BARS")) return true;
        }
        return false;
    }

    private boolean isOnSnowBlock(Player player) {
        Location loc = player.getLocation();
        for (double dy = -0.1; dy >= -0.6; dy -= 0.25) {
            Material m = loc.clone().add(0, dy, 0).getBlock().getType();
            if (m == Material.SNOW || m == Material.SNOW_BLOCK) return true;
        }
        return false;
    }

    private boolean isInPowderSnow(Location loc) {
        if (loc.getWorld() == null) return false;
        double px = loc.getX(), py = loc.getY(), pz = loc.getZ();
        for (double ox = -0.3; ox <= 0.3; ox += 0.3)
            for (double oz = -0.3; oz <= 0.3; oz += 0.3)
                for (double oy = 0; oy <= 1.5; oy += 0.5)
                    if (loc.getWorld().getBlockAt((int)Math.floor(px+ox),(int)Math.floor(py+oy),(int)Math.floor(pz+oz)).getType() == Material.POWDER_SNOW) return true;
        // Also check feet/waist directly
        if (loc.getBlock().getType() == Material.POWDER_SNOW) return true;
        if (loc.clone().add(0,1,0).getBlock().getType() == Material.POWDER_SNOW) return true;
        return false;
    }

    private void flag(Player player, UUID id, String reason) {
        JetpackState s = state.get(id);
        if (s == null) return;
        s.vl++;
        debugLog(player, reason + " vl=" + s.vl + "/" + BAN_VL);
        if (s.vl >= BAN_VL) {
            state.remove(id);
            bm.banPlayer(player, "Jetpack " + reason);
        }
    }

    private boolean isExempt(Player player) {
        UUID id = player.getUniqueId();
        GameMode gm = player.getGameMode();
        return player.hasPermission("anticheat.bypass")
            || wm.isWhitelisted(id)
            || gm == GameMode.SPECTATOR || gm == GameMode.CREATIVE
            || player.isFlying() || player.isGliding() || player.isDead()
            || player.hasPotionEffect(PotionEffectType.LEVITATION)
            || player.hasPotionEffect(PotionEffectType.SLOW_FALLING)
            || vm.hasJoinGrace(id) || vm.hasTeleportGrace(id);
    }

    public void removePlayer(UUID id) { state.remove(id); }

    private void debugLog(Player player, String msg) {
        if (plugin.getConfig().getBoolean("debug.enabled", false)) {
            plugin.getLogger().info("[DEBUG][Jetpack] " + player.getName() + " » " + msg);
        }
    }

    private String fmt(double d) { return String.format("%.4f", d); }
}
