package com.vortex.poker.stats;

import com.vortex.poker.config.ConfigManager;
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

/**
 * Lifetime poker statistics in stats.yml. Everything is loaded at start (the file is small) and saved
 * in the background every few seconds when something changed, and on disable. Main thread only.
 */
public class StatsManager {
    private final JavaPlugin plugin;
    private final File file;
    private final Map<UUID, PlayerStats> stats = new HashMap<>();
    private boolean dirty;
    private BukkitTask saveTask;

    public StatsManager(JavaPlugin plugin, ConfigManager config) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "stats.yml");
        load();
        long ticks = config.getStatsSaveInterval() * 20L;
        saveTask = Bukkit.getScheduler().runTaskTimer(plugin, this::saveIfDirty, ticks, ticks);
    }

    private void load() {
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        for (String key : yaml.getKeys(false)) {
            ConfigurationSection s = yaml.getConfigurationSection(key);
            if (s == null) continue;
            try {
                PlayerStats p = new PlayerStats();
                p.name = s.getString("name");
                p.handsPlayed = s.getLong("hands-played");
                p.handsWon = s.getLong("hands-won");
                p.showdownsWon = s.getLong("showdowns-won");
                p.biggestPot = s.getLong("biggest-pot");
                p.netWinnings = s.getLong("net-winnings");
                stats.put(UUID.fromString(key), p);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Skipping bad stats entry '" + key + "'");
            }
        }
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

    /** Write stats.yml now. */
    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<UUID, PlayerStats> e : stats.entrySet()) {
            PlayerStats p = e.getValue();
            String k = e.getKey().toString();
            yaml.set(k + ".name", p.name);
            yaml.set(k + ".hands-played", p.handsPlayed);
            yaml.set(k + ".hands-won", p.handsWon);
            yaml.set(k + ".showdowns-won", p.showdownsWon);
            yaml.set(k + ".biggest-pot", p.biggestPot);
            yaml.set(k + ".net-winnings", p.netWinnings);
        }
        try {
            yaml.save(file);
            dirty = false;
        } catch (IOException ex) {
            plugin.getLogger().log(Level.WARNING, "Could not save stats.yml", ex);
        }
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
