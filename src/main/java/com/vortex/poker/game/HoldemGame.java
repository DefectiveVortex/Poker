package com.vortex.poker.game;

import com.vortex.poker.model.Card;
import com.vortex.poker.model.Deck;
import com.vortex.poker.model.HandEvaluator;
import com.vortex.poker.model.HandValue;
import com.vortex.poker.model.Pot;
import com.vortex.poker.model.PotCalculator;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntPredicate;
import java.util.function.Supplier;

/**
 * No-limit Texas Hold 'Em for one table, as a plain state machine: no Bukkit, no timers, no money.
 * The table seats players with a chip stack, calls {@link #startHand()}, feeds in actions, and hears
 * about everything through a {@link HoldemListener}.
 *
 * <p>Seats are 0..maxSeats-1 and seat+1 (mod maxSeats) is the next seat clockwise. Everything runs
 * synchronously on the caller's thread; the whole hand can finish inside a single {@link #act} call.
 *
 * <p>Rules: the button moves one funded seat per hand; heads-up the button posts the small blind and
 * acts first preflop. A raise must be at least the last full raise; an all-in for less doesn't reopen
 * the betting to players who already acted, unless the raises they face add up to a full raise.
 * Uncalled chips go back to their owner at the end of each betting round. Split pots give the odd
 * chips one at a time starting from the first seat left of the button.
 */
public final class HoldemGame {

    private final int maxSeats;
    private final Supplier<Deck> deckFactory;
    private HoldemListener listener = new HoldemListener() {};
    private long smallBlind;
    private long bigBlind;

    // Who sits where, across hands
    private final UUID[] player;
    private final long[] stack;
    private final long[] pendingTopUp;

    // Per hand
    private final boolean[] inHand;       // dealt into the current hand (stays true after folding or leaving)
    private final boolean[] folded;
    private final boolean[] acted;        // acted voluntarily on this street (posting a blind doesn't count)
    private final long[] streetBet;
    private final long[] contributed;     // chips put in this hand, uncalled returns already taken back out
    private final long[] facedWhenActed;  // the bet level a player last agreed to, for re-opening after short all-ins
    private final long[] won;
    private final UUID[] handPlayer;      // who was dealt in at each seat, even after they leave
    private final List<List<Card>> hole;
    private final List<Card> board = new ArrayList<>();
    private Deck deck;

    private boolean handInProgress;
    private int handNumber;
    private Street street = Street.PREFLOP;
    private int button = -1;
    private int smallBlindSeat = -1;
    private int bigBlindSeat = -1;
    private int actor = -1;
    private long currentBet;
    private long lastRaiseSize;
    private int streetAggressor = -1;
    private int finalRoundAggressor = -1;
    private boolean showdownReached;

    public HoldemGame(int maxSeats, long smallBlind, long bigBlind, Supplier<Deck> deckFactory) {
        if (maxSeats < 2) throw new IllegalArgumentException("need at least 2 seats");
        this.maxSeats = maxSeats;
        this.deckFactory = deckFactory;
        setBlinds(smallBlind, bigBlind);
        player = new UUID[maxSeats];
        stack = new long[maxSeats];
        pendingTopUp = new long[maxSeats];
        inHand = new boolean[maxSeats];
        folded = new boolean[maxSeats];
        acted = new boolean[maxSeats];
        streetBet = new long[maxSeats];
        contributed = new long[maxSeats];
        facedWhenActed = new long[maxSeats];
        won = new long[maxSeats];
        handPlayer = new UUID[maxSeats];
        hole = new ArrayList<>(maxSeats);
        for (int i = 0; i < maxSeats; i++) {
            hole.add(new ArrayList<>(2));
        }
    }

    public void setListener(HoldemListener listener) {
        this.listener = listener == null ? new HoldemListener() {} : listener;
    }

    /** Change the blinds; takes effect from the next hand. */
    public void setBlinds(long smallBlind, long bigBlind) {
        if (smallBlind < 0 || bigBlind <= 0 || smallBlind > bigBlind) {
            throw new IllegalArgumentException("bad blinds " + smallBlind + "/" + bigBlind);
        }
        if (handInProgress) throw new IllegalStateException("can't change blinds mid-hand");
        this.smallBlind = smallBlind;
        this.bigBlind = bigBlind;
    }

    // ---------------------------------------------------------------- seating

