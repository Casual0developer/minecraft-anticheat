package dev.anticheat.commands;

import dev.anticheat.AntiCheatPlugin;
import dev.anticheat.managers.HistoryManager;
import dev.anticheat.utils.ColorUtil;
import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class AntiCheatCommand implements CommandExecutor, TabCompleter {

    private final AntiCheatPlugin plugin;

    public AntiCheatCommand(AntiCheatPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String prefix = plugin.getPrefix();

        if (!sender.hasPermission("anticheat.admin")) {
            String noperm = plugin.getConfig().getString("messages.no-permission", "&cNo permission.");
            sender.sendMessage(prefix + ColorUtil.colorize(noperm));
            return true;
        }

        if (args.length == 0) { sendHelp(sender); return true; }

        switch (args[0].toLowerCase()) {

            case "reload" -> {
                plugin.reloadConfig();
                sender.sendMessage(prefix + ColorUtil.colorize("&aReloaded."));
            }

            case "nickname", "nick", "name", "branding", "brand" -> handleNickname(sender, args, prefix);

            case "discord" -> handleDiscord(sender, args, prefix);

            case "status" -> {
                String mode = plugin.getConfig().getString("punishment.mode", "temp");
                int days    = plugin.getConfig().getInt("ban.duration-days", 30);
                String modeDisplay = switch (mode) {
                    case "kick"      -> "&eKick only";
                    case "permanent" -> "&4Permanent ban";
                    case "alert"     -> "&bAlerts only";
                    default          -> "&cTemp ban &8(&f" + days + "d&8)";
                };
                String branding = plugin.getDisplayName();
                sender.sendMessage(ColorUtil.colorize("&8--- &c" + branding + " Status &8---"));
                sender.sendMessage(ColorUtil.colorize("&7Nickname:        &f" + branding + (branding.equals("AntiCheat") ? " &8(default)" : " &8(custom)")));
                sender.sendMessage(ColorUtil.colorize("&7Version:          &f" + plugin.getDescription().getVersion()));
                sender.sendMessage(ColorUtil.colorize("&7Punishment Mode:  " + modeDisplay));
                sender.sendMessage(ColorUtil.colorize("&7IP Ban:           &f" + plugin.getConfig().getBoolean("ban.ip-ban", true)));
                sender.sendMessage(ColorUtil.colorize("&7Proxy Check:      &f" + plugin.getConfig().getBoolean("ban.proxy-check.enabled", false)));
                sender.sendMessage(ColorUtil.colorize("&7Debug Mode:       &f" + plugin.getConfig().getBoolean("debug.enabled", false)));
                String wh = plugin.getDiscordWebhookManager().isEnabled()
                        ? "&aEnabled &8(" + plugin.getDiscordWebhookManager().maskUrl(plugin.getDiscordWebhookManager().getWebhookUrl()) + "&8)"
                        : "&cDisabled";
                sender.sendMessage(ColorUtil.colorize("&7Discord Webhook: &f" + wh));
                sender.sendMessage(ColorUtil.colorize("&7(Use /anticheat punishments to manage punishment & alert settings)"));
                sender.sendMessage(ColorUtil.colorize("&7Online Players:   &f" + Bukkit.getOnlinePlayers().size()));
                sender.sendMessage(ColorUtil.colorize("&7Whitelisted:      &f" + plugin.getWhitelistManager().getAll().size()));
            }

            case "punishments" -> handlePunishments(sender, args, prefix);

            case "whitelist" -> {
                if (args.length < 2) {
                    sender.sendMessage(ColorUtil.colorize(prefix + "&cUsage: /anticheat whitelist <add|remove|list> [player]"));
                    return true;
                }
                handleWhitelist(sender, args, prefix);
            }

            case "unban" -> {
                if (args.length < 2) {
                    sender.sendMessage(ColorUtil.colorize(prefix + "&cUsage: /anticheat unban <player>"));
                    return true;
                }
                handleUnban(sender, args[1], prefix);
            }

            case "history" -> {
                if (args.length < 2) {
                    sender.sendMessage(ColorUtil.colorize(prefix + "&cUsage: /anticheat history <player>"));
                    return true;
                }
                handleHistory(sender, args[1], prefix);
            }

            default -> sendHelp(sender);
        }

        return true;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Punishments
    // ─────────────────────────────────────────────────────────────────────────

    private void handlePunishments(CommandSender sender, String[] args, String prefix) {
        // /anticheat punishments               → show current mode
        // /anticheat punishments kick          → set kick mode
        // /anticheat punishments ban 30d       → set temp ban
        // /anticheat punishments ban permanent → set permanent ban

        if (args.length < 2) {
            sendPunishmentStatus(sender, prefix);
            return;
        }

        switch (args[1].toLowerCase()) {

            case "kick" -> {
                setPunishMode("kick");
                sender.sendMessage(ColorUtil.colorize(prefix
                        + "&aPunishment set to &eKick&a."));
            }

            case "ban" -> {
                if (args.length < 3) {
                    sender.sendMessage(ColorUtil.colorize(prefix
                            + "&cUsage: /anticheat punishments ban <30d|permanent>"));
                    return;
                }
                switch (args[2].toLowerCase()) {
                    case "30d", "temp", "temporary" -> {
                        setPunishMode("temp");
                        int days = plugin.getConfig().getInt("ban.duration-days", 30);
                        sender.sendMessage(ColorUtil.colorize(prefix
                                + "&aPunishment set to &cTemp Ban &8(&f" + days + " days&8)&a."));
                    }
                    case "permanent", "perm" -> {
                        setPunishMode("permanent");
                        sender.sendMessage(ColorUtil.colorize(prefix
                                + "&aPunishment set to &4Permanent Ban&a."));
                    }
                    default -> sender.sendMessage(ColorUtil.colorize(prefix
                            + "&cUnknown option — use: 30d or permanent"));
                }
            }

            case "alerts" -> {
                if (args.length >= 3) {
                    // /anticheat punishments alerts <all|staff|ops>  → set recipient
                    String who = args[2].toLowerCase();
                    if (!who.equals("all") && !who.equals("staff") && !who.equals("ops")) {
                        sender.sendMessage(ColorUtil.colorize(prefix
                                + "&cUsage: /anticheat punishments alerts <all|staff|ops>"));
                        return;
                    }
                    plugin.getConfig().set("flags.alerts-recipient", who);
                    plugin.saveConfig();
                    sender.sendMessage(ColorUtil.colorize(prefix
                            + "&aFlag alerts will now be sent to: &f" + who));
                } else {
                    // /anticheat punishments alerts  → set punishment mode to alert-only
                    setPunishMode("alert");
                    sender.sendMessage(ColorUtil.colorize(prefix
                            + "&aPunishment set to &bAlerts only&a. "
                            + "&7(Admins are notified but players are NOT kicked or banned.)"));
                }
            }

            case "rubberband" -> {
                setPunishMode("rubberband");
                sender.sendMessage(ColorUtil.colorize(prefix
                        + "&aPunishment set to &dRubberband&a. "
                        + "&7(Players are teleported back to their last safe position.)"));
            }

            default -> sendPunishmentStatus(sender, prefix);
        }
    }

    private void sendPunishmentStatus(CommandSender sender, String prefix) {
        String mode = plugin.getConfig().getString("punishment.mode", "temp");
        int days    = plugin.getConfig().getInt("ban.duration-days", 30);
        String display = switch (mode) {
            case "kick"       -> "&eKick only &7(no ban)";
            case "permanent"  -> "&4Permanent ban";
            case "alert"      -> "&bAlerts only &7(no action)";
            case "rubberband" -> "&dRubberband &7(lagback to last safe position)";
            default           -> "&cTemp ban &8(&f" + days + "d&8)";
        };
        String recipient = plugin.getConfig().getString("flags.alerts-recipient", "staff");
        sender.sendMessage(ColorUtil.colorize("&8--- &cPunishment Settings &8---"));
        sender.sendMessage(ColorUtil.colorize("&7Active mode:      " + display));
        sender.sendMessage(ColorUtil.colorize("&7Alert recipient:  &f" + recipient
                + " &8(&7/anticheat punishments alerts <all|staff|ops>&8)"));
        sender.sendMessage(ColorUtil.colorize("  &f/anticheat punishments kick               &7- Kick, no ban"));
        sender.sendMessage(ColorUtil.colorize("  &f/anticheat punishments ban 30d            &7- Temp ban (" + days + "d)"));
        sender.sendMessage(ColorUtil.colorize("  &f/anticheat punishments ban permanent      &7- Permanent ban"));
        sender.sendMessage(ColorUtil.colorize("  &f/anticheat punishments alerts             &7- Alert only, no action"));
        sender.sendMessage(ColorUtil.colorize("  &f/anticheat punishments rubberband         &7- Lagback to last safe pos"));
        sender.sendMessage(ColorUtil.colorize("  &f/anticheat punishments alerts <all|staff|ops> &7- Who gets alerts"));
    }

    private void setPunishMode(String mode) {
        plugin.getConfig().set("punishment.mode", mode);
        plugin.saveConfig();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // History
    // ─────────────────────────────────────────────────────────────────────────

    private void handleHistory(CommandSender sender, String name, String prefix) {
        UUID uuid = null;

        Player online = Bukkit.getPlayer(name);
        if (online != null) {
            uuid = online.getUniqueId();
        } else {
            @SuppressWarnings("deprecation")
            OfflinePlayer op = Bukkit.getOfflinePlayer(name);
            if (op != null && op.hasPlayedBefore()) uuid = op.getUniqueId();
        }
        if (uuid == null) uuid = plugin.getHistoryManager().findUUIDByName(name);

        if (uuid == null) {
            sender.sendMessage(ColorUtil.colorize(prefix + "&cPlayer &f" + name + " &cnot found."));
            return;
        }

        List<HistoryManager.BanRecord> records = plugin.getHistoryManager().getHistory(uuid);
        if (records.isEmpty()) {
            sender.sendMessage(ColorUtil.colorize(prefix + "&f" + name + " &7has no punishment history."));
            return;
        }

        sender.sendMessage(ColorUtil.colorize("&8--- &cPunishment History: &f" + name + " &8---"));
        sender.sendMessage(ColorUtil.colorize("&7Total: &f" + records.size()));
        for (int i = 0; i < records.size(); i++) {
            HistoryManager.BanRecord r = records.get(i);
            String typeColor = switch (r.type()) {
                case "KICK"          -> "&e";
                case "BAN_PERMANENT" -> "&4";
                default              -> "&c";
            };
            sender.sendMessage(ColorUtil.colorize(
                    "&8#" + (i + 1) + " &7" + r.date()
                    + "  &fCheck: &c" + r.check()
                    + "  &fPunishment: " + typeColor + r.duration()));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Unban
    // ─────────────────────────────────────────────────────────────────────────

    private void handleUnban(CommandSender sender, String name, String prefix) {
        boolean removedName = false, removedUUID = false, removedIP = false;

        BanList<?> nameBans = Bukkit.getBanList(BanList.Type.NAME);
        if (nameBans.isBanned(name)) { nameBans.pardon(name); removedName = true; }

        UUID uuid = plugin.getBanManager().getUUIDByName(name);
        if (uuid == null) {
            @SuppressWarnings("deprecation")
            OfflinePlayer op = Bukkit.getOfflinePlayer(name);
            if (op != null && op.hasPlayedBefore()) uuid = op.getUniqueId();
        }

        if (uuid != null) {
            if (plugin.getBanManager().removeUUIDBan(uuid)) { removedUUID = true; removedIP = true; }
            Player onlineP = Bukkit.getPlayer(uuid);
            if (onlineP != null && onlineP.getAddress() != null) {
                String liveIP = onlineP.getAddress().getAddress().getHostAddress();
                BanList<?> ipBans = Bukkit.getBanList(BanList.Type.IP);
                if (ipBans.isBanned(liveIP)) { ipBans.pardon(liveIP); removedIP = true; }
            }
        }

        if (removedName || removedUUID || removedIP) {
            StringBuilder detail = new StringBuilder();
            if (removedName) detail.append("name");
            if (removedUUID) detail.append(detail.length() > 0 ? " + " : "").append("UUID");
            if (removedIP)   detail.append(detail.length() > 0 ? " + " : "").append("IP");
            sender.sendMessage(ColorUtil.colorize(prefix + "&aUnbanned &f" + name
                    + " &8(&7" + detail + "&8)&a."));
            plugin.getLogger().info("[AntiCheat] " + sender.getName()
                    + " unbanned: " + name + " (" + detail + ")");
        } else {
            sender.sendMessage(ColorUtil.colorize(prefix + "&e" + name
                    + " &7does not have an active AntiCheat ban."));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Whitelist
    // ─────────────────────────────────────────────────────────────────────────

    private void handleWhitelist(CommandSender sender, String[] args, String prefix) {
        String sub = args[1].toLowerCase();

        if (sub.equals("list")) {
            var all = plugin.getWhitelistManager().getAll();
            if (all.isEmpty()) {
                sender.sendMessage(ColorUtil.colorize(prefix + "&7Whitelist is empty."));
            } else {
                sender.sendMessage(ColorUtil.colorize(prefix + "&7Whitelisted (" + all.size() + "):"));
                for (UUID uuid : all) {
                    Player online = Bukkit.getPlayer(uuid);
                    sender.sendMessage(ColorUtil.colorize("  &f"
                            + (online != null ? online.getName() : uuid.toString())));
                }
            }
            return;
        }

        if (args.length < 3) {
            sender.sendMessage(ColorUtil.colorize(prefix
                    + "&cUsage: /anticheat whitelist <add|remove> <player>"));
            return;
        }

        Player target = Bukkit.getPlayer(args[2]);
        switch (sub) {
            case "add" -> {
                if (target == null) {
                    sender.sendMessage(ColorUtil.colorize(prefix + "&cPlayer must be online to whitelist.")); return;
                }
                boolean added = plugin.getWhitelistManager().add(target.getUniqueId());
                sender.sendMessage(ColorUtil.colorize(prefix + (added
                        ? "&aAdded &f" + target.getName() + " &ato the bypass whitelist."
                        : "&e" + target.getName() + " is already whitelisted.")));
            }
            case "remove" -> {
                if (target == null) {
                    sender.sendMessage(ColorUtil.colorize(prefix + "&cPlayer must be online to remove.")); return;
                }
                boolean removed = plugin.getWhitelistManager().remove(target.getUniqueId());
                sender.sendMessage(ColorUtil.colorize(prefix + (removed
                        ? "&cRemoved &f" + target.getName() + " &cfrom the bypass whitelist."
                        : "&e" + target.getName() + " was not whitelisted.")));
            }
            default -> sender.sendMessage(ColorUtil.colorize(prefix + "&cUse add, remove, or list."));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Discord (moved from /discord-webhook)
    // ─────────────────────────────────────────────────────────────────────────

    private void handleDiscord(CommandSender sender, String[] args, String prefix) {
        // Supported forms:
        //   /anticheat discord                              → status/help
        //   /anticheat discord status                       → status
        //   /anticheat discord webhook                      → status
        //   /anticheat discord webhook test                 → test
        //   /anticheat discord webhook clear                → clear
        //   /anticheat discord webhook <url>                → set url
        //   /anticheat discord <url>                        → set url (shorthand)
        //   /anticheat discord test|status|clear            → same without webhook keyword
        var discord = plugin.getDiscordWebhookManager();

        // No extra args → show status/help
        if (args.length == 1) {
            sendDiscordHelp(sender, prefix);
            return;
        }

        // Normalize: if args[1] is "webhook" shift one token
        int offset = 1;
        if (args.length > 1 && args[1].equalsIgnoreCase("webhook")) {
            if (args.length == 2) {
                sendDiscordHelp(sender, prefix);
                return;
            }
            offset = 2;
        }

        // If only one token after shift, it is either subcommand or url
        if (args.length == offset + 1) {
            String token = args[offset];
            String low = token.toLowerCase();
            if (low.equals("test") || low.equals("status") || low.equals("info") || low.equals("show")
                    || low.equals("clear") || low.equals("remove") || low.equals("disable") || low.equals("help")) {
                // Delegate to existing DiscordWebhookCommand logic
                dispatchDiscord(sender, new String[]{token});
                return;
            } else {
                // Treat as URL
                dispatchDiscord(sender, new String[]{token});
                return;
            }
        }

        // Multiple tokens after shift → join as URL (URLs never contain spaces, but be safe)
        if (args.length > offset + 1) {
            // If first token after shift is test/status/clear/help, delegate those
            String first = args[offset].toLowerCase();
            if (first.equals("test") || first.equals("status") || first.equals("clear") || first.equals("help") || first.equals("info") || first.equals("show")) {
                dispatchDiscord(sender, new String[]{args[offset]});
                return;
            }
            // Otherwise whole remainder is URL (may contain spaces if pasted with <>)
            String url = String.join(" ", java.util.Arrays.copyOfRange(args, offset, args.length));
            dispatchDiscord(sender, new String[]{url});
            return;
        }

        sendDiscordHelp(sender, prefix);
    }

    private void dispatchDiscord(CommandSender sender, String[] discordArgs) {
        // Reuse existing DiscordWebhookCommand implementation so logic stays in one place
        dev.anticheat.commands.DiscordWebhookCommand dw = new dev.anticheat.commands.DiscordWebhookCommand(plugin);
        // Build a dummy Command object — DiscordWebhookCommand only uses sender and args, not the Command
        // We can create a minimal mock via Bukkit's plugin command, but we can also call handle directly
        // Instead, invoke onCommand with null command (it doesn't use the Command param except for tab)
        try {
            dw.onCommand(sender, null, "anticheat", discordArgs);
        } catch (Exception e) {
            sender.sendMessage(ColorUtil.colorize(plugin.getPrefix() + "&cDiscord error: " + e.getMessage()));
        }
    }

    private void sendDiscordHelp(CommandSender sender, String prefix) {
        String branding = plugin.getDisplayName();
        sender.sendMessage(ColorUtil.colorize("&8--- &c" + branding + " Discord &8---"));
        sender.sendMessage(ColorUtil.colorize("&7Current webhook: &f" + (plugin.getDiscordWebhookManager().isEnabled()
                ? plugin.getDiscordWebhookManager().maskUrl(plugin.getDiscordWebhookManager().getWebhookUrl())
                : "&cNot set")));
        sender.sendMessage(ColorUtil.colorize("&f/anticheat discord webhook <url> &7- Set Discord webhook URL"));
        sender.sendMessage(ColorUtil.colorize("&f/anticheat discord webhook test &7- Send test embed"));
        sender.sendMessage(ColorUtil.colorize("&f/anticheat discord webhook status &7- Show config"));
        sender.sendMessage(ColorUtil.colorize("&f/anticheat discord webhook clear &7- Remove webhook"));
        sender.sendMessage(ColorUtil.colorize("&7Shorthand: &f/anticheat discord <url> &7 or &f/anticheat discord test"));
        sender.sendMessage(ColorUtil.colorize("&7Example: &8» &f/anticheat discord webhook https://discord.com/api/webhooks/123/abc"));
        sender.sendMessage(ColorUtil.colorize("&7Legacy: &f/discord-webhook &7still works"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Nickname
    // ─────────────────────────────────────────────────────────────────────────

    private void handleNickname(CommandSender sender, String[] args, String prefix) {
        // /anticheat nickname                    → show current
        // /anticheat nickname <name>             → set (supports &c colors, spaces)
        // /anticheat nickname clear              → reset to default
        if (args.length == 1) {
            String current = plugin.getDisplayName();
            boolean custom = !current.equals("AntiCheat");
            sender.sendMessage(ColorUtil.colorize("&8--- &c" + plugin.getDisplayName() + " Nickname &8---"));
            sender.sendMessage(ColorUtil.colorize("&7Current: &f" + current + (custom ? " &8(custom)" : " &8(default AntiCheat)")));
            sender.sendMessage(ColorUtil.colorize("&7Preview: " + plugin.getPrefix() + ColorUtil.colorize("&7Sample alert / flag prefix")));
            sender.sendMessage(ColorUtil.colorize("&7Usage:"));
            sender.sendMessage(ColorUtil.colorize("&f  /anticheat nickname <name> &8- Set custom name (supports &c colors)"));
            sender.sendMessage(ColorUtil.colorize("&f  /anticheat nickname clear &8- Reset to default"));
            sender.sendMessage(ColorUtil.colorize("&7Examples:"));
            sender.sendMessage(ColorUtil.colorize("&8  » &f/anticheat nickname Sentinel"));
            sender.sendMessage(ColorUtil.colorize("&8  » &f/anticheat nickname &cSentinel &8(&c red&8)"));
            sender.sendMessage(ColorUtil.colorize("&8  » &f/anticheat nickname &6My &eServer &cAC"));
            sender.sendMessage(ColorUtil.colorize("&7Shows in: &femails, console, in-game alerts, broadcasts, bans & Discord embeds"));
            return;
        }

        // clear / reset / default
        if (args.length == 2 && (args[1].equalsIgnoreCase("clear") || args[1].equalsIgnoreCase("reset") || args[1].equalsIgnoreCase("default"))) {
            plugin.getConfig().set("branding.nickname", "");
            plugin.getConfig().set("settings.nickname", "");
            plugin.getConfig().set("nickname", "");
            plugin.saveConfig();
            sender.sendMessage(prefix + ColorUtil.colorize("&aNickname reset to default &fAntiCheat&a."));
            plugin.getLogger().info(plugin.getConsolePrefix() + " Nickname reset by " + sender.getName() + " → AntiCheat (default)");
            return;
        }

        // Join remaining args as nickname (allows spaces and color codes)
        String rawNick = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)).trim();
        // Strip surrounding quotes if user typed "..."
        if ((rawNick.startsWith("\"") && rawNick.endsWith("\"")) || (rawNick.startsWith("'") && rawNick.endsWith("'"))) {
            rawNick = rawNick.substring(1, rawNick.length() - 1).trim();
        }
        if (rawNick.isEmpty()) {
            sender.sendMessage(ColorUtil.colorize(prefix + "&cUsage: /anticheat nickname <name>  &8or &f/anticheat nickname clear"));
            return;
        }
        // Length guard: visible chars (strip colors) max 32
        String stripped = ColorUtil.strip(rawNick);
        if (stripped.length() > 32) {
            sender.sendMessage(ColorUtil.colorize(prefix + "&cNickname too long! Max 32 visible chars (yours: " + stripped.length() + ")."));
            return;
        }
        if (stripped.length() < 2) {
            sender.sendMessage(ColorUtil.colorize(prefix + "&cNickname too short! Min 2 chars."));
            return;
        }
        // Disallow line breaks and section sign directly
        if (rawNick.contains("\n") || rawNick.contains("§")) {
            sender.sendMessage(ColorUtil.colorize(prefix + "&cNickname cannot contain line breaks or §. Use &c for colors."));
            return;
        }

        plugin.getConfig().set("branding.nickname", rawNick);
        // Clear legacy keys to avoid confusion
        plugin.getConfig().set("settings.nickname", "");
        plugin.getConfig().set("nickname", "");
        plugin.saveConfig();

        String preview = ColorUtil.colorize("&8[" + rawNick + "&8]");
        // If rawNick already contains & colors, preview will be colorized accordingly
        sender.sendMessage(prefix + ColorUtil.colorize("&aNickname set to ") + preview + ColorUtil.colorize(" &a→ &f" + rawNick));
        sender.sendMessage(ColorUtil.colorize("&7Now appears in: &fconsole, alerts, broadcasts, bans & Discord embeds"));
        sender.sendMessage(ColorUtil.colorize("&7Preview alert: ") + plugin.getPrefix() + ColorUtil.colorize("&c⚑ &fSteve &7flagged for &cFlight"));
        // Send a test discord embed note if webhook enabled
        if (plugin.getDiscordWebhookManager().isEnabled()) {
            sender.sendMessage(ColorUtil.colorize("&7Discord webhook is &aenabled&7 — next flag will use new name &f" + rawNick));
        }
        plugin.getLogger().info(plugin.getConsolePrefix() + " Nickname set by " + sender.getName() + " → " + rawNick + " (stripped: " + stripped + ")");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Help
    // ─────────────────────────────────────────────────────────────────────────

    private void sendHelp(CommandSender sender) {
        String branding = plugin.getDisplayName();
        sender.sendMessage(ColorUtil.colorize("&8--- &c" + branding + " Commands &8---"));
        sender.sendMessage(ColorUtil.colorize("&f/anticheat reload                    &7- Reload config"));
        sender.sendMessage(ColorUtil.colorize("&f/anticheat status                    &7- Plugin status (shows nickname + webhook)"));
        sender.sendMessage(ColorUtil.colorize("&f/anticheat nickname <name>           &7- Set custom name &8(e.g. Sentinel)"));
        sender.sendMessage(ColorUtil.colorize("&f/anticheat nickname clear            &7- Reset name to AntiCheat"));
        sender.sendMessage(ColorUtil.colorize("&f/anticheat discord webhook <url>     &7- Set Discord webhook"));
        sender.sendMessage(ColorUtil.colorize("&f/anticheat discord webhook test      &7- Test Discord embed"));
        sender.sendMessage(ColorUtil.colorize("&f/anticheat discord webhook status    &7- Show webhook status"));
        sender.sendMessage(ColorUtil.colorize("&f/anticheat punishments               &7- View / change punishment mode"));
        sender.sendMessage(ColorUtil.colorize("&f/anticheat history <player>          &7- Punishment history"));
        sender.sendMessage(ColorUtil.colorize("&f/anticheat unban <player>            &7- Remove all bans"));
        sender.sendMessage(ColorUtil.colorize("&f/anticheat whitelist add <player>    &7- Add to bypass whitelist"));
        sender.sendMessage(ColorUtil.colorize("&f/anticheat whitelist remove <player> &7- Remove from whitelist"));
        sender.sendMessage(ColorUtil.colorize("&f/anticheat whitelist list            &7- List whitelisted players"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Tab complete
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command,
                                      String alias, String[] args) {
        if (!sender.hasPermission("anticheat.admin")) return List.of();

        if (args.length == 1)
            return filter(List.of("reload", "status", "punishments", "history",
                    "unban", "whitelist", "nickname", "discord"), args[0]);

        if (args.length == 2) return switch (args[0].toLowerCase()) {
            case "whitelist"   -> filter(List.of("add", "remove", "list"), args[1]);
            case "unban"       -> bannedNames(args[1]);
            case "history"     -> allKnownNames(args[1]);
            case "punishments" -> filter(List.of("kick", "ban", "alerts", "rubberband"), args[1]);
            case "nickname"    -> filter(List.of("clear", "reset", "Sentinel", "Vanguard", "Guardian"), args[1]);
            case "discord"     -> filter(List.of("webhook", "test", "status", "clear"), args[1]);
            default            -> List.of();
        };

        if (args.length == 3) {
            if (args[0].equalsIgnoreCase("whitelist"))   return onlineNames(args[2]);
            if (args[0].equalsIgnoreCase("punishments")) {
                if (args[1].equalsIgnoreCase("ban"))    return filter(List.of("30d", "permanent"), args[2]);
                if (args[1].equalsIgnoreCase("alerts")) return filter(List.of("all", "staff", "ops"), args[2]);
            }
            if (args[0].equalsIgnoreCase("discord")) {
                if (args[1].equalsIgnoreCase("webhook")) return filter(List.of("test", "status", "clear", "https://discord.com/api/webhooks/"), args[2]);
            }
        }
        if (args.length == 4) {
            if (args[0].equalsIgnoreCase("discord") && args[1].equalsIgnoreCase("webhook")) {
                return filter(List.of("test", "status", "clear"), args[3]);
            }
        }
        return List.of();
    }

    private List<String> filter(List<String> list, String partial) {
        List<String> r = new ArrayList<>();
        for (String s : list)
            if (s.toLowerCase().startsWith(partial.toLowerCase())) r.add(s);
        return r;
    }

    private List<String> onlineNames(String partial) {
        List<String> r = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers())
            if (p.getName().toLowerCase().startsWith(partial.toLowerCase())) r.add(p.getName());
        return r;
    }

    private List<String> bannedNames(String partial) {
        List<String> r = new ArrayList<>();
        for (var e : Bukkit.getBanList(BanList.Type.NAME).getBanEntries()) {
            String t = e.getTarget();
            if (t.toLowerCase().startsWith(partial.toLowerCase())) r.add(t);
        }
        return r;
    }

    private List<String> allKnownNames(String partial) {
        List<String> r = new ArrayList<>(onlineNames(partial));
        for (var e : Bukkit.getBanList(BanList.Type.NAME).getBanEntries()) {
            String t = e.getTarget();
            if (t.toLowerCase().startsWith(partial.toLowerCase()) && !r.contains(t)) r.add(t);
        }
        return r;
    }
}
