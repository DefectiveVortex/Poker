package com.vortex.poker.table;

import com.vortex.poker.display.TableView;
import com.vortex.poker.game.ActionType;
import com.vortex.poker.game.HoldemGame;
import com.vortex.poker.model.Card;
import com.vortex.poker.model.Deck;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static com.vortex.poker.game.ActionType.*;
import static org.junit.jupiter.api.Assertions.*;

/** Regression tests for round-2 bug #2: the board must come off the table after every way a hand ends. */
class TablePresenterTest {

    /** A TableView that just remembers what is on the felt. */
    static final class FeltView implements TableView {
        List<Card> board = new ArrayList<>();
        final Map<Integer, List<Card>> seats = new HashMap<>();
        final List<String> calls = new ArrayList<>();
        List<Integer> winners;
        int clears;

        public void dealHoleCards(int seat, Player owner, List<Card> cards) { seats.put(seat, cards); }
        public void setBoard(List<Card> cards) { board = new ArrayList<>(cards); calls.add("board" + cards.size()); }
        public void revealHoleCards(int seat, List<Card> cards) { calls.add("reveal" + seat); }
        public void clearSeat(int seat) { seats.remove(seat); }
        public void setButton(int seat) {}
        public void setSeatInfo(int seat, String text) {}
        public void setPotInfo(String text) {}
        public void highlightTurn(int seat) {}
        public void clearHand() { board.clear(); seats.clear(); clears++; calls.add("clear"); }
        public void destroy() {}
        @Override public void showWinners(List<Integer> seats, long amount) { winners = seats; calls.add("winners"); }

        boolean empty() { return board.isEmpty() && seats.isEmpty(); }
    }

    /** A clock you advance by hand. */
    static final class Clock implements TablePresenter.Scheduler {
        record Task(long at, Runnable run, boolean[] cancelled) {}
        final List<Task> tasks = new ArrayList<>();
        long now;

        public Runnable later(long ticks, Runnable task) {
            boolean[] cancelled = {false};
            tasks.add(new Task(now + ticks, task, cancelled));
            return () -> cancelled[0] = true;
        }

        void advance(long ticks) {
            long until = now + ticks;
            while (true) {
                Task next = null;
                for (Task t : tasks) if (t.at <= until && (next == null || t.at < next.at)) next = t;
                if (next == null) break;
                tasks.remove(next);
                now = next.at;
                if (!next.cancelled[0]) next.run.run();
            }
            now = until;
        }

        int live() {
            int n = 0;
            for (Iterator<Task> it = tasks.iterator(); it.hasNext(); ) if (!it.next().cancelled[0]) n++;
            return n;
        }
    }

    final FeltView view = new FeltView();
    final Clock clock = new Clock();
    final TablePresenter presenter = new TablePresenter(view, clock, seat -> null, () -> TablePresenter.Timing.DEFAULT);
    int cleared;
    HoldemGame game;

    void table(long... stacks) {
        Random rng = new Random(3);
        game = new HoldemGame(6, 5, 10, () -> new Deck(rng));
        game.setListener(presenter);
        presenter.setOnCleared(() -> cleared++);
        for (int i = 0; i < stacks.length; i++) game.seatPlayer(i, new UUID(0, i + 1), stacks[i]);
        presenter.beginCall();
        game.startHand();
    }

    void act(ActionType type) {
        presenter.beginCall();
        game.act(game.getActor(), type, 0);
    }

    void checkToEnd() {
        while (game.isHandInProgress()) act(game.getOptions().canCheck() ? CHECK : CALL);
    }

    @Test
    void boardClearsAfterShowdown() {
        table(1000, 1000);
        checkToEnd();
        assertEquals(5, view.board.size(), "the finished board stays up while the result is shown");
        assertEquals(0, cleared);
        clock.advance(TablePresenter.Timing.DEFAULT.showdownHoldTicks() + 200);
        assertTrue(view.empty(), "board and hole cards are gone after the hold");
        assertEquals(1, cleared);
        assertTrue(view.calls.indexOf("winners") < view.calls.lastIndexOf("clear"));
    }

    @Test
    void boardClearsWhenEveryoneFolds() {
        table(1000, 1000);
        act(CALL);
        act(CHECK);                 // flop dealt
        assertEquals(3, view.board.size());
        act(CHECK);
        presenter.beginCall();
        game.act(game.getActor(), BET, 50);
        act(FOLD);
        assertFalse(game.isHandInProgress());
        clock.advance(TablePresenter.Timing.DEFAULT.foldHoldTicks());
        assertTrue(view.empty());
        assertEquals(1, cleared);
        assertNotNull(view.winners, "the fold winner is still celebrated");
    }

    @Test
    void boardClearsWhenTheLastOpponentLeaves() {
        table(1000, 1000);
        act(CALL);
        act(CHECK);
        assertEquals(3, view.board.size());
        presenter.beginCall();
        game.removePlayer(game.getActor() == 0 ? 1 : 0); // the one not to act walks away
        assertFalse(game.isHandInProgress());
        clock.advance(TablePresenter.Timing.DEFAULT.foldHoldTicks());
        assertTrue(view.empty());
        assertEquals(1, cleared);
    }

    @Test
    void voidedHandClearsAtOnceAndDropsQueuedRunout() {
        table(1000, 1000);
        act(CALL);
        act(CHECK);
        presenter.beginCall();
        game.voidHand();
        assertTrue(view.empty());
        assertEquals(0, clock.live(), "nothing left queued");
        assertEquals(0, cleared, "no ready phase after a void");
    }

    @Test
    void allInRunoutIsPacedAndNeverRedrawnAfterTheClear() {
        table(1000, 1000);
        presenter.beginCall();
        game.act(game.getActor(), ALL_IN, 0);
        act(CALL);
        assertFalse(game.isHandInProgress());
        assertEquals(3, view.board.size(), "the flop lands at once");
        clock.advance(TablePresenter.Timing.DEFAULT.runoutTicks());
        assertEquals(4, view.board.size(), "turn follows after a pause");
        clock.advance(TablePresenter.Timing.DEFAULT.runoutTicks());
        assertEquals(5, view.board.size());
        clock.advance(10_000);
        assertTrue(view.empty());
        assertEquals(1, cleared);
        int boardCalls = (int) view.calls.stream().filter(c -> c.startsWith("board")).count();
        clock.advance(10_000);
        assertEquals(boardCalls, (int) view.calls.stream().filter(c -> c.startsWith("board")).count());
    }

    @Test
    void nextHandStartingEarlyDropsTheOldClear() {
        table(1000, 1000, 1000);
        checkToEnd();
        presenter.beginCall();
        game.startHand(); // e.g. ready check off: deals before the hold ran out
        int clearsNow = view.clears;
        clock.advance(10_000);
        assertEquals(clearsNow, view.clears, "the previous hand's clear must not wipe the new hand");
        assertFalse(view.seats.isEmpty());
        assertEquals(0, cleared);
    }
}
