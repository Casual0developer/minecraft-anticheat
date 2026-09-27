package dev.anticheat.managers;

import dev.anticheat.AntiCheatPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * DiscordWebhookManager — sends rich embed alerts to a Discord webhook.
 *
 * Set via /discord-webhook <url> or config.yml (discord.webhook-url).
 * All sends are async and rate-limited per-player to avoid Discord rate limits.
 */
public class DiscordWebhookManager {

    private final AntiCheatPlugin plugin;

    // Validation regex for Discord webhook URLs
    private static final Pattern WEBHOOK_PATTERN = Pattern.compile(
            "^https://(discord\\.com|discordapp\\.com)/api/webhooks/\\d+/[A-Za-z0-9_\\-]+.*$"
    );

    // Per-player cooldown to avoid spam (ms)
    private static final long COOLDOWN_MS = 350;
    private final Map<UUID, Long> lastSent = new ConcurrentHashMap<>();

    // Global rate limit: max 4 webhooks per second (Discord limit is ~5)
    private volatile long lastGlobalSend = 0;

    private static final String DEFAULT_USERNAME = "AntiCheat";
    private static final String DEFAULT_AVATAR = "https://cdn.discordapp.com/attachments/0/0/anticheat.png";

    private static final SimpleDateFormat DATE_FMT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    public DiscordWebhookManager(AntiCheatPlugin plugin) {
        this.plugin = plugin;
    }

    // ── Config helpers ────────────────────────────────────────────────────

    public String getWebhookUrl() {
        String url = plugin.getConfig().getString("discord.webhook-url", "");
        if (url == null) return "";
        return url.trim();
    }

    public boolean isEnabled() {
        String url = getWebhookUrl();
        return !url.isEmpty() && isValidUrl(url)
                && plugin.getConfig().getBoolean("discord.enabled", true);
    }

    public void setWebhookUrl(String url) {
        if (url == null) url = "";
        url = url.trim();
        plugin.getConfig().set("discord.webhook-url", url);
        plugin.getConfig().set("discord.enabled", !url.isEmpty());
        plugin.saveConfig();
        if (!url.isEmpty()) {
            plugin.getLogger().info("[AntiCheat] Discord webhook set to: " + maskUrl(url));
        } else {
            plugin.getLogger().info("[AntiCheat] Discord webhook cleared.");
        }
    }

    public boolean isValidUrl(String url) {
        if (url == null || url.isEmpty()) return false;
        // Allow any https webhook for flexibility (some use canary/ptb or custom proxies)
        if (url.startsWith("https://discord.com/api/webhooks/")
                || url.startsWith("https://discordapp.com/api/webhooks/")
                || url.startsWith("https://canary.discord.com/api/webhooks/")
                || url.startsWith("https://ptb.discord.com/api/webhooks/")) {
            return WEBHOOK_PATTERN.matcher(url).matches() || url.contains("/api/webhooks/");
        }
        // Also allow generic https webhooks (for testing/webhook.site)
        // but only if user explicitly allows via config?
        // For now strict to discord domains.
        return false;
    }

    public String maskUrl(String url) {
        if (url == null || url.length() < 20) return "***";
        int slash = url.lastIndexOf('/');
        if (slash > 0) {
            return url.substring(0, Math.min(42, slash)) + "/***";
        }
        return url.substring(0, 20) + "***";
    }

    // ── Public send API ──────────────────────────────────────────────────

    /**
     * Send a flag alert embed. Called from AntiCheatPlugin.alertFlag and BanManager.
     * Rate-limited per player. Always async.
     */
    public void sendAlert(Player player, String reason, String playerIp) {
        if (!isEnabled()) return;
        if (player == null) return;
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();
        Long last = lastSent.get(id);
        if (last != null && now - last < COOLDOWN_MS) return;
        lastSent.put(id, now);

        // Global throttle
        synchronized (this) {
            if (now - lastGlobalSend < 250) {
                // schedule slightly delayed
                long delay = 250 - (now - lastGlobalSend);
                Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () ->
                        sendAlertInternal(player.getName(), id.toString(), playerIp, reason), delay / 50 + 1);
                return;
            }
            lastGlobalSend = now;
        }

