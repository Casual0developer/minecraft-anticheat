package dev.anticheat.listeners;

import dev.anticheat.AntiCheatPlugin;
import dev.anticheat.managers.BanManager;
import dev.anticheat.managers.ViolationManager;
import dev.anticheat.managers.WhitelistManager;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * InventoryWalkListener — detects XZ movement while ANY inventory GUI is open,
 * including the player's own crafting/inventory screen (E key) and all external
 * containers (chest, barrel, furnace, etc.).
 *
 * Architecture:
 *   • InventoryOpenEvent  → marks the player as "has inventory open" regardless
 *     of inventory type.  In Paper 1.21 this fires for ALL inventory opens,
 *     including the player's own E-key inventory screen.
 *   • InventoryCloseEvent → clears that mark.
 *   • Global per-tick scheduler → scans all marked players every tick and
 *     compares their current XZ position to a reference set after a 300 ms
 *     grace window.  Movement ≥ 0.12 blocks → instant ban.
 *
 * False-positive guards:
 *   • 300 ms open grace — drains residual momentum from the interaction approach.
 *   • 0.12 block XZ threshold — well above floating-point noise, well below
 *     the ≈0.20 blocks a sprinting player covers in one tick.
 *   • Grace periods and game-mode exemptions fully respected.
 */
public class InventoryWalkListener implements Listener {

    private final AntiCheatPlugin  plugin;
    private final BanManager       bm;
    private final ViolationManager vm;
    private final WhitelistManager wm;

    /**
     * Players who currently have an inventory open (any type).
     * Populated by InventoryOpenEvent, cleared by InventoryCloseEvent / quit.
     */
    private final Set<UUID>           invOpen   = new HashSet<>();

    /** Timestamp (ms) when we started tracking each player's inventory. */
    private final Map<UUID, Long>     openSince = new HashMap<>();

    /** Reference XZ position — updated during grace, frozen after grace expires. */
    private final Map<UUID, Location> refPos    = new HashMap<>();

    private final Map<UUID, Double> lastMoveX = new HashMap<>();
    private final Map<UUID, Double> lastMoveZ = new HashMap<>();

    private static final long   OPEN_GRACE_MS  = 300L;   // ~6 ticks
    private static final double MOVE_THRESHOLD = 0.12;   // blocks (XZ only)

