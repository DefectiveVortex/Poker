package com.vortex.poker.gui;

import com.vortex.poker.PokerPlugin;
import com.vortex.poker.config.ConfigManager;
import com.vortex.poker.game.ActionOptions;
import com.vortex.poker.table.PokerTable;
import com.vortex.poker.util.ChatUtils;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * The turn menu (fold / check or call / bet or raise / all-in), its raise submenu (min-raise, ½ pot, pot,
 * all-in, custom) and the clickable chat buttons sent on each turn. Every amount is a "raise to" total.
 */
public class ActionMenu extends PokerMenu {
    private static final int INFO_SLOT = 4;
    private static final int FOLD_SLOT = 10;
    private static final int CHECK_CALL_SLOT = 12;
    private static final int WAGER_SLOT = 14;
    private static final int ALL_IN_SLOT = 16;
    private static final int BACK_SLOT = 21;
    private static final int CLOSE_SLOT = 22;
    private static final int CUSTOM_SLOT = 23;

    public ActionMenu(PokerPlugin plugin) {
        super(plugin);
    }

    /** Open the turn menu; does nothing (but say so) when it isn't the player's turn. */
    public void open(Player player, PokerTable table) {
        ActionOptions o = table.getOptions(player);
        if (o == null) {
            player.sendMessage(cfg().getPrefixed("not-your-turn"));
            return;
        }
        ConfigManager cfg = cfg();
        Holder h = newHolder(cfg.formatMessage("menu-title", "pot", money(o.potTotal())));
        set(h, INFO_SLOT, info(o), null);
        set(h, FOLD_SLOT, item(Material.RED_CONCRETE, cfg.getMessage("menu-fold"), List.of()),
            () -> act(player, table, () -> table.fold(player)));
        if (o.canCheck()) {
            set(h, CHECK_CALL_SLOT, item(Material.LIME_CONCRETE, cfg.getMessage("menu-check"), List.of()),
                () -> act(player, table, () -> table.check(player)));
        } else if (o.canCall()) {
            set(h, CHECK_CALL_SLOT, item(Material.YELLOW_CONCRETE,
                    cfg.formatMessage("menu-call", "amount", money(Math.min(o.toCall(), o.stack()))), List.of()),
                () -> act(player, table, () -> table.call(player)));
        }
        if (o.canWager()) {
            set(h, WAGER_SLOT, item(Material.GOLD_INGOT, cfg.getMessage(o.canBet() ? "menu-bet" : "menu-raise"), List.of()),
                () -> openRaise(player, table));
        }
        if (o.canAllIn()) {
            set(h, ALL_IN_SLOT, item(Material.TNT, cfg.formatMessage("menu-allin", "amount", money(o.stack())), List.of()),
                () -> act(player, table, () -> table.allIn(player)));
        }
        set(h, CLOSE_SLOT, item(Material.BARRIER, cfg.getMessage("menu-close"), List.of()), player::closeInventory);
        player.openInventory(h.getInventory());
    }

    /** The raise submenu: preset sizes as chips, plus custom and back. */
    public void openRaise(Player player, PokerTable table) {
        ActionOptions o = table.getOptions(player);
        if (o == null) {
            player.closeInventory();
            player.sendMessage(cfg().getPrefixed("not-your-turn"));
            return;
        }
        ConfigManager cfg = cfg();
        Holder h = newHolder(cfg.getMessage("raise-menu-title"));
        set(h, INFO_SLOT, info(o), null);
        List<RaiseOptions.Option> options = RaiseOptions.compute(o);
        int slot = 9 + (9 - options.size()) / 2;
        for (RaiseOptions.Option option : options) {
            String name = switch (option.kind()) {
                case MIN_RAISE -> cfg.formatMessage("menu-min-raise", "amount", money(option.raiseTo()));
                case HALF_POT -> cfg.formatMessage("menu-half-pot", "amount", money(option.raiseTo()));
                case POT -> cfg.formatMessage("menu-pot", "amount", money(option.raiseTo()));
                case ALL_IN -> cfg.formatMessage("menu-allin", "amount", money(option.raiseTo()));
            };
            Material chip = switch (option.kind()) {
                case MIN_RAISE -> Material.LIME_DYE;
                case HALF_POT -> Material.YELLOW_DYE;
                case POT -> Material.ORANGE_DYE;
                case ALL_IN -> Material.TNT;
            };
            long raiseTo = option.raiseTo();
            set(h, slot++, item(chip, name, List.of()), () -> act(player, table, () -> wager(player, table, raiseTo)));
        }
        if (o.canWager()) {
            set(h, CUSTOM_SLOT, item(Material.NAME_TAG, cfg.getMessage("menu-custom"), List.of(
                    cfg.formatMessage("menu-custom-range", "min", money(Math.min(o.minRaiseTo(), o.maxRaiseTo())),
                        "max", money(o.maxRaiseTo())))),
                () -> askAmount(player, cfg.formatPrefixed("raise-custom-prompt",
                        "min", money(Math.min(o.minRaiseTo(), o.maxRaiseTo())), "max", money(o.maxRaiseTo())),
                    amount -> wager(player, table, amount)));
        }
        set(h, BACK_SLOT, item(Material.ARROW, cfg.getMessage("menu-back"), List.of()), () -> open(player, table));
        set(h, CLOSE_SLOT, item(Material.BARRIER, cfg.getMessage("menu-close"), List.of()), player::closeInventory);
        player.openInventory(h.getInventory());
    }

