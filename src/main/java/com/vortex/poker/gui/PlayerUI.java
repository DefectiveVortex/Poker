package com.vortex.poker.gui;

import com.vortex.poker.PokerPlugin;
import com.vortex.poker.config.ConfigManager;
import com.vortex.poker.game.ActionOptions;
import com.vortex.poker.model.Card;
import com.vortex.poker.model.HandEvaluator;
import com.vortex.poker.model.HandValue;
import com.vortex.poker.table.PokerTable;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;

import java.util.ArrayList;
import java.util.List;

/**
 * What a player sees and hears around the game, as opposed to the rules: the compact turn prompt, the
 * first-time how-to, sounds and the winner's title. PokerTable calls these at the matching game events.
 */
public class PlayerUI implements Listener {
    private final PokerPlugin plugin;

    public PlayerUI(PokerPlugin plugin) {
        this.plugin = plugin;
    }

    private ConfigManager cfg() {
        return plugin.getConfigManager();
    }

    private String money(long amount) {
        return cfg().formatCurrency(String.format("%,d", amount));
    }

    // ---------- turn ----------

    /**
     * One compact prompt per turn: your cards and the board on one line (plus your best hand when
     * interface.show-hand-strength is on); the bot-matched
     * "Your turn - to call | pot | stack" line; then the clickable actions. Also pings.
     */
    public void sendTurnPrompt(Player p, PokerTable table, ActionOptions o, List<Card> holeCards) {
        ConfigManager cfg = cfg();
        String cards = holeCards == null || holeCards.isEmpty() ? "" : cfg.formatCards(holeCards);
        List<Card> board = table.getBoard();
        if (!cards.isEmpty()) {
            String line = cfg.formatMessage(board.isEmpty() ? "turn-line-preflop" : "turn-line",
                "cards", cards, "board", cfg.formatCards(board));
            if (cfg.showHandStrength()) {
                line += cfg.formatMessage("turn-line-hand", "hand", bestHand(holeCards, board));
            }
            send(p, line);
        }
        send(p, cfg.formatPrefixed("turn-prompt",
            "to_call", money(o.toCall()), "pot", money(o.potTotal()), "stack", money(o.stack())));
        if (cfg.sendChatButtons()) {
            plugin.getActionMenu().sendChatButtons(p, table);
        }
        turn(p);
    }

    /** "Pair of Kings" from hole cards alone before the flop, the best five-card hand after it. */
    String bestHand(List<Card> hole, List<Card> board) {
        if (hole == null || hole.size() != 2) {
            return "";
        }
        if (board.size() < 3) {
            return cfg().describeHole(hole);
        }
        List<Card> all = new ArrayList<>(hole);
        all.addAll(board);
        HandValue value = HandEvaluator.best(all);
        return cfg().describeHand(value);
    }

    // ---------- first time at a table ----------

    /** After a successful sit: a short how-to the first time a player ever sits down. */
    public void onSeated(Player p, PokerTable table) {
        if (!cfg().showFirstTimeGuide() || !plugin.getStatsManager().markGuideSeen(p.getUniqueId())) {
            return;
        }
        for (String line : cfg().getMessageList("first-time-guide")) {
            send(p, ConfigManager.fill(line, "big_blind", money(table.getBigBlind()),
                "small_blind", money(table.getSmallBlind())));
        }
    }

    // ---------- sounds and flair ----------

    /** Cards arrived. */
    public void dealt(Player p) {
        sound(p, cfg().getCardDealSound(), "card-deal");
    }

    /** It's your turn. */
    public void turn(Player p) {
        sound(p, cfg().getTurnSound(), "your-turn");
    }

    /** Chips hit the felt (bet, call, raise, all-in): heard by everyone around the table. */
    public void chips(PokerTable table) {
        Sound s = cfg().getChipsSound();
        if (s == null || !cfg().areSoundsEnabled()) {
            return;
        }
        Location at = table.getLayout().getCenter();
        World world = at.getWorld();
        if (world != null) {
            world.playSound(at, s, cfg().getSoundVolume("chips"), cfg().getSoundPitch("chips"));
        }
    }

    public void folded(Player p) {
        sound(p, cfg().getFoldSound(), "fold");
    }

    /**
     * The winner's moment: fanfare, a title ("You win $1,200" / "with a Flush!") and particles.
     * {@code hand} is null when everyone else folded.
     */
    public void win(Player p, long amount, HandValue hand) {
        ConfigManager cfg = cfg();
        sound(p, cfg.getWinSound(), "win");
        sound(p, cfg.getFanfareSound(), "win-fanfare");
        if (cfg.showWinTitle()) {
            String title = cfg.formatMessage("win-title", "amount", money(amount));
            String subtitle = hand == null ? cfg.getMessage("win-subtitle-uncontested")
                : cfg.formatMessage("win-subtitle", "hand", cfg.getMessage("hand." + hand.getRank().name()));
            p.sendTitle(title, subtitle, 5, 50, 15);
        }
        Particle particle = cfg.getWinParticle();
        if (particle != null && cfg.areParticlesEnabled() && cfg.getWinParticleCount() > 0) {
            try {
                p.getWorld().spawnParticle(particle, p.getLocation().add(0, 2, 0), cfg.getWinParticleCount(), 0.5, 0.5, 0.5);
            } catch (IllegalArgumentException e) {
                // a particle that needs data on this version: skip it rather than fail the payout
            }
        }
    }

    private void sound(Player p, Sound s, String name) {
        if (s != null && cfg().areSoundsEnabled()) {
            p.playSound(p.getLocation(), s, cfg().getSoundVolume(name), cfg().getSoundPitch(name));
        }
    }

    /** Sends nothing for a message that's blank once colours are removed, so lines can be muted in messages.yml. */
    public static void send(Player p, String message) {
        if (message != null && !org.bukkit.ChatColor.stripColor(message).isBlank()) {
            p.sendMessage(message);
        }
    }
}
