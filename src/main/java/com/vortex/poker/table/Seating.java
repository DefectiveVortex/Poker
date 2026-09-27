package com.vortex.poker.table;

import com.vortex.poker.PokerPlugin;
import com.vortex.poker.util.ServerCompat;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Puts players on a table's chairs. With table.seat-players on, a player rides an invisible marker
 * armor stand placed so their hips rest on the stair; sneaking takes them off. With it off they're
 * only teleported to the chair, and GSit's /sit is used if GSit is installed.
 *
 * Stands are non-persistent and tagged, so a crash never leaves one behind for good
 * (CardDisplayCleaner removes stragglers when their chunk loads).
 */
public class Seating {
    public static final String SEAT_TAG = "poker-seat";

    private final PokerPlugin plugin;
    private final String tableTag;
    private TableLayout layout;
    private final Map<UUID, ArmorStand> stands = new ConcurrentHashMap<>();

    public Seating(PokerPlugin plugin, TableLayout layout, int tableId) {
        this.plugin = plugin;
        this.layout = layout;
        this.tableTag = com.vortex.poker.display.WorldTableView.tableTag(tableId);
    }

    /** Follow a rebuilt table (seat count changed). Only call with nobody seated. */
    public void setLayout(TableLayout layout) {
        this.layout = layout;
    }

    private static boolean gsitInstalled() {
        return Bukkit.getPluginManager().getPlugin("GSit") != null;
    }

    /**
     * Teleport the player to a seat's chair, looking at the table, and sit them down.
     */
    public void sit(Player player, int seat) {
        if (seat < 0 || seat >= layout.getSeatCount()) {
            return;
        }
        stand(player);
        Location chair = layout.getChairLocation(seat);
        chair.setPitch(28f); // the board and the hole cards both in view
        player.teleport(chair);

        if (plugin.getConfigManager().shouldSeatPlayers()) {
            mount(player, layout.getSeatLocation(seat));
        } else if (gsitInstalled()) {
            // give the teleport a moment to land before GSit looks for the stair under them
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline() && player.getLocation().distanceSquared(chair) < 2.0) {
                    player.performCommand("sit");
                }
            }, 5L);
        }
    }

    /**
     * Mount the player on an invisible marker stand whose rider's hips land on the stair's low
     * step, half a block above the chair block and a little towards the table
     * (TableLayout.SEAT_INSET), so their eyes end up at TableLayout.SEATED_EYE_HEIGHT.
     */
    private void mount(Player player, Location seat) {
        double hipsY = seat.getBlockY() + 0.5;
        Location standLoc = new Location(seat.getWorld(), seat.getX(),
            hipsY - ServerCompat.RIDER_HIP_HEIGHT + ServerCompat.STAND_ABOVE_RIDER_FEET, seat.getZ(),
            seat.getYaw(), 0f);

        ArmorStand stand = seat.getWorld().spawn(standLoc, ArmorStand.class);
        stand.setMarker(true);
        stand.setVisible(false);
        stand.setGravity(false);
        stand.setInvulnerable(true);
        stand.setSilent(true);
        stand.setBasePlate(false);
        stand.setPersistent(false);
        stand.addScoreboardTag(SEAT_TAG);
        stand.addScoreboardTag(tableTag);
        stand.addPassenger(player);
        stands.put(player.getUniqueId(), stand);
    }

    /** Take the player off their seat, if they're on one. Safe to call for anyone. */
    public void stand(Player player) {
        ArmorStand stand = stands.remove(player.getUniqueId());
        if (stand != null) {
            stand.eject();
            stand.remove();
        }
    }

    /** True if the player has a seat stand here (whether or not they're on it right now). */
    public boolean isSeated(Player player) {
        return stands.containsKey(player.getUniqueId());
    }

    /**
     * Put a player who got off their seat back on it. While they still hold sneak the server takes
     * them straight off again, so keep re-seating every tick until they've stayed on for half a
     * second (or five seconds pass, or they left).
     */
    public void reseat(Player player) {
        new BukkitRunnable() {
            private int ticks;
            private int mountedTicks;

            @Override
            public void run() {
                ArmorStand stand = stands.get(player.getUniqueId());
                if (stand == null || !stand.isValid() || !player.isOnline() || ++ticks > 100) {
                    cancel();
                    return;
                }
                if (stand.getPassengers().contains(player)) {
                    if (++mountedTicks >= 10) {
                        cancel();
                    }
                    return;
                }
                mountedTicks = 0;
                stand.addPassenger(player);
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    /** True if the entity is one of this table's seat stands. */
    public boolean ownsEntity(Entity entity) {
        for (ArmorStand stand : stands.values()) {
            if (stand.getUniqueId().equals(entity.getUniqueId())) {
                return true;
            }
        }
        return false;
    }

    /** Stand everyone up and remove every seat. */
    public void clear() {
        for (ArmorStand stand : new ArrayList<>(stands.values())) {
            stand.eject();
            stand.remove();
        }
        stands.clear();
    }
}
