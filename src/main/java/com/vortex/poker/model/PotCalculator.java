package com.vortex.poker.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Splits a hand's contributions into a main pot and side pots. */
public final class PotCalculator {
    private PotCalculator() {
    }

    /**
     * Builds the pots, main pot first.
     * <p>
     * Every contribution level becomes a layer; folded seats' chips count toward the layers they reached but those
     * seats are never eligible. Adjacent layers with the same eligible seats merge. A layer only one live seat reached
     * (the uncalled excess of a bet) comes back as its own single-eligible pot so the caller can refund it. A layer
     * that only folded seats reached has its chips added to the pot below it (or, if there is none, to the next pot).
     * Zero-chip pots are never returned.
     *
     * @param contributionsBySeat total chips each seat put in this hand (zero entries are ignored)
     * @param foldedSeats         seats that folded (they may be absent from the map)
     */
    public static List<Pot> calculate(Map<Integer, Long> contributionsBySeat, Set<Integer> foldedSeats) {
        TreeSet<Long> levels = new TreeSet<>();
        for (Map.Entry<Integer, Long> e : contributionsBySeat.entrySet()) {
            long v = e.getValue();
            if (v < 0) throw new IllegalArgumentException("Negative contribution for seat " + e.getKey());
            if (v > 0) levels.add(v);
        }

        List<Long> amounts = new ArrayList<>();
        List<Set<Integer>> eligibles = new ArrayList<>();
        long carry = 0; // folded-only chips with no pot below them yet
        long prev = 0;
        for (long level : levels) {
            long layer = 0;
            Set<Integer> eligible = new TreeSet<>();
            for (Map.Entry<Integer, Long> e : contributionsBySeat.entrySet()) {
                if (e.getValue() >= level) {
                    layer += level - prev;
                    if (!foldedSeats.contains(e.getKey())) eligible.add(e.getKey());
                }
            }
            prev = level;

            int last = amounts.size() - 1;
            if (eligible.isEmpty()) {
                if (last >= 0) amounts.set(last, amounts.get(last) + layer);
                else carry += layer;
            } else if (last >= 0 && eligibles.get(last).equals(eligible)) {
                amounts.set(last, amounts.get(last) + layer);
            } else {
                amounts.add(layer + carry);
                eligibles.add(eligible);
                carry = 0;
            }
        }

        List<Pot> pots = new ArrayList<>();
        for (int i = 0; i < amounts.size(); i++) pots.add(new Pot(amounts.get(i), eligibles.get(i)));
        if (carry > 0) pots.add(new Pot(carry, Set.of())); // everyone folded: caller decides
        return pots;
    }
}
