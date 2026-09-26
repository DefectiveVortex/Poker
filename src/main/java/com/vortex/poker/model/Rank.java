package com.vortex.poker.model;

/** Card rank, ordered low to high; ace is high here and the evaluator handles the wheel (A-2-3-4-5). */
public enum Rank {
    TWO(2, '2', "Two"),
    THREE(3, '3', "Three"),
    FOUR(4, '4', "Four"),
    FIVE(5, '5', "Five"),
    SIX(6, '6', "Six"),
    SEVEN(7, '7', "Seven"),
    EIGHT(8, '8', "Eight"),
    NINE(9, '9', "Nine"),
    TEN(10, 'T', "Ten"),
    JACK(11, 'J', "Jack"),
    QUEEN(12, 'Q', "Queen"),
    KING(13, 'K', "King"),
    ACE(14, 'A', "Ace");

    private final int value;
    private final char code;
    private final String displayName;

    Rank(int value, char code, String displayName) {
        this.value = value;
        this.code = code;
        this.displayName = displayName;
    }

    /** 2..14 (ace = 14). */
    public int value() {
        return value;
    }

    /** '2'..'9', 'T', 'J', 'Q', 'K', 'A'. */
    public char code() {
        return code;
    }

    /** Short label for card faces: "2".."10", "J", "Q", "K", "A". */
    public String label() {
        return this == TEN ? "10" : String.valueOf(code);
    }

    public String displayName() {
        return displayName;
    }

    public String pluralName() {
        return this == SIX ? "Sixes" : displayName + "s";
    }

    public static Rank fromValue(int value) {
        for (Rank r : values()) {
            if (r.value == value) return r;
        }
        throw new IllegalArgumentException("Invalid rank value: " + value);
    }

    /** Accepts "2".."9", "T"/"10", "J", "Q", "K", "A" (case-insensitive). */
    public static Rank fromCode(String s) {
        if (s.equals("10")) return TEN;
        if (s.length() == 1) {
            char c = Character.toUpperCase(s.charAt(0));
            for (Rank r : values()) {
                if (r.code == c) return r;
            }
        }
        throw new IllegalArgumentException("Invalid rank: " + s);
    }
}
