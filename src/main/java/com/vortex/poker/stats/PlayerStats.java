package com.vortex.poker.stats;

/**
 * One player's lifetime poker results. Amounts are whole currency units.
 */
public class PlayerStats {
    String name;
    long handsPlayed;
    long handsWon;
    long showdownsWon;
    long biggestPot;
    long netWinnings;
    boolean guideSeen;

    public String getName() { return name; }
    public long getHandsPlayed() { return handsPlayed; }
    public long getHandsWon() { return handsWon; }
    public long getShowdownsWon() { return showdownsWon; }
    public long getBiggestPot() { return biggestPot; }
    public long getNetWinnings() { return netWinnings; }

    /** Hands won as a whole-number percentage of hands played. */
    public long getWinRatePercent() {
        return handsPlayed == 0 ? 0 : Math.round(handsWon * 100.0 / handsPlayed);
    }
}
