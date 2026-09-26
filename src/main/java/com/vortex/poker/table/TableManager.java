package com.vortex.poker.table;

import com.vortex.poker.PokerPlugin;
import com.vortex.poker.config.ConfigManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * Builds, removes, persists and finds poker tables.
 *
 * Tables live in tables.yml under a numeric ID with their position, the way they face, and any
 * settings that override config.yml.
 */
public class TableManager {
    private final PokerPlugin plugin;
    private final Map<Integer, PokerTable> tables = new ConcurrentSkipListMap<>();
    private final Map<UUID, PokerTable> playerTables = new ConcurrentHashMap<>();
    private final File tablesFile;
    private YamlConfiguration tablesConfig = new YamlConfiguration();

    /** A block that belongs to a table: a chair (seat 0..n-1) or the felt (seat -1). */
    public record TableBlock(PokerTable table, int seat) {}

    /** Outcome of {@link #updateSettings}. */
    public enum UpdateResult { UPDATED, REBUILT, INVALID, TABLE_BUSY }

    public TableManager(PokerPlugin plugin) {
        this.plugin = plugin;
        this.tablesFile = new File(plugin.getDataFolder(), "tables.yml");
    }

    private ConfigManager config() {
        return plugin.getConfigManager();
    }

    // -------------------------------------------------------------------------
    // Loading
    // -------------------------------------------------------------------------

