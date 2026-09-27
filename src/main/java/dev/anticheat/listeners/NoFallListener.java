package dev.anticheat.listeners;

import dev.anticheat.AntiCheatPlugin;
import dev.anticheat.managers.BanManager;
import dev.anticheat.managers.ViolationManager;
import dev.anticheat.managers.WhitelistManager;
import dev.anticheat.utils.GroundUtil;
import org.bukkit.GameMode;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * NoFallListener — detects NoFall hacks (suppressing fall damage).
 *
 * Strategy: track each player's peak Y while airborne.  When they land,
 * compute the fall distance.  If it exceeds the vanilla damage threshold
 * AND none of the legitimate damage-negation conditions apply, add to
 * pendingBan and schedule a 2-tick check.  If the server's own
 * EntityDamageEvent (FALL cause) fires — even if cancelled by armor,
 * potions, or another plugin — clear them from pendingBan.  Only ban if
 * absolutely no fall damage event arrives.
 *
 * Key false-positive fixes:
 *  • ignoreCancelled=false on fall damage — cancelled events (armor,
 *    Feather Falling reducing to 0, other plugins) still clear pendingBan
 *  • BlockBreakEvent resets peakY — mining below yourself cannot inflate
 *    the accumulated fall distance
 *  • Block-based ground detection — player.isOnGround() can return false
 *    on block edges; we cross-check solid blocks below feet
 *  • 1.0 m landing buffer — accounts for slab/stair surface height offsets
 *  • PlayerTeleportEvent resets state — ender pearls, chorus fruit, etc.
 *
 * Legitimate negation conditions accounted for:
 *  • Slow Falling / Levitation potion
 *  • Feather Falling enchantment (any level) on boots
 *  • Elytra gliding
 *  • In or landing in water / lava
 *  • Slime block, Honey block, Cobweb, Powder Snow
 *  • Hay Bale, Sweet Berry Bush
 *  • All bed types (bounce)
 *  • Scaffolding
 *  • All climbable blocks (ladder, vines, kelp, etc.)
 *  • All leaf block types (passable — Y tracking can drift inside them)
 *  • Stairs & slabs (1.0 m buffer covers half-height landing surfaces)
 *  • Wind charge / explosion (teleport grace via PlayerStateListener)
 *  • Damage grace, teleport grace, join grace
 *  • Inside a vehicle
 *  • Creative / Spectator
 */
public class NoFallListener implements Listener {

    private final AntiCheatPlugin  plugin;
    private final BanManager       bm;
    private final ViolationManager vm;
    private final WhitelistManager wm;

    /** Peak Y recorded while the player was airborne. */
    private final Map<UUID, Double>  peakY       = new HashMap<>();
    /** Whether the player was on the ground last tick. */
    private final Map<UUID, Boolean> wasOnGround = new HashMap<>();
    /**
     * Players awaiting fall-damage confirmation.
     * Cleared by ANY EntityDamageEvent with FALL cause (even cancelled ones).
     */
    private final Set<UUID> pendingBan = new HashSet<>();
    /**
     * Players whose NEXT landing should be skipped entirely.
     * Set when a wind charge or similar velocity-reset event fires mid-air —
     * these grant fall-damage immunity on landing so no FALL event fires,
     * which would otherwise trigger a false NoFall ban.
     */
    private final Set<UUID> skipNextLanding = new HashSet<>();

    /**
     * Vanilla fall damage starts after falling > 3 blocks.
     * We use 3.2 so tiny rounding errors never false-flag.
     * An additional 1.0 m is subtracted at landing for slab/stair offsets.
     * Effective trigger: player must have fallen ~4.2+ blocks as measured
     * by our Y tracking — well within actual damage territory.
     */
    private static final double FALL_THRESHOLD = 3.2;
    private static final double LANDING_BUFFER = 1.0;

