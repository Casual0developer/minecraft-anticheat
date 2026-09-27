package dev.anticheat.listeners;

import dev.anticheat.AntiCheatPlugin;
import dev.anticheat.managers.BanManager;
import dev.anticheat.managers.ViolationManager;
import dev.anticheat.managers.WhitelistManager;
import dev.anticheat.utils.GroundUtil;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffectType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * SnowWalkListener — detects players walking on top of Powder Snow without
 * leather boots equipped (snowwalk / phase cheat).
 *
 * Vanilla rule: players WITHOUT leather boots SINK INTO powder snow — they can
 * never stand on its surface.  If the server observes a player standing on top
 * of a powder snow block without leather boots, they are cheating.
 *
 * False-positive guards (20 total):
 *  1.  player.isOnGround()          — skip players who are airborne/jumping/falling
 *  2.  Integer block coordinates    — no float-offset rounding errors
 *  3.  XZ movement threshold        — skip rotation-only packets (no actual movement)
 *  4.  atFeet == POWDER_SNOW check  — player is sinking through it normally; skip
 *  5.  Leather boots check          — exact item type, any durability
 *  6.  VL threshold 8               — 8 consecutive flagged ticks before ban;
 *                                     absorbs brief surface-grazing during sinking
 *  7.  VL reset when legit          — VL drops when any clean tick is seen
 *  8.  isInWater()                  — water adjacent to powder snow can cause
 *                                     odd block detection; skip
 *  9.  isSwimming()                 — same as above for swim pose
 * 10.  isGliding()                  — elytra flight close to surface
 * 11.  Slow Falling potion          — very slow descent can linger over surface
 * 12.  Levitation potion            — floating upward over the block
 * 13.  isFlying() / getAllowFlight() — admin/spectator-style flight
 * 14.  isInsideVehicle()            — horses/boats can traverse powder snow
 * 15.  Damage grace                 — knockback can push a player above snow surface
 * 16.  Teleport grace               — ender pearl / teleport landing
 * 17.  Join grace                   — first few seconds after joining
 * 18.  Creative / Spectator         — exempt game modes
 * 19.  anticheat.bypass permission  — admin / whitelisted players
 * 20.  World Y bounds guard         — prevents block lookup at Y = -1 (void)
 */
public class SnowWalkListener implements Listener {

    private final AntiCheatPlugin  plugin;
    private final BanManager       bm;
    private final ViolationManager vm;
    private final WhitelistManager wm;

    private final Map<UUID, Integer> vl = new HashMap<>();

    /** Minimum XZ distance per packet to be considered real movement (not rotation). */
    private static final double MIN_MOVE    = 0.001;
    /** Consecutive flagged ticks required before banning. */
    private static final int    BAN_VL      = 8;

    public SnowWalkListener(AntiCheatPlugin plugin) {
        this.plugin = plugin;
        this.bm     = plugin.getBanManager();
        this.vm     = plugin.getViolationManager();
        this.wm     = plugin.getWhitelistManager();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!plugin.getConfig().getBoolean("checks.snowwalk", true)) return;
        // No TPS kill-switch: per-packet movement isn't changed by server lag.
        Player   player = event.getPlayer();
        UUID     id     = player.getUniqueId();
        Location from   = event.getFrom();
        Location to     = event.getTo();

        // ── Guard 1: null destination ─────────────────────────────────────────
        if (to == null) return;

        // ── Guard 2: skip rotation-only packets ───────────────────────────────
        if (Math.abs(to.getX() - from.getX()) < MIN_MOVE
                && Math.abs(to.getZ() - from.getZ()) < MIN_MOVE) return;

        // ── Guard 3: player must be on the ground ─────────────────────────────
        // Client claim OR server-seen (GroundUtil counts powder snow tops), so
        // claiming "airborne" while standing on the snow doesn't skip the check.
        if (!player.isOnGround() && !GroundUtil.touchingGround(player, to)) {
            decay(id);
            return;
        }

        // ── Guard 4: all exemptions ───────────────────────────────────────────
        if (isExempt(player)) {
            vl.remove(id);
            return;
        }

        // ── Guard 5: world null check ─────────────────────────────────────────
        World world = to.getWorld();
        if (world == null) return;

