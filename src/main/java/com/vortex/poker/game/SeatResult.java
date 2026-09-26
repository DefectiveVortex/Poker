package com.vortex.poker.game;

import java.util.UUID;

/**
 * How one dealt-in player did in a hand. {@code contributed} excludes uncalled chips that were returned.
 * {@code left} is true if they left mid-hand (their remaining stack was already cashed out).
 */
public record SeatResult(int seat, UUID player, long contributed, long won, boolean wentToShowdown, boolean left) {

    public long net() {
        return won - contributed;
    }

    public boolean isWinner() {
        return won > 0;
    }
}
