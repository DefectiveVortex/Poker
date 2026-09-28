package com.vortex.poker.table;

import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

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
                double[] reading = layout.readingUV(seat);
                double centre = eyeDistance(layout, seat, layout.boardSpotUV(2, BOARD_SPACING, reading), CARD_HEIGHT);
                assertTrue(centre <= 2.1, "n=" + n + " seat " + seat + " board centre " + centre);
                for (int i = 0; i < 5; i++) {
                    double d = eyeDistance(layout, seat, layout.boardSpotUV(i, BOARD_SPACING, reading), CARD_HEIGHT);
                    assertTrue(d <= 2.6, "n=" + n + " seat " + seat + " board card " + i + " at " + d);
                }
            }
        }
    }

    /** Round 4 (Vortex): from the short end the board read sideways, its far cards tiny. */
    @Test
    void everySeatsBoardCopyRunsAcrossItsViewAndReadsUpright() {
        for (BlockFace f : FACINGS) {
            for (int n = TableLayout.MIN_SEATS; n <= TableLayout.MAX_SEATS; n++) {
                TableLayout layout = new TableLayout(null, 0, 64, 0, f, n);
                for (int seat = 0; seat < n; seat++) {
                    double[] reading = layout.readingUV(seat), in = layout.inwardUV(seat), e = layout.edgeUV(seat);
                    assertArrayEquals(in, reading, "reads the way the sitter faces");
                    assertEquals(layout.getSeatYaw(seat), layout.getBoardTopYaw(reading), 1e-3, "top edge away from the sitter");
                    double[] first = layout.boardSpotUV(0, BOARD_SPACING, reading), last = layout.boardSpotUV(4, BOARD_SPACING, reading);
                    double du = last[0] - first[0], dv = last[1] - first[1];
                    assertEquals(0, du * in[0] + dv * in[1], 1e-9, "the row is square to the sitter");
                    assertTrue(first[0] * e[0] + first[1] * e[1] > 0, "the first card is on the sitter's left");
                    assertArrayEquals(new double[] {0, 0}, layout.boardSpotUV(2, BOARD_SPACING, reading), 1e-9);
                }
                assertArrayEquals(layout.boardSpotUV(0, BOARD_SPACING), layout.boardSpotUV(0, BOARD_SPACING, TableLayout.SPECTATOR_READING_UV));
                assertEquals(layout.getBoardTopYaw(), layout.getBoardTopYaw(TableLayout.SPECTATOR_READING_UV), 1e-6);
            }
        }
    }

    /**
     * Round 4 (Botcam): at 1.6 the pot label sat in front of the far players' name tags. It must stay
     * under every eye line but above the sight lines to the board and to every other seat's cards.
     */
    @Test
    void thePotLabelHidesNoNameTagCardOrBoard() {
        double bottom = com.vortex.poker.display.WorldTableView.POT_INFO_HEIGHT;
        double top = bottom + 0.025 * 11 * com.vortex.poker.display.WorldTableView.POT_INFO_SCALE; // one line + background
        assertTrue(top < TableLayout.SEATED_EYE_HEIGHT - 0.03, "under the eye line: " + top);
        for (int n = TableLayout.MIN_SEATS; n <= TableLayout.MAX_SEATS; n++) {
            TableLayout layout = new TableLayout(null, 0, 64, 0, BlockFace.NORTH, n);
            for (int seat = 0; seat < n; seat++) {
                double[] eye = layout.eyeUV(seat);
                List<double[]> targets = new ArrayList<>();
                for (int i = 0; i < 5; i++) targets.add(layout.boardSpotUV(i, BOARD_SPACING, layout.readingUV(seat)));
                for (int o = 0; o < n; o++) if (o != seat) targets.add(layout.cardSpotUV(o));
                for (double[] t : targets) {
                    // where the sight line to a card on the felt passes the middle of the table, if it does
                    double du = t[0] - eye[0], dv = t[1] - eye[1], len = Math.hypot(du, dv);
                    double along = -(eye[0] * du + eye[1] * dv) / len; // distance to the point nearest the middle
                    double miss = Math.abs(eye[0] * dv - eye[1] * du) / len;
                    if (along <= 0 || along >= len || miss > 0.3) continue; // the label is about 0.6 wide
                    double h = TableLayout.SEATED_EYE_HEIGHT - (TableLayout.SEATED_EYE_HEIGHT - CARD_HEIGHT) * along / len;
                    assertTrue(h < bottom - 0.02, "n=" + n + " seat " + seat + " sight line at " + h + " meets the pot label");
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

    private static double[] box(double[] c, double half) {
        return new double[] {c[0] - half, c[0] + half, c[1] - half, c[1] + half};
    }

    /** The felt a board copy covers, for a reader looking along {@code reading}. */
    private static double[] boardRect(double[] reading) {
        double halfRow = 2 * BOARD_SPACING + BOARD_SCALE * TableLayout.CARD_WIDTH_RATIO / 2, halfDepth = BOARD_SCALE / 2;
        return reading[0] != 0
            ? new double[] {-halfDepth, halfDepth, -halfRow, halfRow}
            : new double[] {-halfRow, halfRow, -halfDepth, halfDepth};
    }

    private static final double[][] READINGS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    @Test
    void cardsButtonAndChipsNeverOverlapAndStayOnTheFelt() {
        double edge = TableLayout.FELT_HALF + 0.5;
        List<double[]> fixed = new ArrayList<>();
        for (double[] r : READINGS) fixed.add(boardRect(r));
        List<double[]> chips = new ArrayList<>();
        for (double[] c : TableLayout.CHIP_STACKS_UV) chips.add(box(c, TableLayout.CHIP_HALF));
        for (double[] a : chips) {
            for (double[] b : fixed) assertTrue(!overlap(a, b), "chips clear of every board copy");
        }
        for (int n = TableLayout.MIN_SEATS; n <= TableLayout.MAX_SEATS; n++) {
            TableLayout layout = new TableLayout(null, 0, 64, 0, BlockFace.NORTH, n);
            List<double[]> holes = new ArrayList<>();
            for (int a = 0; a < n; a++) holes.add(holeRect(layout, a));
            for (int a = 0; a < n; a++) {
                double[] ra = holes.get(a);
                assertTrue(ra[0] > -edge && ra[1] < edge && ra[2] > -edge && ra[3] < edge, "n=" + n + " seat " + a + " on felt");
                for (double[] b : fixed) assertTrue(!overlap(ra, b), "n=" + n + " seat " + a + " clear of the board");
                for (double[] c : chips) assertTrue(!overlap(ra, c), "n=" + n + " seat " + a + " clear of the chips");
                for (int b = a + 1; b < n; b++) {
                    assertTrue(!overlap(ra, holes.get(b)), "n=" + n + " seats " + a + "/" + b + " overlap");
                }
                // the button, wherever it goes, touches nothing
                double[] button = box(layout.buttonSpotUV(a), TableLayout.BUTTON_HALF);
                assertTrue(button[0] > -edge && button[1] < edge && button[2] > -edge && button[3] < edge,
                    "n=" + n + " seat " + a + " button on felt");
                for (double[] b : fixed) assertTrue(!overlap(button, b), "n=" + n + " seat " + a + " button clear of the board");
                for (double[] c : chips) assertTrue(!overlap(button, c), "n=" + n + " seat " + a + " button clear of the chips");
                for (double[] h : holes) assertTrue(!overlap(button, h), "n=" + n + " seat " + a + " button clear of the cards");
            }
        }
    }

    @Test
    void theButtonSitsBesideItsOwnSeatsCards() {
        for (int n = TableLayout.MIN_SEATS; n <= TableLayout.MAX_SEATS; n++) {
            TableLayout layout = new TableLayout(null, 0, 64, 0, BlockFace.NORTH, n);
            for (int a = 0; a < n; a++) {
                double[] b = layout.buttonSpotUV(a), own = layout.cardSpotUV(a);
                double mine = Math.hypot(b[0] - own[0], b[1] - own[1]);
                for (int o = 0; o < n; o++) {
                    if (o == a) continue;
                    double[] other = layout.cardSpotUV(o);
                    assertTrue(Math.hypot(b[0] - other[0], b[1] - other[1]) > mine + 0.2,
                        "n=" + n + " seat " + a + " button nearer seat " + o + "'s cards");
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
