package com.vortex.poker;

import com.vortex.poker.stats.PlayerStats;
import com.vortex.poker.stats.StatsManager;
import com.vortex.poker.table.PokerTable;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;

/**
 * PlaceholderAPI expansion: %poker_stats_*%, %poker_table_*% and %poker_top_<ranking>_<n>_name|value%.
 * See PLACEHOLDERAPI.md for the full list.
 */
public class PokerPlaceholderExpansion extends PlaceholderExpansion {
    private final PokerPlugin plugin;

    public PokerPlaceholderExpansion(PokerPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "poker";
    }

    @Override
    public @NotNull String getAuthor() {
        return String.join(", ", plugin.getDescription().getAuthors());
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public boolean canRegister() {
        return true;
    }

    @Override
    public @Nullable String onRequest(OfflinePlayer player, @NotNull String params) {
        String p = params.toLowerCase(Locale.ROOT);
        if (p.startsWith("top_")) {
            return top(p.substring(4));
        }
        if (player == null) {
            return "";
        }
        if (p.startsWith("stats_")) {
            return stats(player, p.substring(6));
        }
        if (p.startsWith("table_")) {
            return player.getPlayer() == null ? "" : table(player.getPlayer(), p.substring(6));
        }
        return null;
    }

    private String stats(OfflinePlayer player, String key) {
        PlayerStats s = plugin.getStatsManager().getStats(player.getUniqueId());
        if (s == null) {
            s = new PlayerStats();
        }
        return switch (key) {
            case "hands_played" -> String.valueOf(s.getHandsPlayed());
            case "hands_won" -> String.valueOf(s.getHandsWon());
            case "showdowns_won" -> String.valueOf(s.getShowdownsWon());
            case "biggest_pot" -> String.valueOf(s.getBiggestPot());
            case "net" -> String.valueOf(s.getNetWinnings());
            case "win_rate" -> String.valueOf(s.getWinRatePercent());
            case "biggest_pot_formatted" -> plugin.getConfigManager().formatCurrency(s.getBiggestPot());
            case "net_formatted" -> (s.getNetWinnings() < 0 ? "-" : "")
                + plugin.getConfigManager().formatCurrency(Math.abs(s.getNetWinnings()));
            default -> null;
        };
    }

    private String table(Player player, String key) {
        PokerTable t = plugin.getTableManager().getTableOf(player);
        if (key.equals("seated")) {
            return String.valueOf(t != null);
        }
        if (t == null) {
            return "";
        }
        return switch (key) {
            case "id" -> String.valueOf(t.getId());
            case "seat" -> String.valueOf(t.getSeat(player) + 1);
            case "stack" -> String.valueOf(t.getStack(player));
            case "players" -> String.valueOf(t.getPlayerCount());
            case "seats" -> String.valueOf(t.getSeatCount());
            case "pot" -> String.valueOf(t.getPot());
            case "small_blind" -> String.valueOf(t.getSmallBlind());
            case "big_blind" -> String.valueOf(t.getBigBlind());
            case "blinds" -> t.getSmallBlind() + "/" + t.getBigBlind();
            case "in_hand" -> String.valueOf(t.isHandInProgress());
            case "my_turn" -> String.valueOf(t.isPlayerTurn(player));
            default -> null;
        };
    }

    /** top_<net|won|played|biggest_pot>_<n>_<name|value>, n from 1. */
    private String top(String rest) {
        int valueSep = rest.lastIndexOf('_');
        if (valueSep < 0) return null;
        String field = rest.substring(valueSep + 1);
        String head = rest.substring(0, valueSep);
        int rankSep = head.lastIndexOf('_');
        if (rankSep < 0) return null;
        StatsManager.Ranking ranking;
        int position;
        try {
            ranking = StatsManager.Ranking.valueOf(head.substring(0, rankSep).toUpperCase(Locale.ROOT));
            position = Integer.parseInt(head.substring(rankSep + 1));
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (position < 1 || position > 100) return null;
        List<PlayerStats> top = plugin.getStatsManager().top(ranking, position);
        if (top.size() < position) {
            return field.equals("name") ? "-" : "0";
        }
        PlayerStats s = top.get(position - 1);
        return switch (field) {
            case "name" -> s.getName() == null ? "?" : s.getName();
            case "value" -> String.valueOf(ranking.of(s));
            default -> null;
        };
    }
}
