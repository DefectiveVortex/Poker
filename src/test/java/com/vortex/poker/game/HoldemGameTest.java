package com.vortex.poker.game;

import com.vortex.poker.model.Card;
import com.vortex.poker.model.Deck;
import com.vortex.poker.model.HandRank;
import com.vortex.poker.model.HandValue;
import com.vortex.poker.model.Rank;
import com.vortex.poker.model.Suit;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static com.vortex.poker.game.ActionType.*;
import static org.junit.jupiter.api.Assertions.*;

class HoldemGameTest {

    // ------------------------------------------------------------ helpers

    /** Records every callback so tests can assert on the event stream. */
    static class Recorder implements HoldemListener {
        final List<String> events = new ArrayList<>();
        final List<ShowdownHand> shown = new ArrayList<>();
        final List<Map<Integer, Long>> awards = new ArrayList<>();
        final List<HandValue> awardHands = new ArrayList<>();
        final List<Long> uncalled = new ArrayList<>();
        long paidToLeavers;
        HandSummary summary;
        VoidResult voided;

        public void onHandStarted(int n, int button, int sb, int bb) { events.add("start b" + button + " sb" + sb + " bb" + bb); }
        public void onBlindPosted(int seat, long amount, boolean big, boolean allIn) { events.add((big ? "bb" : "sb") + seat + "=" + amount); }
        public void onTurn(int seat, ActionOptions o) { events.add("turn" + seat); }
        public void onAction(int seat, ActionType t, long bet, boolean allIn, boolean left) {
            events.add(seat + ":" + t + (t == BET || t == RAISE || t == CALL ? bet : "") + (allIn ? "!" : "") + (left ? "(left)" : ""));
        }
        public void onStreet(Street s, List<Card> board) { events.add(s.name()); }
        public void onUncalledReturned(int seat, UUID p, long amount, boolean seated) { uncalled.add(amount); if (!seated) paidToLeavers += amount; events.add("uncalled" + seat + "=" + amount); }
        public void onShowdown(List<ShowdownHand> hands) { shown.addAll(hands); }
        public void onPotAwarded(int i, long amount, Map<Integer, Long> shares, HandValue h) { awards.add(shares); awardHands.add(h); }
        public void onHandEnded(HandSummary s) { summary = s; }
        public void onHandVoided(VoidResult r) { voided = r; }
    }

    static final UUID[] P = new UUID[8];
    static {
        for (int i = 0; i < P.length; i++) P[i] = new UUID(0, i + 1);
    }

    /**
     * A deck that deals {@code holes[i]} to the i-th seat in deal order (first seat left of the button
     * first), then the board; burn cards are filled in from unused cards.
     */
    static Supplier<Deck> deck(List<String> holes, String board) {
        Set<Card> used = new LinkedHashSet<>();
        List<Card> first = new ArrayList<>();
        List<Card> second = new ArrayList<>();
        for (String h : holes) {
            first.add(Card.of(h.substring(0, 2)));
            second.add(Card.of(h.substring(2, 4)));
        }
        List<Card> b = new ArrayList<>();
        for (int i = 0; i < board.length(); i += 2) b.add(Card.of(board.substring(i, i + 2)));
        used.addAll(first);
        used.addAll(second);
        used.addAll(b);
        List<Card> spare = new ArrayList<>();
        for (Suit s : Suit.values()) for (Rank r : Rank.values()) {
            Card c = new Card(r, s);
            if (!used.contains(c)) spare.add(c);
        }
        List<Card> order = new ArrayList<>(first);
        order.addAll(second);
        order.add(spare.remove(0));
        order.addAll(b.subList(0, 3));
        order.add(spare.remove(0));
        order.add(b.get(3));
        order.add(spare.remove(0));
        order.add(b.get(4));
        return () -> Deck.stacked(order);
    }

    static Supplier<Deck> anyDeck() {
        Random rng = new Random(42);
        return () -> new Deck(rng);
    }

    static HoldemGame game(int seats, long sb, long bb, Supplier<Deck> deck, Recorder rec, long... stacks) {
        HoldemGame g = new HoldemGame(seats, sb, bb, deck);
        g.setListener(rec);
        for (int i = 0; i < stacks.length; i++) {
            if (stacks[i] >= 0) g.seatPlayer(i, P[i], stacks[i]);
        }
        return g;
    }

