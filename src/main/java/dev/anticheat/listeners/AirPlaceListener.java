package dev.anticheat.listeners;

import dev.anticheat.AntiCheatPlugin;
import dev.anticheat.managers.BanManager;
import dev.anticheat.managers.ViolationManager;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.util.RayTraceResult;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * AirPlaceListener — Three independent checks:
 *
 * ① AirPlace[NoRealNeighbor]  — instant ban  (BlockPlaceEvent)
 *   Placed block has zero non-air neighbours in all 6 cardinal faces.
 *   Physically impossible in vanilla — right-clicking always targets a face.
 *
 * ② FastPlace[Rate]  — instant ban  (BlockPlaceEvent)
 *   Vanilla has a hard 4-tick (200 ms) right-click cooldown → ~5 blocks/s max.
 *   More than FAST_PLACE_BASE_MAX placements (+ Haste bonus) within
 *   FAST_PLACE_WINDOW_MS is physically impossible without a client mod.
 *   Haste I adds +1 and Haste II adds +2 to the threshold as a safety buffer.
 *
 * ③ AirPlace[XRay]  — instant ban  (BlockBreakEvent)
 *   In vanilla a player can ONLY break the block their crosshair is on.
 *   A look-direction ray that hits a different block first means the broken
 *   block is occluded — definitively X-ray / NoClip.
 *
 * NOTE — Through-wall PLACEMENT is intentionally NOT checked here.
 *   Reliable detection is not possible at the Bukkit packet level without
 *   false-banning legitimate builders.  In Minecraft you can legally place
 *   blocks against faces that are NOT perfectly centred in your crosshair
 *   (bridging, corner-building, foot-level placement, etc.).  Any look-
 *   direction or eye-to-target ray test will fire on these cases.
 *   The NoRealNeighbor check already catches the most dangerous air-place
 *   variant; through-wall-only placement is far less impactful.
 */
public class AirPlaceListener implements Listener {

    private final AntiCheatPlugin plugin;
    private final ViolationManager vm;
    private final BanManager bm;

    // ── Fast-place tracking ──────────────────────────────────────────────────
    /** Rolling deque of recent placement wall-clock timestamps per player. */
    private final Map<UUID, Deque<Long>> placeTimes = new HashMap<>();

    /**
     * FastPlace thresholds.
     *
     * Vanilla hard limit: 4-tick (200 ms) right-click cooldown → max ~5 blocks/s.
     * Even with autoclick/spam, bursting more than 5 blocks in 300 ms (16.7/s)
     * is impossible without a client mod.  Scaffold hacks typically fire at
     * 15-30+ blocks/s, so this trips on the first ~300 ms of a scaffold run.
     *
     * Haste I/II only affects mining speed, not placement speed, so the base
     * threshold is the same regardless.  However we add 1 extra slot per Haste
     * amplifier level as a conservative false-positive buffer — some clients/mods
     * send placement packets slightly faster when also receiving fast break
     * confirmations, and being generous costs nothing when scaffold is 3-5× over
     * the cap anyway.
     */
    private static final int  FAST_PLACE_BASE_MAX  = 4;   // ban fires on 5th block in window
    private static final long FAST_PLACE_WINDOW_MS = 200L; // rolling window (ms)

    /** Max ray distance for X-ray detection. */
    private static final double MAX_REACH = 5.5;

    private static final BlockFace[] FACES = {
        BlockFace.NORTH, BlockFace.SOUTH,
        BlockFace.EAST,  BlockFace.WEST,
        BlockFace.UP,    BlockFace.DOWN
    };

