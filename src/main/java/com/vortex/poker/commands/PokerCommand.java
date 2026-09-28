package com.vortex.poker.commands;

import com.vortex.poker.PokerPlugin;
import com.vortex.poker.config.ConfigManager;
import com.vortex.poker.gui.PokerMenu;
import com.vortex.poker.stats.PlayerStats;
import com.vortex.poker.table.CardDisplayCleaner;
import com.vortex.poker.table.PokerTable;
import com.vortex.poker.table.TableManager;
import com.vortex.poker.table.TableSettings;
import com.vortex.poker.util.ChatUtils;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * /poker (alias /pk). Players: join, leave, fold, check, call, bet, raise, allin, topup, menu, stats, tables.
 * Admins (poker.admin): createtable, removetable, settable, cleanup, reload. Amounts for bet and raise are the
 * player's total for the street ("raise to").
 */
public class PokerCommand implements TabExecutor {
    private static final String ADMIN = "poker.admin";
    private static final String PLAY = "poker.play";
    private static final String STATS_OTHERS = "poker.stats.others";
    private static final List<String> PLAYER_SUBS = List.of("help", "join", "leave", "fold", "check", "call", "bet",
        "raise", "allin", "ready", "topup", "menu", "stats", "tables", "version");
    private static final List<String> ADMIN_SUBS = List.of("createtable", "removetable", "settable", "cleanup", "reload");
    private static final int MAX_CLEANUP_RADIUS = 64;

    private final PokerPlugin plugin;

    public PokerCommand(PokerPlugin plugin) {
        this.plugin = plugin;
    }

    private ConfigManager cfg() {
        return plugin.getConfigManager();
    }

