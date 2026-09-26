package com.vortex.poker.model;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HandEvaluatorTest {
    private static List<Card> cards(String s) {
        return Arrays.stream(s.split(" ")).map(Card::of).toList();
    }

    private static HandValue eval(String s) {
        return HandEvaluator.best(cards(s));
    }

    private static void assertBeats(String stronger, String weaker) {
        assertTrue(eval(stronger).compareTo(eval(weaker)) > 0, stronger + " should beat " + weaker);
        assertTrue(eval(weaker).compareTo(eval(stronger)) < 0);
    }

    private static void assertTie(String a, String b) {
        assertEquals(0, eval(a).compareTo(eval(b)), a + " should tie " + b);
        assertEquals(eval(a), eval(b));
        assertEquals(eval(a).hashCode(), eval(b).hashCode());
    }

    @Test
    void recognisesAllTenRanks() {
        assertEquals(HandRank.HIGH_CARD, eval("Ah Jd 8c 5s 3h").getRank());
        assertEquals(HandRank.PAIR, eval("Ah Ad 8c 5s 3h").getRank());
        assertEquals(HandRank.TWO_PAIR, eval("Ah Ad 8c 8s 3h").getRank());
        assertEquals(HandRank.THREE_OF_A_KIND, eval("Ah Ad Ac 8s 3h").getRank());
        assertEquals(HandRank.STRAIGHT, eval("9h 8d 7c 6s 5h").getRank());
        assertEquals(HandRank.FLUSH, eval("Ah Jh 8h 5h 3h").getRank());
        assertEquals(HandRank.FULL_HOUSE, eval("Ah Ad Ac 8s 8h").getRank());
        assertEquals(HandRank.FOUR_OF_A_KIND, eval("Ah Ad Ac As 8h").getRank());
        assertEquals(HandRank.STRAIGHT_FLUSH, eval("9h 8h 7h 6h 5h").getRank());
        assertEquals(HandRank.ROYAL_FLUSH, eval("Ah Kh Qh Jh Th").getRank());
    }

    @Test
    void categoriesAreOrdered() {
        String[] ladder = {"Ah Jd 8c 5s 3h", "2h 2d 8c 5s 3h", "2h 2d 3c 3s 5h", "2h 2d 2c 5s 3h",
                "Ah 2d 3c 4s 5h", "7h 5h 4h 3h 2h", "2h 2d 2c 3s 3h", "2h 2d 2c 2s 3h",
                "Ah 2h 3h 4h 5h", "Ah Kh Qh Jh Th"};
        for (int i = 1; i < ladder.length; i++) assertBeats(ladder[i], ladder[i - 1]);
    }

    @Test
    void aceIsHighAndLowInStraights() {
        HandValue wheel = eval("Ah 2d 3c 4s 5h");
        assertEquals(HandRank.STRAIGHT, wheel.getRank());
        assertEquals("Straight, Five high", wheel.describe());
        assertEquals(cards("5h 4s 3c 2d Ah"), wheel.getBestFive());
        assertBeats("2h 3d 4c 5s 6h", "Ah 2d 3c 4s 5h");
        assertBeats("Ah Kd Qc Js Th", "Kh Qd Jc Ts 9h");
        // No wrap-around.
        assertEquals(HandRank.HIGH_CARD, eval("Qh Kd Ac 2s 3h").getRank());
        // Steel wheel is a straight flush, not a royal.
        HandValue steel = eval("Ad 2d 3d 4d 5d");
        assertEquals(HandRank.STRAIGHT_FLUSH, steel.getRank());
        assertBeats("6d 2d 3d 4d 5d", "Ad 2d 3d 4d 5d");
    }

    @Test
    void kickersDecide() {
        assertBeats("Ah Ad Kc 5s 3h", "As Ac Qc 5d 3d");        // pair: first kicker
        assertBeats("Ah Ad Kc 5s 4h", "As Ac Kd 5d 3d");        // pair: last kicker
        assertBeats("Kh Kd 7c 7s 3h", "Qh Qd Jc Js Ah");        // two pair: top pair
        assertBeats("Kh Kd 7c 7s 3h", "Ks Kc 6c 6s Ah");        // two pair: second pair
        assertBeats("Kh Kd 7c 7s 4h", "Ks Kc 7d 7h 3d");        // two pair: kicker
        assertBeats("3h 3d 3c 2s 2h", "2d 2c 2h As Ad");        // full house: trips first
        assertBeats("Ah Ad Ac Ks 2h", "As Ac Ad Qs Jh");        // trips kicker (impossible in one deck, fine for math)
        assertBeats("Ah Jh 8h 5h 4h", "As Js 8s 5s 3s");        // flush: last card
        assertBeats("Ah Kd Qc Js 9h", "Ah Kd Qc Js 8h");        // high card: last card
        assertBeats("2h 2d 2c 2s Ah", "2h 2d 2c 2s Kh");        // quads kicker
    }

    @Test
    void tiesIgnoreSuits() {
        assertTie("Ah Kd Qc Js 9h", "As Kc Qd Jh 9s");
        assertTie("Ah Kh Qh Jh Th", "As Ks Qs Js Ts");
        assertTie("9h 8d 7c 6s 5h", "9s 8c 7d 6h 5s");
    }

    @Test
    void bestFiveOfSeven() {
        // Board plays: both players hold undercards to a board straight.
        assertTie("2c 3d 9h Th Js Qd Kc", "2h 4s 9h Th Js Qd Kc");
        // Flush beats the straight that is also available.
        HandValue v = eval("2h 7h 9h Th Js Qh Kc");
        assertEquals(HandRank.FLUSH, v.getRank());
        assertEquals(cards("Qh Th 9h 7h 2h"), v.getBestFive());
        // Straight flush hidden among seven cards.
        assertEquals(HandRank.STRAIGHT_FLUSH, eval("5c 6c 7c 8c 9c Ah Ad").getRank());
        // Two trips make a full house with the higher trips.
        HandValue fh = eval("8h 8d 8c 5s 5h 5d 2c");
        assertEquals(HandRank.FULL_HOUSE, fh.getRank());
        assertEquals("Full House, Eights over Fives", fh.describe());
        // Three pairs: best two pairs plus best kicker.
        HandValue tp = eval("Kh Kd 7c 7s 3h 3d Qc");
        assertEquals(HandRank.TWO_PAIR, tp.getRank());
        assertEquals(List.of(Rank.KING, Rank.SEVEN, Rank.QUEEN), tp.getOrderedRanks());
        // Wheel found among seven cards, six-high beats it when available.
        assertEquals("Straight, Five high", eval("Ah 2d 3c 4s 5h Kd Kc").describe());
        assertEquals("Straight, Six high", eval("Ah 2d 3c 4s 5h 6d Kc").describe());
        // Kicker beyond the board.
        assertBeats("Ah Qd 2c 2s 7h 8d Jc", "Ah Td 2c 2s 7h 8d Jc");
    }

    @Test
    void describesHands() {
        assertEquals("High Card, Ace", eval("Ah Jd 8c 5s 3h").describe());
        assertEquals("Pair of Sixes", eval("6h 6d 8c 5s 3h").describe());
        assertEquals("Two Pair, Kings and Sevens", eval("Kh Kd 7c 7s 3h").describe());
        assertEquals("Three of a Kind, Queens", eval("Qh Qd Qc 7s 3h").describe());
        assertEquals("Flush, Ace high", eval("Ah Jh 8h 5h 3h").describe());
        assertEquals("Four of a Kind, Jacks", eval("Jh Jd Jc Js 3h").describe());
        assertEquals("Straight Flush, Nine high", eval("9h 8h 7h 6h 5h").describe());
        assertEquals("Royal Flush", eval("Ah Kh Qh Jh Th").describe());
        assertEquals(cards("Kh Kd 7c 7s 3h"), eval("3h 7c Kh 7s Kd").getBestFive());
    }

    @Test
    void rejectsBadInput() {
        assertThrows(IllegalArgumentException.class, () -> eval("Ah Kh Qh Jh"));
        assertThrows(IllegalArgumentException.class, () -> eval("Ah Kh Qh Jh Th 9h 8h 7h"));
        assertThrows(IllegalArgumentException.class, () -> eval("Ah Ah Qh Jh Th"));
    }
}
