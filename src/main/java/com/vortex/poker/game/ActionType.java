package com.vortex.poker.game;

/**
 * What a player can do on their turn. BET and RAISE take the TOTAL street bet ("raise to"),
 * ALL_IN is resolved to a CALL, BET or RAISE of the player's whole stack.
 */
public enum ActionType {
    FOLD, CHECK, CALL, BET, RAISE, ALL_IN
}