    static long totalChips(HoldemGame g) {
        long t = g.getPot();
        for (int s = 0; s < g.getMaxSeats(); s++) t += g.getStack(s) + g.getPendingTopUp(s);
        return t;
    }

    static IllegalActionException.Reason reasonOf(Runnable r) {
        return assertThrows(IllegalActionException.class, r::run).getReason();
    }

    // ------------------------------------------------------------ blinds, button, order

    @Test
    void headsUpButtonPostsSmallBlindAndActsFirstPreflop() {
        Recorder rec = new Recorder();
        HoldemGame g = game(6, 50, 100, anyDeck(), rec, 1000, 1000);
        g.startHand();
        assertEquals(0, g.getButton());
        assertEquals(0, g.getSmallBlindSeat());
        assertEquals(1, g.getBigBlindSeat());
        assertEquals(0, g.getActor());
        g.act(0, CALL, 0);
        assertEquals(1, g.getActor(), "big blind gets the option");
        ActionOptions o = g.getOptions(1);
        assertTrue(o.canCheck());
        assertTrue(o.canRaise());
        g.act(1, CHECK, 0);
        assertEquals(Street.FLOP, g.getStreet());
        assertEquals(1, g.getActor(), "heads-up the big blind acts first after the flop");
    }

    @Test
    void buttonRotatesAndBlindsFollow() {
        Recorder rec = new Recorder();
        HoldemGame g = game(6, 5, 10, anyDeck(), rec, 1000, -1, 1000, 1000);
        g.startHand();
        assertEquals(List.of(0, 2, 3), List.of(g.getButton(), g.getSmallBlindSeat(), g.getBigBlindSeat()));
        assertEquals(0, g.getActor(), "UTG is the button with three players");
        g.act(0, FOLD, 0);
        g.act(2, FOLD, 0);
        assertFalse(g.isHandInProgress());
        g.startHand();
        assertEquals(List.of(2, 3, 0), List.of(g.getButton(), g.getSmallBlindSeat(), g.getBigBlindSeat()));
        assertEquals(2, g.getActor());
    }

    @Test
    void everyoneFoldsToBigBlindWhoShowsNothing() {
        Recorder rec = new Recorder();
        HoldemGame g = game(6, 50, 100, anyDeck(), rec, 1000, 1000, 1000);
        g.startHand(); // button 0, sb 1, bb 2
        g.act(0, FOLD, 0);
        g.act(1, FOLD, 0);
        assertFalse(g.isHandInProgress());
        assertTrue(rec.shown.isEmpty());
        assertNull(rec.awardHands.get(0), "uncontested: no hand shown");
        assertEquals(List.of(50L), rec.uncalled, "the big blind's unmatched 50 comes back");
        assertEquals(1050, g.getStack(2));
        assertEquals(950, g.getStack(1));
        assertFalse(rec.summary.showdown());
        SeatResult bb = rec.summary.results().stream().filter(r -> r.seat() == 2).findFirst().orElseThrow();
        assertEquals(50, bb.net());
        assertEquals(3000, totalChips(g));
    }

    @Test
    void preflopFoldToRaiseReturnsUncalledPart() {
        Recorder rec = new Recorder();
        HoldemGame g = game(6, 50, 100, anyDeck(), rec, 1000, 1000, 1000);
        g.startHand();
        g.act(0, RAISE, 300);
        g.act(1, FOLD, 0);
        g.act(2, FOLD, 0);
        assertEquals(1150, g.getStack(0), "wins both blinds; 200 of the raise was uncalled");
        assertEquals(List.of(200L), rec.uncalled);
    }

    // ------------------------------------------------------------ raising rules

