package com.vortex.poker.game;

import com.vortex.poker.model.Card;
import com.vortex.poker.model.HandValue;

import java.util.List;
import java.util.UUID;

/** A hand shown at showdown. */
public record ShowdownHand(int seat, UUID player, List<Card> holeCards, HandValue value) {
}
