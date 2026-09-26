package com.vortex.poker.gui;

import com.vortex.poker.PokerPlugin;
import com.vortex.poker.config.ConfigManager;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongConsumer;

/**
 * Shared plumbing for the chest menus: each open menu remembers what every slot does, nothing can be taken
 * out, and "custom amount" reads the player's next chat line.
 *
 * Clicks are identified by raw slot and the event's inventory only. InventoryView became an interface in
 * 1.21, so calling methods on it from this jar would break on 1.20 servers.
 */
public abstract class PokerMenu implements Listener {
    protected static final int SIZE = 27;
    private static final long CUSTOM_AMOUNT_WINDOW_MS = 30_000;

    protected final PokerPlugin plugin;
    private final Map<UUID, PendingAmount> awaitingAmount = new ConcurrentHashMap<>();

    private record PendingAmount(long since, LongConsumer onAmount) {}

    /** Marks our inventories and holds the action behind each slot. */
    protected final class Holder implements InventoryHolder {
        private final Map<Integer, Runnable> actions = new HashMap<>();
        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }

        PokerMenu owner() {
            return PokerMenu.this;
        }
    }

    protected PokerMenu(PokerPlugin plugin) {
        this.plugin = plugin;
    }

    protected ConfigManager cfg() {
        return plugin.getConfigManager();
    }

    protected Holder newHolder(String title) {
        Holder holder = new Holder();
        holder.inventory = Bukkit.createInventory(holder, SIZE, title);
        return holder;
    }

    /** Put an item in a slot; a null action makes it display-only. */
    protected void set(Holder holder, int slot, ItemStack item, Runnable action) {
        holder.inventory.setItem(slot, item);
        if (action != null) {
            holder.actions.put(slot, action);
        }
    }

    protected static ItemStack item(Material material, String name, List<String> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(lore);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    /** Ask for an amount in chat (next line within 30 s, or "cancel"); the callback runs on the main thread. */
    protected void askAmount(Player player, String promptMessage, LongConsumer onAmount) {
        player.closeInventory();
        awaitingAmount.put(player.getUniqueId(), new PendingAmount(System.currentTimeMillis(), onAmount));
        player.sendMessage(promptMessage);
    }

    /** Whole amounts only; "1,500", "$1500" and "1.5k" are accepted. Returns -1 when unparseable. */
    public static long parseAmount(String text) {
        String t = text.trim().toLowerCase().replace(",", "").replace("$", "").replace("_", "");
        long multiplier = 1;
        if (t.endsWith("k")) {
            multiplier = 1_000;
            t = t.substring(0, t.length() - 1);
        } else if (t.endsWith("m")) {
            multiplier = 1_000_000;
            t = t.substring(0, t.length() - 1);
        }
        try {
            double value = Double.parseDouble(t) * multiplier;
            if (value < 0 || value > Long.MAX_VALUE / 2 || Double.isNaN(value)) {
                return -1;
            }
            return (long) Math.floor(value);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Holder holder) || holder.owner() != this) {
            return;
        }
        event.setCancelled(true); // nothing in the menu can be taken or moved in
        if (!(event.getWhoClicked() instanceof Player) || event.getRawSlot() < 0 || event.getRawSlot() >= SIZE) {
            return;
        }
        Runnable action = holder.actions.get(event.getRawSlot());
        if (action != null) {
            action.run();
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Holder holder && holder.owner() == this) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        PendingAmount pending = awaitingAmount.remove(player.getUniqueId());
        if (pending == null || System.currentTimeMillis() - pending.since() > CUSTOM_AMOUNT_WINDOW_MS) {
            return;
        }
        event.setCancelled(true);
        String text = event.getMessage().trim();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            if (text.equalsIgnoreCase("cancel") || text.equalsIgnoreCase(cfg().getMessage("amount-cancel-word"))) {
                player.sendMessage(cfg().getPrefixed("amount-cancelled"));
                return;
            }
            long amount = parseAmount(text);
            if (amount <= 0) {
                player.sendMessage(cfg().formatPrefixed("invalid-amount", "value", text));
                return;
            }
            pending.onAmount().accept(amount);
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        awaitingAmount.remove(event.getPlayer().getUniqueId());
    }
}
