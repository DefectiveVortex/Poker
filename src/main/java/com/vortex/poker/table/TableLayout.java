package com.vortex.poker.table;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;

/**
 * Where everything on a poker table is. The table is a compact 3x3 slab of felt with chairs right
 * up against it, so every seated player can read the board and their own cards from the chair.
 *
 * Geometry is worked out in table coordinates: u runs towards the way the creator faced, v to
 * their right. Top view with the table facing north (digits are chair slots, F is felt):
 * <pre>
 *            0  1  2
 *        11  F  F  F  3
 *        10  F  F  F  4
 *         9  F  F  F  5
 *            8  7  6
 * </pre>
 * Slots go clockwise seen from above. A table with n seats uses the subset {@link #slotsFor}
 * picks (spread evenly, still clockwise) and numbers its seats 0..n-1 in that order.
 *
 * Card placement (all in table coordinates, heights above the bottom of the felt block, whose top
 * is at 1.0): the board runs along u through the middle, flat; each seat's hole cards stand on
 * the felt edge right in front of its chair, tilted up towards the sitter's eyes.
 */
public final class TableLayout {
    public static final int MIN_SEATS = 2;
    public static final int MAX_SEATS = 8;

    /** Felt spans u and v in [-FELT_HALF, FELT_HALF]. */
    static final int FELT_HALF = 1;
    /** Felt plus chairs span u and v in [-FOOTPRINT_HALF, FOOTPRINT_HALF]. */
    static final int FOOTPRINT_HALF = 2;

    /** (u, v) of each chair slot, clockwise from the left end of the head side. */
    private static final int[][] SLOT_UV = {
        {2, -1}, {2, 0}, {2, 1},
        {1, 2}, {0, 2}, {-1, 2},
        {-2, 1}, {-2, 0}, {-2, -1},
        {-1, -2}, {0, -2}, {1, -2},
    };

    /**
     * Which slots a table with n seats uses, indexed by n. Picked to sit close to evenly spaced
     * around the table; the head-centre slot (1) is seat 0 whenever it's used.
     */
    private static final int[][] SLOTS_BY_COUNT = {
        null, null,
        {4, 10},
        {1, 5, 9},
        {1, 4, 7, 10},
        {1, 3, 6, 8, 11},
        {1, 3, 5, 7, 9, 11},
        {1, 3, 5, 6, 8, 9, 11},
        {0, 2, 3, 5, 6, 8, 9, 11},
    };

    /** A seated player sits this far from the chair's centre towards the table (on the stair's low step). */
    public static final double SEAT_INSET = 0.25;
    /** Height of a seated player's eyes above the bottom of the chair block (hips at 0.5, see Seating). */
    public static final double SEATED_EYE_HEIGHT = 1.52;
    /** Hole cards stand this far in from the chair's centre. */
    static final double HOLE_INSET = 0.75;
    /** Hole cards of a chair off the middle of its side move this far towards the middle. */
    static final double HOLE_SIDE_SHIFT = 0.2;
    /** Hole cards lean back this far from flat, facing the sitter. */
    public static final double HOLE_TILT_DEGREES = 56.0;
    /** A card is 12x16 px of its 16x16 texture: width 0.75, height 1.0 times the display scale. */
    public static final double CARD_WIDTH_RATIO = 0.75;
    /** Hole cards and board cards relative to display.card.scale / display.card.spacing. */
    public static final double HOLE_SCALE_FACTOR = 1.15;
    public static final double HOLE_SPACING_FACTOR = 1.07;
    public static final double BOARD_SCALE_FACTOR = 1.3;
    public static final double BOARD_SPACING_FACTOR = 1.3;
    /** How far the button sits in from the hole cards, and along the edge. */
    static final double BUTTON_INSET = 0.25;
    static final double BUTTON_ALONG = 0.35;

    private final World world;
    private final int x, y, z;
    private final BlockFace facing;
    private final int seatCount;
    private final int[] slots;

    /**
     * @param facing NORTH, EAST, SOUTH or WEST (anything else is rounded to one of them)
     * @param seatCount clamped to 2..8
     */
    public TableLayout(World world, int x, int y, int z, BlockFace facing, int seatCount) {
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.facing = cardinal(facing);
        this.seatCount = clampSeats(seatCount);
        this.slots = slotsFor(this.seatCount);
    }

    /** The same table with a different number of seats. */
    public TableLayout withSeatCount(int seats) {
        return new TableLayout(world, x, y, z, facing, seats);
    }