    public NoFallListener(AntiCheatPlugin plugin) {
        this.plugin = plugin;
        this.bm     = plugin.getBanManager();
        this.vm     = plugin.getViolationManager();
        this.wm     = plugin.getWhitelistManager();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Track peak Y and detect landing
    // ─────────────────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!plugin.getConfig().getBoolean("checks.nofall", true)) return;
        // No TPS kill-switch: per-packet movement isn't changed by server lag,
        // and vanilla fires the FALL event during the landing packet itself.
        Player   player = event.getPlayer();
        UUID     id     = player.getUniqueId();
        Location to     = event.getTo();
        if (to == null) return;

        if (isExempt(player)) {
            peakY.remove(id);
            wasOnGround.put(id, true);
            pendingBan.remove(id);
            return;
        }

        boolean onGround  = isOnGround(player, to);
        boolean wasGround = wasOnGround.getOrDefault(id, true);
        wasOnGround.put(id, onGround);

        if (!onGround) {
            // Airborne — update peak Y
            double current = to.getY();
            double peak    = peakY.getOrDefault(id, current);
            if (current > peak) peakY.put(id, current);
            else peakY.putIfAbsent(id, current);
            return;
        }

        // On ground — check if we just landed from a significant fall
        if (!wasGround) {
            // Wind charge (and similar velocity events) grant fall-damage immunity
            // on landing — no FALL event fires, so skip this landing entirely.
            if (skipNextLanding.remove(id)) {
                debugLog(player, "skipNextLanding flag set — landing skipped (wind charge immunity)");
                peakY.remove(id);
                return;
            }

            Double peak = peakY.remove(id);
            if (peak == null) return;

            if (isInCobweb(player)) {
                debugLog(player, "in cobweb — skip NoFall");
                return;
            }

            double fallDist = peak - to.getY() - LANDING_BUFFER;
            if (fallDist < FALL_THRESHOLD) return; // vanilla wouldn't deal damage

            // Check all legitimate no-damage conditions
            if (isFallNegated(player, to)) {
                debugLog(player, "fall negated legitimately (fallDist=" + fmt(fallDist) + ")");
                return;
            }

            pendingBan.add(id);
            debugLog(player, "NoFall pending — fallDist=" + fmt(fallDist));

            new BukkitRunnable() {
                @Override public void run() {
                    if (!pendingBan.remove(id)) return; // FALL event already cleared it
                    if (!player.isOnline() || player.isDead()) return;
                    // Re-check grace periods — teleport/damage can arrive between
                    // the landing tick and now
                    if (vm.hasTeleportGrace(id) || vm.hasDamageGrace(id)) return;
                    debugLog(player, "NoFall confirmed — no FALL event received, banning");
                    bm.banPlayer(player, "NoFall");
                }
            }.runTaskLater(plugin, 4L); // 4 ticks (was 2) — gives FALL event more time to arrive on laggy servers
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Block break — reset peakY so mining below yourself doesn't inflate
    // accumulated fall distance from a previously higher position.
    // ─────────────────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (isExempt(player)) return;
        UUID id = player.getUniqueId();
        // Reset peak to current Y — any prior height is irrelevant after mining
        peakY.put(id, player.getLocation().getY());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Teleport — reset state on ender pearl, chorus fruit, etc.
    // ─────────────────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        peakY.remove(id);
        wasOnGround.put(id, true);
        pendingBan.remove(id);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // FALL damage event — ANY fall event (even cancelled) clears pendingBan.
    // ignoreCancelled is intentionally NOT set here: if armor, Feather Falling,
    // or another plugin reduces/cancels the damage, the event still fired,
    // meaning vanilla acknowledged the fall.  We must clear pendingBan.
    // ─────────────────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR)
    public void onFallDamage(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.FALL) return;
        if (!(event.getEntity() instanceof Player player)) return;
        boolean removed = pendingBan.remove(player.getUniqueId());
        if (removed) debugLog(player, "FALL event received (cancelled=" + event.isCancelled()
                + ") — pendingBan cleared");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Legitimate fall-damage negation
    // ─────────────────────────────────────────────────────────────────────────

    private boolean isFallNegated(Player player, Location landLoc) {

        // ── Potion effects ────────────────────────────────────────────────────
        if (player.hasPotionEffect(PotionEffectType.SLOW_FALLING)) return true;
        if (player.hasPotionEffect(PotionEffectType.LEVITATION))    return true;

        // ── Elytra ───────────────────────────────────────────────────────────
        if (player.isGliding()) return true;

        // ── Feather Falling (any level) — reduces/eliminates fall damage ──────
        ItemStack boots = player.getInventory().getBoots();
        if (boots != null && boots.containsEnchantment(Enchantment.FEATHER_FALLING)) return true;

        // ── Liquid ────────────────────────────────────────────────────────────
        if (player.isInWater()) return true;
        Material feet = landLoc.getBlock().getType();
        Material head = landLoc.clone().add(0, 1, 0).getBlock().getType();
        if (feet == Material.LAVA || head == Material.LAVA) return true;

        // ── Landing block — check feet, one below, two below ──────────────────
        for (int dy = 0; dy >= -2; dy--) {
            if (isSafeBlock(landLoc.clone().add(0, dy, 0).getBlock().getType())) return true;
        }

        // ── Climbable blocks ──────────────────────────────────────────────────
        if (isOnClimbable(landLoc)) return true;

        // ── Leaf blocks (passable — Y tracking can drift inside them) ─────────
        if (isLeafBlock(feet)) return true;

        // ── Grace periods (wind charge, explosion, teleport, damage, join) ────
        UUID id = player.getUniqueId();
        if (vm.hasTeleportGrace(id)) return true;
        if (vm.hasDamageGrace(id))   return true;
        if (vm.hasJoinGrace(id))     return true;

        return false;
    }

    /**
     * Blocks that negate or severely reduce fall damage so that vanilla may
     * not fire a FALL damage event at all.
     */
    private boolean isSafeBlock(Material m) {
        return switch (m) {
            case SLIME_BLOCK, HONEY_BLOCK, COBWEB, POWDER_SNOW  -> true;
            case HAY_BLOCK, SWEET_BERRY_BUSH                     -> true;
            case SCAFFOLDING                                      -> true;
            case WATER, BUBBLE_COLUMN                            -> true;
            case WHITE_BED, ORANGE_BED, MAGENTA_BED, LIGHT_BLUE_BED,
                 YELLOW_BED, LIME_BED, PINK_BED, GRAY_BED, LIGHT_GRAY_BED,
                 CYAN_BED, PURPLE_BED, BLUE_BED, BROWN_BED, GREEN_BED,
                 RED_BED, BLACK_BED                              -> true;
            default -> false;
        };
    }

    private boolean isOnClimbable(Location loc) {
        return switch (loc.getBlock().getType()) {
            case LADDER, VINE, SCAFFOLDING,
                 TWISTING_VINES, TWISTING_VINES_PLANT,
                 WEEPING_VINES,  WEEPING_VINES_PLANT,
                 CAVE_VINES,     CAVE_VINES_PLANT,
                 KELP,           KELP_PLANT -> true;
            default -> false;
        };
    }

    private boolean isLeafBlock(Material m) {
        return m.name().endsWith("_LEAVES");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Robust ground detection — player.isOnGround() can return false on
    // block edges; cross-check with solid blocks below feet.
    // ─────────────────────────────────────────────────────────────────────────

    private boolean isInCobweb(Player player) {
        Location loc = player.getLocation();
        double px = loc.getX(), py = loc.getY(), pz = loc.getZ();
        for (double ox = -0.8; ox <= 0.8; ox += 0.4) {
            for (double oz = -0.8; oz <= 0.8; oz += 0.4) {
                for (double oy = -0.5; oy <= 2.0; oy += 0.5) {
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

    private boolean isOnGround(Player player, Location loc) {
        // Server-confirmed: the classic NoFall hack sends onGround=true the whole way down.
        if (GroundUtil.isOnGround(player, loc)) return true;
        Block below = loc.clone().subtract(0, 0.1, 0).getBlock();
        if (below.getType().isSolid()) return true;
        Block lower = loc.clone().subtract(0, 0.6, 0).getBlock();
        return lower.getType().isSolid();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Exemption check
    // ─────────────────────────────────────────────────────────────────────────

    private boolean isExempt(Player player) {
        GameMode gm = player.getGameMode();

        // Respect the /gamerule fallDamage setting.  When it's false the
        // world won't fire a FALL EntityDamageEvent regardless of the
        // player's fall distance — but that's a server-set rule, not a
        // cheat.  Without this exemption, every fall in a fallDamage=false
        // world looks identical to a NoFall hack.
        Boolean fallDamageRule = player.getWorld().getGameRuleValue(GameRule.FALL_DAMAGE);
        if (fallDamageRule != null && !fallDamageRule) return true;

        // Jump Boost reduces fall damage by 1 per level (vanilla behaviour).
        // A player landing from a jump-boost-augmented jump can take 0
        // damage, which means no FALL EntityDamageEvent fires — and NoFall
        // (which waits for that event to clear its pending state) would
        // falsely ban.  Server-validated effect; cannot be spoofed.
        if (player.hasPotionEffect(PotionEffectType.JUMP_BOOST)) return true;

        return player.hasPermission("anticheat.bypass")
                || wm.isWhitelisted(player.getUniqueId())
                || gm == GameMode.CREATIVE
                || gm == GameMode.SPECTATOR
                || player.isDead()
                || player.isInsideVehicle()
                || player.isFlying()
                || player.getAllowFlight()
                || vm.hasJoinGrace(player.getUniqueId())
                || vm.hasTeleportGrace(player.getUniqueId());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Cleanup
    // ─────────────────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        removePlayer(event.getPlayer().getUniqueId());
    }

    /**
     * Mark that the player's next landing should be skipped.
     * Call this when a wind charge or breeze wind charge hits near the player
     * while they are airborne — these grant fall-damage immunity on landing
     * so no FALL event fires, which would otherwise produce a false ban.
     */
    public void markSkipNextLanding(UUID id) {
        skipNextLanding.add(id);
    }

    public void removePlayer(UUID id) {
        peakY.remove(id);
        wasOnGround.remove(id);
        pendingBan.remove(id);
        skipNextLanding.remove(id);
    }

    private void debugLog(Player player, String msg) {
        if (plugin.getConfig().getBoolean("debug.enabled", false))
            plugin.getLogger().info("[DEBUG][NoFall] " + player.getName() + " » " + msg);
    }

    private String fmt(double d) { return String.format("%.2f", d); }
}