    @Test
    void minRaiseIsTheLastFullRaise() {
        Recorder rec = new Recorder();
        HoldemGame g = game(6, 50, 100, anyDeck(), rec, 5000, 5000, 5000, 5000);
        g.startHand(); // b0 sb1 bb2, utg 3
        ActionOptions o = g.getOptions(3);
        assertEquals(100, o.toCall());
        assertEquals(200, o.minRaiseTo());
        assertEquals(IllegalActionException.Reason.AMOUNT_TOO_SMALL, reasonOf(() -> g.act(3, RAISE, 150)));
        g.act(3, RAISE, 350); // raise of 250
        assertEquals(600, g.getOptions(0).minRaiseTo());
        assertEquals(IllegalActionException.Reason.AMOUNT_TOO_SMALL, reasonOf(() -> g.act(0, RAISE, 599)));
        assertEquals(IllegalActionException.Reason.AMOUNT_TOO_LARGE, reasonOf(() -> g.act(0, RAISE, 5001)));
        g.act(0, RAISE, 600);
        assertEquals(850, g.getOptions(1).minRaiseTo());
    }

    @Test
    void postflopMinimumBetIsTheBigBlind() {
        Recorder rec = new Recorder();
        HoldemGame g = game(6, 50, 100, anyDeck(), rec, 1000, 1000);
        g.startHand();
        g.act(0, CALL, 0);
        g.act(1, CHECK, 0);
        ActionOptions o = g.getOptions(1);
        assertTrue(o.canBet());
        assertFalse(o.canRaise());
        assertEquals(100, o.minRaiseTo());
        assertEquals(IllegalActionException.Reason.AMOUNT_TOO_SMALL, reasonOf(() -> g.act(1, BET, 99)));
        assertEquals(IllegalActionException.Reason.NOTHING_TO_CALL, reasonOf(() -> g.act(1, CALL, 0)));
        g.act(1, BET, 100);
        assertEquals(IllegalActionException.Reason.CANNOT_CHECK, reasonOf(() -> g.act(0, CHECK, 0)));
        assertEquals(IllegalActionException.Reason.NOT_YOUR_TURN, reasonOf(() -> g.act(1, CHECK, 0)));
    }

    @Test
    void shortAllInDoesNotReopenBettingForPlayerWhoActed() {
        Recorder rec = new Recorder();
        // seat 0 button, 1 sb, 2 bb. Seat 1 is short.
        HoldemGame g = game(6, 50, 100, anyDeck(), rec, 5000, 250, 5000);
        g.startHand();
        g.act(0, CALL, 0);
        g.act(1, CALL, 0);
        g.act(2, CHECK, 0);
        // flop: sb first
        assertEquals(1, g.getActor());
        g.act(1, CHECK, 0);
        g.act(2, BET, 100);
        g.act(0, CALL, 0);
        g.act(1, ALL_IN, 0); // 150 total: a raise of 50, less than a full raise
        assertEquals(150, g.getCurrentBet());
        ActionOptions bb = g.getOptions(2);
        assertEquals(50, bb.toCall());
        assertFalse(bb.canRaise(), "a short all-in doesn't reopen the betting");
        assertFalse(bb.canAllIn());
        assertEquals(IllegalActionException.Reason.CANNOT_RAISE, reasonOf(() -> g.act(2, RAISE, 400)));
        assertEquals(IllegalActionException.Reason.CANNOT_RAISE, reasonOf(() -> g.act(2, ALL_IN, 0)));
        g.act(2, CALL, 0);
        assertFalse(g.getOptions(0).canRaise());
        g.act(0, CALL, 0);
        assertEquals(Street.TURN, g.getStreet());
    }

    @Test
    void shortAllInStillLetsAnUnactedPlayerRaiseAndFullRaiseReopens() {
        Recorder rec = new Recorder();
        HoldemGame g = game(6, 50, 100, anyDeck(), rec, 5000, 5000, 5000, 180);
        g.startHand(); // b0 sb1 bb2 utg3 (short)
        g.act(3, ALL_IN, 0); // 180: short raise of 80
        ActionOptions btn = g.getOptions(0);
        assertTrue(btn.canRaise(), "hasn't acted yet");
        assertEquals(280, btn.minRaiseTo(), "min raise is the last FULL raise (100) on top of 180");
        g.act(0, CALL, 0);
        g.act(1, CALL, 0);
        g.act(2, RAISE, 500); // a full raise: re-opens for the button
        assertTrue(g.getOptions(0).canRaise());
    }

