package com.vortex.poker.table;

import com.vortex.poker.display.TableView;
import com.vortex.poker.game.ActionOptions;
import com.vortex.poker.game.ActionType;
import com.vortex.poker.game.HandSummary;
import com.vortex.poker.game.HoldemListener;
import com.vortex.poker.game.SeatResult;
import com.vortex.poker.game.ShowdownHand;
import com.vortex.poker.game.Street;
import com.vortex.poker.game.VoidResult;
import com.vortex.poker.model.Card;
import com.vortex.poker.model.HandValue;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;
import java.util.function.Supplier;

/**
 * Puts a hand on the felt through a {@link TableView}, paced so that runout streets, the showdown reveal
 * and the payout don't all land on the same tick, and clears the table after every way a hand can end
 * (showdown, everyone folding, a leave that ends it, a void). No Bukkit scheduler: time comes from a
 * {@link Scheduler}, so this is unit-tested with a fake clock.
 *
 * <p>Call {@link #beginCall()} before every call into the game; events of one call share a cue that
 * later steps push back. Delayed steps belong to the hand that scheduled them and are dropped once the
 * table is cleared or a new hand starts.
 */
public final class TablePresenter implements HoldemListener {

    /** Runs a task after some ticks; returns something that cancels it. */
    public interface Scheduler {
        Runnable later(long ticks, Runnable task);
    }

    /** Pacing in ticks. */
    public record Timing(long runoutTicks, long revealTicks, long awardTicks,
                         long showdownHoldTicks, long foldHoldTicks) {
        public static final Timing DEFAULT = new Timing(30, 20, 30, 80, 40);
    }

    private final TableView view;
    private final Scheduler scheduler;
    private final IntFunction<Player> ownerOfSeat;
    private final Supplier<Timing> timing;
    private final List<Runnable> cancels = new ArrayList<>();
    private Runnable onCleared = () -> {};

    private long cue;
    private int streetsThisCall;
    private int epoch;
    private boolean handOnTable;

    public TablePresenter(TableView view, Scheduler scheduler, IntFunction<Player> ownerOfSeat, Supplier<Timing> timing) {
        this.view = view;
        this.scheduler = scheduler;
        this.ownerOfSeat = ownerOfSeat;
        this.timing = timing;
    }

    /** Called once the finished hand has been shown and the table cleared (not after a void). */
    public void setOnCleared(Runnable onCleared) {
        this.onCleared = onCleared == null ? () -> {} : onCleared;
    }

    /** Start of a call into the game: nothing queued yet for this call. */
    public void beginCall() {
        cue = 0;
        streetsThisCall = 0;
    }

    /**
     * Run {@code step} now, or after the steps already queued by this call. A step still waiting when
     * the table is cleared or the next hand starts is dropped.
     */
    public void at(Runnable step) {
        if (cue <= 0) {
            step.run();
            return;
        }
        int mine = epoch;
        Runnable[] cancel = new Runnable[1];
        cancel[0] = scheduler.later(cue, () -> {
            cancels.remove(cancel[0]);
            if (epoch == mine) step.run();
        });
        cancels.add(cancel[0]);
    }

    /** Whether cards from a hand (live or just finished) are still on the table. */
    public boolean isHandOnTable() {
        return handOnTable;
    }

    /** Clear the felt at once and drop anything still queued. */
    public void clearNow() {
        dropQueued();
        view.clearHand();
        view.highlightTurn(-1);
        handOnTable = false;
    }

    private void dropQueued() {
        epoch++;
        new ArrayList<>(cancels).forEach(Runnable::run);
        cancels.clear();
    }

    // ------------------------------------------------------------------ game events

    @Override
    public void onHandStarted(int handNumber, int button, int smallBlindSeat, int bigBlindSeat) {
        dropQueued();
        view.clearHand();
        view.setButton(button);
        handOnTable = true;
    }

    @Override
    public void onHoleCards(int seat, List<Card> cards) {
        view.dealHoleCards(seat, ownerOfSeat.apply(seat), cards);
    }

    @Override
    public void onTurn(int seat, ActionOptions options) {
        view.highlightTurn(seat);
    }

    @Override
    public void onAction(int seat, ActionType type, long streetBet, boolean allIn, boolean left) {
        if (type == ActionType.FOLD) view.clearSeat(seat);
    }

    @Override
    public void onStreet(Street street, List<Card> board) {
        if (streetsThisCall++ > 0) cue += timing.get().runoutTicks();
        at(() -> view.setBoard(board));
    }

    @Override
    public void onShowdown(List<ShowdownHand> hands) {
        cue += timing.get().revealTicks();
        at(() -> {
            view.highlightTurn(-1);
            for (ShowdownHand h : hands) {
                view.revealHoleCards(h.seat(), h.holeCards());
            }
        });
    }

    @Override
    public void onPotAwarded(int potIndex, long amount, Map<Integer, Long> shares, HandValue winningHand) {
        if (winningHand != null && potIndex == 0) cue += timing.get().awardTicks();
    }

    @Override
    public void onHandEnded(HandSummary summary) {
        List<Integer> winners = new ArrayList<>();
        long paid = 0;
        for (SeatResult r : summary.results()) {
            if (r.won() > 0) {
                winners.add(r.seat());
                paid += r.won();
            }
        }
        long total = paid;
        at(() -> {
            view.highlightTurn(-1);
            if (!winners.isEmpty()) view.showWinners(List.copyOf(winners), total);
        });
        Timing t = timing.get();
        cue += summary.showdown() ? t.showdownHoldTicks() : t.foldHoldTicks();
        at(() -> {
            view.clearHand();
            handOnTable = false;
            onCleared.run();
        });
    }

    @Override
    public void onHandVoided(VoidResult result) {
        clearNow();
    }
}
