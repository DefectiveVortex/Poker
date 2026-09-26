package com.vortex.poker.model;

import java.util.Set;

/** One pot (main or side): its chips and the seats that can win it. */
public record Pot(long amount, Set<Integer> eligibleSeats) {
    public Pot {
        eligibleSeats = Set.copyOf(eligibleSeats);
    }
}