    @Test
    void shortAllInsThatAddUpToAFullRaiseReopen() {
        Recorder rec = new Recorder();
        HoldemGame g = game(6, 50, 100, anyDeck(), rec, 5000, 5000, 5000, 5000, 470, 700);
        g.startHand(); // b0 sb1 bb2 3 4 5
        g.act(3, CALL, 0);
        g.act(4, CALL, 0);
        g.act(5, CALL, 0);
        g.act(0, CALL, 0);
        g.act(1, CALL, 0);
        g.act(2, CHECK, 0);
        // flop, sb first
        g.act(1, BET, 300);
        g.act(2, FOLD, 0);
        g.act(3, CALL, 0);
        g.act(4, ALL_IN, 0); // 370: +70, short
        assertEquals(370, g.getCurrentBet());
        g.act(5, ALL_IN, 0); // 600: +230, short again, but 300 over what seats 1 and 3 agreed to
        assertEquals(600, g.getCurrentBet());
        assertEquals(900, g.getOptions(0).minRaiseTo(), "minimum stays the last full raise (300)");
        g.act(0, CALL, 0);
        assertTrue(g.getOptions(1).canRaise(), "short all-ins adding up to a full raise reopen the betting");
    }

    @Test
    void shortAllInsBelowAFullRaiseInTotalDoNotReopen() {
        Recorder rec = new Recorder();
        HoldemGame g = game(6, 50, 100, anyDeck(), rec, 5000, 5000, 5000, 5000, 470, 550);
        g.startHand();
        g.act(3, CALL, 0);
        g.act(4, CALL, 0);
        g.act(5, CALL, 0);
        g.act(0, CALL, 0);
        g.act(1, CALL, 0);
        g.act(2, CHECK, 0);
        g.act(1, BET, 300);
        g.act(2, FOLD, 0);
        g.act(3, CALL, 0);
        g.act(4, ALL_IN, 0); // 370
        g.act(5, ALL_IN, 0); // 450: 150 over the 300 seat 1 bet
        g.act(0, CALL, 0);
        assertFalse(g.getOptions(1).canRaise());
        assertEquals(150, g.getOptions(1).toCall());
    }

    // ------------------------------------------------------------ side pots, splits, showdown

    @Test
    void threeWayAllInBuildsSidePots() {
        Recorder rec = new Recorder();
        // button 0 (100 chips), sb 1 (300), bb 2 (500). Deal order: 1, 2, 0.
        // Seat 0 has the best hand, seat 1 second, seat 2 worst.
        HoldemGame g = game(6, 5, 10, deck(List.of("KsKh", "7c2d", "AsAh"), "Ad9c5h3s8d"), rec, 100, 300, 500);
        g.startHand();
        g.act(0, ALL_IN, 0);
        g.act(1, ALL_IN, 0);
        assertFalse(g.getOptions(2).canAllIn(), "nobody left to bet against: only a call");
        g.act(2, CALL, 0);
        assertFalse(g.isHandInProgress());
        assertTrue(rec.uncalled.isEmpty());
        assertEquals(300, g.getStack(0), "main pot 3 x 100");
        assertEquals(400, g.getStack(1), "side pot 2 x 200");
        assertEquals(200, g.getStack(2));
        assertEquals(900, totalChips(g));
        assertTrue(rec.summary.showdown());
        assertEquals(3, rec.shown.size());
        assertEquals(Map.of(0, 300L), rec.awards.get(0));
        assertEquals(Map.of(1, 400L), rec.awards.get(1));
    }

    @Test
    void bestHandWithBiggestStackTakesEverything() {
        Recorder rec = new Recorder();
        HoldemGame g = game(6, 5, 10, deck(List.of("KsKh", "AsAh", "7c2d"), "Ad9c5h3s8d"), rec, 100, 300, 500);
        g.startHand();
        g.act(0, ALL_IN, 0);
        g.act(1, ALL_IN, 0);
        g.act(2, CALL, 0);
        assertEquals(0, g.getStack(0));
        assertEquals(0, g.getStack(1));
        assertEquals(900, g.getStack(2));
    }

