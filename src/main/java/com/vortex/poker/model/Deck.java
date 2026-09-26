package com.vortex.poker.model;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** A deck for one hand. It never reshuffles itself; make a new Deck per hand. */
public class Deck {
    private final List<Card> cards;
    private int next;

    /** A full 52-card deck shuffled with a SecureRandom. */
    public Deck() {
        this(new SecureRandom());
    }

    /** A full 52-card deck shuffled with the given source (seed it for reproducible tests). */
    public Deck(Random rng) {
        cards = new ArrayList<>(52);
        for (Suit suit : Suit.values()) {
            for (Rank rank : Rank.values()) {
                cards.add(new Card(rank, suit));
            }
        }
        Collections.shuffle(cards, rng);
    }

    private Deck(List<Card> topFirst) {
        cards = new ArrayList<>(topFirst);
    }

    /** A deck holding exactly these cards, drawn in the given order. For tests. */
    public static Deck stacked(List<Card> topFirst) {
        if (topFirst.stream().distinct().count() != topFirst.size()) {
            throw new IllegalArgumentException("Duplicate cards in stacked deck: " + topFirst);
        }
        return new Deck(topFirst);
    }

    public Card draw() {
        if (next >= cards.size()) throw new IllegalStateException("Deck is empty");
        return cards.get(next++);
    }

    public int remaining() {
        return cards.size() - next;
    }
}
