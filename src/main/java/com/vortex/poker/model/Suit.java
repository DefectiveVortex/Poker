package com.vortex.poker.model;

/** Card suit. {@link #code()} matches the resource pack's model ids (s/h/d/c). */
public enum Suit {
    SPADES('s', "♠"),
    HEARTS('h', "♥"),
    DIAMONDS('d', "♦"),
    CLUBS('c', "♣");

    private final char code;
    private final String symbol;

    Suit(char code, String symbol) {
        this.code = code;
        this.symbol = symbol;
    }

    public char code() {
        return code;
    }

    public String symbol() {
        return symbol;
    }

    public boolean isRed() {
        return this == HEARTS || this == DIAMONDS;
    }

    public static Suit fromCode(char c) {
        for (Suit s : values()) {
            if (s.code == Character.toLowerCase(c)) return s;
        }
        throw new IllegalArgumentException("Invalid suit: " + c);
    }
}