    @Test
    void splitPotGivesOddChipToFirstSeatLeftOfButton() {
        Recorder rec = new Recorder();
        // Royal flush on the board: everyone still in ties. Deal order 1, 2, 0.
        HoldemGame g = game(6, 5, 10, deck(List.of("2c3d", "2d3c", "4c4d"), "AsKsQsJsTs"), rec, 1000, 1000, 1000);
        g.startHand();
        g.act(0, CALL, 0);
        g.act(1, FOLD, 0); // 5 dead in the pot -> 25
        g.act(2, CHECK, 0);
        for (int street = 0; street < 3; street++) {
            g.act(2, CHECK, 0);
            g.act(0, CHECK, 0);
        }
        assertEquals(Map.of(2, 13L, 0, 12L), rec.awards.get(0));
        assertEquals(HandRank.ROYAL_FLUSH, rec.awardHands.get(0).getRank());
        assertEquals(1003, g.getStack(2));
        assertEquals(1002, g.getStack(0));
        assertEquals(3000, totalChips(g));
    }

    @Test
    void splitHelperHandsOutRemainderInOrder() {
        assertEquals(Map.of(4, 34L, 1, 33L, 2, 33L), HoldemGame.split(100, List.of(4, 1, 2)));
        assertEquals(Map.of(3, 50L, 0, 50L), HoldemGame.split(100, List.of(3, 0)));
    }

    @Test
    void lastAggressorShowsFirst() {
        Recorder rec = new Recorder();
        HoldemGame g = game(6, 5, 10, deck(List.of("KsKh", "7c2d", "AsAh"), "Ad9c5h3s8d"), rec, 1000, 1000, 1000);
        g.startHand(); // b0 sb1 bb2
        g.act(0, CALL, 0);
        g.act(1, CALL, 0);
        g.act(2, CHECK, 0);
        for (int i = 0; i < 2; i++) { // flop, turn: all check
            g.act(1, CHECK, 0);
            g.act(2, CHECK, 0);
            g.act(0, CHECK, 0);
        }
        g.act(1, CHECK, 0);
        g.act(2, BET, 50);
        g.act(0, CALL, 0);
        g.act(1, CALL, 0);
        assertEquals(List.of(2, 0, 1), rec.shown.stream().map(ShowdownHand::seat).toList());
    }

    @Test
    void withoutRiverBetFirstSeatLeftOfButtonShowsFirst() {
        Recorder rec = new Recorder();
        HoldemGame g = game(6, 5, 10, deck(List.of("KsKh", "7c2d", "AsAh"), "Ad9c5h3s8d"), rec, 1000, 1000, 1000);
        g.startHand();
        g.act(0, CALL, 0);
        g.act(1, CALL, 0);
        g.act(2, CHECK, 0);
        for (int i = 0; i < 3; i++) {
            g.act(1, CHECK, 0);
            g.act(2, CHECK, 0);
            g.act(0, CHECK, 0);
        }
        assertEquals(List.of(1, 2, 0), rec.shown.stream().map(ShowdownHand::seat).toList());
        assertEquals(1020, g.getStack(0), "aces win the 30 pot");
    }

    @Test
    void shortBlindAllInRunsTheBoardOut() {
        Recorder rec = new Recorder();
        HoldemGame g = game(2, 50, 100, anyDeck(), rec, 30, 1000);
        g.startHand(); // heads-up: button 0 posts sb all-in for 30
        assertFalse(g.isHandInProgress(), "nobody can act: straight to showdown");
        assertEquals(List.of(70L), rec.uncalled);
        assertEquals(5, g.getBoard().size());
        assertEquals(1030, totalChips(g));
        assertEquals(60, rec.awards.stream().flatMap(m -> m.values().stream()).mapToLong(Long::longValue).sum());
    }

    @Test
    void callerCoveringAnAllInOnlyCallsWhatIsNeeded() {
        Recorder rec = new Recorder();
        HoldemGame g = game(6, 50, 100, anyDeck(), rec, 1000, 1000, 400);
        g.startHand(); // b0 sb1 bb2(400)
        g.act(0, FOLD, 0);
        g.act(1, RAISE, 300);
        g.act(2, ALL_IN, 0); // 400: short raise
        ActionOptions o = g.getOptions(1);
        assertEquals(100, o.toCall());
        assertFalse(o.canRaise(), "nobody left to raise against");
        g.act(1, CALL, 0);
        assertFalse(g.isHandInProgress());
        assertEquals(2400, totalChips(g));
    }