    public AirPlaceListener(AntiCheatPlugin plugin) {
        this.plugin = plugin;
        this.vm     = plugin.getViolationManager();
        this.bm     = plugin.getBanManager();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BlockPlaceEvent — checks ① and ②
    // ─────────────────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!plugin.getConfig().getBoolean("checks.airplace", true)) return;
        if (dev.anticheat.utils.TPSUtil.isSeverelyLagging()) return;
        Player player = event.getPlayer();
        UUID   id     = player.getUniqueId();

        if (isExempt(player)) {
            placeTimes.remove(id);
            return;
        }

        if (vm.hasDamageGrace(id) || vm.hasTeleportGrace(id) || vm.hasJoinGrace(id)) {
            placeTimes.remove(id);
            return;
        }

        Block placed = event.getBlockPlaced();

        // ── Check ①: AirPlace[NoRealNeighbor] ────────────────────────────────
        // A block with zero non-air cardinal neighbours is physically impossible
        // in vanilla — you must right-click a face of an existing block to place.
        boolean allAir = true;
        for (BlockFace face : FACES) {
            Material m = placed.getRelative(face).getType();
            if (m != Material.AIR && m != Material.CAVE_AIR && m != Material.VOID_AIR) {
                allAir = false;
                break;
            }
        }
        if (allAir) {
            event.setCancelled(true);
            placed.setType(Material.AIR);
            placeTimes.remove(id);
            String reason = String.format("AirPlace[NoRealNeighbor] placedY=%d playerY=%.2f",
                    placed.getY(), player.getLocation().getY());
            debugLog(player, reason);
            bm.banPlayer(player, "AirPlace[NoRealNeighbor]");
            return;
        }

        // ── Check ②: FastPlace[Rate] — instant ban ───────────────────────────
        // Vanilla right-click has a hard 4-tick (200 ms) cooldown → max ~5/s.
        // We allow FAST_PLACE_BASE_MAX + hasteBonus blocks per 300 ms window.
        //
        // Haste note: Haste only speeds up mining, not placement.  The bonus is
        // a conservative buffer to avoid banning players who are simultaneously
        // mining fast (Haste II + Efficiency V) and placing in quick succession.
        int hasteBonus = 0;
        if (player.hasPotionEffect(org.bukkit.potion.PotionEffectType.HASTE)) {
            int amp = player.getPotionEffect(org.bukkit.potion.PotionEffectType.HASTE).getAmplifier(); // 0-based
            hasteBonus = amp + 1; // Haste I → +1, Haste II → +2
        }
        int fastPlaceMax = FAST_PLACE_BASE_MAX + hasteBonus;

        long now = System.currentTimeMillis();
        Deque<Long> times = placeTimes.computeIfAbsent(id, k -> new ArrayDeque<>());
        times.addLast(now);
        while (!times.isEmpty() && now - times.peekFirst() > FAST_PLACE_WINDOW_MS) {
            times.pollFirst();
        }
        if (times.size() > fastPlaceMax) {
            event.setCancelled(true);
            placed.setType(Material.AIR);
            placeTimes.remove(id);
            String reason = String.format("FastPlace[Rate] %d blocks in %dms (max %d)",
                    times.size(), FAST_PLACE_WINDOW_MS, fastPlaceMax);
            debugLog(player, reason);
            bm.banPlayer(player, "FastPlace[Rate]");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BlockBreakEvent — check ③ (X-ray)
    // ─────────────────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        UUID   id     = player.getUniqueId();

        if (isExempt(player)) return;

        if (vm.hasDamageGrace(id) || vm.hasTeleportGrace(id) || vm.hasJoinGrace(id)) return;

        Block broken = event.getBlock();

        if (isBreakingThroughWall(player, broken)) {
            debugLog(player, "AirPlace[XRay] block=" + broken.getType()
                    + "@" + fmtLoc(broken.getLocation()) + " (instant ban)");
            event.setCancelled(true);
            bm.banPlayer(player, "AirPlace[XRay]");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // X-ray detection helper
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns true when the player's look-direction ray hits a different block
     * before reaching the block they are breaking.
     *
     * In vanilla, block-break packets are only accepted for the block directly
     * under the crosshair.  A mismatch means the client sent a forged packet
     * to break a block it cannot see (X-ray / NoClip).
     *
     * Skip conditions:
     * • Broken block is not solid — thin/passable/decorative blocks (leaf litter,
     *   carpet, flowers, snow layers, etc.) are not solid, so the rayTraceBlocks
     *   call (ignorePassableBlocks=true) passes through them and hits whatever is
     *   behind, creating a guaranteed false positive.  Real X-ray targets are
     *   always solid (ores, chests, etc.).
     * • Player's eye is inside a solid block (gap/squeeze — ray hits enclosure).
     * • Broken block is < 0.5 b from eye (near-zero geometry is unreliable).
     * • Ray hits nothing within MAX_REACH (treat as benign).
     */
    private boolean isBreakingThroughWall(Player player, Block broken) {
        // Non-solid blocks are thin/passable — ray passes through them, always mismatch.
        if (!broken.getType().isSolid()) return false;

        Location eye = player.getEyeLocation();
        if (eye.getBlock().getType().isSolid()) return false;
        if (eye.distance(broken.getLocation().add(0.5, 0.5, 0.5)) < 0.5) return false;

        RayTraceResult result = eye.getWorld().rayTraceBlocks(
                eye, eye.getDirection(), MAX_REACH,
                FluidCollisionMode.NEVER, true);

        if (result == null || result.getHitBlock() == null) return false;

        Block hit = result.getHitBlock();
        return hit.getX() != broken.getX()
                || hit.getY() != broken.getY()
                || hit.getZ() != broken.getZ();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Exemption / cleanup
    // ─────────────────────────────────────────────────────────────────────────

    private boolean isExempt(Player player) {
        GameMode gm = player.getGameMode();
        return player.hasPermission("anticheat.bypass")
                || plugin.getWhitelistManager().isWhitelisted(player.getUniqueId())
                || gm == GameMode.CREATIVE
                || gm == GameMode.SPECTATOR
                || player.isDead()
                || player.isFlying()
                || player.isGliding();
    }

    /** Called by PlayerStateListener on quit/kick to prevent memory leaks. */
    public void removePlayer(UUID id) {
        placeTimes.remove(id);
    }

    private void debugLog(Player player, String msg) {
        if (plugin.getConfig().getBoolean("debug.enabled", false)) {
            plugin.getLogger().info("[DEBUG][AirPlace] " + player.getName() + " » " + msg);
        }
    }

    private String fmtLoc(Location loc) {
        return loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();
    }
}