    public void loadTables() {
        tablesConfig = YamlConfiguration.loadConfiguration(tablesFile);
        ConfigurationSection section = tablesConfig.getConfigurationSection("tables");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                ConfigurationSection entry = section.getConfigurationSection(key);
                int id;
                try {
                    id = Integer.parseInt(key);
                } catch (NumberFormatException e) {
                    plugin.getLogger().warning("Skipping table with a non-numeric ID in tables.yml: " + key);
                    continue;
                }
                if (entry == null) continue;

                String worldName = entry.getString("world", "");
                World world = Bukkit.getWorld(worldName);
                if (world == null) {
                    // left in tables.yml untouched, so it comes back once the world is loaded again
                    plugin.getLogger().warning("Poker table #" + id + " is in world '" + worldName + "', which isn't loaded; skipping it.");
                    continue;
                }
                BlockFace facing = parseFacing(entry.getString("facing"));
                TableSettings settings = readSettings(entry);
                TableLayout layout = new TableLayout(world, entry.getInt("x"), entry.getInt("y"), entry.getInt("z"),
                    facing, settings.getMaxSeats(config()));
                tables.put(id, new PokerTable(plugin, this, id, layout, settings));
            }
        }
        plugin.getLogger().info("Loaded " + tables.size() + " poker table(s)");
    }

    // -------------------------------------------------------------------------
    // Building and removing
    // -------------------------------------------------------------------------

    /**
     * Build a table centred on the block the player stands in, its long side running the way
     * they face.
     *
     * @return the new table, or null if it would overlap another one
     */
    public PokerTable createTable(Location at, TableSettings settings) {
        World world = at.getWorld();
        if (world == null) return null;

        BlockFace facing = TableLayout.facingFromYaw(at.getYaw());
        TableLayout layout = new TableLayout(world, at.getBlockX(), at.getBlockY(), at.getBlockZ(),
            facing, settings.getMaxSeats(config()));
        for (PokerTable other : tables.values()) {
            if (layout.overlaps(other.getLayout())) {
                return null;
            }
        }

        buildFelt(layout);
        buildChairs(layout);

        int id = nextId();
        writeEntry(id, layout, settings);
        saveTablesFile();

        PokerTable table = new PokerTable(plugin, this, id, layout, settings);
        tables.put(id, table);
        return table;
    }

    private void buildFelt(TableLayout layout) {
        Material felt = config().getTableMaterial();
        for (Block block : layout.getFeltBlocks()) {
            block.setType(felt);
        }
    }

    private void buildChairs(TableLayout layout) {
        Material chair = config().getChairMaterial();
        for (int seat = 0; seat < layout.getSeatCount(); seat++) {
            Block block = layout.getChairBlock(seat);
            block.setType(chair);
            if (block.getBlockData() instanceof Stairs stairs) {
                stairs.setFacing(layout.getChairFacing(seat));
                stairs.setHalf(org.bukkit.block.data.Bisected.Half.BOTTOM);
                block.setBlockData(stairs);
            }
        }
    }

    /** Clear a layout's chairs, but only blocks that are still the configured chair material. */
    private void clearChairs(TableLayout layout) {
        Material chair = config().getChairMaterial();
        for (int seat = 0; seat < layout.getSeatCount(); seat++) {
            Block block = layout.getChairBlock(seat);
            if (block.getType() == chair) {
                block.setType(Material.AIR);
            }
        }
    }

    /**
     * Remove a table: everyone is cashed out and sent away, its displays and seats go, and it's
     * deleted from tables.yml. Felt and chairs are cleared as long as they're still the configured
     * materials, so anything an admin built or swapped in around the table stays.
     */
    public boolean removeTable(PokerTable table) {
        if (tables.remove(table.getId()) == null) return false;

        table.removeAllPlayers();
        table.cleanup();
        playerTables.values().removeIf(t -> t == table);

        TableLayout layout = table.getLayout();
        if (layout.getWorld() != null) {
            Material felt = config().getTableMaterial();
            for (Block block : layout.getFeltBlocks()) {
                if (block.getType() == felt) {
                    block.setType(Material.AIR);
                }
            }
            clearChairs(layout);
        }

        tablesConfig.set("tables." + table.getId(), null);
        saveTablesFile();
        return true;
    }

    /**
     * Apply setting overrides to a table and save them. A different seat count rebuilds the chairs
     * and replaces the table object, so it's only allowed while nobody is sitting there.
     *
     * @param changes only the fields that are set are applied
     */
    public UpdateResult updateSettings(PokerTable table, TableSettings changes) {
        TableSettings merged = new TableSettings();
        merged.apply(table.getSettings());
        merged.apply(changes);
        if (merged.validate(config()) != null) {
            return UpdateResult.INVALID;
        }

        TableLayout oldLayout = table.getLayout();
        int seats = merged.getMaxSeats(config());
        if (seats == oldLayout.getSeatCount()) {
            table.getSettings().apply(changes);
            writeEntry(table.getId(), oldLayout, table.getSettings());
            saveTablesFile();
            table.onSettingsChanged();
            return UpdateResult.UPDATED;
        }

        if (table.hasPlayers()) {
            return UpdateResult.TABLE_BUSY;
        }
        table.cleanup();
        clearChairs(oldLayout);
        TableLayout layout = oldLayout.withSeatCount(seats);
        buildChairs(layout);
        writeEntry(table.getId(), layout, merged);
        saveTablesFile();
        tables.put(table.getId(), new PokerTable(plugin, this, table.getId(), layout, merged));
        return UpdateResult.REBUILT;
    }

    // -------------------------------------------------------------------------
    // Lookup
    // -------------------------------------------------------------------------

    public PokerTable getTable(int id) {
        return tables.get(id);
    }

    /** All loaded tables, in ID order. */
    public Collection<PokerTable> getTables() {
        return List.copyOf(tables.values());
    }

    /** The closest table within its own max-join-distance of the location, or null. */
    public PokerTable findNearestTable(Location loc) {
        double best = Double.MAX_VALUE;
        PokerTable nearest = null;
        for (PokerTable table : tables.values()) {
            Location center = table.getLayout().getCenter();
            if (center.getWorld() == null || !center.getWorld().equals(loc.getWorld())) {
                continue;
            }
            double distance = center.distance(loc);
            if (distance <= table.getSettings().getMaxJoinDistance(config()) && distance < best) {
                best = distance;
                nearest = table;
            }
        }
        return nearest;
    }

    /** Which table (and which chair, if any) a block belongs to, or null. */
    public TableBlock findTableBlock(Block block) {
        for (PokerTable table : tables.values()) {
            TableLayout layout = table.getLayout();
            int seat = layout.seatAt(block);
            if (seat >= 0) {
                return new TableBlock(table, seat);
            }
            if (layout.isFelt(block)) {
                return new TableBlock(table, -1);
            }
        }
        return null;
    }

    /** The table a player is sitting at, or null. */
    public PokerTable getTableOf(Player player) {
        return playerTables.get(player.getUniqueId());
    }

    /** Record which table a player sits at; null clears it. */
    public void setPlayerTable(Player player, PokerTable table) {
        if (table == null) {
            playerTables.remove(player.getUniqueId());
        } else {
            playerTables.put(player.getUniqueId(), table);
        }
    }

    /** True if the entity is a card, text or seat currently in use by a table. */
    public boolean isActiveTableEntity(Entity entity) {
        for (PokerTable table : tables.values()) {
            if (table.ownsEntity(entity)) {
                return true;
            }
        }
        return false;
    }

    /** Plugin disable: void hands in progress, refund, clear displays, stand everyone up. */
    public void shutdown() {
        for (PokerTable table : tables.values()) {
            try {
                table.cleanup();
            } catch (RuntimeException e) {
                plugin.getLogger().severe("Error shutting down poker table #" + table.getId() + ": " + e);
            }
        }
        tables.clear();
        playerTables.clear();
    }

    // -------------------------------------------------------------------------
    // tables.yml
    // -------------------------------------------------------------------------

    private static BlockFace parseFacing(String name) {
        if (name == null) return BlockFace.NORTH;
        try {
            return TableLayout.cardinal(BlockFace.valueOf(name.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return BlockFace.NORTH;
        }
    }

    private int nextId() {
        int id = Math.max(1, tablesConfig.getInt("next-id", 1));
        tablesConfig.set("next-id", id + 1);
        return id;
    }

    private void writeEntry(int id, TableLayout layout, TableSettings settings) {
        String path = "tables." + id;
        tablesConfig.set(path, null);
        tablesConfig.set(path + ".world", layout.getWorld().getName());
        tablesConfig.set(path + ".x", layout.getX());
        tablesConfig.set(path + ".y", layout.getY());
        tablesConfig.set(path + ".z", layout.getZ());
        tablesConfig.set(path + ".facing", layout.getFacing().name());
        // only overrides are written; anything unset follows config.yml
        tablesConfig.set(path + ".seats", settings.getRawMaxSeats());
        tablesConfig.set(path + ".small-blind", settings.getRawSmallBlind());
        tablesConfig.set(path + ".big-blind", settings.getRawBigBlind());
        tablesConfig.set(path + ".min-buy-in-bb", settings.getRawMinBuyInBB());
        tablesConfig.set(path + ".max-buy-in-bb", settings.getRawMaxBuyInBB());
        tablesConfig.set(path + ".max-join-distance", settings.getRawMaxJoinDistance());
    }

    private boolean saveTablesFile() {
        tablesConfig.options().setHeader(List.of(
            "Poker tables, by ID. Managed by the plugin: use /poker createtable, /poker settable and",
            "/poker removetable instead of editing this while the server is running.",
            "Settings left out of a table follow config.yml. Buy-ins are in big blinds."));
        try {
            tablesConfig.save(tablesFile);
            return true;
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save tables.yml: " + e.getMessage());
            return false;
        }
    }

    private static TableSettings readSettings(ConfigurationSection sec) {
        Integer seats = sec.contains("seats") ? sec.getInt("seats") : null;
        Long sb = sec.contains("small-blind") ? sec.getLong("small-blind") : null;
        Long bb = sec.contains("big-blind") ? sec.getLong("big-blind") : null;
        Integer minBB = sec.contains("min-buy-in-bb") ? sec.getInt("min-buy-in-bb") : null;
        Integer maxBB = sec.contains("max-buy-in-bb") ? sec.getInt("max-buy-in-bb") : null;
        Double dist = sec.contains("max-join-distance") ? sec.getDouble("max-join-distance") : null;
        return new TableSettings(seats, sb, bb, minBB, maxBB, dist);
    }
}
