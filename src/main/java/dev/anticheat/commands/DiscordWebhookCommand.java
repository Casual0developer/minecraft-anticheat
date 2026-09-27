package dev.anticheat.commands;

import dev.anticheat.AntiCheatPlugin;
import dev.anticheat.utils.ColorUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.List;

public class DiscordWebhookCommand implements CommandExecutor, TabCompleter {

    private final AntiCheatPlugin plugin;

    public DiscordWebhookCommand(AntiCheatPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String prefix = plugin.getPrefix();

        if (!sender.hasPermission("anticheat.admin")) {
            sender.sendMessage(ColorUtil.colorize(prefix + plugin.getConfig()
                    .getString("messages.no-permission", "&cNo permission.")));
            return true;
        }

        if (args.length == 0) {
            sendHelp(sender, prefix);
            return true;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "test" -> {
                if (!plugin.getDiscordWebhookManager().isEnabled()) {
                    sender.sendMessage(ColorUtil.colorize(prefix + "&cNo webhook configured. Use &f/anticheat discord webhook <url> &cfirst."));
                    sender.sendMessage(ColorUtil.colorize("&7(legacy: &f/discord-webhook <url>&7)"));
                } else {
                    sender.sendMessage(ColorUtil.colorize(prefix + "&7Sending test message to Discord..."));
                    plugin.getDiscordWebhookManager().sendTest(sender);
                }
            }
            case "clear", "remove", "disable", "off" -> {
                plugin.getDiscordWebhookManager().setWebhookUrl("");
                sender.sendMessage(ColorUtil.colorize(prefix + "&aDiscord webhook cleared & disabled."));
            }
            case "status", "info", "show" -> {
                sendStatus(sender, prefix);
            }
            case "help", "?" -> sendHelp(sender, prefix);
            default -> {
                // Treat entire args as URL (may contain spaces? join them)
                // If first arg looks like URL, use it; otherwise show help
                String url = String.join(" ", args).trim();
                // Remove surrounding < > or quotes if user pasted with them
                url = url.replaceAll("^[<\"']+", "").replaceAll("[>\"']+$", "").trim();

                if (!url.startsWith("http")) {
                    sender.sendMessage(ColorUtil.colorize(prefix + "&cInvalid URL. Must start with https://"));
                    sender.sendMessage(ColorUtil.colorize("&7Example: &f/anticheat discord webhook https://discord.com/api/webhooks/123/abc"));
                    return true;
                }

                if (!plugin.getDiscordWebhookManager().isValidUrl(url)) {
                    sender.sendMessage(ColorUtil.colorize(prefix + "&cThat doesn't look like a valid Discord webhook URL."));
                    sender.sendMessage(ColorUtil.colorize("&7Expected: &fhttps://discord.com/api/webhooks/<id>/<token>"));
                    sender.sendMessage(ColorUtil.colorize("&7Use &f/anticheat discord webhook test &7to verify after setting."));
                    // Still save if it contains discord webhook substring? Be strict but allow override
                    // Let's save anyway with warning if it contains webhooks substring
                    if (!url.contains("discord.com/api/webhooks") && !url.contains("discordapp.com/api/webhooks")) {
                        return true;
                    }
                    sender.sendMessage(ColorUtil.colorize("&eSaving anyway (custom webhook domain detected)..."));
                }

                plugin.getDiscordWebhookManager().setWebhookUrl(url);
                sender.sendMessage(ColorUtil.colorize(prefix + "&aDiscord webhook set!"));
                sender.sendMessage(ColorUtil.colorize("&7Masked URL: &f" + plugin.getDiscordWebhookManager().maskUrl(url)));
                sender.sendMessage(ColorUtil.colorize("&7Use &f/anticheat discord webhook test &7to verify."));
                sender.sendMessage(ColorUtil.colorize("&7All future flags will be sent with player, IP & reason + &f" + plugin.getDisplayName()));
                sender.sendMessage(ColorUtil.colorize("&7Alerts use: &f" + plugin.getPrefix() + "&7prefix"));
            }
        }
        return true;
    }

    private void sendStatus(CommandSender sender, String prefix) {
        String url = plugin.getDiscordWebhookManager().getWebhookUrl();
        boolean enabled = plugin.getDiscordWebhookManager().isEnabled();
        String branding = plugin.getDisplayName();
        sender.sendMessage(ColorUtil.colorize("&8--- &c" + branding + " Discord Webhook Status &8---"));
        sender.sendMessage(ColorUtil.colorize("&7Nickname: &f" + branding));
        sender.sendMessage(ColorUtil.colorize("&7Enabled: &f" + (enabled ? "&aYes" : "&cNo")));
        if (url.isEmpty()) {
            sender.sendMessage(ColorUtil.colorize("&7URL: &cNot set"));
            sender.sendMessage(ColorUtil.colorize("&7Set with: &f/anticheat discord webhook https://discord.com/api/webhooks/..."));
        } else {
            sender.sendMessage(ColorUtil.colorize("&7URL: &f" + plugin.getDiscordWebhookManager().maskUrl(url)));
            String uname = plugin.getConfig().getString("discord.username", "");
            if (uname == null || uname.isEmpty()) uname = branding + " &8(auto)";
            sender.sendMessage(ColorUtil.colorize("&7Username: &f" + uname));
        }
        sender.sendMessage(ColorUtil.colorize("&7Server Name: &f" + plugin.getConfig().getString("discord.server-name", "(not set)")));
        sender.sendMessage(ColorUtil.colorize("&7Test: &f/anticheat discord webhook test  &8|  &f/anticheat discord webhook clear"));
        sender.sendMessage(ColorUtil.colorize("&7Legacy: &f/discord-webhook test &8still works"));
    }

    private void sendHelp(CommandSender sender, String prefix) {
        String branding = plugin.getDisplayName();
        sender.sendMessage(ColorUtil.colorize("&8--- &c" + branding + " Discord Webhook &8---"));
        sender.sendMessage(ColorUtil.colorize("&f/anticheat discord webhook <url> &7- Set Discord webhook URL &8(primary)"));
        sender.sendMessage(ColorUtil.colorize("&f/anticheat discord webhook test &7- Send test embed to webhook"));
        sender.sendMessage(ColorUtil.colorize("&f/anticheat discord webhook status &7- Show current webhook config"));
        sender.sendMessage(ColorUtil.colorize("&f/anticheat discord webhook clear &7- Remove webhook"));
        sender.sendMessage(ColorUtil.colorize("&7Legacy: &f/discord-webhook <url> &7still works"));
        sender.sendMessage(ColorUtil.colorize("&7Example:"));
        sender.sendMessage(ColorUtil.colorize("&8» &f/anticheat discord webhook https://discord.com/api/webhooks/123456/abc-def"));
        sender.sendMessage(ColorUtil.colorize("&7Sends &fplayer, IP & reason &7+ " + branding + " branding for every flag."));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("anticheat.admin")) return List.of();
        if (args.length == 1) {
            List<String> opts = List.of("test", "status", "clear", "help");
            List<String> out = new ArrayList<>();
            for (String o : opts) if (o.startsWith(args[0].toLowerCase())) out.add(o);
            return out;
        }
        return List.of();
    }
}
