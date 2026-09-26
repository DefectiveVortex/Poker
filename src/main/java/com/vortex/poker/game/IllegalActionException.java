package com.vortex.poker.game;

/** Thrown by {@link HoldemGame} when an action isn't allowed; {@link #getReason()} maps to a message key. */
public class IllegalActionException extends RuntimeException {

    public enum Reason {
        NO_HAND("no-hand"),
        NOT_YOUR_TURN("not-your-turn"),
        CANNOT_CHECK("cannot-check"),
        NOTHING_TO_CALL("nothing-to-call"),
        CANNOT_BET("cannot-bet"),
        CANNOT_RAISE("cannot-raise"),
        AMOUNT_TOO_SMALL("amount-too-small"),
        AMOUNT_TOO_LARGE("amount-too-large");

        private final String key;

        Reason(String key) {
            this.key = key;
        }

        /** Suffix for the message key, e.g. "action-error-cannot-check". */
        public String key() {
            return key;
        }
    }

    private final Reason reason;

    public IllegalActionException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
