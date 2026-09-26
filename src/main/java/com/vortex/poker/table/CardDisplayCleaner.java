package com.vortex.poker.table;

import com.vortex.poker.PokerPlugin;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.EntitiesLoadEvent;

/**
 * Finds and removes table displays and seats that no table owns any more.
 *
 * Everything a table spawns is non-persistent, so a live one is never written to disk. Anything
 * tagged that comes back when a chunk loads is therefore left over from a crash or a hard stop.
 */
public class CardDisplayCleaner implements Listener {
    /** Carried by every card, text display and button a table spawns. */
    public static final String DISPLAY_TAG = "poker-display";

    private final PokerPlugin plugin;
    private final TableManager tableManager;

    public CardDisplayCleaner(PokerPlugin plugin, TableManager tableManager) {
        this.plugin = plugin;
        this.tableManager = tableManager;
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        int removed = 0;
        for (Entity entity : event.getEntities()) {
            if (isTableEntity(entity) && !tableManager.isActiveTableEntity(entity)) {
                entity.remove();
                removed++;
            }
        }
        if (removed > 0) {
            plugin.getLogger().info("Removed " + removed + " leftover poker display(s)/seat(s) in chunk "
                + event.getChunk().getX() + "," + event.getChunk().getZ()
                + " (" + event.getChunk().getWorld().getName() + ")");
        }
    }

    /**
     * Remove every table entity within {@code radius} blocks that isn't in use. Only loaded chunks
     * can be reached; the load listener handles the rest.
     */
    public int removeNear(Location center, double radius) {
        if (center.getWorld() == null) {
            return 0;
        }
        int removed = 0;
        for (Entity entity : center.getWorld().getNearbyEntities(center, radius, radius, radius,
                CardDisplayCleaner::isTableEntity)) {
            if (!tableManager.isActiveTableEntity(entity)) {
                entity.remove();
                removed++;
            }
        }
        return removed;
    }

    /** A card, text display, button or seat stand spawned by this plugin. */
    public static boolean isTableEntity(Entity entity) {
        var tags = entity.getScoreboardTags();
        return tags.contains(DISPLAY_TAG) || tags.contains(Seating.SEAT_TAG);
    }
}
