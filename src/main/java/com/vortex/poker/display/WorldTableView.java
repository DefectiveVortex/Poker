package com.vortex.poker.display;

import com.vortex.poker.PokerPlugin;
import com.vortex.poker.config.ConfigManager;
import com.vortex.poker.model.Card;
import com.vortex.poker.table.CardDisplayCleaner;
import com.vortex.poker.table.CardModels;
import com.vortex.poker.table.TableLayout;
import com.vortex.poker.util.ServerCompat;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
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
import org.bukkit.scoreboard.ScoreboardManager;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The table as players see it: cards as ItemDisplays textured by the Playing Cards pack,
 * TextDisplays for the pot, the seats and whose turn it is, and a small chip stack.
 *
 * A hole card is two displays in the same spot: the face, shown only to its owner, and the back,
 * hidden from its owner. Everyone else sees the back. Hole cards lie flat on the felt edge in
 * front of their chair; at showdown they flip over (squash edge-on, open out as the face). Bukkit
 * forgets per-player hiding when a player relogs or changes world, so {@link #refreshVisibility}
 * puts it back.
 *
 * The board is dealt once per side of the table that has someone sitting at it, each copy a row
 * square to that side, so nobody reads it end-on or upside down. A seated player is sent only
 * their side's copy; everyone else sees the spectators' copy (read from the v > 0 side).
 *
 * Over each seat hangs a small label stack above the player's own name tag: their stack, and their
 * last action for a few seconds. The name is only added when no name tag shows (they aren't
 * sitting in the chair, or a team hides it), so it never appears twice.
 *
 * Cards slide in from the middle of the table using display interpolation (client-side, so it
 * costs the server two metadata packets per card). Every entity is non-persistent and tagged, so
 * nothing survives a crash or an unloaded chunk and CardDisplayCleaner recognises leftovers.
 */
public class WorldTableView implements TableView {
    /**
     * Label heights above the bottom of the table block, over the seated player's head. A player's
     * name tag spans about 1.97-2.2 there (eyes at 1.52, the tag's top 0.68 above them); each label
     * below grows upwards from its height.
     */
    private static final double NAME_HEIGHT = 1.98;
    private static final double STACK_HEIGHT = 2.28;
    private static final double ACTION_HEIGHT = 2.55;
    private static final double TURN_MARKER_HEIGHT = 2.8;
    private static final float NAME_SCALE = 0.9f;
    private static final float STACK_SCALE = 0.8f;
    private static final float ACTION_SCALE = 0.7f;
    /** A new action stays up this long. */
    private static final int ACTION_SHOW_TICKS = 80;
    /**
     * The pot label over the middle, 1.28 up to about 1.47 at scale 0.7: under every seated eye line
     * (1.52), so it never covers a far player's name tag or labels, and over the sight lines to the
     * board and the opposite hole cards (see TableLayoutTest).
     */
    public static final double POT_INFO_HEIGHT = 1.28;
    public static final float POT_INFO_SCALE = 0.7f;
    /** Top of the felt. */
    private static final double FELT_TOP = 1.0;
    /**
     * Dealer button: a yellow-rimmed white disc (each layer two squares, one turned 45 degrees, so
     * it reads as round) with a bold black letter lying on it.
     */
    private static final double BUTTON_RIM = TableLayout.BUTTON_HALF * Math.sqrt(2);
    private static final double BUTTON_FACE = BUTTON_RIM * 0.8;
    private static final double BUTTON_RIM_THICKNESS = 0.02;
    private static final double BUTTON_FACE_THICKNESS = 0.03;
    private static final float BUTTON_LETTER_SCALE = 0.75f;
    /** Height of the letter's middle above a text display's origin, in font pixels (line box 10, cap 7). */
    private static final double LETTER_MIDDLE_PX = 5.5;
    /** Where dealt cards slide in from: above the middle of the table. */
    private static final double DECK_HEIGHT = 1.35;
    private static final int SLIDE_TICKS = 6;
    private static final int FLIP_TICKS = 3;
    /** Chip stack beside the board: up to three stacks, chips 0.16 wide and 0.035 thick. */
    private static final double CHIP_THICKNESS = 0.035;
    private static final double CHIP_PITCH = 0.04;
    private static final int CHIP_SWEEP_TICKS = 8;
    private static final Material[] CHIP_COLOURS = {Material.RED_CONCRETE, Material.BLACK_CONCRETE, Material.WHITE_CONCRETE};
    private static final int MAX_CHIPS_PER_STACK = 6;
    /** Height of the top of a full chip stack above the table block. */
    public static final double CHIP_STACK_TOP = 1.0 + (MAX_CHIPS_PER_STACK - 1) * CHIP_PITCH + CHIP_THICKNESS;

    /** Per-kind tags, next to CardDisplayCleaner.DISPLAY_TAG, so tests can count by kind. */
    public static final String CARD_TAG = "poker-card";
    public static final String BACK_TAG = "poker-card-back";
    public static final String BOARD_TAG = "poker-board";
    /** Also on the spectators' board copy, the one anybody not seated sees. */
    public static final String BOARD_PUBLIC_TAG = "poker-board-public";
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
    /** Board copies by the side their readers sit on (see sideKey). */
    private final Map<Integer, List<ItemDisplay>> boardCopies = new HashMap<>();
    /** Who sits where, as far as the game has told us (hole cards, seat labels). */
    private final Map<Integer, UUID> seatPlayers = new HashMap<>();
    private final Map<Integer, SeatLabel> labels = new HashMap<>();
    private final List<BlockDisplay> chips = new ArrayList<>();
    private final List<Display> buttonParts = new ArrayList<>();
    /** Displays on their way out (flipping away); removed when the animation ends or the hand clears. */
    private final List<Entity> leaving = new ArrayList<>();
    private final Set<UUID> owned = new HashSet<>();
    private TextDisplay potInfo;
    private TextDisplay turnMarker;
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

    /** The texts over one seat, bottom to top: name (only without a name tag), stack, last action. */
    private static final class SeatLabel {
        UUID player;
        String name = "", stack = "", action = "";
        TextDisplay nameText, stackText, actionText;
        /** Bumped for every new action, so an old action's timer doesn't hide a newer one. */
        int actionSerial;
        boolean winner;
    }

    /** Spectators' side: see TableLayout.SPECTATOR_READING_UV. */
    private static final int SPECTATOR_SIDE = sideKey(TableLayout.SPECTATOR_READING_UV);
    /** A seat label for a player we couldn't identify (the text-only setSeatInfo with an unknown name). */
    private static final UUID NOBODY_KNOWN = new UUID(0L, 0L);

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
        for (int i = 0; i < cards.size(); i++) {
            Location loc = holeCardLocation(seat, i, cards.size(), config().getCardHeight());
            float top = layout.getCardTopYaw(seat);
            String seatTag = SEAT_CARD_TAG_PREFIX + seat;

            ItemDisplay face = spawnCard(loc, cards.get(i), top, 0, holeScale(), true, seatTag);
            if (face == null) continue;
            face.setVisibleByDefault(false);
            sc.faces.add(face);

            ItemDisplay back = spawnCard(loc, null, top, 0, holeScale(), true, seatTag);
            if (back != null) sc.backs.add(back);
        }
        seatCards.put(seat, sc);
        if (owner != null) {
            applyVisibility(owner, sc);
            setSeatPlayer(seat, owner.getUniqueId());
        }
        refreshNames();
        playSound(holeCardLocation(seat, 0, 1, 1.0), DEAL_SOUND, 0.6f, 1.2f);
    }

    @Override
    public void setBoard(List<Card> cards) {
        List<Card> next = cards == null ? List.of() : List.copyOf(cards);
        boolean extendsCurrent = next.size() >= boardCards.size()
            && next.subList(0, boardCards.size()).equals(boardCards);
        if (!extendsCurrent) {
            removeBoard();
            boardCards.clear();
        }
        if (!config().areCardDisplaysEnabled()) {
            boardCards.clear();
            boardCards.addAll(next);
            return;
        }
        int dealtBefore = boardCards.size();
        boardCards.clear();
        boardCards.addAll(next);
        for (int side : boardSides()) {
            dealBoardCopy(side, dealtBefore);
        }
        if (next.size() > dealtBefore) {
            playSound(layout.at(0, 0, config().getCardHeight()), DEAL_SOUND, 0.6f, 1.2f);
        }
    }

    /**
     * Flip a seat's cards face up for everyone: the cards squash away edge-on, then face-up cards
     * open out in their place.
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
        removeAll(buttonParts);
        if (!validSeat(seat) || destroyed) return;
        double[] spot = layout.buttonSpotUV(seat);
        Location centre = layout.at(spot[0], spot[1], FELT_TOP);
        World world = centre.getWorld();
        if (world == null || !world.isChunkLoaded(centre.getBlockX() >> 4, centre.getBlockZ() >> 4)) return;
        for (int turn = 0; turn < 2; turn++) {
            float angle = (float) (turn * Math.PI / 4);
            buttonParts.add(spawnSlab(centre, Material.YELLOW_CONCRETE, BUTTON_RIM, BUTTON_RIM_THICKNESS, angle, BUTTON_TAG));
            buttonParts.add(spawnSlab(centre, Material.WHITE_CONCRETE, BUTTON_FACE, BUTTON_FACE_THICKNESS, angle, BUTTON_TAG));
        }
        // the letter lies on the disc, reading upright from the button seat
        float topYaw = layout.getCardTopYaw(seat);
        double r = Math.toRadians(topYaw), lift = LETTER_MIDDLE_PX * 0.025 * BUTTON_LETTER_SCALE;
        Location at = centre.clone().add(Math.sin(r) * lift, BUTTON_FACE_THICKNESS + 0.004, -Math.cos(r) * lift);
        TextDisplay letter = spawnText(at, ChatColor.BLACK + "" + ChatColor.BOLD + buttonLetter(), BUTTON_LETTER_SCALE);
        if (letter != null) {
            letter.setBillboard(Display.Billboard.FIXED);
            letter.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            letter.setTransformation(new Transformation(new Vector3f(), flatTextRotation(topYaw),
                new Vector3f(BUTTON_LETTER_SCALE, BUTTON_LETTER_SCALE, BUTTON_LETTER_SCALE), new Quaternionf()));
            letter.addScoreboardTag(BUTTON_TAG);
            buttonParts.add(letter);
        }
    }

    /** The button's letter from messages.yml ("D"), without its colours: it is always black on white. */
    private String buttonLetter() {
        String text = config().getMessage("display-button");
        text = text == null ? "" : ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', text)).trim();
        return text.isEmpty() ? "D" : text;
    }

    @Override
    public void setSeatInfo(int seat, String text) {
        if (text == null || text.isEmpty()) {
            setSeatInfo(seat, null, null, null, null);
            return;
        }
        String[] lines = text.split("\n", 3);
        String name = lines[0];
        Player player = Bukkit.getPlayerExact(ChatColor.stripColor(name).trim());
        UUID uuid = player != null ? player.getUniqueId() : seatPlayers.getOrDefault(seat, NOBODY_KNOWN);
        setSeatInfo(seat, uuid, name, lines.length > 1 ? lines[1] : "", lines.length > 2 ? lines[2] : "");
    }

    @Override
    public void setSeatInfo(int seat, UUID player, String name, String stack, String action) {
        if (!validSeat(seat)) return;
        if (player == null) {
            setSeatPlayer(seat, null);
            removeLabel(seat);
            return;
        }
        setSeatPlayer(seat, NOBODY_KNOWN.equals(player) ? null : player);
        if (!config().areTextDisplaysEnabled()) {
            removeLabel(seat);
            return;
        }
        SeatLabel label = labels.computeIfAbsent(seat, s -> new SeatLabel());
        label.player = NOBODY_KNOWN.equals(player) ? null : player;
        label.name = orEmpty(name);
        label.stack = orEmpty(stack);
        placeName(seat, label);
        label.stackText = putText(label.stackText, seat, STACK_HEIGHT, label.stack, STACK_SCALE);
        if (label.stackText != null) label.stackText.setBackgroundColor(label.winner ? WINNER_BACKGROUND : TEXT_BACKGROUND);
        String act = orEmpty(action);
        if (!act.equals(label.action)) {
            label.action = act;
            showAction(seat, label);
        }
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
        potInfo = spawnText(layout.at(0, 0, POT_INFO_HEIGHT), text, POT_INFO_SCALE);
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
            double[] uv = TableLayout.CHIP_STACKS_UV[stack];
            Location loc = layout.at(uv[0], uv[1], FELT_TOP + level * CHIP_PITCH);
            if (world == null || !world.isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) return;
            chips.add(spawnSlab(loc, CHIP_COLOURS[stack], TableLayout.CHIP_HALF * 2, CHIP_THICKNESS, 0f, CHIP_TAG));
        }
    }

    @Override
    public void highlightTurn(int seat) {
        remove(turnMarker);
        turnMarker = null;
        if (!validSeat(seat) || !config().areTextDisplaysEnabled()) return;
        refreshNames();
        turnMarker = spawnText(labelLocation(seat, TURN_MARKER_HEIGHT), config().getMessage("display-turn-marker"), 0.8f);
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
            SeatLabel label = labels.get(seat);
            if (label != null) {
                label.winner = true;
                if (label.stackText != null && label.stackText.isValid()) label.stackText.setBackgroundColor(WINNER_BACKGROUND);
            }
            Particle sparkle = ServerCompat.particle("HAPPY_VILLAGER", "VILLAGER_HAPPY");
            if (world != null && sparkle != null) {
                double[] spot = layout.cardSpotUV(seat);
                world.spawnParticle(sparkle, layout.at(spot[0], spot[1], 1.3), 12, 0.25, 0.15, 0.25, 0);
            }
        }
        Particle burst = ServerCompat.particle("TOTEM_OF_UNDYING", "TOTEM");
        double[] pot = TableLayout.CHIP_STACKS_UV[0];
        if (world != null && burst != null) {
            world.spawnParticle(burst, layout.at(pot[0], pot[1], 1.2), 30, 0.2, 0.1, 0.2, 0.25);
        }
        playSound(layout.at(0, 0, 1.2), "minecraft:entity.player.levelup", 0.5f, 1.6f);
        // the pot has been paid: its chips slide over to the winners and the pot label goes
        sweepChipsTo(seats);
        remove(potInfo);
        potInfo = null;
    }

    @Override
    public void clearHand() {
        for (Integer seat : new ArrayList<>(seatCards.keySet())) {
            clearSeat(seat);
        }
        removeBoard();
        boardCards.clear();
        removeAll(leaving);
        removeAll(chips);
        chipCount = 0;
        for (SeatLabel label : labels.values()) {
            label.winner = false;
            if (label.stackText != null && label.stackText.isValid()) label.stackText.setBackgroundColor(TEXT_BACKGROUND);
        }
        highlightTurn(-1);
    }

    @Override
    public void destroy() {
        clearHand();
        remove(potInfo);
        removeAll(buttonParts);
        for (Integer seat : new ArrayList<>(labels.keySet())) {
            removeLabel(seat);
        }
        potInfo = null;
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
        applyBoardVisibility(viewer);
    }

    // ---------------------------------------------------------------------------------------
    // Board copies
    // ---------------------------------------------------------------------------------------

    /** 0..3 for a reading direction along +u, -u, +v, -v. */
    static int sideKey(double[] reading) {
        return reading[0] != 0 ? (reading[0] > 0 ? 0 : 1) : (reading[1] > 0 ? 2 : 3);
    }

    private static double[] readingOf(int side) {
        return switch (side) {
            case 0 -> new double[] {1, 0};
            case 1 -> new double[] {-1, 0};
            case 2 -> new double[] {0, 1};
            default -> new double[] {0, -1};
        };
    }

    /** The spectators' side, and every side somebody sits on. */
    private Set<Integer> boardSides() {
        Set<Integer> sides = new LinkedHashSet<>();
        sides.add(SPECTATOR_SIDE);
        for (int seat : seatPlayers.keySet()) {
            if (validSeat(seat)) sides.add(sideKey(layout.readingUV(seat)));
        }
        return sides;
    }

    private int sideOf(UUID player) {
        for (Map.Entry<Integer, UUID> e : seatPlayers.entrySet()) {
            if (e.getValue().equals(player) && validSeat(e.getKey())) return sideKey(layout.readingUV(e.getKey()));
        }
        return SPECTATOR_SIDE;
    }

    /**
     * Bring one side's copy up to the current board. Cards from {@code slideFrom} on are new this
     * street and glide in; a copy made late (someone sat down mid-hand) just appears.
     */
    private void dealBoardCopy(int side, int slideFrom) {
        if (boardCards.isEmpty() || destroyed) return;
        List<ItemDisplay> copy = boardCopies.computeIfAbsent(side, k -> new ArrayList<>());
        double spacing = config().getCardSpacing() * TableLayout.BOARD_SPACING_FACTOR;
        double[] reading = readingOf(side);
        boolean spawned = false;
        for (int i = copy.size(); i < boardCards.size(); i++) {
            double[] uv = layout.boardSpotUV(i, spacing, reading);
            Location loc = layout.at(uv[0], uv[1], config().getCardHeight());
            ItemDisplay d = spawnCard(loc, boardCards.get(i), layout.getBoardTopYaw(reading), 0, boardScale(),
                i >= slideFrom, BOARD_TAG);
            if (d == null) return; // chunk unloaded: try again on the next street
            if (side == SPECTATOR_SIDE) {
                d.addScoreboardTag(BOARD_PUBLIC_TAG);
            } else {
                d.setVisibleByDefault(false);
            }
            copy.add(d);
            spawned = true;
        }
        if (!spawned) return;
        for (UUID uuid : seatPlayers.values()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) applyBoardVisibility(p);
        }
    }

    /** A seated player sees their side's copy and no other; anybody else, the spectators' copy. */
    private void applyBoardVisibility(Player viewer) {
        int mine = sideOf(viewer.getUniqueId());
        for (Map.Entry<Integer, List<ItemDisplay>> e : boardCopies.entrySet()) {
            boolean see = e.getKey() == mine;
            for (ItemDisplay d : e.getValue()) {
                if (!d.isValid()) continue;
                if (see) {
                    viewer.showEntity(plugin, d);
                } else {
                    viewer.hideEntity(plugin, d);
                }
            }
        }
    }

    private void removeBoard() {
        for (List<ItemDisplay> copy : boardCopies.values()) {
            removeAll(copy);
        }
        boardCopies.clear();
    }

    /** Record who sits on a seat (null: nobody), and move them and whoever was there onto the right board copy. */
    private void setSeatPlayer(int seat, UUID player) {
        UUID before = player == null ? seatPlayers.remove(seat) : seatPlayers.put(seat, player);
        if (player == null ? before == null : player.equals(before)) return;
        if (player != null) {
            // one seat per player: a stale entry from an earlier seat would pick the wrong copy
            seatPlayers.entrySet().removeIf(e -> e.getKey() != seat && e.getValue().equals(player));
            dealBoardCopy(sideKey(layout.readingUV(seat)), boardCards.size());
        }
        for (UUID uuid : new UUID[] {before, player}) {
            Player p = uuid == null ? null : Bukkit.getPlayer(uuid);
            if (p != null) applyBoardVisibility(p);
        }
    }

    // ---------------------------------------------------------------------------------------
    // Seat labels
    // ---------------------------------------------------------------------------------------

    /** Over the seated player's head (their eyes' u, v), at a height above the table block. */
    private Location labelLocation(int seat, double height) {
        double[] eye = layout.eyeUV(seat);
        return layout.at(eye[0], eye[1], height);
    }

    /** The name, only while the player's own name tag doesn't show it. */
    private void placeName(int seat, SeatLabel label) {
        String name = nameTagShows(seat, label) ? "" : label.name;
        label.nameText = putText(label.nameText, seat, NAME_HEIGHT, name, NAME_SCALE);
    }

    /**
     * Whether other players see this seat's player's vanilla name tag right over the seat: they are
     * online, sitting in this chair, and no scoreboard team hides it.
     */
    private boolean nameTagShows(int seat, SeatLabel label) {
        Player p = label.player == null ? null : Bukkit.getPlayer(label.player);
        if (p == null || p.getWorld() != layout.getWorld() || !p.isInsideVehicle()) return false;
        Location eye = labelLocation(seat, 0);
        double dx = p.getLocation().getX() - eye.getX(), dz = p.getLocation().getZ() - eye.getZ();
        if (dx * dx + dz * dz > 1.0) return false;
        ScoreboardManager boards = Bukkit.getScoreboardManager();
        Team team = boards == null ? null : boards.getMainScoreboard().getEntryTeam(p.getName());
        return team == null || team.getOption(Team.Option.NAME_TAG_VISIBILITY) == Team.OptionStatus.ALWAYS;
    }

    /** Players sit down and stand up between label updates: re-check whose name tag shows. */
    private void refreshNames() {
        for (Map.Entry<Integer, SeatLabel> e : labels.entrySet()) {
            placeName(e.getKey(), e.getValue());
        }
    }

    /** Show a new action for a few seconds. */
    private void showAction(int seat, SeatLabel label) {
        int serial = ++label.actionSerial;
        label.actionText = putText(label.actionText, seat, ACTION_HEIGHT, label.action, ACTION_SCALE);
        if (label.actionText == null) return;
        later(ACTION_SHOW_TICKS, () -> {
            if (labels.get(seat) != label || label.actionSerial != serial) return;
            remove(label.actionText);
            label.actionText = null;
        });
    }

    /** Update one text of a seat label in place, spawn it, or (empty text) remove it. */
    private TextDisplay putText(TextDisplay current, int seat, double height, String text, float scale) {
        if (text == null || text.isEmpty()) {
            remove(current);
            return null;
        }
        if (current != null && current.isValid()) {
            current.setText(text);
            return current;
        }
        return spawnText(labelLocation(seat, height), text, scale);
    }

    private void removeLabel(int seat) {
        SeatLabel label = labels.remove(seat);
        if (label == null) return;
        label.actionSerial++;
        remove(label.nameText);
        remove(label.stackText);
        remove(label.actionText);
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
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

    /**
     * A flat text lying on the table, reading upright for someone it points away from: its top
     * edge along {@code topYaw}. A text display's front faces +Z with its top along +Y; lay it face
     * up (+Z to +Y, +Y to -Z), then turn it about the vertical so -Z becomes (-sin r, 0, cos r).
     */
    static Quaternionf flatTextRotation(float topYaw) {
        double r = Math.toRadians(topYaw);
        return new Quaternionf().rotationY((float) (Math.PI - r)).rotateX((float) (-Math.PI / 2));
    }

    /**
     * A square slab of a block, {@code size} wide and {@code thickness} tall, centred on
     * {@code centre} and turned {@code angle} radians about the vertical.
     */
    private BlockDisplay spawnSlab(Location centre, Material block, double size, double thickness, float angle, String kind) {
        Location loc = centre.clone();
        loc.setYaw(0f);
        loc.setPitch(0f);
        BlockDisplay slab = loc.getWorld().spawn(loc, BlockDisplay.class);
        slab.setBlock(block.createBlockData());
        Quaternionf turn = new Quaternionf().rotationY(angle);
        Vector3f corner = turn.transform(new Vector3f((float) size / 2, 0f, (float) size / 2)).negate();
        slab.setTransformation(new Transformation(corner, turn,
            new Vector3f((float) size, (float) thickness, (float) size), new Quaternionf()));
        tag(slab, kind);
        return slab;
    }

    /** The pot goes to the winners: each chip stack slides onto a winner's cards, then vanishes. */
    private void sweepChipsTo(List<Integer> seats) {
        List<BlockDisplay> going = new ArrayList<>(chips);
        chips.clear();
        chipCount = 0;
        List<Integer> winners = seats.stream().filter(this::validSeat).toList();
        if (winners.isEmpty()) {
            going.forEach(this::remove);
            return;
        }
        for (int i = 0; i < going.size(); i++) {
            BlockDisplay chip = going.get(i);
            if (!chip.isValid()) continue;
            double[] spot = layout.cardSpotUV(winners.get((i / MAX_CHIPS_PER_STACK) % winners.size()));
            Location from = chip.getLocation();
            Location to = layout.at(spot[0], spot[1], from.getY() - layout.getY() + 0.05);
            Transformation t = chip.getTransformation();
            Vector3f moved = new Vector3f(t.getTranslation()).add(
                (float) (to.getX() - from.getX()), (float) (to.getY() - from.getY()), (float) (to.getZ() - from.getZ()));
            leaving.add(chip);
            later(1, () -> {
                if (!chip.isValid()) return;
                chip.setInterpolationDelay(0);
                chip.setInterpolationDuration(CHIP_SWEEP_TICKS);
                chip.setTransformation(new Transformation(moved, t.getLeftRotation(), t.getScale(), t.getRightRotation()));
            });
        }
        later(CHIP_SWEEP_TICKS + 4, () -> {
            for (BlockDisplay chip : going) {
                leaving.remove(chip);
                remove(chip);
            }
        });
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
