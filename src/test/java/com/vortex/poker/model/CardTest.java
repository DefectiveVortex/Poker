package com.vortex.poker.model;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CardTest {
    @Test
    void parsesAndPrints() {
        assertEquals(new Card(Rank.ACE, Suit.HEARTS), Card.of("Ah"));
        assertEquals(new Card(Rank.TEN, Suit.DIAMONDS), Card.of("Td"));
        assertEquals(Card.of("Td"), Card.of("10d"));
        assertEquals("Td", Card.of("10D").toString());
        assertEquals("10♦", Card.of("Td").display());
        assertThrows(IllegalArgumentException.class, () -> Card.of("1h"));
        assertThrows(IllegalArgumentException.class, () -> Card.of("Ax"));
    }

    @Test
    void identifiersMatchBlackjackPack() {
        assertEquals("h1", Card.of("Ah").getCardIdentifier());
        assertEquals("d10", Card.of("Td").getCardIdentifier());
        assertEquals("sk", Card.of("Ks").getCardIdentifier());
        assertEquals("cq", Card.of("Qc").getCardIdentifier());
        assertEquals("sj", Card.of("Js").getCardIdentifier());
        assertEquals("c2", Card.of("2c").getCardIdentifier());
    }

    @Test
    void deckHas52DistinctCardsAndEmpties() {
        Deck deck = new Deck(new Random(1));
        Set<Card> seen = new HashSet<>();
        while (deck.remaining() > 0) assertTrue(seen.add(deck.draw()));
        assertEquals(52, seen.size());
        assertThrows(IllegalStateException.class, deck::draw);
    }

    @Test
    void seededDecksAreReproducibleAndStackedDrawsInOrder() {
        assertEquals(new Deck(new Random(7)).draw(), new Deck(new Random(7)).draw());
        Deck d = Deck.stacked(java.util.List.of(Card.of("As"), Card.of("Kd")));
        assertEquals(Card.of("As"), d.draw());
        assertEquals(Card.of("Kd"), d.draw());
        assertEquals(0, d.remaining());
        assertThrows(IllegalArgumentException.class,
                () -> Deck.stacked(java.util.List.of(Card.of("As"), Card.of("As"))));
    }
}