    /** A seat can be taken if it's empty and wasn't vacated during the hand still being played. */
    public boolean isSeatAvailable(int seat) {
        checkSeat(seat);
        return player[seat] == null && !(handInProgress && inHand[seat]);
    }

    /** Seat a player with a stack. They are dealt in from the next hand. */
    public void seatPlayer(int seat, UUID id, long chips) {
        if (id == null) throw new IllegalArgumentException("player id");
        if (chips < 0) throw new IllegalArgumentException("negative stack");
        if (!isSeatAvailable(seat)) throw new IllegalStateException("seat " + seat + " is taken");
        if (seatOf(id) >= 0) throw new IllegalStateException(id + " is already seated");
        player[seat] = id;
        stack[seat] = chips;
        pendingTopUp[seat] = 0;
    }

    /**
     * Take a player off the table. Mid-hand this is a fold (their chips in the pot stay there).
     * Returns the chips to cash out: the stack behind plus any top-up not yet applied.
     */
    public long removePlayer(int seat) {
        checkSeat(seat);
        if (player[seat] == null) return 0;
        long cashOut = stack[seat] + pendingTopUp[seat];
        stack[seat] = 0;
        pendingTopUp[seat] = 0;
        player[seat] = null;
        if (handInProgress && inHand[seat] && !folded[seat]) {
            folded[seat] = true;
            listener.onAction(seat, ActionType.FOLD, streetBet[seat], false, true);
            if (seat == actor || activeCount() <= 1 || (actor >= 0 && !needsAction(actor))) {
                advance();
            }
        }
        return cashOut;
    }

    /**
     * Add chips to a player's stack. Between hands (or for a player not in the current hand) they land
     * immediately and this returns true; otherwise they wait for the hand to end and this returns false.
     */
    public boolean addChips(int seat, long amount) {
        checkSeat(seat);
        if (player[seat] == null) throw new IllegalStateException("seat " + seat + " is empty");
        if (amount <= 0) throw new IllegalArgumentException("amount");
        if (handInProgress && inHand[seat]) {
            pendingTopUp[seat] += amount;
            return false;
        }
        stack[seat] += amount;
        return true;
    }

    /** Seats that would be dealt into a hand starting now. */
    public int fundedCount() {
        int n = 0;
        for (int s = 0; s < maxSeats; s++) {
            if (player[s] != null && stack[s] + (handInProgress ? 0 : pendingTopUp[s]) > 0) n++;
        }
        return n;
    }

    public boolean canStartHand() {
        return !handInProgress && fundedCount() >= 2;
    }

    // ---------------------------------------------------------------- hand flow

    /** Move the button, post blinds, deal. Throws if a hand is running or fewer than two seats are funded. */
    public void startHand() {
        if (handInProgress) throw new IllegalStateException("hand in progress");
        applyPendingTopUps();
        if (fundedCount() < 2) throw new IllegalStateException("need two funded players");

        Arrays.fill(inHand, false);
        Arrays.fill(folded, false);
        Arrays.fill(acted, false);
        Arrays.fill(streetBet, 0);
        Arrays.fill(contributed, 0);
        Arrays.fill(facedWhenActed, 0);
        Arrays.fill(won, 0);
        Arrays.fill(handPlayer, null);
        hole.forEach(List::clear);
        board.clear();

        int dealt = 0;
        for (int s = 0; s < maxSeats; s++) {
            if (player[s] != null && stack[s] > 0) {
                inHand[s] = true;
                handPlayer[s] = player[s];
                dealt++;
            }
        }

        handInProgress = true;
        showdownReached = false;
        handNumber++;
        street = Street.PREFLOP;
        deck = deckFactory.get();
        button = nextSeat(button < 0 ? maxSeats - 1 : button, s -> inHand[s]);
        if (dealt == 2) {
            smallBlindSeat = button;
            bigBlindSeat = nextSeat(button, s -> inHand[s]);
        } else {
            smallBlindSeat = nextSeat(button, s -> inHand[s]);
            bigBlindSeat = nextSeat(smallBlindSeat, s -> inHand[s]);
        }
        // Everyone has to call the full big blind even if the big blind itself is short
        currentBet = bigBlind;
        lastRaiseSize = bigBlind;
        streetAggressor = -1;
        finalRoundAggressor = -1;
        actor = -1;

        listener.onHandStarted(handNumber, button, smallBlindSeat, bigBlindSeat);
        postBlind(smallBlindSeat, smallBlind, false);
        postBlind(bigBlindSeat, bigBlind, true);

        for (int round = 0; round < 2; round++) {
            int s = button;
            for (int i = 0; i < dealt; i++) {
                s = nextSeat(s, x -> inHand[x]);
                hole.get(s).add(deck.draw());
            }
        }
        for (int s = 0; s < maxSeats; s++) {
            if (inHand[s]) listener.onHoleCards(s, List.copyOf(hole.get(s)));
        }

        actor = bigBlindSeat;
        advance();
    }