    public static int clampSeats(int seats) {
        return Math.max(MIN_SEATS, Math.min(MAX_SEATS, seats));
    }

    /** Slot numbers (0-11) used by a table with this many seats, in seat order. */
    static int[] slotsFor(int seatCount) {
        return SLOTS_BY_COUNT[clampSeats(seatCount)].clone();
    }

    /** NORTH/EAST/SOUTH/WEST for any face, so a diagonal or vertical facing can't break the maths. */
    public static BlockFace cardinal(BlockFace face) {
        return switch (face == null ? BlockFace.NORTH : face) {
            case EAST, EAST_NORTH_EAST, EAST_SOUTH_EAST, NORTH_EAST -> BlockFace.EAST;
            case SOUTH, SOUTH_SOUTH_EAST, SOUTH_SOUTH_WEST, SOUTH_EAST -> BlockFace.SOUTH;
            case WEST, WEST_NORTH_WEST, WEST_SOUTH_WEST, SOUTH_WEST -> BlockFace.WEST;
            default -> BlockFace.NORTH;
        };
    }

    /** The cardinal direction a yaw looks towards (0 = south, 90 = west). */
    public static BlockFace facingFromYaw(float yaw) {
        int quadrant = Math.floorMod(Math.round(yaw / 90f), 4);
        return switch (quadrant) {
            case 0 -> BlockFace.SOUTH;
            case 1 -> BlockFace.WEST;
            case 2 -> BlockFace.NORTH;
            default -> BlockFace.EAST;
        };
    }

    // ---------------------------------------------------------------------------------------
    // Table coordinates <-> world
    // ---------------------------------------------------------------------------------------

    /** World (dx, dz) of table offset (u, v). */
    static double[] toWorld(BlockFace facing, double u, double v) {
        int fx = facing.getModX(), fz = facing.getModZ();
        int rx = -fz, rz = fx; // the facing turned 90 degrees clockwise, seen from above
        return new double[] {u * fx + v * rx, u * fz + v * rz};
    }

    /** Table (u, v) of world offset (dx, dz). */
    static int[] toTable(BlockFace facing, int dx, int dz) {
        int fx = facing.getModX(), fz = facing.getModZ();
        int rx = -fz, rz = fx;
        return new int[] {dx * fx + dz * fz, dx * rx + dz * rz};
    }

    /** Yaw that looks along table direction (du, dv). */
    static float yawOf(BlockFace facing, double du, double dv) {
        double[] d = toWorld(facing, du, dv);
        return (float) Math.toDegrees(Math.atan2(-d[0], d[1]));
    }

    // ---------------------------------------------------------------------------------------
    // Accessors
    // ---------------------------------------------------------------------------------------

    public World getWorld() { return world; }
    public int getX() { return x; }
    public int getY() { return y; }
    public int getZ() { return z; }
    public BlockFace getFacing() { return facing; }
    public int getSeatCount() { return seatCount; }

    /** Centre of the middle felt block, at its bottom face (standing height). */
    public Location getCenter() {
        return new Location(world, x + 0.5, y, z + 0.5);
    }

    /**
     * A point on the table in table coordinates, {@code height} above the bottom of the felt
     * (the felt's top surface is at height 1).
     */
    public Location at(double u, double v, double height) {
        double[] d = toWorld(facing, u, v);
        return new Location(world, x + 0.5 + d[0], y + height, z + 0.5 + d[1]);
    }

    /** World direction of table direction (du, dv). */
    public Vector direction(double du, double dv) {
        double[] d = toWorld(facing, du, dv);
        return new Vector(d[0], 0, d[1]);
    }

    int slotOf(int seat) {
        return slots[seat];
    }

    /** Table (u, v) of a seat's chair. */
    double[] chairUV(int seat) {
        int[] uv = SLOT_UV[slots[seat]];
        return new double[] {uv[0], uv[1]};
    }

    /** Unit (du, dv) pointing from a seat's chair towards the table (square to its side). */
    double[] inwardUV(int seat) {
        int[] uv = SLOT_UV[slots[seat]];
        return Math.abs(uv[0]) == FOOTPRINT_HALF
            ? new double[] {-Math.signum(uv[0]), 0}
            : new double[] {0, -Math.signum(uv[1])};
    }

