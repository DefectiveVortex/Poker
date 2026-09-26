package com.vortex.poker.gui;

import com.vortex.poker.game.ActionOptions;

import java.util.ArrayList;
import java.util.List;

/**
 * The preset sizes offered in the raise menu, as "raise to" totals for this street.
 * Pure logic so it can be unit tested.
 */
public final class RaiseOptions {
    public enum Kind { MIN_RAISE, HALF_POT, POT, ALL_IN }

    public record Option(Kind kind, long raiseTo) {}

    private RaiseOptions() {
    }

    /**
     * Min-raise, half pot, pot and all-in, smallest first. A pot-based size is dropped when it's below the
     * minimum raise or would be an all-in anyway, and duplicates are dropped. Empty if the player can't
     * bet or raise (all-in may still be offered when that's their only way to put more chips in).
     */
    public static List<Option> compute(ActionOptions o) {
        List<Option> out = new ArrayList<>();
        long max = o.maxRaiseTo();
        if (o.canWager()) {
            long min = Math.min(o.minRaiseTo(), max);
            if (min < max) {
                out.add(new Option(Kind.MIN_RAISE, min));
            }
            // A pot-sized raise: call first, then raise by the whole pot (the call included).
            long potAfterCall = o.potTotal() + o.toCall();
            long base = o.currentBet();
            addIfBetween(out, Kind.HALF_POT, base + potAfterCall / 2, min, max);
            addIfBetween(out, Kind.POT, base + potAfterCall, min, max);
        }
        if (o.canAllIn() && max > 0) {
            out.add(new Option(Kind.ALL_IN, max));
        }
        return out;
    }

    private static void addIfBetween(List<Option> out, Kind kind, long raiseTo, long min, long max) {
        if (raiseTo <= min || raiseTo >= max) {
            return;
        }
        for (Option existing : out) {
            if (existing.raiseTo() == raiseTo) {
                return;
            }
        }
        out.add(new Option(kind, raiseTo));
    }
}