    private void postBlind(int seat, long amount, boolean big) {
        long posted = Math.min(amount, stack[seat]);
        putIn(seat, posted);
        listener.onBlindPosted(seat, posted, big, stack[seat] == 0);
    }

    /** The options of the player to act, or null if nobody is to act. */
    public ActionOptions getOptions() {
        return actor < 0 ? null : optionsFor(actor);
    }

    /** The options for {@code seat}, or null if it isn't that seat's turn. */
    public ActionOptions getOptions(int seat) {
        return seat == actor && actor >= 0 ? optionsFor(actor) : null;
    }

    private ActionOptions optionsFor(int seat) {
        boolean othersCanAct = othersCanAct(seat);
        long target = othersCanAct ? currentBet : Math.min(currentBet, maxOtherStreetBet(seat));
        long toCall = Math.min(Math.max(0, target - streetBet[seat]), stack[seat]);
        boolean reopened = !acted[seat] || currentBet - facedWhenActed[seat] >= lastRaiseSize;
        boolean canWager = othersCanAct && reopened && stack[seat] > toCall;
        boolean canBet = canWager && currentBet == 0;
        boolean canRaise = canWager && currentBet > 0;
        long maxTo = streetBet[seat] + stack[seat];
        long minTo = Math.min(currentBet + lastRaiseSize, maxTo);
        boolean canAllIn = stack[seat] > 0 && (toCall >= stack[seat] || canWager);
        return new ActionOptions(seat, toCall, toCall == 0, toCall > 0, canBet, canRaise, canAllIn,
            minTo, maxTo, stack[seat], streetBet[seat], currentBet, getPot(), bigBlind);
    }

    /**
     * Apply an action for {@code seat}. {@code amount} is only read for BET and RAISE, where it is the
     * total street bet to make ("raise to"). BET and RAISE are interchangeable: either one means
     * "put in a wager of this size". Throws {@link IllegalActionException} and changes nothing if the
     * action isn't legal.
     */
    public void act(int seat, ActionType type, long amount) {
        if (!handInProgress) throw new IllegalActionException(IllegalActionException.Reason.NO_HAND);
        if (seat != actor) throw new IllegalActionException(IllegalActionException.Reason.NOT_YOUR_TURN);
        ActionOptions o = optionsFor(seat);
        ActionType done;
        switch (type) {
            case FOLD -> {
                folded[seat] = true;
                done = ActionType.FOLD;
            }
            case CHECK -> {
                if (!o.canCheck()) throw new IllegalActionException(IllegalActionException.Reason.CANNOT_CHECK);
                done = ActionType.CHECK;
            }
            case CALL -> {
                if (!o.canCall()) throw new IllegalActionException(IllegalActionException.Reason.NOTHING_TO_CALL);
                putIn(seat, o.toCall());
                done = ActionType.CALL;
            }
            case BET, RAISE -> done = wager(seat, o, amount);
            case ALL_IN -> {
                if (!o.canAllIn()) throw new IllegalActionException(IllegalActionException.Reason.CANNOT_RAISE);
                if (o.toCall() >= stack[seat]) {
                    putIn(seat, stack[seat]);
                    done = ActionType.CALL;
                } else {
                    done = wager(seat, o, o.maxRaiseTo());
                }
            }
            default -> throw new IllegalStateException();
        }
        acted[seat] = true;
        facedWhenActed[seat] = currentBet;
        listener.onAction(seat, done, streetBet[seat], inHand[seat] && !folded[seat] && stack[seat] == 0, false);
        advance();
    }

