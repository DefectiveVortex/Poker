package com.vortex.poker.display;

import com.vortex.poker.PokerPlugin;
import com.vortex.poker.config.ConfigManager;
import com.vortex.poker.model.Card;
import com.vortex.poker.table.CardDisplayCleaner;
import com.vortex.poker.table.CardModels;
import com.vortex.poker.table.TableLayout;
import com.vortex.poker.util.ServerCompat;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The table as players see it: cards as ItemDisplays textured by the Playing Cards pack,
 * TextDisplays for the pot, the seats and whose turn it is, and a small chip stack.
 *
 * A hole card is two displays in the same spot: the face, shown only to its owner, and the back,
 * hidden from its owner. Everyone else sees the back. Hole cards stand tilted on the felt edge in
 * front of their chair, facing the sitter; at showdown they flip down flat and face up. Bukkit
 * forgets per-player hiding when a player relogs or changes world, so {@link #refreshVisibility}
 * puts it back.
 *
 * Cards slide in from the middle of the table using display interpolation (client-side, so it
 * costs the server two metadata packets per card). Every entity is non-persistent and tagged, so
 * nothing survives a crash or an unloaded chunk and CardDisplayCleaner recognises leftovers.
 */
public class WorldTableView implements TableView {
    /** Text heights above the bottom of the table block. */
    private static final double SEAT_INFO_HEIGHT = 2.45;
    private static final double TURN_MARKER_HEIGHT = 2.9;
    private static final double POT_INFO_HEIGHT = 1.6;
    private static final double BUTTON_HEIGHT = 1.12;
    /** Where dealt cards slide in from: above the middle of the table. */
    private static final double DECK_HEIGHT = 1.35;
    private static final int SLIDE_TICKS = 6;
    private static final int FLIP_TICKS = 3;
    /** Chip stack beside the board: up to three stacks, chips 0.16 wide and 0.035 thick. */
    private static final double CHIP_SIZE = 0.16;
    private static final double CHIP_THICKNESS = 0.035;
    private static final double CHIP_PITCH = 0.04;
    private static final double CHIPS_V = -0.5;
    private static final Material[] CHIP_COLOURS = {Material.RED_CONCRETE, Material.BLACK_CONCRETE, Material.WHITE_CONCRETE};
    private static final int MAX_CHIPS_PER_STACK = 6;

    /** Per-kind tags, next to CardDisplayCleaner.DISPLAY_TAG, so tests can count by kind. */
    public static final String CARD_TAG = "poker-card";
    public static final String BACK_TAG = "poker-card-back";
    public static final String BOARD_TAG = "poker-board";
    public static final String SEAT_CARD_TAG_PREFIX = "poker-seat-card-";
    public static final String TEXT_TAG = "poker-text";
    public static final String BUTTON_TAG = "poker-button";
    public static final String CHIP_TAG = "poker-chip";
    private static final String DEAL_SOUND = "minecraft:block.wooden_button.click_on";
    private static final String FLIP_SOUND = "minecraft:item.book.page_turn";
    private static final Color WINNER_GLOW = Color.fromRGB(255, 200, 40);
    private static final Color TEXT_BACKGROUND = Color.fromARGB(110, 0, 0, 0);
    private static final Color WINNER_BACKGROUND = Color.fromARGB(170, 190, 140, 20);

    private final PokerPlugin plugin;
    private final TableLayout layout;
    private final String tableTag;
    private final String legacyTableTag;

    private final Map<Integer, SeatCards> seatCards = new HashMap<>();
    private final List<Card> boardCards = new ArrayList<>();
    private final List<ItemDisplay> boardDisplays = new ArrayList<>();
    private final Map<Integer, TextDisplay> seatInfo = new HashMap<>();
    private final List<BlockDisplay> chips = new ArrayList<>();
    /** Displays on their way out (flipping away); removed when the animation ends or the hand clears. */
    private final List<Entity> leaving = new ArrayList<>();
    private final Set<UUID> owned = new HashSet<>();
    private TextDisplay potInfo;
    private TextDisplay turnMarker;
    private TextDisplay button;
    private int chipCount;
    private boolean destroyed;

    /** A seat's hole cards: faces for the owner, backs for everyone else, or faces for all once revealed. */
    private static final class SeatCards {
        UUID owner;
        final List<ItemDisplay> faces = new ArrayList<>();
        final List<ItemDisplay> backs = new ArrayList<>();
        boolean revealed;
        boolean winner;
    }

    public WorldTableView(PokerPlugin plugin, TableLayout layout, int tableId) {
        this.plugin = plugin;
        this.layout = layout;
        this.tableTag = tableTag(tableId);
        this.legacyTableTag = legacyTableTag(tableId);
    }

    /**
     * Scoreboard tag carried by every entity a table spawns. No ':' in tags: commands can't
     * select a tag containing one (@e[tag=a:b] is a syntax error).
     */
    public static String tableTag(int tableId) {
        return "poker-table-" + tableId;
    }

    /** The tag 1.0 builds used, still recognised when purging a table's leftovers. */
    static String legacyTableTag(int tableId) {
        return "poker-table:" + tableId;
    }

    private ConfigManager config() {
        return plugin.getConfigManager();
    }

    private double holeScale() {
        return config().getCardScale() * TableLayout.HOLE_SCALE_FACTOR;
    }

    private double boardScale() {
        return config().getCardScale() * TableLayout.BOARD_SCALE_FACTOR;
    }

    // ---------------------------------------------------------------------------------------
    // TableView
    // ---------------------------------------------------------------------------------------

    @Override
    public void dealHoleCards(int seat, Player owner, List<Card> cards) {
        if (!validSeat(seat)) return;
        clearSeat(seat);
        if (!config().areCardDisplaysEnabled() || cards == null || cards.isEmpty()) return;

        SeatCards sc = new SeatCards();
        sc.owner = owner == null ? null : owner.getUniqueId();
        double tilt = Math.toRadians(TableLayout.HOLE_TILT_DEGREES);
        for (int i = 0; i < cards.size(); i++) {
            Location loc = holeCardLocation(seat, i, cards.size(), TableLayout.holeCardHeight(holeScale()));
            float top = layout.getCardTopYaw(seat);
            String seatTag = SEAT_CARD_TAG_PREFIX + seat;

            ItemDisplay face = spawnCard(loc, cards.get(i), top, tilt, holeScale(), true, seatTag);
            if (face == null) continue;
            face.setVisibleByDefault(false);
            sc.faces.add(face);

            ItemDisplay back = spawnCard(loc, null, top, tilt, holeScale(), true, seatTag);
            if (back != null) sc.backs.add(back);
        }
        seatCards.put(seat, sc);
        if (owner != null) {
            applyVisibility(owner, sc);
        }
        playSound(holeCardLocation(seat, 0, 1, 1.0), DEAL_SOUND, 0.6f, 1.2f);
    }

    @Override
    public void setBoard(List<Card> cards) {
        List<Card> next = cards == null ? List.of() : List.copyOf(cards);
        boolean extendsCurrent = next.size() >= boardCards.size()
            && next.subList(0, boardCards.size()).equals(boardCards);
        if (!extendsCurrent) {
            removeAll(boardDisplays);
            boardCards.clear();
        }
        if (!config().areCardDisplaysEnabled()) {
            boardCards.clear();
            boardCards.addAll(next);
            return;
        }
        double spacing = config().getCardSpacing() * TableLayout.BOARD_SPACING_FACTOR;
        for (int i = boardCards.size(); i < next.size(); i++) {
            double[] uv = layout.boardSpotUV(i, spacing);
            Location loc = layout.at(uv[0], uv[1], config().getCardHeight());
            ItemDisplay d = spawnCard(loc, next.get(i), layout.getBoardTopYaw(), 0, boardScale(), true, BOARD_TAG);
            if (d != null) boardDisplays.add(d);
        }
        if (next.size() > boardCards.size()) {
            playSound(layout.at(0, 0, config().getCardHeight()), DEAL_SOUND, 0.6f, 1.2f);
        }
        boardCards.clear();
        boardCards.addAll(next);
    }

    /**
     * Flip a seat's cards face up for everyone: the standing cards squash away edge-on, then flat
     * face-up cards grow back in their place.
     */
    @Override
    public void revealHoleCards(int seat, List<Card> cards) {
        if (!validSeat(seat)) return;
        SeatCards old = seatCards.remove(seat);
        if (old != null) {
            squashAway(old.faces);
            squashAway(old.backs);
        }
        if (!config().areCardDisplaysEnabled() || cards == null || cards.isEmpty()) return;

        SeatCards sc = new SeatCards();
        sc.revealed = true;
        seatCards.put(seat, sc);
        List<Card> shown = List.copyOf(cards);
        later(old == null ? 0 : FLIP_TICKS + 1, () -> {
            if (seatCards.get(seat) != sc) return; // cleared or re-dealt meanwhile
            for (int i = 0; i < shown.size(); i++) {
                Location loc = holeCardLocation(seat, i, shown.size(), config().getCardHeight());
                ItemDisplay face = spawnCard(loc, shown.get(i), layout.getCardTopYaw(seat), 0, holeScale(),
                    false, SEAT_CARD_TAG_PREFIX + seat);
                if (face == null) continue;
                sc.faces.add(face);
                if (sc.winner) glow(face);
                // start edge-on and open out
                setScaleX(face, 0f, 0);
                later(2, () -> setScaleX(face, (float) holeScale(), FLIP_TICKS));
            }
            playSound(holeCardLocation(seat, 0, 1, 1.0), FLIP_SOUND, 0.8f, 1.0f);
        });
    }

    @Override
    public void clearSeat(int seat) {
        SeatCards sc = seatCards.remove(seat);
        if (sc != null) {
            removeAll(sc.faces);
            removeAll(sc.backs);
        }
    }

    @Override
    public void setButton(int seat) {
        remove(button);
        button = null;
        if (!validSeat(seat) || destroyed) return;
        double[] spot = layout.buttonSpotUV(seat);
        button = spawnText(layout.at(spot[0], spot[1], BUTTON_HEIGHT), config().getMessage("display-button"), 0.4f);
        if (button != null) {
            button.addScoreboardTag(BUTTON_TAG);
            button.setBackgroundColor(Color.fromARGB(230, 255, 255, 255));
        }
    }

    @Override
    public void setSeatInfo(int seat, String text) {
        if (!validSeat(seat)) return;
        TextDisplay current = seatInfo.get(seat);
        if (text == null || text.isEmpty() || !config().areTextDisplaysEnabled()) {
            remove(current);
            seatInfo.remove(seat);
            return;
        }
        if (current != null && current.isValid()) {
            current.setText(text);
            return;
        }
        Location chair = layout.getChairLocation(seat);
        TextDisplay d = spawnText(chair.add(0, SEAT_INFO_HEIGHT, 0), text, 0.7f);
        if (d != null) seatInfo.put(seat, d);
    }

    @Override
    public void setPotInfo(String text) {
        if (text == null || text.isEmpty() || !config().areTextDisplaysEnabled()) {
            remove(potInfo);
            potInfo = null;
            return;
        }
        if (potInfo != null && potInfo.isValid()) {
            potInfo.setText(text);
            return;
        }
        potInfo = spawnText(layout.at(0, 0, POT_INFO_HEIGHT), text, 0.8f);
    }

    /**
     * A little stack of chips beside the board that grows with the pot: one chip for a small pot,
     * about three more for every tenfold, in up to three stacks.
     */
    @Override
    public void setPotChips(long amount) {
        int count = amount <= 0 ? 0
            : (int) Math.max(1, Math.min(MAX_CHIPS_PER_STACK * CHIP_COLOURS.length, Math.round(Math.log10(amount) * 3)));
        if (count == chipCount || destroyed) return;
        removeAll(chips);
        chipCount = count;
        if (count == 0 || !config().areCardDisplaysEnabled()) return;
        World world = layout.getWorld();
        for (int i = 0; i < count; i++) {
            int stack = i / MAX_CHIPS_PER_STACK, level = i % MAX_CHIPS_PER_STACK;
            Location loc = layout.at((stack - 1) * 0.2, CHIPS_V, 1.0 + level * CHIP_PITCH);
            if (world == null || !world.isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) return;
            BlockDisplay chip = world.spawn(loc, BlockDisplay.class);
            chip.setBlock(CHIP_COLOURS[stack].createBlockData());
            chip.setTransformation(new Transformation(
                new Vector3f((float) (-CHIP_SIZE / 2), 0f, (float) (-CHIP_SIZE / 2)), new Quaternionf(),
                new Vector3f((float) CHIP_SIZE, (float) CHIP_THICKNESS, (float) CHIP_SIZE), new Quaternionf()));
            tag(chip, CHIP_TAG);
            chips.add(chip);
        }
    }

    @Override
    public void highlightTurn(int seat) {
        remove(turnMarker);
        turnMarker = null;
        if (!validSeat(seat) || !config().areTextDisplaysEnabled()) return;
        Location chair = layout.getChairLocation(seat);
        turnMarker = spawnText(chair.add(0, TURN_MARKER_HEIGHT, 0), config().getMessage("display-turn-marker"), 0.8f);
        if (turnMarker != null) {
            turnMarker.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            turnMarker.setShadowed(true);
        }
    }

    /** Winners' cards glow gold, their seat text lights up, and the pot bursts. */
    @Override
    public void showWinners(List<Integer> seats, long amount) {
        if (seats == null || seats.isEmpty() || destroyed) return;
        World world = layout.getWorld();
        for (int seat : seats) {
            if (!validSeat(seat)) continue;
            SeatCards sc = seatCards.get(seat);
            if (sc != null) {
                sc.winner = true;
                sc.faces.forEach(this::glow); // a revealed seat still flipping glows as its faces appear
                sc.backs.forEach(this::glow); // a fold-win stays face down but still lights up
            }
            TextDisplay info = seatInfo.get(seat);
            if (info != null && info.isValid()) {
                info.setBackgroundColor(WINNER_BACKGROUND);
            }
            Particle sparkle = ServerCompat.particle("HAPPY_VILLAGER", "VILLAGER_HAPPY");
            if (world != null && sparkle != null) {
                double[] spot = layout.cardSpotUV(seat);
                world.spawnParticle(sparkle, layout.at(spot[0], spot[1], 1.3), 12, 0.25, 0.15, 0.25, 0);
            }
        }
        Particle burst = ServerCompat.particle("TOTEM_OF_UNDYING", "TOTEM");
        if (world != null && burst != null) {
            world.spawnParticle(burst, layout.at(0, CHIPS_V, 1.2), 30, 0.2, 0.1, 0.2, 0.25);
        }
        playSound(layout.at(0, 0, 1.2), "minecraft:entity.player.levelup", 0.5f, 1.6f);
    }

    @Override
    public void clearHand() {
        for (Integer seat : new ArrayList<>(seatCards.keySet())) {
            clearSeat(seat);
        }
        removeAll(boardDisplays);
        boardCards.clear();
        removeAll(leaving);
        removeAll(chips);
        chipCount = 0;
        for (TextDisplay info : seatInfo.values()) {
            if (info.isValid()) info.setBackgroundColor(TEXT_BACKGROUND);
        }
        highlightTurn(-1);
    }

    @Override
    public void destroy() {
        clearHand();
        remove(potInfo);
        remove(button);
        seatInfo.values().forEach(this::remove);
        seatInfo.clear();
        potInfo = null;
        button = null;
        owned.clear();
        destroyed = true;
        purgeTagged();
    }

    @Override
    public boolean ownsEntity(Entity entity) {
        return owned.contains(entity.getUniqueId());
    }

    @Override
    public void refreshVisibility(Player viewer) {
        for (SeatCards sc : seatCards.values()) {
            if (!sc.revealed && viewer.getUniqueId().equals(sc.owner)) {
                applyVisibility(viewer, sc);
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Placement
    // ---------------------------------------------------------------------------------------

    private boolean validSeat(int seat) {
        return seat >= 0 && seat < layout.getSeatCount();
    }

    private Location holeCardLocation(int seat, int index, int count, double height) {
        double[] spot = layout.cardSpotUV(seat);
        double[] side = layout.edgeUV(seat);
        double spacing = config().getCardSpacing() * TableLayout.HOLE_SPACING_FACTOR;
        double offset = (index - (count - 1) / 2.0) * spacing;
        // index 0 on the sitter's left, so the hand reads left to right
        return layout.at(spot[0] - side[0] * offset, spot[1] - side[1] * offset, height);
    }

    // ---------------------------------------------------------------------------------------
    // Entities
    // ---------------------------------------------------------------------------------------

    /**
     * The rotation for a card whose top edge points along {@code topYaw} (away from whoever should
     * read it) and which leans {@code tilt} radians up from lying flat, towards that reader.
     *
     * Read right to left: spin the card in its own plane, lay it flat (its top edge then points
     * along (-sin r, 0, cos r), the yaw convention), then tip it about the horizontal axis
     * perpendicular to the top edge. Keeping it all in the left rotation means the scale acts on
     * the card's own width and height, which the flip animation relies on.
     */
    static Quaternionf cardRotation(float topYaw, double tilt) {
        double r = Math.toRadians(topYaw);
        float tx = (float) -Math.sin(r), tz = (float) Math.cos(r);
        // axis = top x up, so a positive angle lifts the top edge
        Quaternionf q = new Quaternionf();
        if (tilt != 0) {
            q.rotateAxis((float) tilt, -tz, 0f, tx);
        }
        return q.rotateX((float) (Math.PI / 2)).rotateZ((float) r);
    }

    /**
     * A card on the table. {@code slide} makes it glide in from above the middle of the table.
     */
    private ItemDisplay spawnCard(Location loc, Card card, float topYaw, double tilt, double scale,
                                  boolean slide, String extraTag) {
        World world = loc.getWorld();
        if (world == null || destroyed || !world.isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) {
            return null;
        }
        loc.setYaw(0f);
        loc.setPitch(0f);
        ItemDisplay display = world.spawn(loc, ItemDisplay.class);
        display.setItemStack(CardModels.item(card));
        Vector3f start = new Vector3f();
        if (slide) {
            Vector from = layout.at(0, 0, DECK_HEIGHT).toVector().subtract(loc.toVector());
            start.set((float) from.getX(), (float) from.getY(), (float) from.getZ());
        }
        float s = (float) scale;
        display.setTransformation(new Transformation(start, cardRotation(topYaw, tilt),
            new Vector3f(s, s, s), new Quaternionf()));
        tag(display, card == null ? BACK_TAG : CARD_TAG);
        if (extraTag != null) display.addScoreboardTag(extraTag);
        if (slide) {
            later(2, () -> {
                if (!display.isValid()) return;
                display.setInterpolationDelay(0);
                display.setInterpolationDuration(SLIDE_TICKS);
                Transformation t = display.getTransformation();
                display.setTransformation(new Transformation(new Vector3f(), t.getLeftRotation(), t.getScale(), t.getRightRotation()));
            });
        }
        return display;
    }

    /** Change a card's width (for the flip), animated over {@code ticks}. */
    private void setScaleX(ItemDisplay display, float width, int ticks) {
        if (!display.isValid()) return;
        Transformation t = display.getTransformation();
        Vector3f scale = new Vector3f(width, t.getScale().y(), t.getScale().z());
        display.setInterpolationDelay(0);
        display.setInterpolationDuration(ticks);
        display.setTransformation(new Transformation(t.getTranslation(), t.getLeftRotation(), scale, t.getRightRotation()));
    }

    /** Squash cards edge-on, then remove them. */
    private void squashAway(List<ItemDisplay> displays) {
        for (ItemDisplay d : displays) {
            if (!d.isValid()) continue;
            leaving.add(d);
            setScaleX(d, 0f, FLIP_TICKS);
        }
        List<ItemDisplay> going = new ArrayList<>(displays);
        displays.clear();
        later(FLIP_TICKS + 1, () -> {
            for (ItemDisplay d : going) {
                leaving.remove(d);
                remove(d);
            }
        });
    }

    private void glow(ItemDisplay display) {
        if (display.isValid()) {
            display.setGlowColorOverride(WINNER_GLOW);
            display.setGlowing(true);
        }
    }

    private TextDisplay spawnText(Location loc, String text, float scale) {
        World world = loc.getWorld();
        if (world == null || destroyed || !world.isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) {
            return null;
        }
        TextDisplay display = world.spawn(loc, TextDisplay.class);
        display.setText(text);
        display.setBillboard(Display.Billboard.CENTER);
        display.setAlignment(TextDisplay.TextAlignment.CENTER);
        display.setShadowed(false);
        display.setSeeThrough(false);
        display.setBackgroundColor(TEXT_BACKGROUND);
        display.setTransformation(new Transformation(new Vector3f(), new Quaternionf(),
            new Vector3f(scale, scale, scale), new Quaternionf()));
        tag(display, TEXT_TAG);
        return display;
    }

    private void tag(Entity entity, String kind) {
        entity.setPersistent(false);
        entity.addScoreboardTag(CardDisplayCleaner.DISPLAY_TAG);
        entity.addScoreboardTag(kind);
        entity.addScoreboardTag(tableTag);
        owned.add(entity.getUniqueId());
    }

    /** Owner sees the faces and not the backs. */
    private void applyVisibility(Player owner, SeatCards sc) {
        for (ItemDisplay face : sc.faces) {
            if (face.isValid()) owner.showEntity(plugin, face);
        }
        for (ItemDisplay back : sc.backs) {
            if (back.isValid()) owner.hideEntity(plugin, back);
        }
    }

    private void later(long ticks, Runnable task) {
        if (ticks <= 0) {
            task.run();
        } else if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!destroyed) task.run();
            }, ticks);
        }
    }

    private void playSound(Location loc, String sound, float volume, float pitch) {
        if (loc.getWorld() != null) {
            loc.getWorld().playSound(loc, sound, volume, pitch);
        }
    }

    private void remove(Entity entity) {
        if (entity != null) {
            owned.remove(entity.getUniqueId());
            if (entity.isValid()) entity.remove();
        }
    }

    private void removeAll(List<? extends Entity> entities) {
        new ArrayList<>(entities).forEach(this::remove);
        entities.clear();
    }

    /** Catch anything tagged for this table that the maps lost track of. */
    private void purgeTagged() {
        World world = layout.getWorld();
        if (world == null || !world.isChunkLoaded(layout.getX() >> 4, layout.getZ() >> 4)) {
            return;
        }
        for (Entity e : world.getNearbyEntities(layout.getCenter(), 5, 4, 5,
                e -> e.getScoreboardTags().contains(tableTag) || e.getScoreboardTags().contains(legacyTableTag))) {
            e.remove();
        }
    }
}
