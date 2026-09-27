package dev.anticheat.listeners;

import dev.anticheat.AntiCheatPlugin;
import dev.anticheat.managers.BanManager;
import dev.anticheat.managers.ViolationManager;
import dev.anticheat.managers.WhitelistManager;
import dev.anticheat.utils.GroundUtil;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
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

/**
 * LevitationSimulationListener — NEW simulation for levitation mismatches.
 *
 * Vanilla levitation: upward 0.05 * (amp+1) per tick, ignoring gravity.
 * Block above head, cobweb, powder snow, water, honey, slime, climbing etc. legitimately
 * cancel/reduce it. This simulation flags two cases:
 *  - Has levitation but NOT levitating (dy ~0/falling) while no obstruction (block above/cobweb/water)
 *  - Has levitation but levitating WAY faster than amplifier allows
 *
 * Covers amp 0..255.
 */
public class LevitationSimulationListener implements Listener {

    private final AntiCheatPlugin plugin;
    private final BanManager bm;
    private final ViolationManager vm;
    private final WhitelistManager wm;

    private static class LevState {
        int notRisingTicks = 0;
        int tooFastTicks = 0;
        int vl = 0;
        double prevDy = Double.MIN_VALUE;
    }

    private final Map<UUID, LevState> state = new ConcurrentHashMap<>();

    public LevitationSimulationListener(AntiCheatPlugin plugin) {
        this.plugin = plugin;
        this.bm = plugin.getBanManager();
        this.vm = plugin.getViolationManager();
        this.wm = plugin.getWhitelistManager();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        // Moved into MovementSimulationListener — keep dormant to avoid double flags
        if (plugin.getConfig().getBoolean("checks.movement-simulation", true)) return;
        if (!plugin.getConfig().getBoolean("checks.levitation-simulation", true)) return;
        Location to = event.getTo();
        Location from = event.getFrom();
        if (to == null) return;
        Player p = event.getPlayer();
        UUID id = p.getUniqueId();

        PotionEffect lev = p.getPotionEffect(PotionEffectType.LEVITATION);
        if (lev == null) { state.remove(id); return; }

        if (isExempt(p)) { state.remove(id); return; }
        if (vm.hasJoinGrace(id) || vm.hasTeleportGrace(id)) return;

        LevState s = state.computeIfAbsent(id, k -> new LevState());

        double dy = to.getY() - from.getY();
        int amp = lev.getAmplifier(); // 0 = I

        // Legit obstructions that prevent levitation
        boolean hasBlockAbove = hasBlockAbove(p, to);
        boolean inCobweb = isInCobweb(to);
        boolean inPowder = isInPowderSnow(to);
        boolean onWater = isOnWater(to) || isInWater(p, to);
        boolean onHoney = isOnHoney(to);
        boolean onSlime = isOnSlime(to);
        boolean inLiquid = isInLiquid(p);

        // If any of these, levitation is legitimately not rising — skip
        if (hasBlockAbove || inCobweb || inPowder || onWater || onHoney || onSlime || inLiquid || isOnClimbable(p)) {
            s.notRisingTicks = 0;
            s.tooFastTicks = 0;
            s.prevDy = dy;
            return;
        }

        // For NoLevitation: even onGround should be considered not rising if levitation should lift you
        // Don't early-return on ground — let notRising handle it (ground with levitation + no obstruction = cheat)
        boolean onGround = isOnGround(p, to);

        // Expected dy for this amp: vanilla 0.05*(amp+1). For amp 255 => 12.8 (capped by server, but use formula)
        double expected = 0.05 * (amp + 1);
        // Apply drag-like reduction for high amps to be realistic (vanilla drag 0.98)
        // For high amps, effective after drag ~ expected*0.98, but we keep simple
        // Add attribute / speed? No, levitation is independent

        // Case 1: Has levitation but NOT levitating (dy <=0 or << expected) without obstruction -> fake/cancel
        // Noleviation: dy ~0 on ground or falling while should rise
        // Fix: was 0.07 too strict for amp0 on ground (0 < -0.02 false), now 0.025 so ground (dy=0) counts
        boolean shouldRise = true;
        // Block above already handled above, but also need to handle ground case specially: onGround with levitation should rise unless blocked
        if (shouldRise) {
            // For ground, dy ~0 is definitely not rising
            double notRiseThresh = expected - 0.025; // for amp0 0.05-0.025=0.025, dy=0 <0.025 true
            // If onGround, require dy <0.05 to count as not rising (since on ground dy is 0)
            if (onGround) notRiseThresh = Math.max(notRiseThresh, 0.02);
            if (dy < notRiseThresh) {
                if (expected > 0.02) { // include amp0 (0.05)
                    s.notRisingTicks++;
                    // Faster flag for ground nolavitation (8 ticks = 0.4s), air 12 ticks
                    int need = onGround ? 8 : 12;
                    if (s.notRisingTicks >= need) {
                        s.vl++;
                        debug(p, "Levitation[NotRising] amp=" + (amp+1) + " exp=" + String.format("%.3f", expected) + " dy=" + String.format("%.3f", dy) + " ticks=" + s.notRisingTicks + " vl=" + s.vl);
                        int vlThresh = plugin.getConfig().getInt("settings.levitation-sim-vl", 2);
                        if (s.vl >= vlThresh) {
                            flag(p, id, "Levitation[NotRising] has levitation " + (amp+1) + " but not rising (dy=" + String.format("%.3f", dy) + ") blockAbove=" + hasBlockAbove + " cobweb=" + inCobweb + " water=" + onWater);
                            s.vl = 0;
                        }
                        s.notRisingTicks = 0;
                    }
                }
            } else {
                s.notRisingTicks = Math.max(0, s.notRisingTicks - 2);
            }
        }

        // Case 2: Levitation WAY faster than allowed (modified levitation)
        // For amp 0, max allowed ~ expected +0.15; for 255, allow larger but still detect impossible (e.g., 30 vs 12.8)
        double maxAllowed = expected + 0.14 + (amp > 10 ? amp * 0.012 : 0); // slight scaling for high amp
        if (dy > maxAllowed) {
            s.tooFastTicks++;
            if (s.tooFastTicks >= 6) {
                s.vl++;
                debug(p, "Levitation[TooFast] amp=" + (amp+1) + " max=" + String.format("%.3f", maxAllowed) + " dy=" + String.format("%.3f", dy) + " vl=" + s.vl);
                int thresh = plugin.getConfig().getInt("settings.levitation-sim-vl", 2);
                if (s.vl >= thresh) {
                    flag(p, id, "Levitation[TooFast] dy=" + String.format("%.3f", dy) + " > max " + String.format("%.3f", maxAllowed) + " for amp " + (amp+1));
                    s.vl = 0;
                }
                s.tooFastTicks = 0;
            }
        } else {
            s.tooFastTicks = Math.max(0, s.tooFastTicks - 1);
        }

        s.prevDy = dy;
    }