    /**
     * Bet or raise to a street total, whichever applies right now; the full stack means all-in.
     * Used by the menus, /poker bet and /poker raise. Returns what the table returned.
     */
    public boolean wager(Player player, PokerTable table, long raiseTo) {
        ActionOptions o = table.getOptions(player);
        if (o == null) {
            player.sendMessage(cfg().getPrefixed("not-your-turn"));
            return false;
        }
        if (o.canAllIn() && raiseTo >= o.maxRaiseTo()) {
            return table.allIn(player);
        }
        return o.canBet() ? table.bet(player, raiseTo) : table.raise(player, raiseTo);
    }

    private void act(Player player, PokerTable table, Runnable action) {
        player.closeInventory();
        action.run();
    }

    /** Chat buttons for the current turn: fold, check/call, bet/raise (typed amount), all-in, menu. */
    public void sendChatButtons(Player player, PokerTable table) {
        ActionOptions o = table.getOptions(player);
        if (o == null) {
            return;
        }
        ConfigManager cfg = cfg();
        List<TextComponent> buttons = new ArrayList<>();
        buttons.add(button("fold", "/poker fold"));
        if (o.canCheck()) {
            buttons.add(button("check", "/poker check"));
        } else if (o.canCall()) {
            buttons.add(ChatUtils.runButton(cfg.formatMessage("buttons.call.text", "amount", money(Math.min(o.toCall(), o.stack()))),
                "/poker call", cfg.getButtonHover("call")));
        }
        if (o.canWager()) {
            String kind = o.canBet() ? "bet" : "raise";
            long suggested = Math.min(o.minRaiseTo(), o.maxRaiseTo());
            buttons.add(ChatUtils.suggestButton(cfg.getButtonText(kind), "/poker " + kind + " " + suggested,
                cfg.formatMessage("buttons." + kind + ".hover", "min", money(suggested), "max", money(o.maxRaiseTo()))));
        }
        if (o.canAllIn()) {
            buttons.add(ChatUtils.runButton(cfg.formatMessage("buttons.allin.text", "amount", money(o.stack())),
                "/poker allin", cfg.getButtonHover("allin")));
        }
        if (cfg.useMenus()) {
            buttons.add(button("menu", "/poker menu"));
        }
        ChatUtils.sendRow(player, cfg.getMessage("action-prompt"), cfg.getMessage("action-separator"), buttons);
    }

    private TextComponent button(String name, String command) {
        return ChatUtils.runButton(cfg().getButtonText(name), command, cfg().getButtonHover(name));
    }

    private org.bukkit.inventory.ItemStack info(ActionOptions o) {
        ConfigManager cfg = cfg();
        return item(Material.GOLD_NUGGET, cfg.formatMessage("menu-info-name", "pot", money(o.potTotal())), List.of(
            cfg.formatMessage("menu-info-to-call", "amount", money(o.toCall())),
            cfg.formatMessage("menu-stack-lore", "stack", money(o.stack()))));
    }

    private String money(long amount) {
        return cfg().formatCurrency(amount);
    }
}
