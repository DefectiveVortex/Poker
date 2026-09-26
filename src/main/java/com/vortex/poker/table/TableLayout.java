package com.vortex.poker.table;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;

/**
 * Where everything on a poker table is. The table is a 5x3 slab of felt with up to eight chairs
 * around it, built along whichever way its creator was facing.
 *
 * Geometry is worked out in table coordinates: u runs along the long side (towards the facing),
 * v across it (towards the creator's right). Top view with the table facing north:
 * <pre>
 *           0
 *       7 F F F 1        F = felt, digits = chair slots
 *         F F F
 *       6 F F F 2
 *         F F F
 *       5 F F F 3
 *           4
 * </pre>
 * Slots go clockwise seen from above. A table with fewer than eight seats uses a subset of them
 * (see {@link #slotsFor}), still clockwise, and numbers its seats 0..n-1 in that order.
 */
public final class TableLayout {
    public static final int MIN_SEATS = 2;
    public static final int MAX_SEATS = 8;

    /** Felt spans u in [-FELT_HALF_LENGTH, FELT_HALF_LENGTH] and v in [-FELT_HALF_WIDTH, FELT_HALF_WIDTH]. */
    static final int FELT_HALF_LENGTH = 2;
    static final int FELT_HALF_WIDTH = 1;
    /** Felt plus chairs: u in [-3, 3], v in [-2, 2]. */
    static final int FOOTPRINT_HALF_LENGTH = 3;
    static final int FOOTPRINT_HALF_WIDTH = 2;

    /** (u, v) of each chair slot, clockwise from the head of the table. */
    private static final int[][] SLOT_UV = {
        {3, 0}, {2, 2}, {0, 2}, {-2, 2}, {-3, 0}, {-2, -2}, {0, -2}, {2, -2},
    };

    /** Which slots a table with n seats uses, indexed by n. Spread evenly and kept clockwise. */
    private static final int[][] SLOTS_BY_COUNT = {
        null, null,
        {2, 6},
        {0, 3, 5},
        {1, 3, 5, 7},
        {0, 1, 3, 5, 7},
        {1, 2, 3, 5, 6, 7},
        {0, 1, 2, 3, 5, 6, 7},
        {0, 1, 2, 3, 4, 5, 6, 7},
    };

    /** How far in from the chair a seat's cards lie, in blocks. */
    static final double CARD_INSET = 0.85;

    private final World world;
    private final int x, y, z;
    private final BlockFace facing;
    private final int seatCount;
    private final int[] slots;

    /**
     * @param facing NORTH, EAST, SOUTH or WEST; the direction the table's long side runs
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

    /** Slot numbers (0-7) used by a table with this many seats, in seat order. */
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

    int slotOf(int seat) {
        return slots[seat];
    }

    /** Table (u, v) of a seat's chair. */
    double[] chairUV(int seat) {
        int[] uv = SLOT_UV[slots[seat]];
        return new double[] {uv[0], uv[1]};
    }

    /** Unit (du, dv) pointing from a seat's chair towards the table. */
    double[] inwardUV(int seat) {
        int[] uv = SLOT_UV[slots[seat]];
        return Math.abs(uv[0]) == FOOTPRINT_HALF_LENGTH
            ? new double[] {-Math.signum(uv[0]), 0}
            : new double[] {0, -Math.signum(uv[1])};
    }

    /** Where a seat's cards lie, in table coordinates (u, v). */
    public double[] cardSpotUV(int seat) {
        double[] c = chairUV(seat), in = inwardUV(seat);
        return new double[] {c[0] + in[0] * CARD_INSET, c[1] + in[1] * CARD_INSET};
    }

    /** Unit (du, dv) along the table edge in front of a seat, towards the sitter's left. */
    public double[] edgeUV(int seat) {
        double[] in = inwardUV(seat);
        // the inward direction turned 90 degrees anticlockwise seen from above
        return new double[] {in[1], -in[0]};
    }

    /** Yaw the top edge of a flat card points to when a seat reads it upright (away from them). */
    public float getCardTopYaw(int seat) {
        return getSeatYaw(seat);
    }

    /**
     * Yaw the top edge of the board cards points to. They read upright from the right-hand long
     * side (seats with v = +2), which is the side most seats of a small table use.
     */
    public float getBoardTopYaw() {
        return yawOf(facing, 0, -1);
    }

    /** Yaw a player on this seat faces (towards the table). */
    public float getSeatYaw(int seat) {
        double[] in = inwardUV(seat);
        return yawOf(facing, in[0], in[1]);
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

    /** Every felt block. */
    public List<Block> getFeltBlocks() {
        List<Block> blocks = new ArrayList<>();
        for (int u = -FELT_HALF_LENGTH; u <= FELT_HALF_LENGTH; u++) {
            for (int v = -FELT_HALF_WIDTH; v <= FELT_HALF_WIDTH; v++) {
                double[] d = toWorld(facing, u, v);
                blocks.add(world.getBlockAt(x + (int) d[0], y, z + (int) d[1]));
            }
        }
        return blocks;
    }

    /** Every block that could hold a chair of this table, whatever its seat count. */
    public List<Block> getAllChairSlotBlocks() {
        List<Block> blocks = new ArrayList<>();
        for (int[] uv : SLOT_UV) {
            double[] d = toWorld(facing, uv[0], uv[1]);
            blocks.add(world.getBlockAt(x + (int) d[0], y, z + (int) d[1]));
        }
        return blocks;
    }

    // ---------------------------------------------------------------------------------------
    // Lookup
    // ---------------------------------------------------------------------------------------

    /** True if the block is part of this table's felt. */
    public boolean isFelt(Block block) {
        int[] uv = localOf(block);
        return uv != null && Math.abs(uv[0]) <= FELT_HALF_LENGTH && Math.abs(uv[1]) <= FELT_HALF_WIDTH;
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
        boolean alongX = facing.getModX() != 0;
        int hx = alongX ? FOOTPRINT_HALF_LENGTH : FOOTPRINT_HALF_WIDTH;
        int hz = alongX ? FOOTPRINT_HALF_WIDTH : FOOTPRINT_HALF_LENGTH;
        return new int[] {x - hx, x + hx, z - hz, z + hz};
    }

    /** Unit vector (world) from a seat's chair towards the table. */
    public Vector getInward(int seat) {
        double[] in = inwardUV(seat);
        double[] d = toWorld(facing, in[0], in[1]);
        return new Vector(d[0], 0, d[1]);
    }
}