    /** Unit (du, dv) along the table edge in front of a seat, towards the sitter's left. */
    public double[] edgeUV(int seat) {
        double[] in = inwardUV(seat);
        // the inward direction turned 90 degrees anticlockwise seen from above
        return new double[] {in[1], -in[0]};
    }

    /** Position along its side of a seat's chair: -1, 0 or 1 in the edgeUV direction. */
    private double sideOffset(int seat) {
        double[] c = chairUV(seat), e = edgeUV(seat);
        return c[0] * e[0] + c[1] * e[1];
    }

    /** Where a seat's hole cards stand, in table coordinates (u, v): on the felt edge before the chair. */
    public double[] cardSpotUV(int seat) {
        double[] c = chairUV(seat), in = inwardUV(seat), e = edgeUV(seat);
        double shift = -Math.signum(sideOffset(seat)) * HOLE_SIDE_SHIFT;
        return new double[] {
            c[0] + in[0] * HOLE_INSET + e[0] * shift,
            c[1] + in[1] * HOLE_INSET + e[1] * shift,
        };
    }

    /** Where the dealer button goes for a seat: in from its cards, towards the middle of its side. */
    public double[] buttonSpotUV(int seat) {
        double[] spot = cardSpotUV(seat), in = inwardUV(seat), e = edgeUV(seat);
        double side = sideOffset(seat);
        // off-middle chairs: towards the middle of the side; middle chairs: the sitter's right
        double along = side != 0 ? -Math.signum(side) * BUTTON_ALONG : -BUTTON_ALONG;
        return new double[] {
            spot[0] + in[0] * BUTTON_INSET + e[0] * along,
            spot[1] + in[1] * BUTTON_INSET + e[1] * along,
        };
    }

    /** Where a seated player's eyes are, in table coordinates (u, v); height is SEATED_EYE_HEIGHT. */
    public double[] eyeUV(int seat) {
        double[] c = chairUV(seat), in = inwardUV(seat);
        return new double[] {c[0] + in[0] * SEAT_INSET, c[1] + in[1] * SEAT_INSET};
    }

    /** Height of a tilted hole card's centre, so its bottom edge just clears the felt top (1.0). */
    public static double holeCardHeight(double scale) {
        return 1.0 + scale / 2 * Math.sin(Math.toRadians(HOLE_TILT_DEGREES)) + 0.01;
    }

    /** Table (u, v) of board card {@code index} (0-4) with the given spacing. */
    public double[] boardSpotUV(int index, double spacing) {
        return new double[] {(index - 2) * spacing, 0};
    }

    /** Yaw a player on this seat faces (towards the table). */
    public float getSeatYaw(int seat) {
        double[] in = inwardUV(seat);
        return yawOf(facing, in[0], in[1]);
    }

    /** Yaw the top edge of a seat's hole cards points to: away from the sitter, so they read upright. */
    public float getCardTopYaw(int seat) {
        return getSeatYaw(seat);
    }

    /**
     * Yaw the top edge of the board cards points to. They lie along u and read upright from the
     * right-hand side of the table (v > 0), sideways from the ends.
     */
    public float getBoardTopYaw() {
        return yawOf(facing, 0, -1);
    }

    /** The chair block for a seat. */
    public Block getChairBlock(int seat) {
        double[] c = chairUV(seat);
        double[] d = toWorld(facing, c[0], c[1]);
        return world.getBlockAt(x + (int) Math.round(d[0]), y, z + (int) Math.round(d[1]));
    }

    /** The way a seat's stair faces: away from the table, so the sitter's back is to the outside. */
    public BlockFace getChairFacing(int seat) {
        double[] in = inwardUV(seat);
        double[] d = toWorld(facing, -in[0], -in[1]);
        for (BlockFace face : new BlockFace[] {BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST}) {
            if (face.getModX() == (int) Math.round(d[0]) && face.getModZ() == (int) Math.round(d[1])) {
                return face;
            }
        }
        return BlockFace.NORTH;
    }

    /** Chair location for a seat: centre of the chair block, looking at the table. */
    public Location getChairLocation(int seat) {
        Block b = getChairBlock(seat);
        return new Location(world, b.getX() + 0.5, y, b.getZ() + 0.5, getSeatYaw(seat), 0f);
    }

    /** Where a seated player's feet-level position is: on the chair's low step, looking at the table. */
    public Location getSeatLocation(int seat) {
        double[] eye = eyeUV(seat);
        Location loc = at(eye[0], eye[1], 0);
        loc.setYaw(getSeatYaw(seat));
        return loc;
    }

