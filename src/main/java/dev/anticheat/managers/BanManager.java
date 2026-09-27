package dev.anticheat.managers;

import dev.anticheat.AntiCheatPlugin;
import dev.anticheat.utils.ColorUtil;
import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Handles banning by username, UUID, and IP.
 * Optionally checks whether a player's IP is a known proxy/VPN
 * using the free ip-api.com endpoint (no API key required for low volume).
 */
public class BanManager {

    private final AntiCheatPlugin plugin;

    // Persistent store of banned UUIDs (survives restarts, unlike name bans alone)
    private final File uuidBanFile;
    private final Set<UUID>         bannedUUIDs = new HashSet<>();
    /** UUID → last-known IP, persisted alongside UUID bans for offline unban. */
    private final Map<UUID, String> bannedIPs   = new LinkedHashMap<>();
    /** UUID → last-known username, so the unban command can resolve by name. */
    private final Map<UUID, String> bannedNames = new LinkedHashMap<>();

    public BanManager(AntiCheatPlugin plugin) {
        this.plugin = plugin;
        this.uuidBanFile = new File(plugin.getDataFolder(), "banned-uuids.yml");
        loadUUIDBans();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Primary ban entry point
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Bans a player by username, UUID, and IP simultaneously.
     * Optionally runs a proxy check first (async).
     */
    public void banPlayer(Player player, String checkName) {
        // If proxy-check is enabled, do it async before banning
        if (plugin.getConfig().getBoolean("ban.proxy-check.enabled", false)) {
            final String ip = getIP(player);
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                boolean isProxy = ip != null && isProxy(ip);
                String finalCheck = isProxy ? checkName + " + Proxy/VPN" : checkName;
                // Ban must run back on the main thread
                Bukkit.getScheduler().runTask(plugin, () -> executeBan(player, finalCheck, ip));
            });
        } else {
            executeBan(player, checkName, getIP(player));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal: apply all three ban types
    // ─────────────────────────────────────────────────────────────────────────

    private void executeBan(Player player, String checkName, String ip) {
        if (!player.isOnline()) return; // left before ban resolved

        // ── Punishment mode (set via /punishment) ─────────────────────────────
        // Values: "kick"  → kick only, no ban entry
        //         "temp"  → temporary ban (ban.duration-days from config)
        //         "permanent" → permanent ban
        String mode = plugin.getConfig().getString("punishment.mode", "temp");

        String rawReason = plugin.getConfig().getString(
                "ban.reason",
                "&cYou have been banned.\n\n&fReason: &cUsing a modified client\n\n&7Your ban expires in &f30 days&7."
        );
        String rawKickReason = plugin.getConfig().getString(
                "ban.kick-reason",
                "&cYou have been kicked from this server.\n\n&fReason: &cUsing a modified client"
        );
        String rawBroadcastTemplate = plugin.getConfig().getString(
                "ban.broadcast",
                "&8[&cAntiCheat&8] &f{player} &7has been banned for &cusing a modified client&7."
        );
        // Inject custom nickname into broadcast if set
        String branding = plugin.getDisplayName();
        String broadcastTemplate = rawBroadcastTemplate;
        if (!branding.equals("AntiCheat") && broadcastTemplate.contains("AntiCheat")) {
            broadcastTemplate = broadcastTemplate.replace("AntiCheat", branding);
        }
        // Allow {nickname} placeholder in broadcast / reasons
        broadcastTemplate = broadcastTemplate.replace("{nickname}", branding).replace("{brand}", branding);
        String kickMessage = ColorUtil.colorize(
                (mode.equals("kick") ? rawKickReason : rawReason)
                        .replace("{nickname}", branding).replace("{brand}", branding)
                        .replace("AntiCheat", branding.equals("AntiCheat") ? "AntiCheat" : branding)
        );
        String banReason   = "Using a modified client (" + checkName + ")";

        String historyType;
        String historyDuration;

        switch (mode) {

            case "alert" -> {
                // Alert-only — notify admins and record history, NO kick or ban or broadcast
                plugin.alertFlag(player, checkName);
                plugin.getHistoryManager().record(
                        player.getUniqueId(), player.getName(),
                        checkName, "ALERT", "Alert only");
                plugin.getLogger().info(plugin.getConsolePrefix() + " FLAGGED (alert-only): " + player.getName()
                        + " | UUID: " + player.getUniqueId()
                        + " | Check: " + checkName);
                return; // skip kick/ban/broadcast entirely
            }

            case "rubberband" -> {
                org.bukkit.Location safe = plugin.getViolationManager()
                        .getLastSafeLocation(player.getUniqueId());
                if (safe != null) {
                    player.teleport(safe);
                    player.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
                } else {
                    if (checkName.startsWith("Fly") || checkName.startsWith("Speed")
                            || checkName.startsWith("Jetpack") || checkName.startsWith("Elytra")) {
                        Block ground = findGroundBelow(player);
                        if (ground != null) {
                            player.teleport(ground.getLocation().add(0.5, 1, 0.5));
                            player.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
                        }
                    }
                    plugin.getLogger().info(plugin.getConsolePrefix() + " RUBBERBAND: no safe location for "
                            + player.getName() + " yet.");
                }
                plugin.alertFlag(player, checkName);
                plugin.getHistoryManager().record(
                        player.getUniqueId(), player.getName(),
                        checkName, "RUBBERBAND", "Lagback");
                plugin.getLogger().info(plugin.getConsolePrefix() + " RUBBERBAND: " + player.getName()
                        + " | UUID: " + player.getUniqueId()
                        + " | Check: " + checkName);
                return;
            }

            case "kick" -> {
                player.kickPlayer(kickMessage);
                historyType     = "KICK";
                historyDuration = "Kick (no ban)";
                plugin.getLogger().warning(plugin.getConsolePrefix() + " KICKED: " + player.getName()
                        + " | UUID: " + player.getUniqueId()
                        + " | IP: " + (ip != null ? ip : "unknown")
                        + " | Check: " + checkName);
            }

            case "permanent" -> {
                String banSource = ColorUtil.strip(branding);
                Bukkit.getBanList(BanList.Type.NAME).addBan(
                        player.getName(), banReason, null, banSource);
                addUUIDBan(player.getUniqueId(), player.getName(), ip);
                if (ip != null && plugin.getConfig().getBoolean("ban.ip-ban", true))
                    Bukkit.getBanList(BanList.Type.IP).addBan(ip, banReason, null, banSource);
                player.kickPlayer(kickMessage);
                historyType     = "BAN_PERMANENT";
                historyDuration = "Permanent";
                plugin.getLogger().warning(plugin.getConsolePrefix() + " BANNED: " + player.getName()
                        + " | UUID: " + player.getUniqueId()
                        + " | IP: " + (ip != null ? ip : "unknown")
                        + " | Check: " + checkName
                        + " | Duration: PERMANENT");
            }

            default -> {
                // Temporary ban (default: 30 days)
                int durationDays = plugin.getConfig().getInt("ban.duration-days", 30);
                Date expiry = new Date(System.currentTimeMillis()
                        + TimeUnit.DAYS.toMillis(durationDays));
                String banSource = ColorUtil.strip(branding);
                Bukkit.getBanList(BanList.Type.NAME).addBan(
                        player.getName(), banReason, expiry, banSource);
                addUUIDBan(player.getUniqueId(), player.getName(), ip);
                if (ip != null && plugin.getConfig().getBoolean("ban.ip-ban", true))
                    Bukkit.getBanList(BanList.Type.IP).addBan(ip, banReason, expiry, banSource);
                player.kickPlayer(kickMessage);
                historyType     = "BAN_" + durationDays + "D";
                historyDuration = durationDays + " days";
                plugin.getLogger().warning(plugin.getConsolePrefix() + " BANNED: " + player.getName()
                        + " | UUID: " + player.getUniqueId()
                        + " | IP: " + (ip != null ? ip : "unknown")
                        + " | Check: " + checkName
                        + " | Duration: " + durationDays + " days"
                        + " | Expires: " + expiry);
            }
        }

        // Flag alert — notifies online admins with a plain-English summary
        // Also dispatches Discord webhook if enabled
        plugin.alertFlag(player, checkName);

        // Ensure Discord webhook is sent even when alerts are configured to broadcast-only on ban
        // (if discord.send-on-flag is false, alertFlag skips webhook, so we send here)
        if (!plugin.getConfig().getBoolean("discord.send-on-flag", true)
                && plugin.getDiscordWebhookManager() != null
                && plugin.getDiscordWebhookManager().isEnabled()) {
            String banIp = ip != null ? ip : getIP(player);
            String discordReason = checkName + " [" + historyType + " — " + historyDuration + "]";
            try {
                plugin.getDiscordWebhookManager().sendAlert(player, discordReason, banIp);
            } catch (Exception ignored) {}
        }

        // Record to persistent history
        plugin.getHistoryManager().record(
                player.getUniqueId(), player.getName(),
                checkName, historyType, historyDuration);

        // Broadcast to all players
        String broadcast = ColorUtil.colorize(
                broadcastTemplate.replace("{player}", player.getName()));
        Bukkit.broadcastMessage(broadcast);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UUID ban persistence
    // ─────────────────────────────────────────────────────────────────────────

    public boolean isUUIDBanned(UUID uuid) {
        return bannedUUIDs.contains(uuid);
    }

    private void addUUIDBan(UUID uuid, String name, String ip) {
        bannedUUIDs.add(uuid);
        if (ip   != null) bannedIPs.put(uuid, ip);
        if (name != null) bannedNames.put(uuid, name);
        saveUUIDBans();
    }

    /**
     * Removes all stored ban data for this UUID and also pardons the
     * matching IP from Bukkit's IP ban list (works for offline players
     * because we persisted the IP at ban time).
     *
     * @return true if the UUID was found and removed
     */
    public boolean removeUUIDBan(UUID uuid) {
        if (!bannedUUIDs.remove(uuid)) return false;

        // Clear stored IP from Bukkit's ban list
        String storedIP = bannedIPs.remove(uuid);
        if (storedIP != null) {
            BanList<?> ipBans = Bukkit.getBanList(BanList.Type.IP);
            if (ipBans.isBanned(storedIP)) {
                ipBans.pardon(storedIP);
            }
        }

        bannedNames.remove(uuid);
        saveUUIDBans();
        return true;
    }

    /**
     * Resolves a UUID from our stored name → UUID map.
     * Falls back to null if the player was never banned through this plugin.
     */
    public UUID getUUIDByName(String name) {
        for (Map.Entry<UUID, String> e : bannedNames.entrySet()) {
            if (e.getValue().equalsIgnoreCase(name)) return e.getKey();
        }
        return null;
    }

    /** Returns the stored IP for a banned UUID, or null if not recorded. */
    public String getBanIP(UUID uuid) {
        return bannedIPs.get(uuid);
    }

    private void loadUUIDBans() {
        if (!uuidBanFile.exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(uuidBanFile);

        // Load UUIDs
        for (String s : cfg.getStringList("banned")) {
            try { bannedUUIDs.add(UUID.fromString(s)); }
            catch (IllegalArgumentException ignored) {}
        }

        // Load IPs (uuid-string → ip-string)
        org.bukkit.configuration.ConfigurationSection ips = cfg.getConfigurationSection("ips");
        if (ips != null) {
            for (String key : ips.getKeys(false)) {
                try { bannedIPs.put(UUID.fromString(key), ips.getString(key)); }
                catch (IllegalArgumentException ignored) {}
            }
        }

        // Load names (uuid-string → player-name)
        org.bukkit.configuration.ConfigurationSection names = cfg.getConfigurationSection("names");
        if (names != null) {
            for (String key : names.getKeys(false)) {
                try { bannedNames.put(UUID.fromString(key), names.getString(key)); }
                catch (IllegalArgumentException ignored) {}
            }
        }

        plugin.getLogger().info("[AntiCheat] Loaded " + bannedUUIDs.size() + " UUID bans.");
    }

    private void saveUUIDBans() {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("banned", bannedUUIDs.stream().map(UUID::toString).toList());

        for (Map.Entry<UUID, String> e : bannedIPs.entrySet()) {
            cfg.set("ips." + e.getKey(), e.getValue());
        }
        for (Map.Entry<UUID, String> e : bannedNames.entrySet()) {
            cfg.set("names." + e.getKey(), e.getValue());
        }

        try { cfg.save(uuidBanFile); }
        catch (IOException e) { plugin.getLogger().warning("[AntiCheat] Failed to save UUID bans: " + e.getMessage()); }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Enforce UUID ban on join (checked from PlayerStateListener)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns a kick message if the player is UUID-banned, or null if they're clean.
     */
    public String getUUIDBanKickMessage(UUID uuid) {
        if (!isUUIDBanned(uuid)) return null;
        return ColorUtil.colorize(plugin.getConfig().getString(
                "ban.reason",
                "&cYou are banned from this server.\n\n&fReason: &cUsing a modified client."));
    }

    private Block findGroundBelow(Player player) {
        Location loc = player.getLocation();
        for (int y = loc.getBlockY(); y > loc.getWorld().getMinHeight(); y--) {
            Block b = loc.getWorld().getBlockAt(loc.getBlockX(), y, loc.getBlockZ());
            if (b.getType().isSolid()) return b;
        }
        return null;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Proxy / VPN detection (ip-api.com free tier — no key needed)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Queries ip-api.com to check if an IP is a known proxy, VPN, or hosting provider.
     * This runs synchronously — ALWAYS call from an async task.
     *
     * @return true if the IP is flagged as a proxy/VPN
     */
    private boolean isProxy(String ip) {
        try {
            String urlStr = "http://ip-api.com/json/" + ip
                    + "?fields=status,proxy,hosting,query";
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);

            if (conn.getResponseCode() != 200) return false;

            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream()))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                String json = sb.toString();

                // Simple JSON field extraction (no external library needed)
                boolean proxy   = extractBool(json, "proxy");
                boolean hosting = extractBool(json, "hosting");

                if (proxy || hosting) {
                    plugin.getLogger().info("[AntiCheat] Proxy/VPN detected for IP: " + ip
                            + " proxy=" + proxy + " hosting=" + hosting);
                    return true;
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[AntiCheat] Proxy check failed for " + ip + ": " + e.getMessage());
        }
        return false;
    }

    /** Extracts a boolean value from a simple JSON string without a library. */
    private boolean extractBool(String json, String key) {
        String search = "\"" + key + "\":true";
        return json.contains(search);
    }

    private String getIP(Player player) {
        if (player.getAddress() == null) return null;
        return player.getAddress().getAddress().getHostAddress();
    }
}
