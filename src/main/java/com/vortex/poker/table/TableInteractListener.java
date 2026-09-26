package com.vortex.poker.table;

import com.vortex.poker.PokerPlugin;
import com.vortex.poker.config.ConfigManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.EquipmentSlot;

/**
 * The physical side of a table: right-click a chair or the felt to sit down, right-click the felt
 * while seated for the action menu, sneak to leave. Also takes players away from their table when
 * they quit or teleport off, keeps hidden hole cards hidden across relogs and world changes, and
 * stops the felt and chairs being broken by anyone without poker.admin.
 */
public class TableInteractListener implements Listener {
    private final PokerPlugin plugin;
    private final TableManager tableManager;

    public TableInteractListener(PokerPlugin plugin, TableManager tableManager) {
        this.plugin = plugin;
        this.tableManager = tableManager;
    }

    // LOW so the click is claimed before sit plugins (GSit sits players on stairs) see it
    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND
                || event.getClickedBlock() == null) {
            return;
        }
        Player player = event.getPlayer();
        if (player.isSneaking()) {
            return; // sneak-click still places blocks against the table, for building around it
        }
        TableManager.TableBlock hit = tableManager.findTableBlock(event.getClickedBlock());
        if (hit == null) {
            return;
        }
        ConfigManager cfg = plugin.getConfigManager();
        PokerTable current = tableManager.getTableOf(player);
        if (current == hit.table()) {
            event.setCancelled(true);
            if (hit.seat() < 0) {
                current.onTableClick(player);
            }
            return;
        }
        if (!cfg.isClickToJoin()) {
            return;
        }
        event.setCancelled(true);
        if (current != null) {
            player.sendMessage(cfg.getMessage("already-at-table"));
            return;
        }
        if (!player.hasPermission("poker.play")) {
            player.sendMessage(cfg.getMessage("no-permission"));
            return;
        }
        hit.table().join(player, hit.seat());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSneak(PlayerToggleSneakEvent event) {
        if (!event.isSneaking() || !plugin.getConfigManager().shouldSeatPlayers()) {
            return;
        }
        Player player = event.getPlayer();
        PokerTable table = tableManager.getTableOf(player);
        if (table != null && table.confirmSneakLeave(player)) {
            table.leave(player);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        PokerTable table = tableManager.getTableOf(player);
        if (table != null) {
            table.leave(player);
        }
        tableManager.setPlayerTable(player, null);
        plugin.getCardResourcePack().forget(player);
    }

    /** Teleporting to another world, or well away from the table, leaves it. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        PokerTable table = tableManager.getTableOf(player);
        Location to = event.getTo();
        if (table == null || to == null) {
            return;
        }
        Location center = table.getLayout().getCenter();
        double range = table.getSettings().getMaxJoinDistance(plugin.getConfigManager());
        if (to.getWorld() != center.getWorld() || to.distanceSquared(center) > range * range) {
            table.leave(player);
        }
    }

    /**
     * A player saved while riding a seat (server crash) would log back in on a recreated stand; get
     * them off it. Then re-apply hole-card hiding, which Bukkit doesn't keep across a relog.
     */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Entity vehicle = player.getVehicle();
        if (vehicle != null && vehicle.getScoreboardTags().contains(Seating.SEAT_TAG)) {
            vehicle.eject();
            vehicle.remove();
        }
        refreshVisibilityLater(player);
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        refreshVisibilityLater(event.getPlayer());
    }

    private void refreshVisibilityLater(Player player) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                for (PokerTable table : tableManager.getTables()) {
                    table.getView().refreshVisibility(player);
                }
            }
        }, 2L);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (!event.getPlayer().hasPermission("poker.admin") && tableManager.findTableBlock(event.getBlock()) != null) {
            event.setCancelled(true);
        }
    }
}
