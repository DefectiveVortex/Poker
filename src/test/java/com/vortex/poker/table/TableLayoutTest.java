package com.vortex.poker.table;

import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TableLayoutTest {
    private static final BlockFace[] FACINGS = {BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST};

    @Test
    void tableAndWorldCoordinatesRoundTrip() {
        for (BlockFace f : FACINGS) {
            for (int u = -3; u <= 3; u++) {
                for (int v = -2; v <= 2; v++) {
                    double[] d = TableLayout.toWorld(f, u, v);
                    assertArrayEquals(new int[] {u, v}, TableLayout.toTable(f, (int) d[0], (int) d[1]), f + " " + u + "," + v);
                }
            }
        }
    }

    @Test
    void facingNorthPutsTheRightSideToTheEast() {
        assertArrayEquals(new double[] {1, 0}, TableLayout.toWorld(BlockFace.NORTH, 0, 1));
        assertArrayEquals(new double[] {0, -1}, TableLayout.toWorld(BlockFace.NORTH, 1, 0));
    }

    @Test
    void everySeatCountUsesDistinctSlotsInClockwiseOrder() {
        for (int n = TableLayout.MIN_SEATS; n <= TableLayout.MAX_SEATS; n++) {
            int[] slots = TableLayout.slotsFor(n);
            assertEquals(n, slots.length);
            for (int i = 1; i < slots.length; i++) {
                assertTrue(slots[i] > slots[i - 1], "slots for " + n + " must increase");
            }
        }
    }

    @Test
    void seatsRunClockwiseSeenFromAbove() {
        // Facing north: clockwise from above is north -> east -> south -> west, i.e. the compass
        // bearing atan2(dx, -dz) keeps increasing.
        TableLayout layout = new TableLayout(null, 0, 64, 0, BlockFace.NORTH, 8);
        double last = -1;
        for (int seat = 0; seat < 8; seat++) {
            double[] c = layout.chairUV(seat);
            double[] d = TableLayout.toWorld(BlockFace.NORTH, c[0], c[1]);
            double bearing = Math.floorMod((int) Math.round(Math.toDegrees(Math.atan2(d[0], -d[1]))), 360);
            assertTrue(bearing > last, "seat " + seat + " bearing " + bearing + " after " + last);
            last = bearing;
        }
    }

    @Test
    void seatsLookAtTheTableAndChairsFaceAway() {
        for (BlockFace f : FACINGS) {
            for (int n = TableLayout.MIN_SEATS; n <= TableLayout.MAX_SEATS; n++) {
                TableLayout layout = new TableLayout(null, 0, 64, 0, f, n);
                for (int seat = 0; seat < n; seat++) {
                    double[] c = TableLayout.toWorld(f, layout.chairUV(seat)[0], layout.chairUV(seat)[1]);
                    double yaw = Math.toRadians(layout.getSeatYaw(seat));
                    double lookX = -Math.sin(yaw), lookZ = Math.cos(yaw);
                    // looking from the chair towards the centre
                    assertTrue(-c[0] * lookX - c[1] * lookZ > 0, f + " n=" + n + " seat " + seat);
                    BlockFace chair = layout.getChairFacing(seat);
                    assertTrue(chair.getModX() * c[0] + chair.getModZ() * c[1] > 0, "stair faces outwards");
                }
            }
        }
    }

    @Test
    void cardSpotsLieOnTheFelt() {
        for (int n = TableLayout.MIN_SEATS; n <= TableLayout.MAX_SEATS; n++) {
            TableLayout layout = new TableLayout(null, 0, 64, 0, BlockFace.EAST, n);
            for (int seat = 0; seat < n; seat++) {
                double[] spot = layout.cardSpotUV(seat);
                assertTrue(Math.abs(spot[0]) <= TableLayout.FELT_HALF_LENGTH + 0.5);
                assertTrue(Math.abs(spot[1]) <= TableLayout.FELT_HALF_WIDTH + 0.5);
            }
        }
    }

    @Test
    void chairLookupFindsEachSeat() {
        TableLayout layout = new TableLayout(null, 0, 64, 0, BlockFace.SOUTH, 6);
        for (int seat = 0; seat < 6; seat++) {
            double[] c = layout.chairUV(seat);
            assertEquals(seat, layout.seatAtLocal((int) c[0], (int) c[1]));
        }
        assertEquals(-1, layout.seatAtLocal(3, 0), "the head slot is unused on a 6-seat table");
        assertEquals(-1, layout.seatAtLocal(0, 0));
    }

    @Test
    void seatCountIsClamped() {
        assertEquals(2, new TableLayout(null, 0, 0, 0, BlockFace.NORTH, 0).getSeatCount());
        assertEquals(8, new TableLayout(null, 0, 0, 0, BlockFace.NORTH, 12).getSeatCount());
    }

    @Test
    void yawMapsToCardinalFacing() {
        assertEquals(BlockFace.SOUTH, TableLayout.facingFromYaw(10));
        assertEquals(BlockFace.WEST, TableLayout.facingFromYaw(95));
        assertEquals(BlockFace.NORTH, TableLayout.facingFromYaw(-170));
        assertEquals(BlockFace.EAST, TableLayout.facingFromYaw(-80));
    }

    @Test
    void footprintBoundsFollowTheFacing() {
        assertArrayEquals(new int[] {-2, 2, -3, 3}, new TableLayout(null, 0, 0, 0, BlockFace.NORTH, 6).bounds());
        assertArrayEquals(new int[] {-3, 3, -2, 2}, new TableLayout(null, 0, 0, 0, BlockFace.WEST, 6).bounds());
        assertFalse(TableLayout.cardinal(BlockFace.UP) != BlockFace.NORTH);
    }
}