        String name = player.getName();
        String uuid = id.toString();
        // Capture values async
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () ->
                sendAlertInternal(name, uuid, playerIp, reason));
    }

    /**
     * Synchronous-ish helper that captures all data before async.
     * Use when player may be offline.
     */
    public void sendAlertOffline(String playerName, UUID uuid, String ip, String reason) {
        if (!isEnabled()) return;
        long now = System.currentTimeMillis();
        if (uuid != null) {
            Long last = lastSent.get(uuid);
            if (last != null && now - last < COOLDOWN_MS) return;
            lastSent.put(uuid, now);
        }
        synchronized (this) {
            if (now - lastGlobalSend < 250) {
                lastGlobalSend = now;
            } else {
                lastGlobalSend = now;
            }
        }
        String uuidStr = uuid != null ? uuid.toString() : "unknown";
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () ->
                sendAlertInternal(playerName, uuidStr, ip, reason));
    }

    /**
     * Send a test embed to verify webhook connectivity.
     */
    public void sendTest(org.bukkit.command.CommandSender sender) {
        if (!isEnabled()) {
            sender.sendMessage(dev.anticheat.utils.ColorUtil.colorize("&cNo webhook configured. Use /discord-webhook <url>"));
            return;
        }
        String senderName = sender.getName();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean ok = postJson(getWebhookUrl(), buildTestJson(senderName));
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (ok) {
                    sender.sendMessage(dev.anticheat.utils.ColorUtil.colorize("&aTest message sent to Discord webhook!"));
                } else {
                    sender.sendMessage(dev.anticheat.utils.ColorUtil.colorize("&cFailed to send test. Check console for details & verify URL."));
                }
            });
        });
    }

    // ── Internal builders ─────────────────────────────────────────────────

    private void sendAlertInternal(String playerName, String uuid, String ip, String reason) {
        String url = getWebhookUrl();
        if (url.isEmpty()) return;

        String serverName = plugin.getConfig().getString("discord.server-name", "");
        if (serverName == null) serverName = "";
        serverName = serverName.trim();

        String json = buildFlagJson(playerName, uuid, ip, reason, serverName);
        boolean ok = postJson(url, json);
        if (!ok && plugin.getConfig().getBoolean("debug.enabled", false)) {
            plugin.getLogger().warning("[AntiCheat] Discord webhook POST failed for " + playerName);
        }
    }

    private String buildFlagJson(String playerName, String uuid, String ip, String reason, String serverName) {
        String timestamp = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").format(new Date());
        String safeIp = ip != null ? ip : "unknown";
        String safeReason = escapeJson(reason);
        String safeName = escapeJson(playerName);
        String safeServer = escapeJson(serverName);
        String branding = plugin.getDisplayName();
        String safeBranding = escapeJson(branding);

        // Color: red for bans/flags
        int color = 15158332; // #E74C3C
        if (reason != null) {
            String low = reason.toLowerCase();
            if (low.contains("alert")) color = 3447003; // blue
            else if (low.contains("rubberband")) color = 10181046; // purple
            else if (low.contains("kick")) color = 16705372; // orange
        }

        StringBuilder fields = new StringBuilder();
        fields.append("{\"name\":\"Player\",\"value\":\"`").append(safeName).append("`\",\"inline\":true},");
        fields.append("{\"name\":\"UUID\",\"value\":\"`").append(escapeJson(uuid)).append("`\",\"inline\":true},");
        fields.append("{\"name\":\"IP Address\",\"value\":\"`").append(escapeJson(safeIp)).append("`\",\"inline\":true},");
        fields.append("{\"name\":\"Reason\",\"value\":\"`").append(safeReason).append("`\",\"inline\":false},");
        fields.append("{\"name\":\"Time\",\"value\":\"<t:").append(System.currentTimeMillis() / 1000).append(":F>\",\"inline\":false}");
        if (!safeServer.isEmpty()) {
            fields.append(",{\"name\":\"Server\",\"value\":\"`").append(safeServer).append("`\",\"inline\":true}");
        }

        String cfgUsername = plugin.getConfig().getString("discord.username", "");
        String username;
        if (cfgUsername == null || cfgUsername.trim().isEmpty() || cfgUsername.trim().equals(DEFAULT_USERNAME)) {
            // Use custom branding if set, otherwise default
            username = branding;
        } else {
            username = cfgUsername.trim();
        }
        username = escapeJson(username);

        String avatar = plugin.getConfig().getString("discord.avatar-url", "");
        String avatarField = "";
        if (avatar != null && !avatar.trim().isEmpty() && avatar.startsWith("http")) {
            avatarField = ",\"avatar_url\":\"" + escapeJson(avatar.trim()) + "\"";
        }

        return "{"
                + "\"username\":\"" + username + "\""
                + avatarField
                + ",\"embeds\":[{"
                + "\"title\":\"" + safeBranding + " Alert\","
                + "\"description\":\"Player flagged by " + safeBranding + "\","
                + "\"color\":" + color + ","
                + "\"fields\":[" + fields + "],"
                + "\"thumbnail\":{\"url\":\"https://mc-heads.net/avatar/" + safeName + "/64\"},"
                + "\"footer\":{\"text\":\"" + safeBranding + " • Bukkit 1.21.1\"},"
                + "\"timestamp\":\"" + timestamp + "\""
                + "}]"
                + "}";
    }

    private String buildTestJson(String senderName) {
        String timestamp = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").format(new Date());
        String safeSender = escapeJson(senderName);
        String branding = plugin.getDisplayName();
        String cfgUsername = plugin.getConfig().getString("discord.username", "");
        String username;
        if (cfgUsername == null || cfgUsername.trim().isEmpty() || cfgUsername.trim().equals(DEFAULT_USERNAME)) {
            username = branding;
        } else {
            username = cfgUsername.trim();
        }
        return "{"
                + "\"username\":\"" + escapeJson(username) + "\","
                + "\"embeds\":[{"
                + "\"title\":\"" + escapeJson(branding) + " — Test Webhook\","
                + "\"description\":\"✅ Webhook is working! Triggered by **" + safeSender + "**.\","
                + "\"color\":3066993,"
                + "\"fields\":["
                + "{\"name\":\"Status\",\"value\":\"Connected and ready to receive alerts from " + escapeJson(branding) + "\",\"inline\":false},"
                + "{\"name\":\"Time\",\"value\":\"<t:" + (System.currentTimeMillis() / 1000) + ":F>\",\"inline\":false}"
                + "],"
                + "\"footer\":{\"text\":\"" + escapeJson(branding) + " Test\"},"
                + "\"timestamp\":\"" + timestamp + "\""
                + "}]"
                + "}";
    }

    // ── HTTP POST ─────────────────────────────────────────────────────────

    private boolean postJson(String urlStr, String json) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("User-Agent", "AntiCheat-Plugin/1.0");

            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            conn.setRequestProperty("Content-Length", String.valueOf(body.length));

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body);
                os.flush();
            }

            int code = conn.getResponseCode();
            // Discord returns 200 or 204 on success
            if (code == 200 || code == 204) return true;

            // Read error body for debugging
            String error = "";
            try (var is = conn.getErrorStream()) {
                if (is != null) error = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            } catch (Exception ignored) {}
            plugin.getLogger().warning("[AntiCheat] Discord webhook returned HTTP " + code + ": " + error);
            return false;
        } catch (Exception e) {
            plugin.getLogger().warning("[AntiCheat] Discord webhook error: " + e.getMessage());
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    public void removePlayer(UUID id) {
        lastSent.remove(id);
    }
}
