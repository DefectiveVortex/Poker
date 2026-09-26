package com.vortex.poker.display;

import com.vortex.poker.PokerPlugin;
import com.vortex.poker.config.ConfigManager;
import com.vortex.poker.model.Card;
import com.vortex.poker.table.CardDisplayCleaner;
import com.vortex.poker.table.CardModels;
import com.vortex.poker.table.TableLayout;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The table as players see it: cards as ItemDisplays textured by the Playing Cards pack, and
 * TextDisplays for the pot, the seats and whose turn it is.
 *
 * A hole card is two displays in the same spot: the face, shown only to its owner, and the back,
 * hidden from its owner. Everyone else sees the back. Bukkit forgets per-player hiding when a
 * player relogs or changes world, so {@link #refreshVisibility} puts it back.
 *
 * Every display is non-persistent and tagged, so nothing survives a crash or an unloaded chunk,
 * and CardDisplayCleaner can recognise any leftovers.
 */
public class WorldTableView implements TableView {
    /** Text heights above the bottom of the table block. */
    private static final double SEAT_INFO_HEIGHT = 2.45;
    private static final double TURN_MARKER_HEIGHT = 2.9;
    private static final double POT_INFO_HEIGHT = 1.55;
    private static final double BUTTON_HEIGHT = 1.12;
    private static final String DEAL_SOUND = "minecraft:block.wooden_button.click_on";

    private final PokerPlugin plugin;
    private final TableLayout layout;
    private final String tableTag;

    private final Map<Integer, SeatCards> seatCards = new HashMap<>();
    private final List<Card> boardCards = new ArrayList<>();
    private final List<ItemDisplay> boardDisplays = new ArrayList<>();
    private final Map<Integer, TextDisplay> seatInfo = new HashMap<>();
    private final Set<UUID> owned = new HashSet<>();
    private TextDisplay potInfo;
    private TextDisplay turnMarker;
    private TextDisplay button;
    private boolean destroyed;

    /** A seat's hole cards: faces for the owner, backs for everyone else, or faces for all once revealed. */
    private static final class SeatCards {
        UUID owner;
        final List<ItemDisplay> faces = new ArrayList<>();
        final List<ItemDisplay> backs = new ArrayList<>();
        boolean revealed;
    }

    public WorldTableView(PokerPlugin plugin, TableLayout layout, int tableId) {
        this.plugin = plugin;
        this.layout = layout;
        this.tableTag = tableTag(tableId);
    }

    /** Scoreboard tag carried by every entity a table spawns. */
    public static String tableTag(int tableId) {
        return "poker-table:" + tableId;
    }

    private ConfigManager config() {
        return plugin.getConfigManager();
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
            Location loc = holeCardLocation(seat, i, cards.size());
            float top = layout.getCardTopYaw(seat);

            ItemDisplay face = spawnCard(loc, cards.get(i), top);
            if (face == null) continue;
            face.setVisibleByDefault(false);
            sc.faces.add(face);

            ItemDisplay back = spawnCard(loc, null, top);
            if (back != null) sc.backs.add(back);
        }
        seatCards.put(seat, sc);
        if (owner != null) {
            applyVisibility(owner, sc);
        }
        playDealSound(holeCardLocation(seat, 0, 1));
    }

    @Override
    public void setBoard(List<Card> cards) {
        List<Card> next = cards == null ? List.of() : List.copyOf(cards);
        boolean extends_ = next.size() >= boardCards.size() && next.subList(0, boardCards.size()).equals(boardCards);
        if (!extends_) {
            removeAll(boardDisplays);
            boardCards.clear();
        }
        if (!config().areCardDisplaysEnabled()) {
            boardCards.clear();
            boardCards.addAll(next);
            return;
        }
        for (int i = boardCards.size(); i < next.size(); i++) {
            ItemDisplay d = spawnCard(boardCardLocation(i), next.get(i), layout.getBoardTopYaw());
            if (d != null) boardDisplays.add(d);
        }
        if (next.size() > boardCards.size()) {
            playDealSound(layout.at(0, 0, config().getCardHeight()));
        }
        boardCards.clear();
        boardCards.addAll(next);
    }

    @Override
    public void revealHoleCards(int seat, List<Card> cards) {
        if (!validSeat(seat)) return;
        clearSeat(seat);
        if (!config().areCardDisplaysEnabled() || cards == null || cards.isEmpty()) return;

        SeatCards sc = new SeatCards();
        sc.revealed = true;
        for (int i = 0; i < cards.size(); i++) {
            ItemDisplay face = spawnCard(holeCardLocation(seat, i, cards.size()), cards.get(i), layout.getCardTopYaw(seat));
            if (face != null) sc.faces.add(face);
        }
        seatCards.put(seat, sc);
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
        double[] spot = layout.cardSpotUV(seat);
        double[] side = layout.edgeUV(seat);
        // beside the hole cards, on the sitter's right
        double offset = -config().getCardSpacing() * 1.9;
        Location loc = layout.at(spot[0] + side[0] * offset, spot[1] + side[1] * offset, BUTTON_HEIGHT);
        button = spawnText(loc, config().getMessage("display-button"), 0.45f);
        if (button != null) {
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
        TextDisplay d = spawnText(chair.add(0, SEAT_INFO_HEIGHT, 0), text, 0.8f);
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
        potInfo = spawnText(layout.at(0, 0, POT_INFO_HEIGHT), text, 0.9f);
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

    @Override
    public void clearHand() {
        for (Integer seat : new ArrayList<>(seatCards.keySet())) {
            clearSeat(seat);
        }
        removeAll(boardDisplays);
        boardCards.clear();
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

    private Location holeCardLocation(int seat, int index, int count) {
        double[] spot = layout.cardSpotUV(seat);
        double[] side = layout.edgeUV(seat);
        double spacing = config().getCardSpacing();
        double offset = (index - (count - 1) / 2.0) * spacing;
        // index 0 on the sitter's left, so the hand reads left to right
        return layout.at(spot[0] - side[0] * offset, spot[1] - side[1] * offset, config().getCardHeight());
    }

    private Location boardCardLocation(int index) {
        double spacing = config().getCardSpacing() * 1.25;
        return layout.at((index - 2) * spacing, 0, config().getCardHeight());
    }

    // ---------------------------------------------------------------------------------------
    // Entities
    // ---------------------------------------------------------------------------------------

    /**
     * A card lying flat on the felt. {@code topYaw} is the direction (as a yaw) its top edge points;
     * pointing it away from a player lets them read it the right way up.
     */
    private ItemDisplay spawnCard(Location loc, Card card, float topYaw) {
        World world = loc.getWorld();
        if (world == null || destroyed || !world.isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) {
            return null;
        }
        loc.setYaw(0f);
        loc.setPitch(0f);
        ItemDisplay display = world.spawn(loc, ItemDisplay.class);
        display.setItemStack(CardModels.item(card));
        float scale = (float) config().getCardScale();
        // The right rotation spins the card in its own plane; the left one lays it flat. Lying flat,
        // the top edge then points along (-sin r, 0, cos r), which is exactly the yaw convention.
        display.setTransformation(new Transformation(
            new Vector3f(),
            new AxisAngle4f((float) (Math.PI / 2), 1f, 0f, 0f),
            new Vector3f(scale, scale, scale),
            new AxisAngle4f((float) Math.toRadians(topYaw), 0f, 0f, 1f)));
        tag(display);
        return display;
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
        display.setBackgroundColor(Color.fromARGB(110, 0, 0, 0));
        display.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(),
            new Vector3f(scale, scale, scale), new AxisAngle4f()));
        tag(display);
        return display;
    }

    private void tag(Entity entity) {
        entity.setPersistent(false);
        entity.addScoreboardTag(CardDisplayCleaner.DISPLAY_TAG);
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

    private void playDealSound(Location loc) {
        if (loc.getWorld() != null) {
            loc.getWorld().playSound(loc, DEAL_SOUND, 0.6f, 1.2f);
        }
    }

    private void remove(Entity entity) {
        if (entity != null) {
            owned.remove(entity.getUniqueId());
            if (entity.isValid()) entity.remove();
        }
    }

    private void removeAll(List<? extends Entity> entities) {
        entities.forEach(this::remove);
        entities.clear();
    }

    /** Catch anything tagged for this table that the maps lost track of (e.g. after a reload). */
    private void purgeTagged() {
        World world = layout.getWorld();
        if (world == null || Bukkit.isStopping() && !world.isChunkLoaded(layout.getX() >> 4, layout.getZ() >> 4)) {
            return;
        }
        for (Entity e : world.getNearbyEntities(layout.getCenter(), 6, 4, 6,
                e -> e.getScoreboardTags().contains(tableTag))) {
            e.remove();
        }
    }
}
