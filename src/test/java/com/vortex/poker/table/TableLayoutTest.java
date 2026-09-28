package com.vortex.poker.table;

import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TableLayoutTest {
    private static final BlockFace[] FACINGS = {BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST};
    // config.yml defaults
    private static final double SCALE = 0.35, SPACING = 0.3, CARD_HEIGHT = 1.03;

    private static final double HOLE_SCALE = SCALE * TableLayout.HOLE_SCALE_FACTOR;
    private static final double HOLE_SPACING = SPACING * TableLayout.HOLE_SPACING_FACTOR;
    private static final double BOARD_SCALE = SCALE * TableLayout.BOARD_SCALE_FACTOR;
    private static final double BOARD_SPACING = SPACING * TableLayout.BOARD_SPACING_FACTOR;

    @Test
    void tableAndWorldCoordinatesRoundTrip() {
        for (BlockFace f : FACINGS) {
            for (int u = -2; u <= 2; u++) {
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
        // bearing atan2(dx, -dz) keeps increasing (starting just west of north for slot 0).
        for (int n = TableLayout.MIN_SEATS; n <= TableLayout.MAX_SEATS; n++) {
            TableLayout layout = new TableLayout(null, 0, 64, 0, BlockFace.NORTH, n);
            double last = -1;
            for (int seat = 0; seat < n; seat++) {
                double[] c = layout.chairUV(seat);
                double[] d = TableLayout.toWorld(BlockFace.NORTH, c[0], c[1]);
                double bearing = Math.toDegrees(Math.atan2(d[0], -d[1]));
                bearing = (bearing + 360 + 30) % 360; // slot 0 sits at -26.6 degrees
                assertTrue(bearing > last, "n=" + n + " seat " + seat + " bearing " + bearing + " after " + last);
                last = bearing;
            }
        }
    }

    @Test
    void seatsLookAtTheTableAndChairsFaceAway() {
        for (BlockFace f : FACINGS) {
            for (int n = TableLayout.MIN_SEATS; n <= TableLayout.MAX_SEATS; n++) {
                TableLayout layout = new TableLayout(null, 0, 64, 0, f, n);
                for (int seat = 0; seat < n; seat++) {
                    double[] in = TableLayout.toWorld(f, layout.inwardUV(seat)[0], layout.inwardUV(seat)[1]);
                    double yaw = Math.toRadians(layout.getSeatYaw(seat));
                    assertEquals(1.0, -Math.sin(yaw) * in[0] + Math.cos(yaw) * in[1], 1e-9, f + " n=" + n + " seat " + seat);
                    BlockFace chair = layout.getChairFacing(seat);
                    assertEquals(-1.0, chair.getModX() * in[0] + chair.getModZ() * in[1], 1e-9, "stair faces outwards");
                }
            }
        }
    }

    @Test
    void chairsTouchTheFeltAndNeverEachOther() {
        for (int n = TableLayout.MIN_SEATS; n <= TableLayout.MAX_SEATS; n++) {
            TableLayout layout = new TableLayout(null, 0, 64, 0, BlockFace.NORTH, n);
            for (int seat = 0; seat < n; seat++) {
                double[] c = layout.chairUV(seat);
                assertEquals(2.0, Math.max(Math.abs(c[0]), Math.abs(c[1])), 1e-9, "chair against the felt");
                assertTrue(Math.min(Math.abs(c[0]), Math.abs(c[1])) <= 1, "not on a corner");
            }
        }
    }

    // ------------------------------------------------------------------------------------
    // What a seated player can see (Vortex, round 2: the board and your own cards must be
    // readable from the chair)
    // ------------------------------------------------------------------------------------

    private static double eyeDistance(TableLayout layout, int seat, double[] uv, double height) {
        double[] eye = layout.eyeUV(seat);
        double du = uv[0] - eye[0], dv = uv[1] - eye[1], dh = height - TableLayout.SEATED_EYE_HEIGHT;
        return Math.sqrt(du * du + dv * dv + dh * dh);
    }

    @Test
    void theBoardIsCloseToEverySeat() {
        for (int n = TableLayout.MIN_SEATS; n <= TableLayout.MAX_SEATS; n++) {
            TableLayout layout = new TableLayout(null, 0, 64, 0, BlockFace.NORTH, n);
            for (int seat = 0; seat < n; seat++) {
                double centre = eyeDistance(layout, seat, layout.boardSpotUV(2, BOARD_SPACING), CARD_HEIGHT);
                assertTrue(centre <= 2.1, "n=" + n + " seat " + seat + " board centre " + centre);
                for (int i = 0; i < 5; i++) {
                    double d = eyeDistance(layout, seat, layout.boardSpotUV(i, BOARD_SPACING), CARD_HEIGHT);
                    assertTrue(d <= 2.8, "n=" + n + " seat " + seat + " board card " + i + " at " + d);
                }
            }
        }
    }

    @Test
    void ownHoleCardsAreRightInFront() {
        for (int n = TableLayout.MIN_SEATS; n <= TableLayout.MAX_SEATS; n++) {
            TableLayout layout = new TableLayout(null, 0, 64, 0, BlockFace.NORTH, n);
            for (int seat = 0; seat < n; seat++) {
                double[] spot = layout.cardSpotUV(seat), eye = layout.eyeUV(seat), in = layout.inwardUV(seat);
                double d = eyeDistance(layout, seat, spot, CARD_HEIGHT);
                assertTrue(d >= 0.45 && d <= 0.9, "n=" + n + " seat " + seat + " hole cards at " + d);
                double ahead = (spot[0] - eye[0]) * in[0] + (spot[1] - eye[1]) * in[1];
                assertTrue(ahead > 0.3, "n=" + n + " seat " + seat + " cards ahead of the sitter: " + ahead);
            }
        }
    }

    /** {minU, maxU, minV, maxV} a seat's two tilted hole cards cover on the felt. */
    private static double[] holeRect(TableLayout layout, int seat) {
        double[] s = layout.cardSpotUV(seat), in = layout.inwardUV(seat);
        double halfAlong = HOLE_SPACING / 2 + HOLE_SCALE * TableLayout.CARD_WIDTH_RATIO / 2;
        double halfDepth = HOLE_SCALE / 2; // flat: the card's full height runs across the edge
        double hu = in[0] != 0 ? halfDepth : halfAlong, hv = in[0] != 0 ? halfAlong : halfDepth;
        return new double[] {s[0] - hu, s[0] + hu, s[1] - hv, s[1] + hv};
    }

    private static boolean overlap(double[] a, double[] b) {
        return a[0] < b[1] && b[0] < a[1] && a[2] < b[3] && b[2] < a[3];
    }

    @Test
    void cardsNeverOverlapAndStayOnTheFelt() {
        double[] board = {
            -2 * BOARD_SPACING - BOARD_SCALE * TableLayout.CARD_WIDTH_RATIO / 2,
            2 * BOARD_SPACING + BOARD_SCALE * TableLayout.CARD_WIDTH_RATIO / 2,
            -BOARD_SCALE / 2, BOARD_SCALE / 2};
        double edge = TableLayout.FELT_HALF + 0.5;
        assertTrue(board[1] < edge);
        for (int n = TableLayout.MIN_SEATS; n <= TableLayout.MAX_SEATS; n++) {
            TableLayout layout = new TableLayout(null, 0, 64, 0, BlockFace.NORTH, n);
            for (int a = 0; a < n; a++) {
                double[] ra = holeRect(layout, a);
                assertTrue(ra[0] > -edge && ra[1] < edge && ra[2] > -edge && ra[3] < edge, "n=" + n + " seat " + a + " on felt");
                assertTrue(!overlap(ra, board), "n=" + n + " seat " + a + " clear of the board");
                double[] button = layout.buttonSpotUV(a);
                assertTrue(Math.abs(button[0]) < edge && Math.abs(button[1]) < edge, "button on felt");
                assertTrue(!overlap(new double[] {button[0] - 0.08, button[0] + 0.08, button[1] - 0.08, button[1] + 0.08}, board),
                    "n=" + n + " seat " + a + " button clear of the board");
                for (int b = a + 1; b < n; b++) {
                    assertTrue(!overlap(ra, holeRect(layout, b)), "n=" + n + " seats " + a + "/" + b + " overlap");
                }
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
        assertEquals(-1, layout.seatAtLocal(0, 2), "the right-middle slot is unused on a 6-seat table");
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
        assertEquals(BlockFace.NORTH, TableLayout.cardinal(BlockFace.UP));
    }

    @Test
    void footprintIsFiveByFive() {
        assertArrayEquals(new int[] {-2, 2, -2, 2}, new TableLayout(null, 0, 0, 0, BlockFace.NORTH, 6).bounds());
        assertArrayEquals(new int[] {8, 12, -2, 2}, new TableLayout(null, 10, 0, 0, BlockFace.WEST, 8).bounds());
    }
}