    /** Every felt block. */
    public List<Block> getFeltBlocks() {
        List<Block> blocks = new ArrayList<>();
        for (int u = -FELT_HALF; u <= FELT_HALF; u++) {
            for (int v = -FELT_HALF; v <= FELT_HALF; v++) {
                double[] d = toWorld(facing, u, v);
                blocks.add(world.getBlockAt(x + (int) d[0], y, z + (int) d[1]));
            }
        }
        return blocks;
    }

    // ---------------------------------------------------------------------------------------
    // Lookup
    // ---------------------------------------------------------------------------------------

    /** True if the block is part of this table's felt. */
    public boolean isFelt(Block block) {
        int[] uv = localOf(block);
        return uv != null && Math.abs(uv[0]) <= FELT_HALF && Math.abs(uv[1]) <= FELT_HALF;
    }

    /** The seat whose chair is this block, or -1. */
    public int seatAt(Block block) {
        int[] uv = localOf(block);
        return uv == null ? -1 : seatAtLocal(uv[0], uv[1]);
    }

    int seatAtLocal(int u, int v) {
        for (int seat = 0; seat < seatCount; seat++) {
            int[] slot = SLOT_UV[slots[seat]];
            if (slot[0] == u && slot[1] == v) {
                return seat;
            }
        }
        return -1;
    }

    private int[] localOf(Block block) {
        if (block == null || block.getY() != y || world == null || !world.equals(block.getWorld())) {
            return null;
        }
        return toTable(facing, block.getX() - x, block.getZ() - z);
    }

    /**
     * True if this table's footprint (felt and chairs), plus a one-block walkway, would touch
     * another table's footprint.
     */
    public boolean overlaps(TableLayout other) {
        if (world == null || !world.equals(other.world) || Math.abs(other.y - y) > 2) {
            return false;
        }
        int[] a = bounds(), b = other.bounds();
        // a and b are {minX, maxX, minZ, maxZ}; keep one clear block between them
        return a[0] <= b[1] + 1 && b[0] <= a[1] + 1 && a[2] <= b[3] + 1 && b[2] <= a[3] + 1;
    }

    /** World {minX, maxX, minZ, maxZ} of the footprint. */
    int[] bounds() {
        return new int[] {x - FOOTPRINT_HALF, x + FOOTPRINT_HALF, z - FOOTPRINT_HALF, z + FOOTPRINT_HALF};
    }

    /** Unit vector (world) from a seat's chair towards the table. */
    public Vector getInward(int seat) {
        double[] in = inwardUV(seat);
        return direction(in[0], in[1]);
    }

    // ---------------------------------------------------------------------------------------
    // The pre-1.1 layout (5x3 felt), for moving old tables over
    // ---------------------------------------------------------------------------------------

    private static final int[][] LEGACY_SLOT_UV = {
        {3, 0}, {2, 2}, {0, 2}, {-2, 2}, {-3, 0}, {-2, -2}, {0, -2}, {2, -2},
    };
    private static final int[][] LEGACY_SLOTS_BY_COUNT = {
        null, null, {2, 6}, {0, 3, 5}, {1, 3, 5, 7}, {0, 1, 3, 5, 7}, {1, 2, 3, 5, 6, 7},
        {0, 1, 2, 3, 5, 6, 7}, {0, 1, 2, 3, 4, 5, 6, 7},
    };

    /** Felt blocks of the old 5x3 table on this spot. */
    List<Block> legacyFeltBlocks() {
        List<Block> blocks = new ArrayList<>();
        for (int u = -2; u <= 2; u++) {
            for (int v = -1; v <= 1; v++) {
                double[] d = toWorld(facing, u, v);
                blocks.add(world.getBlockAt(x + (int) d[0], y, z + (int) d[1]));
            }
        }
        return blocks;
    }

    /** Chair blocks the old layout built for this many seats. */
    List<Block> legacyChairBlocks(int seats) {
        List<Block> blocks = new ArrayList<>();
        for (int slot : LEGACY_SLOTS_BY_COUNT[clampSeats(seats)]) {
            int[] uv = LEGACY_SLOT_UV[slot];
            double[] d = toWorld(facing, uv[0], uv[1]);
            blocks.add(world.getBlockAt(x + (int) d[0], y, z + (int) d[1]));
        }
        return blocks;
    }
}
