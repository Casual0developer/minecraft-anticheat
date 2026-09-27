package dev.anticheat.managers;

import dev.anticheat.AntiCheatPlugin;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * HistoryManager — persists every punishment action and lets you query
 * the full history for any player by name or UUID.
 *
 * Storage: plugins/AntiCheat/ban-history.yml
 * Format per record:
 *   history.<uuid>.entries[]:
 *     - date: "2026-04-17 23:46:26"
 *       name: "ExecutionerV2"
 *       check: "Fly[ConsecUpTicks]"
 *       type: "BAN_30D" | "BAN_PERMANENT" | "KICK"
 *       duration: "30 days" | "Permanent" | "Kick (no ban)"
 *
 * Records live in memory and are written to disk in batches (at most once
 * every SAVE_DELAY_TICKS), with the YAML serialisation and file write done
 * off the main thread.  Previously every record rewrote the whole file on
 * the main thread, so a player flagging constantly in alert mode made the
 * server lag more and more as the file grew.
 */
public class HistoryManager {

    private final AntiCheatPlugin plugin;
    private final File            historyFile;

    /** UUID → records, oldest first.  Main thread only. */
    private final Map<UUID, List<BanRecord>> history = new LinkedHashMap<>();

    /** Oldest records are dropped past this, so one player can't grow the file forever. */
    private static final int  MAX_ENTRIES_PER_PLAYER = 200;
    /** Delay before a batched save (10 s). */
    private static final long SAVE_DELAY_TICKS = 200L;

    private BukkitTask saveTask;
    /** Bumped on every record (main thread). */
    private long changeVersion  = 0;
    /** Version last written to disk (guarded by this); stops an older async write landing last. */
    private long writtenVersion = 0;

    private static final SimpleDateFormat DATE_FMT =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    public HistoryManager(AntiCheatPlugin plugin) {
        this.plugin      = plugin;
        this.historyFile = new File(plugin.getDataFolder(), "ban-history.yml");
        load();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Record a punishment event
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Appends one record for this player.  Call from BanManager.executeBan().
     *
     * @param uuid      player UUID
     * @param name      player name at the time of punishment
     * @param check     check that triggered (e.g. "Fly[ConsecUpTicks]")
     * @param type      "BAN_30D", "BAN_PERMANENT", or "KICK"
     * @param duration  human-readable duration string
     */
    public void record(UUID uuid, String name, String check,
                       String type, String duration) {
        List<BanRecord> entries = history.computeIfAbsent(uuid, k -> new ArrayList<>());
        entries.add(new BanRecord(DATE_FMT.format(new Date()), name, check, type, duration));
        while (entries.size() > MAX_ENTRIES_PER_PLAYER) entries.remove(0);
        changeVersion++;
        scheduleSave();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Query history
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns all punishment records for the given UUID, oldest first.
     * Each BanRecord is a plain data carrier.
     */
    public List<BanRecord> getHistory(UUID uuid) {
        return new ArrayList<>(history.getOrDefault(uuid, List.of()));
    }

    /**
     * Searches all stored entries for any that match the given player name
     * (case-insensitive) and returns the first UUID found.
     * Used by the history command when the player is offline.
     */
    public UUID findUUIDByName(String name) {
        for (Map.Entry<UUID, List<BanRecord>> e : history.entrySet()) {
            for (BanRecord r : e.getValue()) {
                if (r.name().equalsIgnoreCase(name)) return e.getKey();
            }
        }
        return null;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Persistence
    // ─────────────────────────────────────────────────────────────────────────

    private void load() {
        if (!historyFile.exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(historyFile);
        ConfigurationSection section = cfg.getConfigurationSection("history");
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            UUID uuid;
            try { uuid = UUID.fromString(key); }
            catch (IllegalArgumentException ignored) { continue; }
            List<BanRecord> records = new ArrayList<>();
            for (Map<?, ?> m : cfg.getMapList("history." + key + ".entries")) {
                records.add(new BanRecord(
                        str(m, "date"),
                        str(m, "name"),
                        str(m, "check"),
                        str(m, "type"),
                        str(m, "duration")
                ));
            }
            history.put(uuid, records);
        }
    }

    /** Queues one batched save; further records within the delay ride along with it. */
    private void scheduleSave() {
        if (saveTask != null) return;
        saveTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            saveTask = null;
            Map<UUID, List<BanRecord>> snapshot = snapshot();
            long version = changeVersion;
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> write(snapshot, version));
        }, SAVE_DELAY_TICKS);
    }

    /**
     * Writes anything not yet on disk, synchronously. Call from onDisable —
     * also covers a batch whose async write hadn't started before shutdown.
     */
    public void flush() {
        if (saveTask != null) {
            saveTask.cancel();
            saveTask = null;
        }
        synchronized (this) {
            if (changeVersion <= writtenVersion) return;
        }
        write(snapshot(), changeVersion);
    }

    /** Cheap copy of the record lists (records themselves are immutable). Main thread only. */
    private Map<UUID, List<BanRecord>> snapshot() {
        Map<UUID, List<BanRecord>> copy = new LinkedHashMap<>();
        for (Map.Entry<UUID, List<BanRecord>> e : history.entrySet()) {
            copy.put(e.getKey(), new ArrayList<>(e.getValue()));
        }
        return copy;
    }

    private synchronized void write(Map<UUID, List<BanRecord>> snapshot, long version) {
        if (version <= writtenVersion) return; // a newer snapshot already hit the disk
        YamlConfiguration cfg = new YamlConfiguration();
        for (Map.Entry<UUID, List<BanRecord>> e : snapshot.entrySet()) {
            List<Map<String, String>> entries = new ArrayList<>();
            for (BanRecord r : e.getValue()) {
                Map<String, String> entry = new LinkedHashMap<>();
                entry.put("date",     r.date());
                entry.put("name",     r.name());
                entry.put("check",    r.check());
                entry.put("type",     r.type());
                entry.put("duration", r.duration());
                entries.add(entry);
            }
            cfg.set("history." + e.getKey() + ".entries", entries);
        }
        try {
            Files.writeString(historyFile.toPath(), cfg.saveToString(), StandardCharsets.UTF_8);
            writtenVersion = version;
        } catch (IOException e) {
            plugin.getLogger().warning("[AntiCheat] Failed to save ban-history.yml: "
                    + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private static String str(Map<?, ?> m, String key) {
        Object v = m.get(key);
        return v != null ? v.toString() : "?";
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Data carrier
    // ─────────────────────────────────────────────────────────────────────────

    public record BanRecord(String date, String name, String check,
                            String type, String duration) {}
}
