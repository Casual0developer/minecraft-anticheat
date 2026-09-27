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
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.potion.PotionEffectType;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AirJumpListener — detects double-jump / air-jump cheats.
 *
 * Vanilla: jumping is only possible when onGround==true. An upward impulse
 * while airborne (outside of slime/honey/wind/levitation/elytra) is impossible.
 *
 * Two-phase detection:
 *   1) Legit initial jump is recorded when leaving ground (airTicks <=2 and deltaY>0.25)
 *   2) Any second upward impulse while still airborne (airTicks >= minAirTicks)
 *      with deltaY > minDelta and falling before (prevDeltaY < 0.05) is flagged.
 *
 * Exemptions use server-side state (ground, levitation, liquids, climbables, slime,
 * honey, cobweb, powder snow, vehicle, elytra, flying, grace periods) — all server-validated.
 */
public class AirJumpListener implements Listener {

    private final AntiCheatPlugin plugin;
    private final BanManager bm;
    private final ViolationManager vm;
    private final WhitelistManager wm;

    private static class AirState {
        int airTicks = 0;
        double prevDeltaY = Double.MIN_VALUE;
        boolean hasJumped = false;
        int vl = 0;
        long lastFlagMs = 0;
        boolean wasOnGround = true;
        double lastGroundY = 0;
        int slimeGrace = 0;
        int honeyGrace = 0;
    }

    private final Map<UUID, AirState> state = new ConcurrentHashMap<>();

    private static final double DEFAULT_MIN_DELTA = 0.28;
    private static final int DEFAULT_MIN_AIRTICKS = 6;
    private static final int DEFAULT_VL_THRESHOLD = 2;
    private static final long FLAG_COOLDOWN_MS = 300L;

