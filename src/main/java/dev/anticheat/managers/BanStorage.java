package dev.anticheat.managers;

import dev.anticheat.AntiCheatPlugin;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Persistent storage for UUID and IP bans, saved to bans.yml.
 * Works alongside Bukkit's built-in NAME and IP ban lists to provide
 * UUID-level banning that survives name changes.
 */
public class BanStorage {

    private final AntiCheatPlugin plugin;
    private final File             file;
    private YamlConfiguration     config;

    private final Set<UUID>   bannedUUIDs = new HashSet<>();
    private final Set<String> bannedIPs   = new HashSet<>();

    public BanStorage(AntiCheatPlugin plugin) {
        this.plugin = plugin;
        this.file   = new File(plugin.getDataFolder(), "bans.yml");
        load();
    }

    public void load() {
        if (!file.exists()) {
            try { file.createNewFile(); }
            catch (IOException e) { plugin.getLogger().warning("[AntiCheat] Could not create bans.yml: " + e.getMessage()); }
        }
        config = YamlConfiguration.loadConfiguration(file);

        bannedUUIDs.clear();
        bannedIPs.clear();

        for (String s : config.getStringList("banned-uuids")) {
            try { bannedUUIDs.add(UUID.fromString(s)); }
            catch (IllegalArgumentException ignored) {}
        }
        bannedIPs.addAll(config.getStringList("banned-ips"));
    }

    public void save() {
        List<String> uuidStrings = new ArrayList<>();
        for (UUID u : bannedUUIDs) uuidStrings.add(u.toString());
        config.set("banned-uuids", uuidStrings);
        config.set("banned-ips",   new ArrayList<>(bannedIPs));
        try { config.save(file); }
        catch (IOException e) { plugin.getLogger().warning("[AntiCheat] Could not save bans.yml: " + e.getMessage()); }
    }

    /** Add a UUID and/or IP to the ban storage. */
    public void addBan(UUID uuid, String ip) {
        if (uuid != null)                        bannedUUIDs.add(uuid);
        if (ip != null && !ip.isBlank())         bannedIPs.add(ip);
        save();
    }

    /** Remove a UUID ban entry (e.g. for /anticheat unban). */
    public void removeByUUID(UUID uuid) {
        bannedUUIDs.remove(uuid);
        save();
    }

    /** Remove an IP ban entry. */
    public void removeByIP(String ip) {
        bannedIPs.remove(ip);
        save();
    }

    public boolean isBannedUUID(UUID uuid) { return bannedUUIDs.contains(uuid); }
    public boolean isBannedIP(String ip)   { return bannedIPs.contains(ip); }

    public Set<UUID>   getBannedUUIDs() { return Collections.unmodifiableSet(bannedUUIDs); }
    public Set<String> getBannedIPs()   { return Collections.unmodifiableSet(bannedIPs); }
}
