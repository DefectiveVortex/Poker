package com.vortex.poker.display;

import com.vortex.poker.model.Card;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

/**
 * What players see on a poker table: cards, the dealer button and floating text. The game drives
 * it; nothing in here knows the rules.
 *
 * Seats are numbered 0..n-1 clockwise seen from above, the same numbering the table layout uses.
 * Every call replaces what that part of the table showed before, so calling one twice is harmless.
 */
public interface TableView {

    /** Shows nothing. For tests, and for a table whose view isn't built yet. */
    TableView NOOP = new TableView() {
        @Override public void dealHoleCards(int seat, Player owner, List<Card> cards) { }
        @Override public void setBoard(List<Card> cards) { }
        @Override public void revealHoleCards(int seat, List<Card> cards) { }
        @Override public void clearSeat(int seat) { }
        @Override public void setButton(int seat) { }
        @Override public void setSeatInfo(int seat, String text) { }
        @Override public void setPotInfo(String text) { }
        @Override public void highlightTurn(int seat) { }
        @Override public void clearHand() { }
        @Override public void destroy() { }
    };

    /** Lay a seat's two hole cards face down: the owner sees the faces, everyone else the backs. */
    void dealHoleCards(int seat, Player owner, List<Card> cards);

    /** Show the community cards (0, 3, 4 or 5 of them) in the middle of the table. */
    void setBoard(List<Card> cards);

    /** Turn a seat's hole cards face up for everyone (showdown). */
    void revealHoleCards(int seat, List<Card> cards);

    /** Take a seat's cards away (fold, or the player left). */
    void clearSeat(int seat);

    /** Move the dealer button in front of this seat. */
    void setButton(int seat);

    /**
     * Text floating over a seat, such as the name, stack and last action. Null or empty hides it.
     * Prefer the five-argument form: this one is read as the lines "name", "stack", "action".
     */
    void setSeatInfo(int seat, String text);

    /**
     * The label over a seat, in parts, so the view can stack them tidily: it leaves the name out
     * while the player's own name tag shows above their head, and shows a new action only briefly.
     * {@code player} is who sits there, or null for an empty seat (then the other parts are ignored
     * and the label goes). Null or empty parts are left out.
     */
    default void setSeatInfo(int seat, UUID player, String name, String stack, String action) {
        if (player == null) {
            setSeatInfo(seat, "");
            return;
        }
        // keep each part on its own line even when one is missing
        StringBuilder text = new StringBuilder(name == null ? "" : name)
            .append('\n').append(stack == null ? "" : stack);
        if (action != null && !action.isEmpty()) text.append('\n').append(action);
        setSeatInfo(seat, text.toString());
    }

    /** Text floating over the middle of the table. Null or empty hides it. */
    void setPotInfo(String text);

    /** Mark whose turn it is; -1 clears the marker. */
    void highlightTurn(int seat);

    /** Remove every card and the turn marker between hands. The button and seat texts stay. */
    void clearHand();

    /** Remove everything this view spawned. The view can't be used afterwards. */
    void destroy();

    /**
     * Celebrate a hand's winners: every seat that won anything, and the total paid out. Called
     * once per hand after the reveal (or the last fold), before clearHand().
     */
    default void showWinners(List<Integer> seats, long amount) {
    }

    /** Total in the middle, for the chip stack next to the board. 0 removes it. */
    default void setPotChips(long amount) {
    }

    /** True if the entity is one of this view's live displays (see CardDisplayCleaner). */
    default boolean ownsEntity(Entity entity) {
        return false;
    }

    /**
     * Re-apply who may see which hole card. Bukkit forgets per-player entity hiding when a player
     * relogs or changes world, so the listener calls this then.
     */
    default void refreshVisibility(Player viewer) {
    }
}
