package com.vortex.poker.game;

import java.util.Map;
import java.util.UUID;

/**
 * A voided hand's refunds. Seated players got their contributions back on their table stack;
 * players who already left have to be paid directly.
 */
public record VoidResult(Map<Integer, Long> refundedToStacks, Map<UUID, Long> refundedToLeavers) {
}
