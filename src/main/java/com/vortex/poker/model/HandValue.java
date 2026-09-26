package com.vortex.poker.model;

import java.util.List;

/**
 * The value of a best five-card hand. Two values comparing equal split the pot.
 * equals/hashCode agree with compareTo: suits never matter.
 */
public final class HandValue implements Comparable<HandValue> {
    private final HandRank rank;
    /** Tiebreak ranks, most significant first (e.g. full house: trips, pair; wheel: FIVE). */
    private final List<Rank> ordered;
    private final List<Card> bestFive;

    HandValue(HandRank rank, List<Rank> ordered, List<Card> bestFive) {
        this.rank = rank;
        this.ordered = List.copyOf(ordered);
        this.bestFive = List.copyOf(bestFive);
    }

    public HandRank getRank() {
        return rank;
    }

    /** The five cards making the hand, ordered for display (groups first, then kickers; wheel as 5-4-3-2-A). */
    public List<Card> getBestFive() {
        return bestFive;
    }

    /**
     * Ranks that decide ties, most significant first, one per group (pair ranks once, kickers each).
     * Straights and straight flushes hold just the high card. Useful for localised descriptions.
     */
    public List<Rank> getOrderedRanks() {
        return ordered;
    }

    /** English description, e.g. "Two Pair, Kings and Sevens". */
    public String describe() {
        Rank a = ordered.get(0);
        return switch (rank) {
            case HIGH_CARD -> "High Card, " + a.displayName();
            case PAIR -> "Pair of " + a.pluralName();
            case TWO_PAIR -> "Two Pair, " + a.pluralName() + " and " + ordered.get(1).pluralName();
            case THREE_OF_A_KIND -> "Three of a Kind, " + a.pluralName();
            case STRAIGHT -> "Straight, " + a.displayName() + " high";
            case FLUSH -> "Flush, " + a.displayName() + " high";
            case FULL_HOUSE -> "Full House, " + a.pluralName() + " over " + ordered.get(1).pluralName();
            case FOUR_OF_A_KIND -> "Four of a Kind, " + a.pluralName();
            case STRAIGHT_FLUSH -> "Straight Flush, " + a.displayName() + " high";
            case ROYAL_FLUSH -> "Royal Flush";
        };
    }

    @Override
    public int compareTo(HandValue o) {
        int c = rank.compareTo(o.rank);
        if (c != 0) return c;
        for (int i = 0; i < Math.min(ordered.size(), o.ordered.size()); i++) {
            c = ordered.get(i).compareTo(o.ordered.get(i));
            if (c != 0) return c;
        }
        return Integer.compare(ordered.size(), o.ordered.size());
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof HandValue h && compareTo(h) == 0;
    }

    @Override
    public int hashCode() {
        return rank.hashCode() * 31 + ordered.hashCode();
    }

    @Override
    public String toString() {
        return describe() + " " + bestFive;
    }
}
