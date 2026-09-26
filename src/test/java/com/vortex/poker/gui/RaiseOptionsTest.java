package com.vortex.poker.gui;

import com.vortex.poker.game.ActionOptions;
import com.vortex.poker.gui.RaiseOptions.Kind;
import com.vortex.poker.gui.RaiseOptions.Option;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RaiseOptionsTest {
    // seat, toCall, canCheck, canCall, canBet, canRaise, canAllIn, minRaiseTo, maxRaiseTo,
    // stack, streetBet, currentBet, potTotal, bigBlind
    private static ActionOptions opts(long toCall, boolean canBet, boolean canRaise, long minRaiseTo, long maxRaiseTo,
                                      long stack, long streetBet, long currentBet, long pot) {
        return new ActionOptions(0, toCall, toCall == 0, toCall > 0, canBet, canRaise, stack > 0,
            minRaiseTo, maxRaiseTo, stack, streetBet, currentBet, pot, 20);
    }

    @Test
    void preflopFacingBigBlind() {
        // Button, 1000 behind, blinds 10/20 posted: pot 30, 20 to call, min raise to 40.
        List<Option> o = RaiseOptions.compute(opts(20, false, true, 40, 1000, 1000, 0, 20, 30));
        // Half pot: 20 + (30+20)/2 = 45. Pot: 20 + 50 = 70.
        assertEquals(List.of(new Option(Kind.MIN_RAISE, 40), new Option(Kind.HALF_POT, 45),
            new Option(Kind.POT, 70), new Option(Kind.ALL_IN, 1000)), o);
    }

    @Test
    void openingBetOnFlop() {
        // Nobody has bet; pot 200, min bet = BB 20.
        List<Option> o = RaiseOptions.compute(opts(0, true, false, 20, 500, 500, 0, 0, 200));
        assertEquals(List.of(new Option(Kind.MIN_RAISE, 20), new Option(Kind.HALF_POT, 100),
            new Option(Kind.POT, 200), new Option(Kind.ALL_IN, 500)), o);
    }

    @Test
    void shortStackOnlyAllIn() {
        // Can't make a full raise: min raise is above the stack, so only all-in is left.
        List<Option> o = RaiseOptions.compute(opts(50, false, true, 200, 120, 120, 0, 100, 300));
        assertEquals(List.of(new Option(Kind.ALL_IN, 120)), o);
    }

    @Test
    void potSizesAboveStackCollapseIntoAllIn() {
        List<Option> o = RaiseOptions.compute(opts(0, true, false, 20, 150, 150, 0, 0, 400));
        assertEquals(List.of(new Option(Kind.MIN_RAISE, 20), new Option(Kind.ALL_IN, 150)), o);
    }

    @Test
    void noWagerNoAllInIsEmpty() {
        ActionOptions o = new ActionOptions(0, 0, true, false, false, false, false, 0, 0, 0, 0, 0, 100, 20);
        assertTrue(RaiseOptions.compute(o).isEmpty());
    }
}
