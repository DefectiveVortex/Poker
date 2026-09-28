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
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.Collection;
import java.util.List;
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
    private final TableStore store;
    /** Tables whose world isn't loaded (yet), by ID. */
    private final Map<Integer, TableStore.Entry> waitingForWorld = new ConcurrentSkipListMap<>();

    /** A block that belongs to a table: a chair (seat 0..n-1) or the felt (seat -1). */
    public record TableBlock(PokerTable table, int seat) {}

    /** Table geometry version written to tables.yml. 1 = 5x3 felt (1.0), 2 = compact 3x3. */
    static final int LAYOUT_VERSION = 2;

    /** Outcome of {@link #updateSettings}. */
    public enum UpdateResult { UPDATED, REBUILT, INVALID, TABLE_BUSY }

    public TableManager(PokerPlugin plugin) {
        this.plugin = plugin;
        this.store = new TableStore(new File(plugin.getDataFolder(), "tables.yml"), plugin.getLogger());
    }

    private ConfigManager config() {
        return plugin.getConfigManager();
    }

    // -------------------------------------------------------------------------
    // Loading
    // -------------------------------------------------------------------------

    public void loadTables() {
        TableStore.Loaded loaded = store.load();
        for (TableStore.Skipped skip : loaded.skipped()) {
            String where = loaded.state() == TableStore.FileState.BROKEN
                ? "it is only in " + (loaded.brokenCopy() == null ? "the broken copy" : loaded.brokenCopy().getName())
                : "left in tables.yml untouched";
            plugin.getLogger().warning("Poker table '" + skip.key() + "' in tables.yml not loaded: " + skip.reason() + " (" + where + ")");
        }
        int waiting = 0, skipped = loaded.skipped().size();
        for (TableStore.Entry entry : loaded.entries()) {
            switch (tryLoad(entry)) {
                case WAITING_FOR_WORLD -> waiting++;
                case SKIPPED -> skipped++;
                default -> { }
            }
        }
        StringBuilder line = new StringBuilder("Loaded " + tables.size() + " poker table(s)");
        if (waiting > 0) line.append(", ").append(waiting).append(" waiting for their world to load");
        if (skipped > 0) line.append(", ").append(skipped).append(" skipped (see the warnings above)");
        plugin.getLogger().info(line.toString());
    }

    private enum LoadOutcome { LOADED, WAITING_FOR_WORLD, SKIPPED }

    /**
     * Build one table from its tables.yml entry, if its world is loaded and it fits there. A table
     * whose world isn't loaded waits for it (see {@link #onWorldLoaded}); it is never removed from
     * tables.yml for that, nor for anything else short of /poker removetable.
     */
    private LoadOutcome tryLoad(TableStore.Entry entry) {
        int id = entry.id();
        if (tables.containsKey(id)) return LoadOutcome.LOADED;
        World world = Bukkit.getWorld(entry.world());
        if (world == null) {
            waitingForWorld.put(id, entry);
            plugin.getLogger().warning("Poker table #" + id + " is in world '" + entry.world()
                + "', which isn't loaded; it will load when the world does (left in tables.yml).");
            return LoadOutcome.WAITING_FOR_WORLD;
        }
        waitingForWorld.remove(id);
        String problem = null;
        if (entry.y() < world.getMinHeight() || entry.y() + 1 >= world.getMaxHeight()) {
            problem = "y " + entry.y() + " is outside world '" + world.getName() + "' (" + world.getMinHeight()
                + " to " + (world.getMaxHeight() - 2) + ")";
        }
        if (problem == null) problem = entry.settings().validate(config());
        TableLayout layout = null;
        if (problem == null) {
            layout = new TableLayout(world, entry.x(), entry.y(), entry.z(), entry.facing(), entry.settings().getMaxSeats(config()));
            for (PokerTable other : tables.values()) {
                if (layout.overlaps(other.getLayout())) {
                    problem = "it overlaps table #" + other.getId();
                    break;
                }
            }
        }
        if (problem != null) {
            plugin.getLogger().warning("Poker table #" + id + " in tables.yml not loaded: " + problem + " (left in tables.yml untouched)");
            return LoadOutcome.SKIPPED;
        }
        if (entry.layout() < LAYOUT_VERSION) {
            rebuildLegacyTable(id, layout, entry.settings());
        }
        tables.put(id, new PokerTable(plugin, this, id, layout, entry.settings()));
        return LoadOutcome.LOADED;
    }

    /** A world came up: load the tables that were waiting for it. */
    public void onWorldLoaded(World world) {
        List<TableStore.Entry> ready = waitingForWorld.values().stream()
            .filter(e -> e.world().equals(world.getName())).toList();
        int loaded = 0;
        for (TableStore.Entry entry : ready) {
            if (tryLoad(entry) == LoadOutcome.LOADED) loaded++;
        }
        if (loaded > 0) {
            plugin.getLogger().info("Loaded " + loaded + " poker table(s) in world '" + world.getName() + "'");
        }
    }

    /**
     * Tables built before the compact layout (5x3 felt, chairs a block out) are torn down and
     * rebuilt as 3x3 on the same centre and facing. Only blocks that are still the configured felt
     * and chair materials are cleared, so anything an admin built around the table stays.
     */
    private void rebuildLegacyTable(int id, TableLayout layout, TableSettings settings) {
        Material felt = config().getTableMaterial();
        Material chair = config().getChairMaterial();
        for (Block block : layout.legacyFeltBlocks()) {
            if (block.getType() == felt) block.setType(Material.AIR);
        }
        for (Block block : layout.legacyChairBlocks(layout.getSeatCount())) {
            if (block.getType() == chair) block.setType(Material.AIR);
        }
        buildFelt(layout);
        buildChairs(layout);
        writeEntry(id, layout, settings);
        saveTablesFile();
        plugin.getLogger().info("Rebuilt poker table #" + id + " with the compact layout");
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

        int id = store.nextId();
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

        store.remove(table.getId());
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

    private void writeEntry(int id, TableLayout layout, TableSettings settings) {
        store.put(id, layout.getWorld().getName(), layout.getX(), layout.getY(), layout.getZ(),
            layout.getFacing(), LAYOUT_VERSION, settings);
    }

    private boolean saveTablesFile() {
        return store.save();
    }
}
