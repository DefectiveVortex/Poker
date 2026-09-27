package com.vortex.poker.game;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ReadyCheckTest {

    static final UUID A = new UUID(0, 1), B = new UUID(0, 2), C = new UUID(0, 3), D = new UUID(0, 4);

    @Test
    void everyoneMustConfirmBeforeTheNextHand() {
        ReadyCheck r = new ReadyCheck();
        r.start(List.of(A, B, C), List.of(), 0, 30_000);
        assertTrue(r.isActive());
        assertFalse(r.allConfirmed());
        assertEquals(ReadyCheck.Result.CONFIRMED, r.confirm(A));
        assertEquals(ReadyCheck.Result.ALREADY, r.confirm(A));
        assertEquals(ReadyCheck.Result.CONFIRMED, r.confirm(B));
        assertFalse(r.allConfirmed());
        assertEquals(ReadyCheck.Result.CONFIRMED, r.confirm(C));
        assertTrue(r.allConfirmed());
        assertEquals(3, r.confirmedCount());
    }

    @Test
    void latePlayersAreHandedBackOnceAndDropOut() {
        ReadyCheck r = new ReadyCheck();
        r.start(List.of(A, B, C), List.of(), 1_000, 30_000);
        r.confirm(A);
        assertTrue(r.expired(30_999).isEmpty());
        assertEquals(1, r.secondsLeft(B, 30_001));
        assertEquals(Set.of(B, C), Set.copyOf(r.expired(31_000)));
        assertTrue(r.expired(40_000).isEmpty(), "each late player is reported once");
        assertTrue(r.allConfirmed(), "the rest are all in");
        assertEquals(ReadyCheck.Result.NOT_WAITING, r.confirm(B), "too late to confirm");
        assertEquals(1, r.total());
    }

    @Test
    void sittingDownDuringTheCheckCountsAsConfirmed() {
        ReadyCheck r = new ReadyCheck();
        r.start(List.of(A, B), List.of(C), 0, 30_000);
        assertTrue(r.isConfirmed(C), "sat down during the last hand: already in");
        r.join(D);
        assertTrue(r.isConfirmed(D));
        assertFalse(r.isPending(D));
        r.confirm(A);
        r.confirm(B);
        assertTrue(r.allConfirmed());
        assertEquals(4, r.confirmedCount());
    }

    @Test
    void leavingRemovesAPendingPlayer() {
        ReadyCheck r = new ReadyCheck();
        r.start(List.of(A, B), List.of(), 0, 30_000);
        r.confirm(A);
        r.remove(B);
        assertTrue(r.allConfirmed());
        assertTrue(r.expired(Long.MAX_VALUE).isEmpty());
    }

    @Test
    void zeroTimeoutMeansNoCheck() {
        ReadyCheck r = new ReadyCheck();
        r.start(List.of(A, B), List.of(), 0, 0);
        assertTrue(r.allConfirmed());
    }

    @Test
    void stoppedCheckAcceptsNothing() {
        ReadyCheck r = new ReadyCheck();
        assertEquals(ReadyCheck.Result.NOT_WAITING, r.confirm(A));
        r.join(A);
        assertFalse(r.isConfirmed(A), "no check running: joining is a no-op");
        r.start(List.of(A), List.of(), 0, 5_000);
        r.stop();
        assertFalse(r.isActive());
        assertFalse(r.allConfirmed());
        assertEquals(ReadyCheck.Result.NOT_WAITING, r.confirm(A));
    }

    @Test
    void onlyPlayersDealtInAndStillSeatedMustConfirm() {
        ReadyCheck r = new ReadyCheck();
        r.handStarted(List.of(A, B));
        r.join(C); // sat down mid-hand: no check running, so this is a no-op...
        r.open(List.of(A, B, C), 0, 30_000);
        assertEquals(Set.of(A, B), r.getPending());
        assertTrue(r.isConfirmed(C), "...and they are in once the check opens");
    }

    @Test
    void leavingAndSittingStraightBackDownCountsAsReady() {
        // Round-2 bug: both players left mid-hand and sat again before the check opened; the one who was
        // dealt in was still asked to confirm, and no hand was dealt.
        ReadyCheck r = new ReadyCheck();
        r.handStarted(List.of(A, B));
        r.remove(A);
        r.remove(B);
        r.join(A); // sits again during the post-hand pause (check not open yet)
        r.open(List.of(A), 0, 30_000);
        assertTrue(r.allConfirmed(), "a fresh sit is a confirm");
        r.join(B); // sits again after the check opened
        assertTrue(r.allConfirmed());
        assertEquals(2, r.confirmedCount());
    }

    @Test
    void newHandForgetsWhoPlayedTheOneBefore() {
        ReadyCheck r = new ReadyCheck();
        r.handStarted(List.of(A, B));
        r.handStarted(List.of(B, C));
        r.open(List.of(A, B, C), 0, 30_000);
        assertEquals(Set.of(B, C), r.getPending());
        assertFalse(r.isActive() && r.allConfirmed());
    }
}
