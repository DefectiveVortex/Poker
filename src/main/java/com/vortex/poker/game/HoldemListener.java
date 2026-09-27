package com.vortex.poker.game;

import com.vortex.poker.model.Card;
import com.vortex.poker.model.HandValue;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Receives everything that happens in a {@link HoldemGame}, synchronously and in order. Callbacks must
 * not call back into the game (schedule instead): the game is mid-transition while it notifies.
 */
public interface HoldemListener {

    default void onHandStarted(int handNumber, int button, int smallBlindSeat, int bigBlindSeat) {}

    default void onBlindPosted(int seat, long amount, boolean bigBlind, boolean allIn) {}

    /** Private cards for one seat; only its owner should see them. */
    default void onHoleCards(int seat, List<Card> cards) {}

    /** It is {@code seat}'s turn; start their timer and show their options. */
    default void onTurn(int seat, ActionOptions options) {}

    /**
     * A player acted. {@code type} is resolved (an ALL_IN arrives as CALL/BET/RAISE with {@code allIn}
     * set); {@code streetBet} is their total bet on this street afterwards. {@code left} marks a fold
     * caused by leaving the table.
     */
    default void onAction(int seat, ActionType type, long streetBet, boolean allIn, boolean left) {}

    /** New community cards; {@code board} is the whole board so far. */
    default void onStreet(Street street, List<Card> board) {}

    /**
     * Chips nobody called went back to their owner. If they already left the table ({@code seated}
     * false) the table has to pay {@code player} directly.
     */
    default void onUncalledReturned(int seat, UUID player, long amount, boolean seated) {}

    /** Hands still live at showdown, in the order they are shown (last aggressor first). */
    default void onShowdown(List<ShowdownHand> hands) {}

    /** One pot paid out. {@code winningHand} is null when everyone else folded (nothing is shown). */
    default void onPotAwarded(int potIndex, long amount, Map<Integer, Long> shares, HandValue winningHand) {}

    default void onHandEnded(HandSummary summary) {}

    default void onHandVoided(VoidResult result) {}

    /** One listener that forwards every event to each of {@code listeners}, in order. */
    static HoldemListener all(HoldemListener... listeners) {
        List<HoldemListener> ls = List.of(listeners);
        return new HoldemListener() {
            public void onHandStarted(int n, int b, int sb, int bb) { ls.forEach(l -> l.onHandStarted(n, b, sb, bb)); }
            public void onBlindPosted(int s, long a, boolean big, boolean allIn) { ls.forEach(l -> l.onBlindPosted(s, a, big, allIn)); }
            public void onHoleCards(int s, List<Card> c) { ls.forEach(l -> l.onHoleCards(s, c)); }
            public void onTurn(int s, ActionOptions o) { ls.forEach(l -> l.onTurn(s, o)); }
            public void onAction(int s, ActionType t, long bet, boolean allIn, boolean left) { ls.forEach(l -> l.onAction(s, t, bet, allIn, left)); }
            public void onStreet(Street st, List<Card> board) { ls.forEach(l -> l.onStreet(st, board)); }
            public void onUncalledReturned(int s, UUID p, long a, boolean seated) { ls.forEach(l -> l.onUncalledReturned(s, p, a, seated)); }
            public void onShowdown(List<ShowdownHand> h) { ls.forEach(l -> l.onShowdown(h)); }
            public void onPotAwarded(int i, long a, Map<Integer, Long> sh, HandValue v) { ls.forEach(l -> l.onPotAwarded(i, a, sh, v)); }
            public void onHandEnded(HandSummary s) { ls.forEach(l -> l.onHandEnded(s)); }
            public void onHandVoided(VoidResult r) { ls.forEach(l -> l.onHandVoided(r)); }
        };
    }
}