    // ------------------------------------------------------------ timer, leaving, voiding, top-ups

    @Test
    void timeoutChecksWhenPossibleOtherwiseFolds() {
        Recorder rec = new Recorder();
        HoldemGame g = game(6, 50, 100, anyDeck(), rec, 1000, 1000, 1000);
        g.startHand();
        assertEquals(FOLD, g.timeout()); // button faces the big blind
        g.act(1, CALL, 0);
        assertEquals(CHECK, g.timeout()); // big blind option
        assertEquals(Street.FLOP, g.getStreet());
    }

    @Test
    void leavingOnYourTurnFoldsAndCashesOut() {
        Recorder rec = new Recorder();
        HoldemGame g = game(6, 50, 100, anyDeck(), rec, 1000, 1000, 1000);
        g.startHand();
        g.act(0, RAISE, 300);
        g.act(1, CALL, 0);
        assertEquals(2, g.getActor());
        assertEquals(900, g.removePlayer(2));
        assertTrue(rec.events.contains("2:FOLD(left)"));
        assertEquals(Street.FLOP, g.getStreet());
        assertEquals(1, g.getActor());
        assertFalse(g.isSeatAvailable(2), "seat stays blocked until the hand is over");
    }

    @Test
    void leavingOutOfTurnFoldsAndLastPlayerWins() {
        Recorder rec = new Recorder();
        HoldemGame g = game(6, 50, 100, anyDeck(), rec, 1000, 1000);
        g.startHand(); // heads-up, button 0 to act
        assertEquals(900, g.removePlayer(1));
        assertFalse(g.isHandInProgress());
        assertEquals(1050, g.getStack(0), "wins the called half of the big blind");
        assertEquals(50, rec.paidToLeavers, "the uncalled half goes back to the leaver");
        SeatResult leaver = rec.summary.results().stream().filter(r -> r.seat() == 1).findFirst().orElseThrow();
        assertTrue(leaver.left());
        assertEquals(-50, leaver.net());
        assertTrue(g.isSeatAvailable(1));
    }

    @Test
    void allInCallForLessEndsBettingAndReturnsTheExcess() {
        Recorder rec = new Recorder();
        List<Object[]> paid = new ArrayList<>();
        HoldemGame g = game(6, 50, 100, anyDeck(), rec, 1000, 1000, 200);
        g.setListener(new Recorder() {
            @Override
            public void onUncalledReturned(int seat, UUID p, long amount, boolean seated) {
                paid.add(new Object[]{p, amount, seated});
            }
        });
        g.startHand(); // b0 sb1 bb2(200)
        g.act(0, RAISE, 800);
        g.act(1, FOLD, 0);
        g.act(2, ALL_IN, 0); // 200
        // All-in call for less ends the betting at once, returns 600 to seat 0 and runs the board.
        assertFalse(g.isHandInProgress());
        assertEquals(1, paid.size());
        assertEquals(P[0], paid.get(0)[0]);
        assertEquals(600L, paid.get(0)[1]);
        assertEquals(true, paid.get(0)[2]);
    }

    @Test
    void leaverGetsTheirUncalledBetBackDirectly() {
        Recorder rec = new Recorder();
        HoldemGame g = game(6, 50, 100, anyDeck(), rec, 1000, 1000);
        g.startHand();
        g.act(0, CALL, 0);
        g.act(1, CHECK, 0);
        g.act(1, BET, 500);
        assertEquals(400, g.removePlayer(1));
        assertEquals(500, rec.paidToLeavers);
        assertEquals(1100, g.getStack(0));
        assertEquals(-100, rec.summary.results().stream().filter(r -> r.seat() == 1).findFirst().orElseThrow().net());
    }