    public InventoryWalkListener(AntiCheatPlugin plugin) {
        this.plugin = plugin;
        this.bm     = plugin.getBanManager();
        this.vm     = plugin.getViolationManager();
        this.wm     = plugin.getWhitelistManager();

        // Global per-tick movement scan for all players with an inventory open.
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Inventory open / close events — track WHO has a GUI open
    // ─────────────────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!plugin.getConfig().getBoolean("checks.inventorywalk", true)) return;
        if (dev.anticheat.utils.TPSUtil.isSeverelyLagging()) return;
        if (!(event.getPlayer() instanceof Player player)) return;
        if (isExempt(player)) return;

        UUID id = player.getUniqueId();
        invOpen.add(id);

        // Reset grace timer so every new open gets a fresh 300 ms grace,
        // even if the player rapidly opens different inventories.
        openSince.remove(id);
        refPos.remove(id);

        debugLog(player, "inventory opened ("
                + event.getInventory().getType().name() + ") — tracking started");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        clearTracking(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (isExempt(player)) return;
        // Off-hand swapping (F key) inside inventory is explicitly exempt — not inventory walk
        if (event.getClick() == ClickType.SWAP_OFFHAND) return;
        // Also exempt offhand swap via click type? NUM pad swaps etc. not flagged as walk
        if (!invOpen.contains(player.getUniqueId())) return;

        // Immediate walk check: if player is currently moving while interacting, flag
        // This catches InventoryWalk[Drag/Click] even if refPos distance is small but player has velocity
        boolean isMovingClick = false;
        double recentDist = 0;
        Location ref = refPos.get(player.getUniqueId());
        if (ref != null) {
            Location current = player.getLocation();
            double dx = Math.abs(current.getX() - ref.getX());
            double dz = Math.abs(current.getZ() - ref.getZ());
            recentDist = Math.sqrt(dx * dx + dz * dz);
            if (recentDist >= MOVE_THRESHOLD) isMovingClick = true;
        }
        // Also check current tick velocity via per-player last position tracking for immediate drags
        Double lastX = lastMoveX.get(player.getUniqueId());
        Double lastZ = lastMoveZ.get(player.getUniqueId());
        if (!isMovingClick && lastX != null && lastZ != null) {
            double inst = Math.hypot(player.getLocation().getX() - lastX, player.getLocation().getZ() - lastZ);
            if (inst > 0.06) isMovingClick = true;
        }

        if (isMovingClick) {
            clearTracking(player.getUniqueId());
            debugLog(player, "InventoryWalk[Click] moved " + String.format("%.3f", recentDist)
                    + "b while clicking inventory type=" + event.getClick().name() + " (banning)");
            bm.banPlayer(player, "InventoryWalk moved=" + String.format("%.3f", recentDist) + "b while clicking");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (isExempt(player)) return;
        if (!invOpen.contains(player.getUniqueId())) return;

        Location ref = refPos.get(player.getUniqueId());
        if (ref == null) return;

        Location current = player.getLocation();
        double dx = Math.abs(current.getX() - ref.getX());
        double dz = Math.abs(current.getZ() - ref.getZ());
        double dist = Math.sqrt(dx * dx + dz * dz);

        // If dragging items while walking, flag immediately
        if (dist >= MOVE_THRESHOLD * 0.8) { // drag is continuous, slightly lower threshold
            clearTracking(player.getUniqueId());
            debugLog(player, "InventoryWalk[Drag] moved " + String.format("%.3f", dist)
                    + "b while dragging (banning)");
            bm.banPlayer(player, "InventoryWalk[Drag] moved=" + String.format("%.3f", dist) + "b while dragging");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwapHand(PlayerSwapHandItemsEvent event) {
        // Offhand key (F) while walking is explicitly allowed — clear any pending inventory tracking
        // Do NOT flag for offhand swaps
        Player player = event.getPlayer();
        // If player is walking while swapping to offhand, it's legit (quick swap), exempt
        // We just ensure we don't have false InventoryWalk from swap
        // No ban, just debug if needed
        if (isExempt(player)) return;
        debugLog(player, "Offhand swap exempt — not InventoryWalk");
        // Optionally, we could clear tracking if any, but swap doesn't open inventory, so no tracking exists
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMoveTrack(org.bukkit.event.player.PlayerMoveEvent event) {
        if (event.getTo() == null) return;
        Player p = event.getPlayer();
        UUID id = p.getUniqueId();
        if (!invOpen.contains(id)) {
            lastMoveX.remove(id);
            lastMoveZ.remove(id);
            return;
        }
        lastMoveX.put(id, event.getTo().getX());
        lastMoveZ.put(id, event.getTo().getZ());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Per-tick movement scan
    // ─────────────────────────────────────────────────────────────────────────

    private void tick() {
        for (UUID id : new HashSet<>(invOpen)) {            // snapshot to avoid CME
            Player player = plugin.getServer().getPlayer(id);

            if (player == null || !player.isOnline() || isExempt(player)) {
                clearTracking(id);
                continue;
            }

            long now = System.currentTimeMillis();

            // ── First tick of tracking — initialise timestamps & ref ──────────
            if (!openSince.containsKey(id)) {
                openSince.put(id, now);
                refPos.put(id, player.getLocation().clone());
                continue;
            }

            long elapsed = now - openSince.get(id);

            // ── Inside grace window — keep refreshing ref to drain momentum ───
            if (elapsed < OPEN_GRACE_MS) {
                refPos.put(id, player.getLocation().clone());
                continue;
            }

            // ── Past grace — check movement against frozen reference ──────────
            Location ref = refPos.get(id);
            if (ref == null) {
                refPos.put(id, player.getLocation().clone());
                continue;
            }

            Location current = player.getLocation();
            double dx   = Math.abs(current.getX() - ref.getX());
            double dz   = Math.abs(current.getZ() - ref.getZ());
            double dist = Math.sqrt(dx * dx + dz * dz);

            debugLog(player, "GUI open poll dist=" + String.format("%.4f", dist));

            if (dist >= MOVE_THRESHOLD) {
                clearTracking(id);
                debugLog(player, "InventoryWalk — dist=" + String.format("%.3f", dist) + " (banning)");
                bm.banPlayer(player, "InventoryWalk");
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Exemption check
    // ─────────────────────────────────────────────────────────────────────────

    private boolean isExempt(Player player) {
        GameMode gm = player.getGameMode();
        return player.hasPermission("anticheat.bypass")
                || wm.isWhitelisted(player.getUniqueId())
                || gm == GameMode.CREATIVE
                || gm == GameMode.SPECTATOR
                || player.isDead()
                || vm.hasJoinGrace(player.getUniqueId());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Cleanup
    // ─────────────────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        removePlayer(event.getPlayer().getUniqueId());
    }

    public boolean isInventoryOpen(UUID id) { return invOpen.contains(id); }

    public void removePlayer(UUID id) {
        clearTracking(id);
    }

    private void clearTracking(UUID id) {
        invOpen.remove(id);
        openSince.remove(id);
        refPos.remove(id);
        lastMoveX.remove(id);
        lastMoveZ.remove(id);
    }

    private void debugLog(Player player, String msg) {
        if (plugin.getConfig().getBoolean("debug.enabled", false))
            plugin.getLogger().info("[DEBUG][InvWalk] " + player.getName() + " » " + msg);
    }
}