        // ── Guard 6: Y bounds — no block lookup below void ────────────────────
        int bx = to.getBlockX();
        int by = to.getBlockY();
        int bz = to.getBlockZ();
        if (by - 1 < world.getMinHeight()) {
            decay(id);
            return;
        }

        // ── Guard 7: if the block at feet is POWDER_SNOW the player is sinking ─
        //            into it normally — that is vanilla behaviour, not a cheat.
        Material atFeet = world.getBlockAt(bx, by, bz).getType();
        if (atFeet == Material.POWDER_SNOW) {
            decay(id);
            return;
        }

        // ── Guard 8: block directly below center must be POWDER_SNOW ─────────
        Material below = world.getBlockAt(bx, by - 1, bz).getType();
        if (below != Material.POWDER_SNOW) {
            decay(id);
            return;
        }

        // ── Guard 9: hitbox corner check — require ≥2 of 4 corners over snow ─
        //
        // When a player "runs across a corner" of a powder snow block their
        // center X,Z momentarily floors onto the snow column, but physically
        // only one corner of their 0.6×0.6 hitbox overlaps it.  A player who
        // is genuinely standing/walking on top of the snow will have at least
        // 2 hitbox corners over it.  This single guard eliminates the corner-
        // graze false positive without weakening detection for real snowwalk.
        double px  = to.getX();
        double pz  = to.getZ();
        double hw  = 0.3;                  // player hitbox half-width
        int    wy  = by - 1;
        int snowCorners = 0;
        for (int sx : new int[]{-1, 1}) {
            for (int sz : new int[]{-1, 1}) {
                int cx = (int) Math.floor(px + sx * hw);
                int cz = (int) Math.floor(pz + sz * hw);
                if (world.getBlockAt(cx, wy, cz).getType() == Material.POWDER_SNOW) {
                    snowCorners++;
                }
            }
        }
        if (snowCorners < 2) {
            decay(id);
            return;
        }

        // ── Guard 10: leather boots — any durability, just the item type ──────
        ItemStack boots = player.getInventory().getBoots();
        if (boots != null && boots.getType() == Material.LEATHER_BOOTS) {
            decay(id);
            return;
        }

        // ── Player is genuinely standing on powder snow without leather boots ─
        int currentVL = vl.merge(id, 1, Integer::sum);
        debugLog(player, "SnowWalk — corners=" + snowCorners + "/4 vl=" + currentVL + "/" + BAN_VL);

        if (currentVL >= BAN_VL) {
            vl.remove(id);
            bm.banPlayer(player, "SnowWalk");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Exemption check (guards 10-19)
    // ─────────────────────────────────────────────────────────────────────────

    private boolean isExempt(Player player) {
        GameMode gm = player.getGameMode();
        return player.hasPermission("anticheat.bypass")          // 10
                || wm.isWhitelisted(player.getUniqueId())        // 11
                || gm == GameMode.CREATIVE                       // 12
                || gm == GameMode.SPECTATOR                      // 13
                || player.isDead()                               // 14
                || player.isInsideVehicle()                      // 15 — horse/boat
                || player.isFlying()                             // 16
                || player.getAllowFlight()                       // 17
                || player.isGliding()                           // 18 — elytra
                || player.isSwimming()                          // 19
                || player.isInWater()                           // 20
                || player.hasPotionEffect(PotionEffectType.SLOW_FALLING)
                || player.hasPotionEffect(PotionEffectType.LEVITATION)
                || vm.hasJoinGrace(player.getUniqueId())
                || vm.hasTeleportGrace(player.getUniqueId())
                || vm.hasDamageGrace(player.getUniqueId());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Reduce VL by 1 on a clean tick rather than wiping it, so brief spikes
     *  don't reset the counter entirely (which would let cheaters stay just
     *  below the threshold by occasionally stepping off). */
    private void decay(UUID id) {
        Integer v = vl.get(id);
        if (v != null) {
            if (v <= 1) vl.remove(id);
            else vl.put(id, v - 1);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Cleanup
    // ─────────────────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        removePlayer(event.getPlayer().getUniqueId());
    }

    public void removePlayer(UUID id) {
        vl.remove(id);
    }

    private void debugLog(Player player, String msg) {
        if (plugin.getConfig().getBoolean("debug.enabled", false))
            plugin.getLogger().info("[DEBUG][SnowWalk] " + player.getName() + " » " + msg);
    }
}
