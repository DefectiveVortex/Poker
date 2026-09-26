package com.vortex.poker.model;

import java.util.Objects;

/** An immutable playing card. */
public final class Card {
    private final Rank rank;
    private final Suit suit;

    public Card(Rank rank, Suit suit) {
        this.rank = Objects.requireNonNull(rank, "rank");
        this.suit = Objects.requireNonNull(suit, "suit");
    }

    /** Parses "Ah", "Td", "10d", "2c" (rank then suit, case-insensitive). */
    public static Card of(String text) {
        String s = text.trim();
        if (s.length() < 2 || s.length() > 3) throw new IllegalArgumentException("Invalid card: " + text);
        return new Card(Rank.fromCode(s.substring(0, s.length() - 1)), Suit.fromCode(s.charAt(s.length() - 1)));
    }

    public Rank getRank() {
        return rank;
    }

    public Suit getSuit() {
        return suit;
    }

    /**
     * Resource pack model id, identical to Blackjack's: suit s|h|d|c + rank 1 (ace), 2..10, j, q, k.
     * e.g. "h1", "d10", "sk".
     */
    public String getCardIdentifier() {
        String r = switch (rank) {
            case ACE -> "1";
            case JACK -> "j";
            case QUEEN -> "q";
            case KING -> "k";
            default -> String.valueOf(rank.value());
        };
        return suit.code() + r;
    }

    /** Human-readable, e.g. "A♥", "10♠". */
    public String display() {
        return rank.label() + suit.symbol();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Card c && c.rank == rank && c.suit == suit;
    }

    @Override
    public int hashCode() {
        return rank.ordinal() * 4 + suit.ordinal();
    }

    /** Compact form, e.g. "Ah", "Td". */
    @Override
    public String toString() {
        return "" + rank.code() + suit.code();
    }
}