    private TableManager tables() {
        return plugin.getTableManager();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "help" -> help(sender);
            case "tables" -> listTables(sender);
            case "version" -> sender.sendMessage(cfg().formatPrefixed("version-current",
                "version", plugin.getDescription().getVersion()));
            case "stats" -> stats(sender, args);
            case "reload" -> {
                if (!check(sender, ADMIN)) return true;
                int warnings = cfg().reload();
                sender.sendMessage(warnings == 0 ? cfg().getPrefixed("reload-done")
                    : cfg().formatPrefixed("reload-warnings", "count", warnings));
            }
            case "createtable" -> admin(sender, p -> createTable(p, args));
            case "removetable" -> admin(sender, p -> removeTable(p, args));
            case "settable" -> admin(sender, p -> setTable(p, args));
            case "cleanup" -> admin(sender, p -> cleanup(p, args));
            case "join" -> player(sender, p -> join(p, args));
            case "leave" -> atTable(sender, (p, t) -> t.leave(p));
            case "fold" -> atTable(sender, (p, t) -> t.fold(p));
            case "check" -> atTable(sender, (p, t) -> t.check(p));
            case "call" -> atTable(sender, (p, t) -> t.call(p));
            case "allin", "all-in", "shove" -> atTable(sender, (p, t) -> t.allIn(p));
            case "bet", "raise" -> atTable(sender, (p, t) -> {
                long amount = args.length < 2 ? -1 : PokerMenu.parseAmount(args[1]);
                if (args.length < 2) {
                    p.sendMessage(cfg().getPrefixed(sub + "-usage"));
                } else if (amount <= 0) {
                    p.sendMessage(cfg().formatPrefixed("invalid-amount", "value", args[1]));
                } else {
                    plugin.getActionMenu().wager(p, t, amount);
                }
            });
            case "topup" -> atTable(sender, (p, t) -> {
                if (args.length < 2) {
                    plugin.getBuyInMenu().openTopUp(p, t, t.getMaxTopUp(p));
                    return;
                }
                long amount = PokerMenu.parseAmount(args[1]);
                if (amount <= 0) {
                    p.sendMessage(cfg().formatPrefixed("invalid-amount", "value", args[1]));
                } else {
                    t.topUp(p, amount);
                }
            });
            case "menu" -> atTable(sender, (p, t) -> t.onTableClick(p));
            case "ready", "again", "play", "playagain" -> atTable(sender, (p, t) -> t.ready(p));
            default -> sender.sendMessage(cfg().getPrefixed("unknown-command"));
        }
        return true;
    }

    // ---------- helpers ----------

    private boolean check(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) {
            return true;
        }
        sender.sendMessage(cfg().getPrefixed("no-permission"));
        return false;
    }

    private void player(CommandSender sender, java.util.function.Consumer<Player> action) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(cfg().getPrefixed("player-only-command"));
            return;
        }
        if (check(p, PLAY)) {
            action.accept(p);
        }
    }

    private void admin(CommandSender sender, java.util.function.Consumer<Player> action) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(cfg().getPrefixed("player-only-command"));
            return;
        }
        if (check(p, ADMIN)) {
            action.accept(p);
        }
    }

    private void atTable(CommandSender sender, BiConsumer<Player, PokerTable> action) {
        player(sender, p -> {
            PokerTable table = tables().getTableOf(p);
            if (table == null) {
                p.sendMessage(cfg().getPrefixed("not-at-table"));
                return;
            }
            action.accept(p, table);
        });
    }

    private String money(long amount) {
        return cfg().formatCurrency(amount);
    }

    // ---------- player commands ----------

    private void help(CommandSender sender) {
        sender.sendMessage(cfg().getPrefixed("help-header"));
        cfg().getMessageList("help-intro").forEach(sender::sendMessage);
        if (sender instanceof Player p && tables().getTableOf(p) == null && p.hasPermission(PLAY)) {
            ChatUtils.sendRow(p, "", " ", List.of(ChatUtils.runButton(cfg().getButtonText("sit-down"), "/poker join",
                cfg().getButtonHover("sit-down"))));
        }
        sender.sendMessage(cfg().getMessage("help-commands-header"));
        cfg().getMessageList("help-lines").forEach(sender::sendMessage);
        if (sender.hasPermission(ADMIN)) {
            cfg().getMessageList("help-admin-lines").forEach(sender::sendMessage);
        }
    }

    /** /poker join [amount] [seat]: nearest table in range; no amount opens the buy-in prompt. */
    private void join(Player p, String[] args) {
        if (tables().getTableOf(p) != null) {
            p.sendMessage(cfg().getPrefixed("already-at-table"));
            return;
        }
        PokerTable table = tables().findNearestTable(p.getLocation());
        if (table == null) {
            p.sendMessage(cfg().getPrefixed("no-table-nearby"));
            return;
        }
        int seat = -1;
        if (args.length >= 3) {
            try {
                seat = Integer.parseInt(args[2]) - 1;
            } catch (NumberFormatException e) {
                p.sendMessage(cfg().formatPrefixed("invalid-seat", "value", args[2], "max", table.getSeatCount()));
                return;
            }
            if (seat < 0 || seat >= table.getSeatCount()) {
                p.sendMessage(cfg().formatPrefixed("invalid-seat", "value", args[2], "max", table.getSeatCount()));
                return;
            }
        }
        if (args.length < 2) {
            table.join(p, seat);
            return;
        }
        long amount = PokerMenu.parseAmount(args[1]);
        if (amount <= 0) {
            p.sendMessage(cfg().formatPrefixed("invalid-amount", "value", args[1]));
            return;
        }
        table.sit(p, seat, amount);
    }

    private void listTables(CommandSender sender) {
        List<PokerTable> all = new ArrayList<>(tables().getTables());
        if (all.isEmpty()) {
            sender.sendMessage(cfg().getPrefixed("tables-none"));
            return;
        }
        all.sort((a, b) -> Integer.compare(a.getId(), b.getId()));
        sender.sendMessage(cfg().formatPrefixed("tables-header", "count", all.size()));
        for (PokerTable t : all) {
            Location c = t.getLayout().getCenter();
            sender.sendMessage(cfg().formatMessage("tables-entry", "id", t.getId(),
                "world", c.getWorld() == null ? "?" : c.getWorld().getName(),
                "x", c.getBlockX(), "y", c.getBlockY(), "z", c.getBlockZ(),
                "players", t.getPlayerCount(), "seats", t.getSeatCount(),
                "small_blind", money(t.getSmallBlind()), "big_blind", money(t.getBigBlind())));
        }
    }

    private void stats(CommandSender sender, String[] args) {
        UUID target;
        String name;
        if (args.length >= 2) {
            if (!(sender instanceof Player self && self.getName().equalsIgnoreCase(args[1])) && !check(sender, STATS_OTHERS)) {
                return;
            }
            target = plugin.getStatsManager().findByName(args[1]);
            name = args[1];
            if (target == null) {
                sender.sendMessage(cfg().formatPrefixed("stats-player-not-found", "player", args[1]));
                return;
            }
        } else if (sender instanceof Player p) {
            target = p.getUniqueId();
            name = p.getName();
        } else {
            sender.sendMessage(cfg().getPrefixed("player-only-command"));
            return;
        }
        PlayerStats s = plugin.getStatsManager().getStats(target);
        if (s == null || s.getHandsPlayed() == 0) {
            sender.sendMessage(cfg().formatPrefixed("stats-no-data", "player", name));
            return;
        }
        if (s.getName() != null) {
            name = s.getName();
        }
        long net = s.getNetWinnings();
        sender.sendMessage(cfg().formatPrefixed("stats-header", "player", name));
        sender.sendMessage(cfg().formatMessage("stats-hands-played", "value", s.getHandsPlayed()));
        sender.sendMessage(cfg().formatMessage("stats-hands-won", "value", s.getHandsWon(), "percent", s.getWinRatePercent()));
        sender.sendMessage(cfg().formatMessage("stats-showdowns-won", "value", s.getShowdownsWon()));
        sender.sendMessage(cfg().formatMessage("stats-biggest-pot", "value", money(s.getBiggestPot())));
        sender.sendMessage(cfg().formatMessage("stats-net", "value",
            (net >= 0 ? cfg().getMessage("stats-net-positive-color") + "+" : cfg().getMessage("stats-net-negative-color") + "-")
                + money(Math.abs(net))));
    }

    // ---------- admin commands ----------

    private void createTable(Player p, String[] args) {
        StringBuilder error = new StringBuilder();
        TableSettings settings = TableSettings.parseArgs(args, 1, cfg(), error);
        if (settings == null) {
            p.sendMessage(cfg().formatPrefixed("createtable-invalid-arg", "error", error));
            return;
        }
        PokerTable table = tables().createTable(p.getLocation(), settings);
        if (table == null) {
            p.sendMessage(cfg().getPrefixed("table-overlaps"));
            return;
        }
        p.sendMessage(cfg().formatPrefixed("table-created", "id", table.getId(), "seats", table.getSeatCount(),
            "small_blind", money(table.getSmallBlind()), "big_blind", money(table.getBigBlind()),
            "min_buy_in", money(table.getMinBuyIn()), "max_buy_in", money(table.getMaxBuyIn())));
    }

    private PokerTable targetTable(Player p, String[] args, int idIndex) {
        if (args.length > idIndex) {
            try {
                int id = Integer.parseInt(args[idIndex].replace("#", ""));
                PokerTable table = tables().getTable(id);
                if (table == null) {
                    p.sendMessage(cfg().formatPrefixed("table-not-found-id", "id", id));
                }
                return table;
            } catch (NumberFormatException e) {
                p.sendMessage(cfg().formatPrefixed("table-not-found-id", "id", args[idIndex]));
                return null;
            }
        }
        PokerTable table = tables().findNearestTable(p.getLocation());
        if (table == null) {
            p.sendMessage(cfg().getPrefixed("no-table-nearby"));
        }
        return table;
    }

    private void removeTable(Player p, String[] args) {
        PokerTable table = targetTable(p, args, 1);
        if (table != null && tables().removeTable(table)) {
            p.sendMessage(cfg().formatPrefixed("table-removed", "id", table.getId()));
        }
    }

    /** /poker settable key:value [key:value ...], or /poker settable key value. Applies to the nearest table. */
    private void setTable(Player p, String[] args) {
        if (args.length < 2) {
            p.sendMessage(cfg().getPrefixed("settable-usage"));
            return;
        }
        String[] tokens = args;
        if (args.length == 3 && !args[1].contains(":") && !args[2].contains(":")) {
            tokens = new String[]{args[0], args[1] + ":" + args[2]};
        }
        StringBuilder error = new StringBuilder();
        TableSettings changes = TableSettings.parseArgs(tokens, 1, cfg(), error);
        if (changes == null) {
            p.sendMessage(cfg().formatPrefixed("createtable-invalid-arg", "error", error));
            return;
        }
        PokerTable table = tables().findNearestTable(p.getLocation());
        if (table == null) {
            p.sendMessage(cfg().getPrefixed("no-table-nearby"));
            return;
        }
        String described = String.join(", ", Arrays.copyOfRange(tokens, 1, tokens.length));
        switch (tables().updateSettings(table, changes)) {
            case UPDATED -> p.sendMessage(cfg().formatPrefixed("settable-updated", "setting", described, "value", "", "id", table.getId()));
            case REBUILT -> p.sendMessage(cfg().formatPrefixed("settable-rebuilt", "id", table.getId(),
                "value", tables().getTable(table.getId()) == null ? "?" : tables().getTable(table.getId()).getSeatCount()));
            case TABLE_BUSY -> p.sendMessage(cfg().getPrefixed("table-busy-seats"));
            case INVALID -> p.sendMessage(cfg().formatPrefixed("settable-invalid", "setting", described));
        }
    }

    private void cleanup(Player p, String[] args) {
        int radius = 10;
        if (args.length >= 2) {
            try {
                radius = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                p.sendMessage(cfg().getPrefixed("cleanup-usage"));
                return;
            }
        }
        radius = Math.max(1, Math.min(MAX_CLEANUP_RADIUS, radius));
        int removed = new CardDisplayCleaner(plugin, tables()).removeNear(p.getLocation(), radius);
        p.sendMessage(cfg().formatPrefixed("cleanup-done", "count", removed, "radius", radius));
    }

    // ---------- tab completion ----------

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> options = new ArrayList<>();
        if (args.length == 1) {
            options.addAll(PLAYER_SUBS);
            if (sender.hasPermission(ADMIN)) {
                options.addAll(ADMIN_SUBS);
            }
        } else {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if ((sub.equals("createtable") || sub.equals("settable")) && sender.hasPermission(ADMIN)) {
                TableSettings.KEYS.forEach(k -> options.add(k + ":"));
            } else if (sub.equals("stats") && args.length == 2 && sender.hasPermission(STATS_OTHERS)) {
                plugin.getServer().getOnlinePlayers().forEach(pl -> options.add(pl.getName()));
            } else if (sub.equals("removetable") && args.length == 2 && sender.hasPermission(ADMIN)) {
                tables().getTables().forEach(t -> options.add(String.valueOf(t.getId())));
            } else if ((sub.equals("bet") || sub.equals("raise")) && args.length == 2 && sender instanceof Player p) {
                PokerTable t = tables().getTableOf(p);
                var o = t == null ? null : t.getOptions(p);
                if (o != null && o.canWager()) {
                    options.add(String.valueOf(Math.min(o.minRaiseTo(), o.maxRaiseTo())));
                    options.add(String.valueOf(o.maxRaiseTo()));
                }
            }
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        options.removeIf(o -> !o.toLowerCase(Locale.ROOT).startsWith(prefix));
        return options;
    }
}