    private ActionType wager(int seat, ActionOptions o, long raiseTo) {
        if (!o.canWager()) {
            throw new IllegalActionException(o.currentBet() == 0
                ? IllegalActionException.Reason.CANNOT_BET : IllegalActionException.Reason.CANNOT_RAISE);
        }
        if (raiseTo > o.maxRaiseTo()) throw new IllegalActionException(IllegalActionException.Reason.AMOUNT_TOO_LARGE);
        if (raiseTo <= currentBet || (raiseTo < o.minRaiseTo() && raiseTo != o.maxRaiseTo())) {
            throw new IllegalActionException(IllegalActionException.Reason.AMOUNT_TOO_SMALL);
        }
        ActionType done = currentBet == 0 ? ActionType.BET : ActionType.RAISE;
        long increment = raiseTo - currentBet;
        if (increment >= lastRaiseSize) {
            lastRaiseSize = increment; // a full raise; a short all-in leaves the minimum where it was
        }
        currentBet = raiseTo;
        streetAggressor = seat;
        putIn(seat, raiseTo - streetBet[seat]);
        return done;
    }

    /** The turn timer ran out: check if possible, otherwise fold. Returns what was done. */
    public ActionType timeout() {
        if (actor < 0) throw new IllegalActionException(IllegalActionException.Reason.NO_HAND);
        ActionType type = optionsFor(actor).canCheck() ? ActionType.CHECK : ActionType.FOLD;
        act(actor, type, 0);
        return type;
    }

    /**
     * Abandon the hand (e.g. shutdown) and give every player back what they put in. Seated players get
     * it on their stack, players who left get it in {@link VoidResult#refundedToLeavers()}.
     */
    public VoidResult voidHand() {
        Map<Integer, Long> toStacks = new LinkedHashMap<>();
        Map<UUID, Long> toLeavers = new LinkedHashMap<>();
        if (!handInProgress) return new VoidResult(toStacks, toLeavers);
        for (int s = 0; s < maxSeats; s++) {
            if (!inHand[s] || contributed[s] == 0) continue;
            long refund = contributed[s];
            contributed[s] = 0;
            if (player[s] != null && player[s].equals(handPlayer[s])) {
                stack[s] += refund;
                toStacks.put(s, refund);
            } else {
                toLeavers.merge(handPlayer[s], refund, Long::sum);
            }
        }
        Arrays.fill(streetBet, 0);
        handInProgress = false;
        actor = -1;
        applyPendingTopUps();
        VoidResult result = new VoidResult(toStacks, toLeavers);
        listener.onHandVoided(result);
        return result;
    }

    // ---------------------------------------------------------------- internals

    /** Pass the turn on, or close the betting round if nobody still has to act. */
    private void advance() {
        if (!handInProgress) return;
        if (activeCount() <= 1) {
            finishUncontested();
            return;
        }
        int next = nextSeat(actor, this::needsAction);
        if (next >= 0) {
            actor = next;
            listener.onTurn(actor, optionsFor(actor));
        } else {
            endBettingRound();
        }
    }

    private void endBettingRound() {
        actor = -1;
        returnUncalled();
        finalRoundAggressor = streetAggressor;
        Arrays.fill(streetBet, 0);
        Arrays.fill(acted, false);
        Arrays.fill(facedWhenActed, 0);
        currentBet = 0;
        lastRaiseSize = bigBlind;
        streetAggressor = -1;

        if (street == Street.RIVER) {
            showdown();
            return;
        }
        if (canActCount() <= 1) {
            // Nobody left to bet against: run the board out
            while (street != Street.RIVER) {
                dealNextStreet();
            }
            showdown();
            return;
        }
        dealNextStreet();
        actor = button;
        advance();
    }

    private void dealNextStreet() {
        street = Street.values()[street.ordinal() + 1];
        deck.draw(); // burn
        while (board.size() < street.boardSize()) {
            board.add(deck.draw());
        }
        listener.onStreet(street, List.copyOf(board));
    }

    /** Give back whatever the biggest contributor put in beyond what anyone else matched. */
    private void returnUncalled() {
        int top = -1;
        long topAmount = 0;
        long second = 0;
        for (int s = 0; s < maxSeats; s++) {
            if (!inHand[s]) continue;
            if (contributed[s] > topAmount) {
                second = topAmount;
                topAmount = contributed[s];
                top = s;
            } else if (contributed[s] > second) {
                second = contributed[s];
            }
        }
        long excess = topAmount - second;
        if (top < 0 || excess <= 0) return;
        contributed[top] -= excess;
        streetBet[top] = Math.max(0, streetBet[top] - excess);
        boolean seated = player[top] != null && player[top].equals(handPlayer[top]);
        if (seated) {
            stack[top] += excess;
        }
        listener.onUncalledReturned(top, handPlayer[top], excess, seated);
    }

