package com.vortex.poker.game;

/** Betting rounds of a Hold 'Em hand, in order. */
public enum Street {
    PREFLOP, FLOP, TURN, RIVER, SHOWDOWN;

    /** Community cards on the board once this street has been dealt. */
    public int boardSize() {
        return switch (this) {
            case PREFLOP -> 0;
            case FLOP -> 3;
            case TURN -> 4;
            case RIVER, SHOWDOWN -> 5;
        };
    }
}
