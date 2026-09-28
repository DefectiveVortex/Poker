package com.vortex.poker.stats;

import com.vortex.poker.config.ConfigFileUpdater;
import com.vortex.poker.config.ConfigManager;
import com.vortex.poker.util.SafeYaml;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.ToLongFunction;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Lifetime poker statistics in stats.yml. Everything is loaded at start (the file is small) and saved
 * in the background every few seconds when something changed, and on disable. Main thread only.
 * A damaged stats.yml is moved aside and the readable entries recovered (see {@link #read}).
 */
public class StatsManager {
    private final JavaPlugin plugin;
    private final File file;
    private final Map<UUID, PlayerStats> stats = new HashMap<>();
    /** stats.yml version this build writes (the file's own {@code config-version}). */
    static final int FILE_VERSION = 1;

    private boolean dirty;
    /** Set when stats.yml exists but couldn't be read or moved aside: never write over it. */
    private boolean readOnly;
    private BukkitTask saveTask;

    public StatsManager(JavaPlugin plugin, ConfigManager config) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "stats.yml");
        try {
            load();
        } catch (IllegalStateException e) {
            plugin.getLogger().severe(e.getMessage());
            readOnly = true;
        }
        long ticks = config.getStatsSaveInterval() * 20L;
        saveTask = Bukkit.getScheduler().runTaskTimer(plugin, this::saveIfDirty, ticks, ticks);
    }

    private void load() {
        stats.putAll(read(file, plugin.getLogger()));
    }

    /**
     * Read stats.yml. A file that isn't valid YAML is moved to stats.yml.broken-<timestamp> and every player
     * entry that still parses is recovered and written back straight away, so the next autosave can't
     * replace the file with less. A counter that isn't a number reads as 0, with a warning.
     */
    static Map<UUID, PlayerStats> read(File file, Logger log) {
        Map<UUID, PlayerStats> out = new HashMap<>();
        if (!file.exists()) {
            return out;
        }
        SafeYaml.LoadResult loaded = SafeYaml.load(file, log, false);
        YamlConfiguration yaml = loaded.config();
        if (loaded.status() == SafeYaml.Status.BROKEN) {
            if (loaded.brokenCopy() == null) {
                // Unreadable and still in place: don't let an autosave overwrite it.
                throw new IllegalStateException("stats.yml could not be read (" + loaded.error()
                    + ") and could not be moved aside. Fix or remove it and restart; statistics are not saved until then.");
            }
            yaml = SafeYaml.salvage(loaded.brokenCopy());
        }
        Object version = yaml.get(ConfigFileUpdater.VERSION_KEY);
        if (version instanceof Number n && n.intValue() > FILE_VERSION) {
            log.warning("stats.yml has " + ConfigFileUpdater.VERSION_KEY + " " + n + ", newer than this Poker build ("
                + FILE_VERSION + "). Reading it as it is.");
        }
        int bad = 0;
        for (String key : yaml.getKeys(false)) {
            if (key.equals(ConfigFileUpdater.VERSION_KEY)) continue;
            ConfigurationSection s = yaml.getConfigurationSection(key);
            UUID id;
            try {
                id = UUID.fromString(key);
            } catch (IllegalArgumentException e) {
                id = null;
            }
            if (s == null || id == null) {
                log.warning("stats.yml: skipping '" + key + "', which is not a player entry (it is dropped on the next save).");
                bad++;
                continue;
            }
            PlayerStats p = new PlayerStats();
            p.name = s.getString("name");
            p.handsPlayed = count(s, "hands-played", key, log, false);
            p.handsWon = count(s, "hands-won", key, log, false);
            p.showdownsWon = count(s, "showdowns-won", key, log, false);
            p.biggestPot = count(s, "biggest-pot", key, log, false);
            p.netWinnings = count(s, "net-winnings", key, log, true);
            p.guideSeen = s.getBoolean("guide-seen");
            out.put(id, p);
        }
        if (loaded.status() == SafeYaml.Status.BROKEN) {
            log.warning("stats.yml could not be read (" + loaded.error() + "). Moved it to " + loaded.brokenCopy().getName()
                + "; recovered " + out.size() + " player(s).");
            try {
                SafeYaml.saveAtomically(toYaml(out), file);
            } catch (IOException e) {
                log.log(Level.WARNING, "Could not write the recovered stats.yml", e);
            }
        }
        return out;
    }

    /** A counter from a player entry: missing reads 0, and so does anything that isn't a whole number (with a warning). */
    private static long count(ConfigurationSection s, String field, String player, Logger log, boolean mayBeNegative) {
        Object v = s.get(field);
        if (v == null) return 0;
        long n;
        if (v instanceof Integer || v instanceof Long) {
            n = ((Number) v).longValue();
        } else if (v instanceof Number num && num.doubleValue() == Math.rint(num.doubleValue())) {
            n = num.longValue();
        } else {
            try {
                n = Long.parseLong(String.valueOf(v).trim());
            } catch (NumberFormatException e) {
                log.warning("stats.yml: " + player + "." + field + " is not a whole number (" + v + "); using 0.");
                return 0;
            }
        }
        if (n < 0 && !mayBeNegative) {
            log.warning("stats.yml: " + player + "." + field + " can't be negative (" + n + "); using 0.");
            return 0;
        }
        return n;
    }

    /**
     * Record one finished hand for one player who was dealt in. Called by PokerTable when a hand ends
     * (not for hands voided at shutdown).
     *
     * @param net           chips won minus chips put in (negative when they lost)
     * @param won           whether they won any part of a pot
     * @param wonAtShowdown whether they won at a showdown rather than by everyone folding
     * @param potWon        total chips they collected (0 if they lost)
     */
    public void recordHand(UUID player, long net, boolean won, boolean wonAtShowdown, long potWon) {
        PlayerStats p = stats.computeIfAbsent(player, id -> new PlayerStats());
        OfflinePlayer offline = Bukkit.getOfflinePlayer(player);
        if (offline.getName() != null) {
            p.name = offline.getName();
        }
        p.handsPlayed++;
        if (won) p.handsWon++;
        if (wonAtShowdown) p.showdownsWon++;
        p.biggestPot = Math.max(p.biggestPot, potWon);
        p.netWinnings += net;
        dirty = true;
    }

    /** True the first time it's called for a player (who hasn't played before); records that they've seen the guide. */
    public boolean markGuideSeen(UUID player) {
        PlayerStats p = stats.computeIfAbsent(player, id -> new PlayerStats());
        if (p.guideSeen || p.handsPlayed > 0) {
            return false;
        }
        p.guideSeen = true;
        OfflinePlayer offline = Bukkit.getOfflinePlayer(player);
        if (offline.getName() != null) {
            p.name = offline.getName();
        }
        dirty = true;
        return true;
    }

    /** Stats for a player, or null if they've never played. */
    public PlayerStats getStats(UUID player) {
        return stats.get(player);
    }

    /** Find a player who has played by (case-insensitive) name. */
    public UUID findByName(String name) {
        String wanted = name.toLowerCase(Locale.ROOT);
        for (Map.Entry<UUID, PlayerStats> e : stats.entrySet()) {
            if (e.getValue().name != null && e.getValue().name.toLowerCase(Locale.ROOT).equals(wanted)) {
                return e.getKey();
            }
        }
        return null;
    }

    public enum Ranking {
        NET(PlayerStats::getNetWinnings),
        WON(PlayerStats::getHandsWon),
        PLAYED(PlayerStats::getHandsPlayed),
        BIGGEST_POT(PlayerStats::getBiggestPot);

        final ToLongFunction<PlayerStats> value;

        Ranking(ToLongFunction<PlayerStats> value) {
            this.value = value;
        }

        public long of(PlayerStats p) {
            return value.applyAsLong(p);
        }
    }

    /** The top players by a ranking, best first. */
    public List<PlayerStats> top(Ranking ranking, int limit) {
        List<PlayerStats> all = new ArrayList<>(stats.values());
        all.sort(Comparator.comparingLong(ranking.value).reversed());
        return all.subList(0, Math.min(limit, all.size()));
    }

    private void saveIfDirty() {
        if (dirty) {
            save();
        }
    }

    /** Write stats.yml now (through a temporary file, so a crash mid-write can't truncate it). */
    public void save() {
        if (readOnly) {
            return;
        }
        try {
            SafeYaml.saveAtomically(toYaml(stats), file);
            dirty = false;
        } catch (IOException ex) {
            plugin.getLogger().log(Level.WARNING, "Could not save stats.yml", ex);
        }
    }

    private static YamlConfiguration toYaml(Map<UUID, PlayerStats> stats) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set(ConfigFileUpdater.VERSION_KEY, FILE_VERSION);
        for (Map.Entry<UUID, PlayerStats> e : stats.entrySet()) {
            PlayerStats p = e.getValue();
            String k = e.getKey().toString();
            yaml.set(k + ".name", p.name);
            yaml.set(k + ".hands-played", p.handsPlayed);
            yaml.set(k + ".hands-won", p.handsWon);
            yaml.set(k + ".showdowns-won", p.showdownsWon);
            yaml.set(k + ".biggest-pot", p.biggestPot);
            yaml.set(k + ".net-winnings", p.netWinnings);
            if (p.guideSeen) yaml.set(k + ".guide-seen", true);
        }
        return yaml;
    }

    /** Stop autosaving and write any pending changes. Call from onDisable. */
    public void shutdown() {
        if (saveTask != null) {
            saveTask.cancel();
            saveTask = null;
        }
        save();
    }
}
