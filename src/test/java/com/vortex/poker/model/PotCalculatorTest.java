package com.vortex.poker.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PotCalculatorTest {
    private static long total(List<Pot> pots) {
        return pots.stream().mapToLong(Pot::amount).sum();
    }

    @Test
    void singlePotWhenAllEqual() {
        List<Pot> pots = PotCalculator.calculate(Map.of(0, 100L, 1, 100L, 2, 100L), Set.of());
        assertEquals(List.of(new Pot(300, Set.of(0, 1, 2))), pots);
    }

    @Test
    void foldedChipsStayInPotButFolderIsNotEligible() {
        // Seat 2 called 40 then folded to a bet.
        List<Pot> pots = PotCalculator.calculate(Map.of(0, 100L, 1, 100L, 2, 40L), Set.of(2));
        assertEquals(List.of(new Pot(240, Set.of(0, 1))), pots);
    }

    @Test
    void shortAllInMakesSidePot() {
        // Seat 0 all-in for 50, seats 1 and 2 play on to 200.
        List<Pot> pots = PotCalculator.calculate(Map.of(0, 50L, 1, 200L, 2, 200L), Set.of());
        assertEquals(List.of(new Pot(150, Set.of(0, 1, 2)), new Pot(300, Set.of(1, 2))), pots);
    }

    @Test
    void multipleAllInsMakeLayeredSidePots() {
        Map<Integer, Long> c = Map.of(0, 25L, 1, 100L, 2, 250L, 3, 250L);
        List<Pot> pots = PotCalculator.calculate(c, Set.of());
        assertEquals(List.of(
                new Pot(100, Set.of(0, 1, 2, 3)),
                new Pot(225, Set.of(1, 2, 3)),
                new Pot(300, Set.of(2, 3))), pots);
        assertEquals(625, total(pots));
    }

    @Test
    void uncalledExcessComesBackAsSingleEligiblePot() {
        // Seat 1 shoves 500 into seat 0's 200 all-in call.
        List<Pot> pots = PotCalculator.calculate(Map.of(0, 200L, 1, 500L), Set.of());
        assertEquals(List.of(new Pot(400, Set.of(0, 1)), new Pot(300, Set.of(1))), pots);
    }

    @Test
    void foldedContributorInsideSidePotLayers() {
        // Seat 0 all-in 50; seat 3 put in 120 then folded; seats 1 and 2 went to 300.
        Map<Integer, Long> c = Map.of(0, 50L, 1, 300L, 2, 300L, 3, 120L);
        List<Pot> pots = PotCalculator.calculate(c, Set.of(3));
        // 50x4 = 200 main; the 50..120 layer (3 x 70) and 120..300 layer (2 x 180) both belong to {1,2} and merge.
        assertEquals(List.of(new Pot(200, Set.of(0, 1, 2)), new Pot(570, Set.of(1, 2))), pots);
        assertEquals(770, total(pots));
    }

    @Test
    void everyoneElseFoldedGivesOnePotToLastSeat() {
        List<Pot> pots = PotCalculator.calculate(Map.of(0, 10L, 1, 20L, 2, 60L), Set.of(0, 1));
        assertEquals(List.of(new Pot(90, Set.of(2))), pots);
    }

    @Test
    void folderAboveAllInBelowNoOtherCaller() {
        // Seat 0 bet 500 and folded to seat 1's shove; seat 2 all-in for 100 earlier; seat 1 put in 800.
        Map<Integer, Long> c = Map.of(0, 500L, 1, 800L, 2, 100L);
        List<Pot> pots = PotCalculator.calculate(c, Set.of(0));
        assertEquals(List.of(new Pot(300, Set.of(1, 2)), new Pot(1100, Set.of(1))), pots);
        assertEquals(1400, total(pots));
    }

    @Test
    void layerReachedOnlyByFoldedSeatsJoinsPotBelow() {
        // Degenerate: a folded seat out-contributed every live seat.
        List<Pot> pots = PotCalculator.calculate(Map.of(0, 100L, 1, 300L), Set.of(1));
        assertEquals(List.of(new Pot(400, Set.of(0))), pots);
    }

    @Test
    void zeroesIgnoredAndNegativesRejected() {
        assertEquals(List.of(), PotCalculator.calculate(Map.of(0, 0L, 1, 0L), Set.of()));
        assertThrows(IllegalArgumentException.class, () -> PotCalculator.calculate(Map.of(0, -1L), Set.of()));
    }
}
