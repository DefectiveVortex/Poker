package com.vortex.poker.config;

import com.vortex.poker.model.Card;
import com.vortex.poker.model.HandRank;
import com.vortex.poker.model.HandValue;
import com.vortex.poker.model.Rank;
import com.vortex.poker.model.Suit;
import com.vortex.poker.util.ServerCompat;
import org.bukkit.ChatColor;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Centralized configuration: config.yml plus the messages file for the configured language.
 * Constructing it creates and tops up both files from the bundled defaults.
 */
public class ConfigManager {
    public static final int MIN_SEATS = 2;
    public static final int MAX_SEATS = 8;

    private final JavaPlugin plugin;
    private FileConfiguration config;
    private FileConfiguration messagesConfig;

    // Cached values
    private Material tableMaterial;
    private Material chairMaterial;
    private int maxSeats;
    private long smallBlind;
    private long bigBlind;
    private int minBuyInBB;
    private int maxBuyInBB;
    private Sound cardDealSound;
    private Sound turnSound;
    private Sound winSound;
    private Sound foldSound;
    private Sound chipsSound;
    private Sound fanfareSound;
    private Particle winParticle;

    public ConfigManager(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    /** Re-read config.yml and the messages file (picks up a changed language too). */
    public void reload() {
        plugin.saveDefaultConfig();
        ConfigFileUpdater.update(plugin, "config.yml", new File(plugin.getDataFolder(), "config.yml"));
        plugin.reloadConfig();
        this.config = plugin.getConfig();
        this.messagesConfig = loadMessages();
        loadAndValidateConfig();
    }

    /**
     * English lives in messages.yml, other languages in messages_<code>.yml. Bundled translations are
     * copied out on first use and topped up with new keys on update; a server can add its own language by
     * dropping in a messages_<code>.yml. Anything a translation lacks falls back to the bundled English.
     */
    private FileConfiguration loadMessages() {
        String language = getLanguage();
        String fileName = language.isEmpty() || language.equals("en") ? "messages.yml" : "messages_" + language + ".yml";
        File messagesFile = new File(plugin.getDataFolder(), fileName);

        YamlConfiguration messages;
        if (plugin.getResource(fileName) != null) {
            messages = ConfigFileUpdater.update(plugin, fileName, messagesFile);
        } else if (messagesFile.exists()) {
            messages = YamlConfiguration.loadConfiguration(messagesFile);
        } else {
            plugin.getLogger().warning("No messages file for language '" + language + "' (expected " + fileName
                + "). Bundled languages: en, ko, tr, ru. Falling back to English.");
            messages = ConfigFileUpdater.update(plugin, "messages.yml", new File(plugin.getDataFolder(), "messages.yml"));
        }

        try (InputStream stream = plugin.getResource("messages.yml")) {
            if (stream != null) {
                messages.setDefaults(YamlConfiguration.loadConfiguration(
                    new InputStreamReader(stream, StandardCharsets.UTF_8)));
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Could not read bundled English messages: " + e.getMessage());
        }
        return messages;
    }

    private void loadAndValidateConfig() {
        tableMaterial = material(config.getString("table.material"), Material.GREEN_TERRACOTTA);
        chairMaterial = material(config.getString("table.chair-material"), Material.DARK_OAK_STAIRS);
        maxSeats = Math.max(MIN_SEATS, Math.min(MAX_SEATS, config.getInt("table.max-seats", 6)));

        bigBlind = Math.max(2, config.getLong("table.big-blind", 20));
        smallBlind = Math.max(1, Math.min(bigBlind, config.getLong("table.small-blind", bigBlind / 2)));
        minBuyInBB = Math.max(1, config.getInt("table.min-buy-in-bb", 40));
        maxBuyInBB = Math.max(minBuyInBB, config.getInt("table.max-buy-in-bb", 100));

        cardDealSound = resolveSound(config.getString("sounds.card-deal.sound"), Sound.BLOCK_WOODEN_BUTTON_CLICK_ON);
        turnSound = resolveSound(config.getString("sounds.your-turn.sound"), Sound.BLOCK_NOTE_BLOCK_PLING);
        winSound = resolveSound(config.getString("sounds.win.sound"), Sound.ENTITY_PLAYER_LEVELUP);
        foldSound = resolveSound(config.getString("sounds.fold.sound"), Sound.ITEM_BOOK_PAGE_TURN);
        chipsSound = resolveSound(config.getString("sounds.chips.sound"), Sound.BLOCK_CHAIN_PLACE);
        fanfareSound = resolveSound(config.getString("sounds.win-fanfare.sound"), Sound.UI_TOAST_CHALLENGE_COMPLETE);
        winParticle = ServerCompat.particle(config.getString("particles.win.type"), "HAPPY_VILLAGER", "VILLAGER_HAPPY");
    }

    private static Material material(String name, Material fallback) {
        if (name == null) return fallback;
        Material m = Material.matchMaterial(name.trim());
        return m != null && m.isBlock() ? m : fallback;
    }

    public FileConfiguration getConfig() { return config; }

    /** Language code from config.yml, e.g. "en", "ko", "tr", "ru". */
    public String getLanguage() {
        return config.getString("language", "en").trim().toLowerCase(Locale.ROOT);
    }

    // ---- Table (defaults for new tables; each table keeps its own TableSettings) ----
    public Material getTableMaterial() { return tableMaterial; }
    public Material getChairMaterial() { return chairMaterial; }
    public boolean shouldSeatPlayers() { return config.getBoolean("table.seat-players", true); }
    public boolean isClickToJoin() { return config.getBoolean("table.click-to-join", true); }
    public double getMaxJoinDistance() { return Math.max(1.0, config.getDouble("table.max-join-distance", 10.0)); }
    public int getMaxSeats() { return maxSeats; }
    public long getDefaultSmallBlind() { return smallBlind; }
    public long getDefaultBigBlind() { return bigBlind; }
    public int getDefaultMinBuyInBB() { return minBuyInBB; }
    public int getDefaultMaxBuyInBB() { return maxBuyInBB; }

    // ---- Game timing ----
    /** Seconds a player has to act; 0 turns the timer off. */
    public int getTurnTimeoutSeconds() {
        int seconds = config.getInt("game.turn-timeout-seconds", 30);
        return seconds <= 0 ? 0 : Math.max(5, seconds);
    }

    /** Seconds between the end of one hand and the deal of the next. */
    public int getNextHandDelaySeconds() { return Math.max(1, config.getInt("game.next-hand-delay-seconds", 5)); }

    /** Seconds the showdown (revealed cards, winners) stays on the table before it's cleared. */
    public int getShowdownDisplaySeconds() { return Math.max(1, config.getInt("game.showdown-display-seconds", 4)); }

    /** Seconds each seated player has to confirm "play again" after a hand; 0 turns the ready check off. */
    public int getReadyTimeoutSeconds() {
        int seconds = config.getInt("game.ready-timeout-seconds", 30);
        return seconds <= 0 ? 0 : Math.max(5, seconds);
    }

    /** Players who time out this many hands in a row are stood up and cashed out; 0 = never. */
    public int getMaxMissedTurns() { return Math.max(0, config.getInt("game.max-missed-turns", 2)); }

    // ---- Player interface ----
    /** True for the chest menus, false for clickable chat buttons only. */
    public boolean useMenus() { return !"chat".equalsIgnoreCase(config.getString("interface.menu", "gui")); }
    public boolean sendChatButtons() { return config.getBoolean("interface.chat-buttons", true); }

    // ---- Displays ----
    public boolean areCardDisplaysEnabled() { return config.getBoolean("display.card.enabled", true); }
    public float getCardScale() { return (float) config.getDouble("display.card.scale", 0.35); }
    public double getCardSpacing() { return config.getDouble("display.card.spacing", 0.3); }
    public double getCardHeight() { return config.getDouble("display.card.height", 1.03); }
    public boolean areTextDisplaysEnabled() { return config.getBoolean("display.text.enabled", true); }

    // ---- Resource pack ----
    public boolean shouldSendResourcePack() { return config.getBoolean("resource-pack.send-on-join", true); }
    public String getResourcePackUrl() { return config.getString("resource-pack.url", ""); }
    public String getResourcePackSha1() { return config.getString("resource-pack.sha1", ""); }
    public boolean isResourcePackRequired() { return config.getBoolean("resource-pack.required", false); }

    // ---- Sounds and particles ----
    public boolean areSoundsEnabled() { return config.getBoolean("sounds.enabled", true); }
    public boolean areParticlesEnabled() { return config.getBoolean("particles.enabled", true); }
    public Sound getCardDealSound() { return cardDealSound; }
    public Sound getTurnSound() { return turnSound; }
    public Sound getWinSound() { return winSound; }
    public Sound getFoldSound() { return foldSound; }
    public Sound getChipsSound() { return chipsSound; }
    public Sound getFanfareSound() { return fanfareSound; }
    public int getWinParticleCount() { return Math.max(0, config.getInt("particles.win.count", 20)); }
    /** Show the winner a big "You win" title. */
    public boolean showWinTitle() { return config.getBoolean("interface.win-title", true); }
    /** Send first-time players a short how-to when they first sit down. */
    public boolean showFirstTimeGuide() { return config.getBoolean("interface.first-time-guide", true); }
    /** Null when no candidate name exists on this server version. */
    public Particle getWinParticle() { return winParticle; }
    public float getSoundVolume(String sound) { return (float) config.getDouble("sounds." + sound + ".volume", 1.0); }
    public float getSoundPitch(String sound) { return (float) config.getDouble("sounds." + sound + ".pitch", 1.0); }

    // ---- Stats ----
    /** Seconds between background saves of stats.yml. */
    public int getStatsSaveInterval() { return Math.max(5, config.getInt("performance.stats-save-interval", 60)); }

    /**
     * Resolve a sound from config. Accepts enum-style names (BLOCK_NOTE_BLOCK_PLING) and namespaced keys
     * (minecraft:block.note_block.pling). Sound became an interface in 1.21.3, so Sound.valueOf can't be
     * called safely on every version; walking the registry works on all of them.
     */
    private Sound resolveSound(String soundName, Sound fallback) {
        if (soundName == null || soundName.isBlank()) {
            return fallback;
        }
        String wanted = soundName.trim();
        if (wanted.startsWith("minecraft:")) {
            wanted = wanted.substring("minecraft:".length());
        }
        wanted = wanted.replace('.', '_').toUpperCase(Locale.ROOT);
        try {
            for (Sound sound : Registry.SOUNDS) {
                String key = ((Keyed) sound).getKey().getKey();
                if (key.replace('.', '_').toUpperCase(Locale.ROOT).equals(wanted)) {
                    return sound;
                }
            }
        } catch (RuntimeException | LinkageError e) {
            return fallback;
        }
        return fallback;
    }

    // ---- Messages ----

    /** A message with colour codes applied. The prefix is not added; use {@link #getPrefixed}. */
    public String getMessage(String path) {
        // The messages file carries the bundled English file as its defaults, so a translation
        // that lacks a key still shows the English text instead of an error.
        String message = messagesConfig.getString(path);
        if (message == null) {
            message = "&cMissing message: " + path;
        }
        return color(message);
    }

    /** A message with the plugin prefix in front. */
    public String getPrefixed(String path) {
        return getMessage("prefix") + getMessage(path);
    }

    /**
     * A message with placeholders filled in: formatMessage("key", "amount", 50, "player", name).
     * Both %amount% and {amount} are replaced.
     */
    public String formatMessage(String path, Object... args) {
        return fill(getMessage(path), args);
    }

    /** {@link #formatMessage} with the plugin prefix in front. */
    public String formatPrefixed(String path, Object... args) {
        return getMessage("prefix") + formatMessage(path, args);
    }

    /** Whether the messages file (or its English defaults) has this key. */
    public boolean hasMessage(String path) {
        return messagesConfig.getString(path) != null;
    }

    public List<String> getMessageList(String path) {
        return messagesConfig.getStringList(path).stream().map(ConfigManager::color).toList();
    }

    /** Wrap an amount in the language's currency format ("$%amount%" in English). */
    public String formatCurrency(Object amount) {
        return formatMessage("currency-format", "amount", amount);
    }

    public static String fill(String message, Object... args) {
        for (int i = 0; i + 1 < args.length; i += 2) {
            String value = String.valueOf(args[i + 1]);
            message = message.replace("%" + args[i] + "%", value).replace("{" + args[i] + "}", value);
        }
        return message;
    }

    public static String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    // ---- Cards and hands as text ----

    /** Colour code for a suit, from config.yml cards.colors (hearts/diamonds red, spades/clubs dark gray by default). */
    public String getSuitColor(Suit suit) {
        String key = suit.name().toLowerCase(Locale.ROOT);
        String fallback = suit == Suit.HEARTS || suit == Suit.DIAMONDS ? "&c" : "&8";
        return color(config.getString("cards.colors." + key, fallback));
    }

    /** "A♥" in its suit colour, followed by a reset to white so surrounding text isn't tinted. */
    public String formatCard(Card card) {
        return getSuitColor(card.getSuit()) + card.display() + color(config.getString("cards.after", "&f"));
    }

    /** Cards separated by spaces, each in its suit colour. The plain text is unchanged ("A♥ 10♠"). */
    public String formatCards(List<Card> cards) {
        return cards.stream().map(this::formatCard).collect(Collectors.joining(" "));
    }

    /**
     * A hand with what decides it: "Two Pair, Kings and Sevens" in English (the model's own wording), and
     * "%hand% (K/7)"-style templates from messages (hand-detail, hand-detail-two) in other languages.
     */
    public String describeHand(HandValue value) {
        if (getLanguage().equals("en") || getLanguage().isEmpty()) {
            return value.describe();
        }
        String hand = getMessage("hand." + value.getRank().name());
        List<Rank> ranks = value.getOrderedRanks();
        if (value.getRank() == HandRank.ROYAL_FLUSH || ranks.isEmpty()) {
            return hand;
        }
        boolean two = (value.getRank() == HandRank.TWO_PAIR || value.getRank() == HandRank.FULL_HOUSE) && ranks.size() > 1;
        return formatMessage(two ? "hand-detail-two" : "hand-detail", "hand", hand,
            "r1", ranks.get(0).label(), "r2", two ? ranks.get(1).label() : "");
    }

    /** Before the flop: "Pair of Kings" / "Ace high" for two hole cards (the evaluator needs five). */
    public String describeHole(List<Card> hole) {
        if (hole.size() != 2) {
            return "";
        }
        Rank a = hole.get(0).getRank();
        Rank b = hole.get(1).getRank();
        Rank high = a.value() >= b.value() ? a : b;
        boolean en = getLanguage().equals("en") || getLanguage().isEmpty();
        if (a == b) {
            return en ? "Pair of " + a.pluralName() : formatMessage("hand-detail", "hand", getMessage("hand.PAIR"), "r1", a.label());
        }
        return en ? high.displayName() + " high"
            : formatMessage("hand-detail", "hand", getMessage("hand.HIGH_CARD"), "r1", high.label());
    }

    // ---- Chat buttons (messages.yml "buttons.<name>.text|hover") ----
    public String getButtonText(String buttonName) {
        return color(messagesConfig.getString("buttons." + buttonName + ".text", "&7[" + buttonName.toUpperCase(Locale.ROOT) + "]"));
    }

    public String getButtonHover(String buttonName) {
        return color(messagesConfig.getString("buttons." + buttonName + ".hover", "Click to " + buttonName));
    }
}