    @Test
    void voidHandRefundsEveryContribution() {
        Recorder rec = new Recorder();
        HoldemGame g = game(6, 50, 100, anyDeck(), rec, 1000, 1000, 1000);
        g.startHand();
        g.act(0, RAISE, 300);
        g.act(1, CALL, 0);
        long cashOut = g.removePlayer(2); // folds, 100 left behind in the pot
        assertEquals(900, cashOut);
        VoidResult r = g.voidHand();
        assertEquals(Map.of(0, 300L, 1, 300L), r.refundedToStacks());
        assertEquals(Map.of(P[2], 100L), r.refundedToLeavers());
        assertEquals(1000, g.getStack(0));
        assertEquals(1000, g.getStack(1));
        assertFalse(g.isHandInProgress());
        assertEquals(0, g.getPot());
    }

    @Test
    void topUpWaitsForTheHandToEnd() {
        Recorder rec = new Recorder();
        HoldemGame g = game(6, 50, 100, anyDeck(), rec, 1000, 1000);
        g.startHand();
        assertFalse(g.addChips(0, 500));
        assertEquals(950, g.getStack(0));
        g.act(0, FOLD, 0);
        assertEquals(1450, g.getStack(0));
        assertTrue(g.addChips(0, 50));
        assertEquals(1500, g.getStack(0));
    }

    @Test
    void newPlayerIsDealtInNextHandAndBustedPlayerIsSkipped() {
        Recorder rec = new Recorder();
        HoldemGame g = game(6, 5, 10, deck(List.of("7c2d", "AsAh"), "Ad9c5h3s8d"), rec, 100, 100);
        g.startHand();
        g.seatPlayer(3, P[3], 500);
        g.act(0, ALL_IN, 0);
        g.act(1, CALL, 0);
        assertEquals(0, g.getStack(0) + g.getStack(1) - 200);
        int busted = g.getStack(0) == 0 ? 0 : 1;
        assertTrue(g.canStartHand());
        g.startHand();
        assertFalse(g.isLive(busted));
        assertTrue(g.isLive(3));
    }

    @Test
    void cannotStartWithOneFundedPlayer() {
        HoldemGame g = game(6, 5, 10, anyDeck(), new Recorder(), 100, 0);
        assertFalse(g.canStartHand());
        assertThrows(IllegalStateException.class, g::startHand);
    }

    // ------------------------------------------------------------ fuzz: chips are conserved

    @Test
    void randomPlayConservesChips() {
        Random rng = new Random(7);
        for (int table = 0; table < 40; table++) {
            int seats = 2 + rng.nextInt(7);
            Recorder rec = new Recorder();
            HoldemGame g = new HoldemGame(seats, 5, 10, () -> new Deck(rng));
            g.setListener(rec);
            long total = 0;
            for (int s = 0; s < seats; s++) {
                if (rng.nextInt(4) == 0) continue;
                long chips = 1 + rng.nextInt(400);
                g.seatPlayer(s, P[s], chips);
                total += chips;
            }
            long paidToLeavers = 0;
            for (int hand = 0; hand < 60 && g.canStartHand(); hand++) {
                g.startHand();
                int guard = 0;
                while (g.isHandInProgress()) {
                    assertTrue(++guard < 500, "hand never ended");
                    assertEquals(total, totalChips(g) + paidToLeavers + rec.paidToLeavers);
                    ActionOptions o = g.getOptions();
                    assertNotNull(o, "hand in progress with nobody to act");
                    int seat = o.seat();
                    int pick = rng.nextInt(20);
                    if (pick == 0 && rng.nextInt(10) == 0) {
                        paidToLeavers += g.removePlayer(seat);
                        continue;
                    }
                    if (pick < 3) g.act(seat, FOLD, 0);
                    else if (pick < 5 && o.canAllIn()) g.act(seat, ALL_IN, 0);
                    else if (pick < 9 && o.canWager()) {
                        long to = o.minRaiseTo() + (long) (rng.nextDouble() * (o.maxRaiseTo() - o.minRaiseTo()));
                        g.act(seat, o.canBet() ? BET : RAISE, to);
                    } else if (o.canCheck()) g.act(seat, CHECK, 0);
                    else g.act(seat, CALL, 0);
                }
                assertEquals(total, totalChips(g) + paidToLeavers + rec.paidToLeavers);
                long net = rec.summary.results().stream().mapToLong(SeatResult::net).sum();
                assertEquals(0, net, "a hand's winnings equal its losses: " + rec.summary);
            }
        }
    }
}
