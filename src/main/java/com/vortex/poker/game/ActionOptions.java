package com.vortex.poker.game;

/**
 * The legal actions for the player whose turn it is. Amounts are in chips.
 *
 * @param toCall     chips the player must add to call (already capped at their stack)
 * @param minRaiseTo smallest legal total street bet for a bet/raise (their all-in if they can't cover it)
 * @param maxRaiseTo largest total street bet: their whole stack plus what they have already bet this street
 * @param currentBet the street bet everyone has to match
 * @param potTotal   every chip committed this hand, current street included
 */
public record ActionOptions(int seat, long toCall, boolean canCheck, boolean canCall, boolean canBet,
                            boolean canRaise, boolean canAllIn, long minRaiseTo, long maxRaiseTo,
                            long stack, long streetBet, long currentBet, long potTotal, long bigBlind) {

    /** Whether a bet or raise of any size is available. */
    public boolean canWager() {
        return canBet || canRaise;
    }

    /** Whether calling would put the player all-in. */
    public boolean callIsAllIn() {
        return canCall && toCall >= stack;
    }
}
