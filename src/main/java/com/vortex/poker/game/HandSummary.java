package com.vortex.poker.game;

import java.util.List;

/** Outcome of a finished hand, one entry per player dealt in. */
public record HandSummary(int handNumber, boolean showdown, long potTotal, List<SeatResult> results) {
}