    private void finishUncontested() {
        actor = -1;
        returnUncalled();
        int winner = nextSeat(button, s -> inHand[s] && !folded[s]);
        long pot = getPot();
        Map<Integer, Long> shares = new LinkedHashMap<>();
        if (winner >= 0 && pot > 0) {
            stack[winner] += pot;
            won[winner] += pot;
            shares.put(winner, pot);
            listener.onPotAwarded(0, pot, shares, null);
        }
        finishHand(pot);
    }

    private void showdown() {
        street = Street.SHOWDOWN;
        showdownReached = true;
        actor = -1;
        long potTotal = getPot();

        // Last aggressor on the final betting round shows first, otherwise the first live seat after the button
        int lead = finalRoundAggressor >= 0 && inHand[finalRoundAggressor] && !folded[finalRoundAggressor]
            ? finalRoundAggressor : nextSeat(button, s -> inHand[s] && !folded[s]);
        Map<Integer, HandValue> values = new HashMap<>();
        List<ShowdownHand> shown = new ArrayList<>();
        for (int i = 0, s = lead; i < maxSeats; i++, s = (s + 1) % maxSeats) {
            if (!inHand[s] || folded[s]) continue;
            List<Card> seven = new ArrayList<>(hole.get(s));
            seven.addAll(board);
            HandValue value = HandEvaluator.best(seven);
            values.put(s, value);
            shown.add(new ShowdownHand(s, handPlayer[s], List.copyOf(hole.get(s)), value));
        }
        listener.onShowdown(Collections.unmodifiableList(shown));

        Map<Integer, Long> contributions = new HashMap<>();
        Set<Integer> foldedSeats = new HashSet<>();
        for (int s = 0; s < maxSeats; s++) {
            if (!inHand[s]) continue;
            if (contributed[s] > 0) contributions.put(s, contributed[s]);
            if (folded[s]) foldedSeats.add(s);
        }
        List<Pot> pots = PotCalculator.calculate(contributions, foldedSeats);
        for (int i = 0; i < pots.size(); i++) {
            Pot pot = pots.get(i);
            List<Integer> contenders = new ArrayList<>();
            for (int s : seatsFromButton()) {
                if (values.containsKey(s) && pot.eligibleSeats().contains(s)) contenders.add(s);
            }
            if (contenders.isEmpty()) {
                // Only folded players reached this level; the live hands share it
                contenders.addAll(seatsFromButton().stream().filter(values::containsKey).toList());
            }
            HandValue best = null;
            for (int s : contenders) {
                if (best == null || values.get(s).compareTo(best) > 0) best = values.get(s);
            }
            List<Integer> winners = new ArrayList<>();
            for (int s : contenders) {
                if (values.get(s).compareTo(best) == 0) winners.add(s);
            }
            Map<Integer, Long> shares = split(pot.amount(), winners);
            shares.forEach((s, chips) -> {
                stack[s] += chips;
                won[s] += chips;
            });
            listener.onPotAwarded(i, pot.amount(), shares, best);
        }
        finishHand(potTotal);
    }

    /**
     * Split {@code amount} evenly; the odd chips go one each to the winners in seat order starting
     * left of the button ({@code winners} must already be in that order).
     */
    static Map<Integer, Long> split(long amount, List<Integer> winners) {
        Map<Integer, Long> shares = new LinkedHashMap<>();
        long each = amount / winners.size();
        long odd = amount % winners.size();
        for (int i = 0; i < winners.size(); i++) {
            shares.put(winners.get(i), each + (i < odd ? 1 : 0));
        }
        return shares;
    }

    /** Every seat index, starting with the first seat left of the button. */
    private List<Integer> seatsFromButton() {
        List<Integer> order = new ArrayList<>(maxSeats);
        for (int i = 1; i <= maxSeats; i++) {
            order.add((button + i) % maxSeats);
        }
        return order;
    }

    private void finishHand(long potTotal) {
        List<SeatResult> results = new ArrayList<>();
        for (int s = 0; s < maxSeats; s++) {
            if (!inHand[s]) continue;
            boolean left = player[s] == null || !player[s].equals(handPlayer[s]);
            results.add(new SeatResult(s, handPlayer[s], contributed[s], won[s],
                showdownReached && !folded[s], left));
        }
        Arrays.fill(streetBet, 0);
        handInProgress = false;
        actor = -1;
        applyPendingTopUps();
        listener.onHandEnded(new HandSummary(handNumber, showdownReached, potTotal, List.copyOf(results)));
    }