    public AirJumpListener(AntiCheatPlugin plugin) {
        this.plugin = plugin;
        this.bm = plugin.getBanManager();
        this.vm = plugin.getViolationManager();
        this.wm = plugin.getWhitelistManager();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!plugin.getConfig().getBoolean("checks.airjump", true)) return;
        // No TPS kill-switch: per-packet movement isn't changed by server lag.

        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) return;

        Player player = event.getPlayer();
        UUID id = player.getUniqueId();

        boolean onGround = isOnGround(player, to);
        boolean exempt = isExempt(player);

        AirState s = state.computeIfAbsent(id, k -> new AirState());

        // Grace countdowns
        if (s.slimeGrace > 0) s.slimeGrace--;
        if (s.honeyGrace > 0) s.honeyGrace--;

        // Refresh grace if near slime/honey — prevents bounce false positives
        if (isNearSlime(to) || isNearSlime(from)) s.slimeGrace = 10;
        if (isNearHoney(to) || isNearHoney(from)) s.honeyGrace = 12;

        double deltaY = to.getY() - from.getY();

        // Landed
        if (onGround) {
            s.airTicks = 0;
            s.hasJumped = false;
            s.prevDeltaY = Double.MIN_VALUE;
            s.wasOnGround = true;
            s.lastGroundY = to.getY();
            // Decay vl on legit ground ticks
            if (exempt) {
                s.vl = 0;
                return;
            }
            if (s.vl > 0) s.vl = Math.max(0, s.vl - 0); // keep vl until next air phase
            return;
        }

        // Airborne
        if (s.wasOnGround) {
            // Just left ground — this could be the legit initial jump
            s.airTicks = 1;
            if (deltaY > 0.25 && !exempt && s.slimeGrace == 0 && s.honeyGrace == 0) {
                s.hasJumped = true;
            }
            s.wasOnGround = false;
            s.prevDeltaY = deltaY;
            return;
        }

        s.airTicks++;
        s.wasOnGround = false;

        if (exempt || s.slimeGrace > 0 || s.honeyGrace > 0) {
            s.prevDeltaY = deltaY;
            return;
        }

        // Config thresholds (allow tuning)
        double minDelta = plugin.getConfig().getDouble("settings.airjump-min-delta", DEFAULT_MIN_DELTA);
        int minAirTicks = plugin.getConfig().getInt("settings.airjump-min-airticks", DEFAULT_MIN_AIRTICKS);
        int vlThreshold = plugin.getConfig().getInt("settings.airjump-vl-threshold", DEFAULT_VL_THRESHOLD);

        // If player hasn't yet jumped after leaving ground, check if current delta is the first jump
        if (!s.hasJumped) {
            if (deltaY > 0.25 && s.airTicks <= 3) {
                s.hasJumped = true;
            }
            s.prevDeltaY = deltaY;
            return;
        }

        // Has jumped once, now looking for SECOND upward impulse while still airborne
        // Must be well into air phase and coming from falling/hover, not just initial ascent continuation
        if (s.airTicks >= minAirTicks && s.hasJumped) {
            boolean wasFallingOrHovering = s.prevDeltaY == Double.MIN_VALUE || s.prevDeltaY < 0.05;
            boolean strongUp = deltaY > minDelta;
            // Also ignore tiny upward due to stepping on honey edge etc.
            boolean notNearBounce = s.slimeGrace == 0 && s.honeyGrace == 0;
            if (strongUp && wasFallingOrHovering && notNearBounce) {
                long now = System.currentTimeMillis();
                if (now - s.lastFlagMs < FLAG_COOLDOWN_MS) {
                    s.prevDeltaY = deltaY;
                    return;
                }
                s.lastFlagMs = now;
                s.vl++;
                debugLog(player, "AirJump deltaY=" + fmt(deltaY) + " prev=" + fmt(s.prevDeltaY) + " airTicks=" + s.airTicks + " vl=" + s.vl + "/" + vlThreshold);
                if (s.vl >= vlThreshold) {
                    s.vl = 0;
                    s.hasJumped = false; // reset to prevent instant re-trigger
                    bm.banPlayer(player, "AirJump deltaY=" + fmt(deltaY) + " airTicks=" + s.airTicks);
                } else {
                    // Prevent immediate double flag on same impulse, require new fall phase
                    s.hasJumped = false;
                    s.airTicks = 0;
                }
            }
        }

        s.prevDeltaY = deltaY;
    }

    // ── Ground / block helpers ────────────────────────────────────────────

    private boolean isOnGround(Player player, Location loc) {
        // Server-confirmed: AirJump hacks jump by claiming onGround=true mid-air.
        if (GroundUtil.isOnGround(player, loc)) return true;
        // Block below feet check (server-side, like FlyListener)
        Block below = loc.clone().subtract(0, 0.1, 0).getBlock();
        if (below.getType().isSolid()) return true;
        Block lower = loc.clone().subtract(0, 0.6, 0).getBlock();
        return lower.getType().isSolid();
    }

    private boolean isNearSlime(Location loc) {
        if (loc == null || loc.getWorld() == null) return false;
        for (double dy = -0.1; dy >= -2.2; dy -= 0.55) {
            Material m = loc.clone().add(0, dy, 0).getBlock().getType();
            if (m == Material.SLIME_BLOCK) return true;
            if (m.name().endsWith("_BED")) return true; // bounce
        }
        return false;
    }

    private boolean isNearHoney(Location loc) {
        if (loc == null || loc.getWorld() == null) return false;
        // Check feet, below, and adjacent
        Block feet = loc.getBlock();
        Block below = loc.clone().subtract(0, 0.1, 0).getBlock();
        Block lower = loc.clone().subtract(0, 0.6, 0).getBlock();
        Block n = loc.clone().add(0, 0, 1).getBlock();
        Block s = loc.clone().add(0, 0, -1).getBlock();
        Block e = loc.clone().add(1, 0, 0).getBlock();
        Block w = loc.clone().add(-1, 0, 0).getBlock();
        Material h = Material.HONEY_BLOCK;
        return feet.getType() == h || below.getType() == h || lower.getType() == h
                || n.getType() == h || s.getType() == h || e.getType() == h || w.getType() == h;
    }

    private boolean isInSticky(Location loc) {
        if (loc == null) return false;
        Material feet = loc.getBlock().getType();
        Material upper = loc.clone().add(0, 0.9, 0).getBlock().getType();
        return feet == Material.COBWEB || upper == Material.COBWEB
                || feet == Material.POWDER_SNOW || upper == Material.POWDER_SNOW;
    }

    private boolean isExempt(Player player) {
        if (player.hasPermission("anticheat.bypass")) return true;
        if (wm.isWhitelisted(player.getUniqueId())) return true;
        GameMode gm = player.getGameMode();
        if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR) return true;
        if (player.isDead()) return true;
        if (player.isInsideVehicle()) return true;
        if (player.isFlying() || player.getAllowFlight()) return true;
        if (player.isGliding()) return true;
        if (player.isSwimming() || player.isInWater()) return true;
        if (player.isClimbing()) return true;
        if (player.hasPotionEffect(PotionEffectType.LEVITATION)) return true;
        if (player.hasPotionEffect(PotionEffectType.SLOW_FALLING)) return true;
        // Jump boost alone doesn't allow double jump, so not exempt — just note it
        if (isInSticky(player.getLocation())) return true;
        UUID id = player.getUniqueId();
        if (vm.hasJoinGrace(id)) return true;
        if (vm.hasTeleportGrace(id)) return true;
        if (vm.hasDamageGrace(id)) return true;
        return false;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        state.remove(event.getPlayer().getUniqueId());
    }

    public void removePlayer(UUID id) {
        state.remove(id);
    }

    /** Fresh air-phase state, carrying VL and flag cooldown over. */
    public void resetMotion(UUID id) {
        AirState old = state.remove(id);
        if (old == null) return;
        AirState s = new AirState();
        s.vl = old.vl;
        s.lastFlagMs = old.lastFlagMs;
        state.put(id, s);
    }

    private void debugLog(Player player, String msg) {
        if (plugin.getConfig().getBoolean("debug.enabled", false)) {
            plugin.getLogger().info("[DEBUG][AirJump] " + player.getName() + " » " + msg);
        }
    }

    private String fmt(double d) {
        return String.format("%.3f", d);
    }
}
