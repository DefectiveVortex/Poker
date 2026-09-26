package com.vortex.poker.model;

/** Hand categories, weakest first: ordinal order is strength order. */
public enum HandRank {
    HIGH_CARD("High Card"),
    PAIR("Pair"),
    TWO_PAIR("Two Pair"),
    THREE_OF_A_KIND("Three of a Kind"),
    STRAIGHT("Straight"),
    FLUSH("Flush"),
    FULL_HOUSE("Full House"),
    FOUR_OF_A_KIND("Four of a Kind"),
    STRAIGHT_FLUSH("Straight Flush"),
    ROYAL_FLUSH("Royal Flush");

    private final String displayName;

    HandRank(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
