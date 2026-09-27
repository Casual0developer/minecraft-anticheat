package dev.anticheat.utils;

import org.bukkit.ChatColor;

public final class ColorUtil {

    private ColorUtil() {}

    /** Translate '&' color codes into Bukkit's §-based color codes. */
    public static String colorize(String message) {
        if (message == null) return "";
        return ChatColor.translateAlternateColorCodes('&', message);
    }

    /** Strip all color codes from a string (useful for logger output). */
    public static String strip(String message) {
        return ChatColor.stripColor(colorize(message));
    }
}