    private void applyPendingTopUps() {
        for (int s = 0; s < maxSeats; s++) {
            if (player[s] != null && pendingTopUp[s] > 0) {
                stack[s] += pendingTopUp[s];
            }
            pendingTopUp[s] = 0;
        }
    }

    private void putIn(int seat, long chips) {
        if (chips < 0 || chips > stack[seat]) throw new IllegalStateException("bad amount " + chips);
        stack[seat] -= chips;
        streetBet[seat] += chips;
        contributed[seat] += chips;
    }

    /** Dealt in, not folded, and has chips left to bet with. */
    private boolean canAct(int s) {
        return inHand[s] && !folded[s] && stack[s] > 0;
    }

    private boolean needsAction(int s) {
        if (!canAct(s)) return false;
        if (!othersCanAct(s)) {
            // Everyone else is all-in or folded: only a shortfall against their bets needs answering
            return streetBet[s] < Math.min(currentBet, maxOtherStreetBet(s));
        }
        return !acted[s] || streetBet[s] < currentBet;
    }

    private boolean othersCanAct(int seat) {
        for (int s = 0; s < maxSeats; s++) {
            if (s != seat && canAct(s)) return true;
        }
        return false;
    }

    private long maxOtherStreetBet(int seat) {
        long max = 0;
        for (int s = 0; s < maxSeats; s++) {
            if (s != seat && inHand[s] && !folded[s]) max = Math.max(max, streetBet[s]);
        }
        return max;
    }

    private int activeCount() {
        int n = 0;
        for (int s = 0; s < maxSeats; s++) {
            if (inHand[s] && !folded[s]) n++;
        }
        return n;
    }

    private int canActCount() {
        int n = 0;
        for (int s = 0; s < maxSeats; s++) {
            if (canAct(s)) n++;
        }
        return n;
    }

    /** The first seat after {@code from} (clockwise, wrapping, {@code from} itself last) matching, or -1. */
    private int nextSeat(int from, IntPredicate test) {
        for (int i = 1; i <= maxSeats; i++) {
            int s = (from + i) % maxSeats;
            if (test.test(s)) return s;
        }
        return -1;
    }

    private void checkSeat(int seat) {
        if (seat < 0 || seat >= maxSeats) throw new IllegalArgumentException("seat " + seat);
    }

    // ---------------------------------------------------------------- state for the table and tests

    public int getMaxSeats() { return maxSeats; }
    public long getSmallBlind() { return smallBlind; }
    public long getBigBlind() { return bigBlind; }
    public boolean isHandInProgress() { return handInProgress; }
    public int getHandNumber() { return handNumber; }
    public Street getStreet() { return street; }
    public int getButton() { return button; }
    public int getSmallBlindSeat() { return smallBlindSeat; }
    public int getBigBlindSeat() { return bigBlindSeat; }
    /** Seat whose turn it is, or -1. */
    public int getActor() { return actor; }
    public long getCurrentBet() { return currentBet; }
    public List<Card> getBoard() { return List.copyOf(board); }
    public UUID getPlayer(int seat) { checkSeat(seat); return player[seat]; }
    public long getStack(int seat) { checkSeat(seat); return stack[seat]; }
    public long getPendingTopUp(int seat) { checkSeat(seat); return pendingTopUp[seat]; }
    public long getStreetBet(int seat) { checkSeat(seat); return streetBet[seat]; }
    public long getContributed(int seat) { checkSeat(seat); return contributed[seat]; }

    /** Dealt into the current hand and not folded. */
    public boolean isLive(int seat) {
        checkSeat(seat);
        return handInProgress && inHand[seat] && !folded[seat];
    }

    public boolean isAllIn(int seat) {
        return isLive(seat) && stack[seat] == 0;
    }

    public List<Card> getHoleCards(int seat) {
        checkSeat(seat);
        return List.copyOf(hole.get(seat));
    }

    /** Every chip committed to the current hand (0 between hands). */
    public long getPot() {
        if (!handInProgress) return 0;
        long pot = 0;
        for (long c : contributed) pot += c;
        return pot;
    }

    public int seatOf(UUID id) {
        for (int s = 0; s < maxSeats; s++) {
            if (id.equals(player[s])) return s;
        }
        return -1;
    }
}
