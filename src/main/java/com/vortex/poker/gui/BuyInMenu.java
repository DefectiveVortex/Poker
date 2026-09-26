package com.vortex.poker.gui;

import com.vortex.poker.PokerPlugin;
import com.vortex.poker.config.ConfigManager;
import com.vortex.poker.economy.EconomyProvider;
import com.vortex.poker.table.PokerTable;
import com.vortex.poker.util.ChatUtils;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.function.LongConsumer;
import java.util.function.LongFunction;

/**
 * Choosing how much to bring to the table: the buy-in when sitting down, and top-ups between hands.
 * A chest menu of amounts (or clickable chat amounts when interface.menu is chat), plus a typed custom amount.
 */
public class BuyInMenu extends PokerMenu {
    private static final int INFO_SLOT = 4;
    private static final int CLOSE_SLOT = 22;
    private static final int CUSTOM_SLOT = 23;

    public BuyInMenu(PokerPlugin plugin) {
        super(plugin);
    }

    /**
     * Offer buy-in amounts between min and max (capped to the player's balance). A choice calls
     * {@code table.sit(player, seat, amount)}. Says so and does nothing if they can't afford the minimum.
     */
    public void open(Player player, PokerTable table, int seat, long minBuyIn, long maxBuyIn, long defaultBuyIn) {
        EconomyProvider economy = plugin.getEconomyProvider();
        if (economy == null || !economy.isAvailable()) {
            player.sendMessage(cfg().getPrefixed("economy-unavailable"));
            return;
        }
        long balance = economy.getBalance(player.getUniqueId());
        if (balance < minBuyIn) {
            player.sendMessage(cfg().formatPrefixed("insufficient-funds", "amount", money(minBuyIn), "balance", money(balance)));
            return;
        }
        long max = Math.min(maxBuyIn, balance);
        List<Long> amounts = amounts(minBuyIn, max, defaultBuyIn);
        LongConsumer choose = amount -> {
            if (amount < minBuyIn || amount > maxBuyIn) {
                player.sendMessage(cfg().formatPrefixed("buy-in-out-of-range", "min", money(minBuyIn), "max", money(maxBuyIn)));
                return;
            }
            table.sit(player, seat, amount);
        };
        show(player, "buyin-menu-title", "buy-in-prompt", "buy-in", "buy-in-custom-prompt",
            minBuyIn, max, balance, amounts, choose,
            amount -> "/poker join " + amount + (seat >= 0 ? " " + (seat + 1) : ""));
    }

    /** Offer top-up amounts up to maxTopUp (capped to the balance); a choice calls {@code table.topUp}. */
    public void openTopUp(Player player, PokerTable table, long maxTopUp) {
        EconomyProvider economy = plugin.getEconomyProvider();
        if (economy == null || !economy.isAvailable()) {
            player.sendMessage(cfg().getPrefixed("economy-unavailable"));
            return;
        }
        if (maxTopUp <= 0) {
            player.sendMessage(cfg().getPrefixed("topup-none-allowed"));
            return;
        }
        long balance = economy.getBalance(player.getUniqueId());
        if (balance <= 0) {
            player.sendMessage(cfg().formatPrefixed("insufficient-funds", "amount", money(1), "balance", money(balance)));
            return;
        }
        long max = Math.min(maxTopUp, balance);
        List<Long> amounts = amounts(Math.max(1, max / 4), max, max / 2);
        show(player, "topup-menu-title", "topup-prompt", "topup", "topup-custom-prompt",
            1, max, balance, amounts, amount -> table.topUp(player, amount), amount -> "/poker topup " + amount);
    }

    private void show(Player player, String titleKey, String promptKey, String buttonKey, String customPromptKey,
                      long min, long max, long balance, List<Long> amounts, LongConsumer choose, LongFunction<String> command) {
        ConfigManager cfg = cfg();
        if (!cfg.useMenus()) {
            player.sendMessage(cfg.formatPrefixed(promptKey, "min", money(min), "max", money(max)));
            List<TextComponent> buttons = new ArrayList<>();
            for (long amount : amounts) {
                buttons.add(ChatUtils.runButton(cfg.formatMessage("buttons." + buttonKey + ".text", "amount", money(amount)),
                    command.apply(amount), cfg.formatMessage("buttons." + buttonKey + ".hover", "amount", money(amount))));
            }
            ChatUtils.sendRow(player, "", " ", buttons);
            player.sendMessage(cfg.formatMessage(customPromptKey, "min", money(min), "max", money(max)));
            return;
        }
        Holder h = newHolder(cfg.getMessage(titleKey));
        set(h, INFO_SLOT, item(Material.GOLD_INGOT, cfg.formatMessage(promptKey, "min", money(min), "max", money(max)),
            List.of(cfg.formatMessage("menu-balance-lore", "balance", money(balance)))), null);
        int slot = 9 + (9 - amounts.size()) / 2;
        Material[] chips = {Material.LIME_DYE, Material.YELLOW_DYE, Material.ORANGE_DYE, Material.RED_DYE, Material.MAGENTA_DYE};
        for (int i = 0; i < amounts.size(); i++) {
            long amount = amounts.get(i);
            set(h, slot++, item(chips[Math.min(i, chips.length - 1)], cfg.formatMessage("menu-amount", "amount", money(amount)), List.of()),
                () -> {
                    player.closeInventory();
                    choose.accept(amount);
                });
        }
        set(h, CUSTOM_SLOT, item(Material.NAME_TAG, cfg.getMessage("menu-custom"),
                List.of(cfg.formatMessage("menu-custom-range", "min", money(min), "max", money(max)))),
            () -> askAmount(player, cfg.formatPrefixed("amount-prompt", "min", money(min), "max", money(max)), choose));
        set(h, CLOSE_SLOT, item(Material.BARRIER, cfg.getMessage("menu-close"), List.of()), player::closeInventory);
        player.openInventory(h.getInventory());
    }

    /** Up to five distinct amounts between min and max: min, a quarter and half of the range, the default, max. */
    static List<Long> amounts(long min, long max, long preferred) {
        TreeSet<Long> set = new TreeSet<>();
        if (max < min) {
            return List.of();
        }
        set.add(min);
        set.add(min + (max - min) / 4);
        set.add(min + (max - min) / 2);
        if (preferred >= min && preferred <= max) {
            set.add(preferred);
        }
        set.add(max);
        return new ArrayList<>(set).subList(0, Math.min(5, set.size()));
    }

    private String money(long amount) {
        return cfg().formatCurrency(amount);
    }
}
