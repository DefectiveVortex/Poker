package com.vortex.poker.model;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** Finds the best five-card poker hand out of 5 to 7 cards. */
public final class HandEvaluator {
    private HandEvaluator() {
    }

    /** Best hand from 5–7 distinct cards (hole cards plus board). */
    public static HandValue best(List<Card> cards) {
        int n = cards.size();
        if (n < 5 || n > 7) throw new IllegalArgumentException("Need 5-7 cards, got " + n);
        if (new HashSet<>(cards).size() != n) throw new IllegalArgumentException("Duplicate cards: " + cards);

        // At most C(7,5) = 21 combinations, so brute force is cheap and obviously correct.
        HandValue best = null;
        Card[] hand = new Card[5];
        for (int a = 0; a < n; a++)
            for (int b = a + 1; b < n; b++)
                for (int c = b + 1; c < n; c++)
                    for (int d = c + 1; d < n; d++)
                        for (int e = d + 1; e < n; e++) {
                            hand[0] = cards.get(a);
                            hand[1] = cards.get(b);
                            hand[2] = cards.get(c);
                            hand[3] = cards.get(d);
                            hand[4] = cards.get(e);
                            HandValue v = evaluate5(hand);
                            if (best == null || v.compareTo(best) > 0) best = v;
                        }
        return best;
    }

    private static HandValue evaluate5(Card[] hand) {
        // Group by rank: bigger groups first, then higher rank.
        Map<Rank, List<Card>> byRank = new EnumMap<>(Rank.class);
        for (Card c : hand) byRank.computeIfAbsent(c.getRank(), r -> new ArrayList<>()).add(c);
        List<List<Card>> groups = new ArrayList<>(byRank.values());
        groups.sort(Comparator.<List<Card>>comparingInt(List::size).reversed()
                .thenComparing(g -> g.get(0).getRank(), Comparator.reverseOrder()));

        List<Rank> ordered = new ArrayList<>();
        List<Card> display = new ArrayList<>();
        for (List<Card> g : groups) {
            ordered.add(g.get(0).getRank());
            display.addAll(g);
        }

        boolean flush = true;
        for (Card c : hand) flush &= c.getSuit() == hand[0].getSuit();

        Rank straightHigh = null;
        if (groups.size() == 5) {
            if (ordered.get(0).value() - ordered.get(4).value() == 4) {
                straightHigh = ordered.get(0);
            } else if (ordered.get(0) == Rank.ACE && ordered.get(1) == Rank.FIVE) {
                // The wheel: A-2-3-4-5 is a five-high straight; show the ace last.
                straightHigh = Rank.FIVE;
                display.add(display.remove(0));
            }
        }

        if (straightHigh != null) {
            HandRank r = !flush ? HandRank.STRAIGHT
                    : straightHigh == Rank.ACE ? HandRank.ROYAL_FLUSH : HandRank.STRAIGHT_FLUSH;
            return new HandValue(r, List.of(straightHigh), display);
        }
        if (flush) return new HandValue(HandRank.FLUSH, ordered, display);

        int top = groups.get(0).size();
        int second = groups.get(1).size();
        HandRank r;
        if (top == 4) r = HandRank.FOUR_OF_A_KIND;
        else if (top == 3 && second == 2) r = HandRank.FULL_HOUSE;
        else if (top == 3) r = HandRank.THREE_OF_A_KIND;
        else if (top == 2 && second == 2) r = HandRank.TWO_PAIR;
        else if (top == 2) r = HandRank.PAIR;
        else r = HandRank.HIGH_CARD;
        return new HandValue(r, ordered, display);
    }
}
