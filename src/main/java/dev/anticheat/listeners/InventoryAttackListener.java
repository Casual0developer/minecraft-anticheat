package dev.anticheat.listeners;

import dev.anticheat.AntiCheatPlugin;
import dev.anticheat.managers.BanManager;
import dev.anticheat.managers.WhitelistManager;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * InventoryAttackListener — detects Killaura / attack hacks triggering while
 * the player has a container GUI open.
 *
 * In vanilla Minecraft, ANY open inventory screen (chest, furnace, crafting
 * table, own inventory, etc.) captures all mouse clicks and keyboard input —
 * it is physically impossible to land an attack on another entity while a GUI
 * is open.  A damage packet arriving while the server knows a container is open
 * for that player can ONLY come from a modified client.
 *
 * Zero-false-positive design:
 *   • Grace period: we require the inventory to have been open for at least
 *     OPEN_GRACE_MS (100 ms = 2 ticks) before we start watching for attacks.
 *     This absorbs any packet ordering edge-case where open and attack events
 *     land in the same server tick due to network buffering.
 *   • Close clears state immediately: the moment InventoryCloseEvent fires the
 *     player is no longer flagged, so rapid open→close→attack sequences are
 *     never caught — only sustained open + attack.
 *   • Own-inventory excluded: pressing E to open the player's own CRAFTING
 *     inventory is excluded because some server-side events (e.g. respawn)
 *     can briefly trigger that open event without a real GUI.  Every other
 *     container type (CHEST, FURNACE, HOPPER, DISPENSER, DROPPER, BREWING,
 *     ENCHANTING, ANVIL, BEACON, etc.) is included.
 */
public class InventoryAttackListener implements Listener {

    private final AntiCheatPlugin  plugin;
    private final BanManager       bm;
    private final WhitelistManager wm;

    /**
     * Wall-clock timestamp (ms) when the player's current container was opened.
     * Present → container is open.  Absent → no container open.
     */
    private final Map<UUID, Long> containerOpenSince = new HashMap<>();

    /**
     * Minimum time (ms) the container must have been open before we start
     * watching for attack packets.  Absorbs same-tick packet ordering noise.
     * 100 ms = 2 server ticks — safe for all realistic network conditions.
     */
    private static final long OPEN_GRACE_MS = 100L;

    public InventoryAttackListener(AntiCheatPlugin plugin) {
        this.plugin = plugin;
        this.bm     = plugin.getBanManager();
        this.wm     = plugin.getWhitelistManager();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Track container open / close
    // ─────────────────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!plugin.getConfig().getBoolean("checks.inventoryattack", true)) return;
        if (dev.anticheat.utils.TPSUtil.isSeverelyLagging()) return;
        if (!(event.getPlayer() instanceof Player player)) return;

        // Exclude the player's own crafting/inventory screen — it can fire
        // automatically on respawn and other server-driven transitions.
        InventoryType type = event.getInventory().getType();
        if (type == InventoryType.CRAFTING || type == InventoryType.PLAYER) return;

        containerOpenSince.put(player.getUniqueId(), System.currentTimeMillis());
        debugLog(player, "container opened: " + type.name());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        if (containerOpenSince.remove(player.getUniqueId()) != null) {
            debugLog(player, "container closed — state cleared");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        containerOpenSince.remove(event.getPlayer().getUniqueId());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Detection
    // ─────────────────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        // Melee only.  A Thorns proc reports the Thorns wearer as the damager, so
        // hitting a Thorns wearer who had a chest open got THEM instantly banned.
        if (event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) return;
        if (!(event.getDamager() instanceof Player attacker)) return;

        UUID id = attacker.getUniqueId();

        // Bypass checks
        if (attacker.hasPermission("anticheat.bypass")) return;
        if (wm.isWhitelisted(id)) return;
        if (plugin.getViolationManager().hasJoinGrace(id)) return;
        GameMode gm = attacker.getGameMode();
        if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR) return;

        Long openSince = containerOpenSince.get(id);
        if (openSince == null) return; // no container open — fine

        long openDuration = System.currentTimeMillis() - openSince;
        if (openDuration < OPEN_GRACE_MS) {
            // Within the grace window — could be same-tick packet noise, skip
            debugLog(attacker, "attack within grace window (" + openDuration
                    + "ms < " + OPEN_GRACE_MS + "ms) — ignored");
            return;
        }

        // Container has been genuinely open for > OPEN_GRACE_MS and a damage
        // packet arrived.  Physically impossible from a vanilla client.
        debugLog(attacker, "KillauraInventory — container open for "
                + openDuration + "ms, attack packet received (instant ban)");
        bm.banPlayer(attacker, "KillauraInventory");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Cleanup
    // ─────────────────────────────────────────────────────────────────────────

    public void removePlayer(UUID id) {
        containerOpenSince.remove(id);
    }

    private void debugLog(Player player, String msg) {
        if (plugin.getConfig().getBoolean("debug.enabled", false)) {
            plugin.getLogger().info("[DEBUG][InvAttack] " + player.getName() + " » " + msg);
        }
    }
}