    private boolean hasBlockAbove(Player p, Location loc) {
        // Check 1.8 to 2.5 above feet (head + 0.5)
        for (double dy = 1.7; dy <= 2.2; dy += 0.3) {
            Block b = loc.clone().add(0, dy, 0).getBlock();
            Material m = b.getType();
            if (m.isSolid()) return true;
            try { if (!b.isPassable()) return true; } catch (Throwable ignored) {}
            // Slabs/stairs above also block
            if (m.name().endsWith("_SLAB") || m.name().endsWith("_STAIRS") || m.name().endsWith("_TRAPDOOR")) return true;
        }
        // Also check eye level +0.4
        Block eye = p.getEyeLocation().getBlock();
        if (eye.getType().isSolid()) return true;
        try { if (!eye.isPassable()) return true; } catch (Throwable ignored) {}
        return false;
    }

    private boolean isInCobweb(Location loc) {
        if (loc.getWorld() == null) return false;
        double px = loc.getX(), py = loc.getY(), pz = loc.getZ();
        for (double ox=-0.3; ox<=0.3; ox+=0.3)
            for (double oz=-0.3; oz<=0.3; oz+=0.3)
                for (double oy=0; oy<=1.5; oy+=0.5)
                    if (loc.getWorld().getBlockAt((int)Math.floor(px+ox),(int)Math.floor(py+oy),(int)Math.floor(pz+oz)).getType()==Material.COBWEB) return true;
        return false;
    }

    private boolean isInPowderSnow(Location loc) {
        if (loc.getBlock().getType()==Material.POWDER_SNOW) return true;
        if (loc.clone().add(0,0.5,0).getBlock().getType()==Material.POWDER_SNOW) return true;
        if (loc.clone().add(0,1.0,0).getBlock().getType()==Material.POWDER_SNOW) return true;
        return false;
    }

    private boolean isOnWater(Location loc) {
        Block b = loc.clone().subtract(0,0.3,0).getBlock();
        return b.getType()==Material.WATER || b.getType()==Material.BUBBLE_COLUMN;
    }

    private boolean isInWater(Player p, Location loc) {
        return p.isInWater() || p.isSwimming() || loc.getBlock().getType()==Material.WATER || p.getEyeLocation().getBlock().getType()==Material.WATER;
    }

    private boolean isOnHoney(Location loc) {
        for(double dy=-0.1; dy>=-0.7; dy-=0.3) if (loc.clone().add(0,dy,0).getBlock().getType()==Material.HONEY_BLOCK) return true;
        return false;
    }

    private boolean isOnSlime(Location loc) {
        for(double dy=-0.1; dy>=-0.7; dy-=0.3) if (loc.clone().add(0,dy,0).getBlock().getType()==Material.SLIME_BLOCK) return true;
        return false;
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

    private boolean isInLiquid(Player p) { return p.isInWater() || p.isSwimming() || p.getLocation().getBlock().getType()==Material.LAVA; }

    private boolean isExempt(Player p) {
        GameMode gm = p.getGameMode();
        return p.hasPermission("anticheat.bypass") || wm.isWhitelisted(p.getUniqueId())
                || gm==GameMode.CREATIVE || gm==GameMode.SPECTATOR || p.isDead()
                || p.isFlying() && p.getAllowFlight() // creative flight, but levitation checks should still run? Keep exempt
                || vm.hasJoinGrace(p.getUniqueId());
    }

    private void flag(Player p, UUID id, String reason) {
        LevState s = state.get(id);
        if (s==null) return;
        // Use shared vl already incremented; just ban via BanManager with levitation prefix
        bm.banPlayer(p, reason);
    }

    public void removePlayer(UUID id){ state.remove(id); }

    private void debug(Player p, String msg){
        if (plugin.getConfig().getBoolean("debug.enabled", false)) plugin.getLogger().info("[DEBUG][LevitationSim] "+p.getName()+" » "+msg);
    }
}
