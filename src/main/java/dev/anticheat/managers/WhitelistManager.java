package dev.anticheat.managers;

import dev.anticheat.AntiCheatPlugin;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Persistent whitelist — stores bypassed player UUIDs in whitelist.yml.
 * Previously the /anticheat whitelist command just printed a message; now
 * it actually adds/removes players from this list and saves to disk.
 */
public class WhitelistManager {

    private final AntiCheatPlugin plugin;
    private final File whitelistFile;
    private final Set<UUID> whitelisted = new HashSet<>();

    public WhitelistManager(AntiCheatPlugin plugin) {
        this.plugin = plugin;
        this.whitelistFile = new File(plugin.getDataFolder(), "whitelist.yml");
        load();
    }

    public boolean isWhitelisted(UUID uuid) {
        return whitelisted.contains(uuid);
    }

    public boolean add(UUID uuid) {
        if (whitelisted.add(uuid)) { save(); return true; }
        return false;
    }

    public boolean remove(UUID uuid) {
        if (whitelisted.remove(uuid)) { save(); return true; }
        return false;
    }

    public Set<UUID> getAll() { return new HashSet<>(whitelisted); }

    private void load() {
        if (!whitelistFile.exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(whitelistFile);
        for (String s : cfg.getStringList("whitelisted")) {
            try { whitelisted.add(UUID.fromString(s)); }
            catch (IllegalArgumentException ignored) {}
        }
        plugin.getLogger().info("[AntiCheat] Loaded " + whitelisted.size() + " whitelisted players.");
    }

    private void save() {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("whitelisted", whitelisted.stream().map(UUID::toString).toList());
        try { cfg.save(whitelistFile); }
        catch (IOException e) {
            plugin.getLogger().warning("[AntiCheat] Failed to save whitelist: " + e.getMessage());
        }
    }
}
