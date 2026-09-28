package com.vortex.poker.config;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The self-repair path for config.yml and the messages files, against fixtures and the bundled files. */
class ConfigFileUpdaterTest {
    private static final List<String> BUNDLED = List.of("config.yml", "messages.yml", "messages_ko.yml",
        "messages_tr.yml", "messages_ru.yml");

    @TempDir
    File dir;

    private final List<LogRecord> records = new ArrayList<>();
    private final Logger log = captureLogger();

    private Logger captureLogger() {
        Logger l = Logger.getAnonymousLogger();
        l.setUseParentHandlers(false);
        l.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        return l;
    }

    private List<String> warnings() {
        return records.stream().filter(r -> r.getLevel().intValue() >= Level.WARNING.intValue())
            .map(LogRecord::getMessage).collect(Collectors.toList());
    }

    private List<String> infos() {
        return records.stream().filter(r -> r.getLevel() == Level.INFO).map(LogRecord::getMessage).collect(Collectors.toList());
    }

    static String resource(String name) {
        try (InputStream in = ConfigFileUpdaterTest.class.getClassLoader().getResourceAsStream(name)) {
            assertNotNull(in, "missing resource " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    static YamlConfiguration yaml(String text) {
        YamlConfiguration y = new YamlConfiguration();
        try {
            y.loadFromString(text);
        } catch (InvalidConfigurationException e) {
            throw new AssertionError(e);
        }
        return y;
    }

    private File write(String name, String text) throws IOException {
        File f = new File(dir, name);
        Files.writeString(f.toPath(), text, StandardCharsets.UTF_8);
        return f;
    }

    private ConfigFileUpdater.Result update(File file, String resource) {
        return ConfigFileUpdater.update(file, resource(resource), log, ConfigMigrations.forFile(resource),
            ConfigMigrations.optionalKeys(resource));
    }

    private static String text(File f) throws IOException {
        return Files.readString(f.toPath(), StandardCharsets.UTF_8);
    }

    // ---------- 1. missing file ----------

    @ParameterizedTest
    @ValueSource(strings = {"config.yml", "messages.yml", "messages_ko.yml", "messages_tr.yml", "messages_ru.yml"})
    void missingFileIsRecreatedFromTheBundledDefault(String name) throws IOException {
        File f = new File(dir, name);
        ConfigFileUpdater.Result r = update(f, name);
        assertTrue(r.created());
        assertArrayEquals(resource(name).getBytes(StandardCharsets.UTF_8), Files.readAllBytes(f.toPath()));
        assertTrue(infos().contains("Created " + name + " from bundled defaults."), infos().toString());
        assertTrue(warnings().isEmpty(), warnings().toString());
    }

    // ---------- a current file is left alone ----------

    @ParameterizedTest
    @ValueSource(strings = {"config.yml", "messages.yml", "messages_ko.yml", "messages_tr.yml", "messages_ru.yml"})
    void aCurrentFileIsNotTouchedAndHasNoWarnings(String name) throws IOException {
        File f = write(name, resource(name));
        ConfigFileUpdater.Result r = update(f, name);
        assertFalse(r.saved());
        assertEquals(resource(name), text(f));
        assertFalse(new File(dir, name + ".pre-update.bak").exists());
        int problems = name.equals("config.yml")
            ? ConfigValidator.validateConfig(r.config(), yaml(resource(name)), log)
            : ConfigValidator.validateTypes(r.config(), yaml(resource(name)), name, log);
        assertEquals(0, problems);
        assertTrue(warnings().isEmpty(), warnings().toString());
    }

    @Test
    void everyBundledFileCarriesTheCurrentVersion() {
        for (String name : BUNDLED) {
            assertEquals(ConfigFileUpdater.CURRENT_VERSION, yaml(resource(name)).getInt(ConfigFileUpdater.VERSION_KEY), name);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"messages_ko.yml", "messages_tr.yml", "messages_ru.yml"})
    void translationsOnlyUseEnglishKeysWithTheSameTypes(String name) {
        YamlConfiguration en = yaml(resource("messages.yml"));
        YamlConfiguration tr = yaml(resource(name));
        for (String key : ConfigFileUpdater.leaves(tr)) {
            assertTrue(en.contains(key), name + " has " + key + ", which English doesn't");
            assertEquals(en.get(key) instanceof List, tr.get(key) instanceof List, key);
        }
        assertEquals(0, ConfigValidator.validateTypes(tr, en, name, log), warnings().toString());
    }

    // ---------- 2. broken YAML ----------

    @Test
    void garbageIsMovedAsideAndRebuiltFromTheDefaults() throws IOException {
        byte[] garbage = {0x00, 0x01, 'P', 'K', 0x03, 0x04, ':', ':', '[', '{', (byte) 0xC3, 0x28, '\n', '\t', '-'};
        File f = new File(dir, "config.yml");
        Files.write(f.toPath(), garbage);
        ConfigFileUpdater.Result r = update(f, "config.yml");

        assertNotNull(r.brokenCopy());
        assertTrue(r.brokenCopy().getName().matches("config\\.yml\\.broken-\\d{8}-\\d{6}"));
        assertArrayEquals(garbage, Files.readAllBytes(r.brokenCopy().toPath()), "the admin's bytes are kept");
        YamlConfiguration rebuilt = yaml(text(f));
        for (String key : ConfigFileUpdater.leaves(yaml(resource("config.yml")))) {
            assertTrue(rebuilt.contains(key), key);
        }
        String warning = warnings().stream().filter(w -> w.contains("could not be read")).findFirst().orElseThrow();
        assertTrue(warning.startsWith("config.yml could not be read ("), warning);
        assertTrue(warning.contains("Moved it to " + r.brokenCopy().getName() + " and rebuilt it from the defaults"), warning);
    }

    @Test
    void aTruncatedFileKeepsEveryValueThatStillReads() throws IOException {
        String edited = resource("config.yml")
            .replace("language: en", "language: ru")
            .replace("  max-seats: 6", "  max-seats: 8")
            .replace("  small-blind: 10", "  small-blind: 5");
        // cut it off inside the resource pack URL (a half-written file after a power cut)
        String truncated = edited.substring(0, edited.indexOf("cdn.modrinth.com"));
        File f = write("config.yml", truncated);
        ConfigFileUpdater.Result r = update(f, "config.yml");

        assertNotNull(r.brokenCopy());
        assertEquals(truncated, text(r.brokenCopy()));
        YamlConfiguration now = yaml(text(f));
        assertEquals("ru", now.getString("language"));
        assertEquals(8, now.getInt("table.max-seats"));
        assertEquals(5, now.getInt("table.small-blind"));
        assertTrue(now.getBoolean("resource-pack.send-on-join"));
        assertEquals(yaml(resource("config.yml")).getString("resource-pack.url"), now.getString("resource-pack.url"));
        assertEquals(12, now.getInt("updates.interval-hours"), "sections after the cut come from the defaults");
        assertTrue(r.salvaged() > 30, "salvaged " + r.salvaged());
    }

    @Test
    void oneBadLineOnlyCostsThatLine() throws IOException {
        String bad = resource("config.yml").replace("  big-blind: 20", "  big-blind: [20")
            .replace("  menu: gui", "  menu: gui\n\tchat-buttons: false");
        File f = write("config.yml", bad);
        update(f, "config.yml");
        YamlConfiguration now = yaml(text(f));
        assertEquals(20, now.getInt("table.big-blind"), "the broken value falls back to the default");
        assertEquals(10, now.getInt("table.small-blind"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"messages.yml", "messages_ko.yml", "messages_tr.yml", "messages_ru.yml"})
    void aBrokenMessagesFileIsRebuiltInItsOwnLanguage(String name) throws IOException {
        YamlConfiguration bundled = yaml(resource(name));
        String broken = resource(name).replace("\nreload-done: ", "\nreload-done: \"unterminated\nx: ");
        File f = write(name, broken.replaceFirst("(?m)^prefix: .*$", "prefix: \"&8[&dMine&8] \""));
        ConfigFileUpdater.Result r = update(f, name);
        assertNotNull(r.brokenCopy());
        YamlConfiguration now = yaml(text(f));
        assertEquals("&8[&dMine&8] ", now.getString("prefix"), "the admin's prefix survives");
        assertEquals(bundled.getString("reload-done"), now.getString("reload-done"));
        assertEquals(bundled.getString("help-header"), now.getString("help-header"));
    }

    // ---------- 3. missing keys ----------

    @Test
    void missingKeysAreAddedKeepingValuesCommentsAndOrder() throws IOException {
        String original = resource("config.yml")
            .replace("  small-blind: 10", "  # our blinds, agreed with the players\n  small-blind: 5")
            .replace("  win-title: true\n", "")
            .replace("  ready-timeout-seconds: 30\n", "")
            .replaceAll("(?s)\nupdates:.*$", "\n");
        File f = write("config.yml", original);
        ConfigFileUpdater.Result r = update(f, "config.yml");

        assertEquals(6, r.added(), "win-title, ready-timeout and the four updates keys");
        String now = text(f);
        YamlConfiguration y = yaml(now);
        assertEquals(5, y.getInt("table.small-blind"));
        assertTrue(y.getBoolean("interface.win-title"));
        assertEquals(30, y.getInt("game.ready-timeout-seconds"));
        assertEquals("release", y.getString("updates.channel"));
        assertTrue(now.contains("# our blinds, agreed with the players"), "the admin's comment is kept");
        assertTrue(now.contains("# Card Resource Pack"), "the bundled comments are kept");
        assertTrue(now.indexOf("language:") < now.indexOf("table:") && now.indexOf("table:") < now.indexOf("updates:"),
            "bundled order");
        assertTrue(now.indexOf("win-title") > now.indexOf("interface:") && now.indexOf("win-title") < now.indexOf("cards:"),
            "an added key goes into its own section");
        assertEquals(original, text(new File(dir, "config.yml.pre-update.bak")));
        assertTrue(infos().stream().anyMatch(i -> i.startsWith("Updated config.yml: ") && i.contains("6 missing key(s) added")
            && i.endsWith("Backup: config.yml.pre-update.bak")), infos().toString());

        records.clear();
        assertFalse(update(f, "config.yml").saved(), "a second start changes nothing");
        assertTrue(warnings().isEmpty(), warnings().toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"messages.yml", "messages_ko.yml", "messages_tr.yml", "messages_ru.yml"})
    void aTranslationIsToppedUpFromItsOwnLanguage(String name) throws IOException {
        YamlConfiguration bundled = yaml(resource(name));
        String original = resource(name).replaceFirst("(?m)^reload-done: .*\n", "").replaceFirst("(?m)^prefix: .*$", "prefix: \"&6[P] \"");
        File f = write(name, original);
        ConfigFileUpdater.Result r = update(f, name);
        assertEquals(1, r.added());
        YamlConfiguration now = yaml(text(f));
        assertEquals(bundled.getString("reload-done"), now.getString("reload-done"));
        assertEquals("&6[P] ", now.getString("prefix"));
    }

    // ---------- 4. wrong types and ranges ----------

    @Test
    void wrongValuesFallBackInMemoryOnly() throws IOException {
        String original = resource("config.yml")
            .replace("  big-blind: 20", "  big-blind: \"lots\"")
            .replace("  small-blind: 10", "  small-blind: -5")
            .replace("  max-seats: 6 ", "  max-seats: 12 ")
            .replace("  min-buy-in-bb: 40", "  min-buy-in-bb: \"40\"")
            .replace("  turn-timeout-seconds: 30", "  turn-timeout-seconds: -1")
            .replace("  ready-timeout-seconds: 30", "  ready-timeout-seconds: 3")
            .replace("  menu: gui", "  menu: banana")
            .replace("  chat-buttons: true", "  chat-buttons: maybe")
            .replace("  win-title: true", "  win-title: \"no\"")
            .replace("    hearts: \"&c\"", "    hearts: red")
            .replace("  channel: release", "  channel: nightly");
        File f = write("config.yml", original);
        YamlConfiguration c = update(f, "config.yml").config();
        int problems = ConfigValidator.validateConfig(c, yaml(resource("config.yml")), log);

        assertEquals(20, c.getInt("table.big-blind"));
        assertEquals(10, c.getInt("table.small-blind"));
        assertEquals(6, c.getInt("table.max-seats"));
        assertEquals(40, c.getInt("table.min-buy-in-bb"), "a quoted number is read as the number");
        assertEquals(30, c.getInt("game.turn-timeout-seconds"));
        assertEquals(5, c.getInt("game.ready-timeout-seconds"));
        assertEquals("gui", c.getString("interface.menu"));
        assertTrue(c.getBoolean("interface.chat-buttons"));
        assertFalse(c.getBoolean("interface.win-title"), "\"no\" means false");
        assertEquals("&c", c.getString("cards.colors.hearts"));
        assertEquals("release", c.getString("updates.channel"));
        assertEquals(9, problems, warnings().toString());
        assertTrue(warnings().contains("config.yml: table.big-blind must be a whole number, got \"lots\"; using 20."),
            warnings().toString());
        assertTrue(warnings().contains("config.yml: game.turn-timeout-seconds must be 0 (off) or at least 5, got -1; using 30."),
            warnings().toString());
        assertEquals(original, text(f), "the file keeps what the admin wrote");
    }

    @Test
    void bigBlindBelowSmallBlindHalvesTheSmallBlind() {
        YamlConfiguration c = yaml(resource("config.yml").replace("  small-blind: 10", "  small-blind: 50")
            .replace("  big-blind: 20", "  big-blind: 30")
            .replace("  max-buy-in-bb: 100", "  max-buy-in-bb: 20"));
        ConfigValidator.validateConfig(c, yaml(resource("config.yml")), log);
        assertEquals(15, c.getInt("table.small-blind"));
        assertEquals(30, c.getInt("table.big-blind"));
        assertEquals(40, c.getInt("table.max-buy-in-bb"), "max buy-in rises to the min");
    }

    @Test
    void zeroStillTurnsTimersOff() {
        YamlConfiguration c = yaml(resource("config.yml").replace("  turn-timeout-seconds: 30", "  turn-timeout-seconds: 0")
            .replace("  ready-timeout-seconds: 30", "  ready-timeout-seconds: 0"));
        assertEquals(0, ConfigValidator.validateConfig(c, yaml(resource("config.yml")), log));
        assertEquals(0, c.getInt("game.turn-timeout-seconds"));
    }

    @Test
    void aSectionReplacedByAValueUsesTheDefaultSection() throws IOException {
        File f = write("config.yml", resource("config.yml").replaceAll("(?s)\nparticles:\n.*?\nperformance:", "\nparticles: off\nperformance:"));
        YamlConfiguration c = update(f, "config.yml").config();
        assertEquals(20, c.getInt("particles.win.count"));
        assertTrue(warnings().stream().anyMatch(w -> w.contains("particles should be a section")), warnings().toString());
    }

    // ---------- 5. old versions ----------

    @Test
    void aPreReleaseConfigUpgradesInPlace() throws IOException {
        String original = resource("config-fixtures/config-pre1.0.yml");
        YamlConfiguration before = yaml(original);
        File f = write("config.yml", original);
        ConfigFileUpdater.Result r = update(f, "config.yml");

        YamlConfiguration now = yaml(text(f));
        assertEquals(1, now.getInt(ConfigFileUpdater.VERSION_KEY));
        assertTrue(now.getBoolean("interface.show-hand-strength"), "moved from the top level");
        assertFalse(now.contains("show-hand-strength"));
        for (String key : ConfigFileUpdater.leaves(before)) {
            if (!key.equals("show-hand-strength")) {
                assertEquals(before.get(key), now.get(key), key + " survives the upgrade");
            }
        }
        assertEquals("tr", now.getString("language"));
        assertEquals(25, now.getInt("table.small-blind"));
        assertEquals(50, now.getInt("table.big-blind"));
        assertEquals(45, now.getInt("game.turn-timeout-seconds"));
        assertEquals(30, now.getInt("game.ready-timeout-seconds"), "added since the old build");
        assertEquals("ask Vortex before changing blinds", now.getString("my-server-note"), "unknown keys stay");
        assertTrue(text(f).contains("# raised for the casino floor"));
        assertEquals(original, text(new File(dir, "config.yml.pre-update.bak")));
        assertEquals(List.of("my-server-note"), r.unknownKeys());
        assertTrue(warnings().contains("config.yml: unknown key(s) left as they are: my-server-note"), warnings().toString());
        assertTrue(infos().stream().anyMatch(i -> i.startsWith("Updated config.yml: config-version 0 -> 1")), infos().toString());
        assertEquals(0, ConfigValidator.validateConfig(r.config(), yaml(resource("config.yml")), log));
    }

    @Test
    void showHandStrengthUnderGameMovesToo() throws IOException {
        File f = write("config.yml", resource("config.yml").replace("config-version: 1", "")
            .replace("  show-hand-strength: false\n", "")
            .replace("  ready-timeout-seconds: 30", "  ready-timeout-seconds: 30\n  show-hand-strength: true"));
        update(f, "config.yml");
        YamlConfiguration now = yaml(text(f));
        assertTrue(now.getBoolean("interface.show-hand-strength"));
        assertFalse(now.contains("game.show-hand-strength"));
    }

    @Test
    void aKnownKeyAtTheWrongLevelIsMoved() throws IOException {
        File f = write("config.yml", resource("config.yml").replace("  chat-buttons: true\n", "") + "chat-buttons: false\n");
        ConfigFileUpdater.Result r = update(f, "config.yml");
        assertEquals(1, r.moved());
        YamlConfiguration now = yaml(text(f));
        assertFalse(now.getBoolean("interface.chat-buttons"));
        assertFalse(now.contains("chat-buttons"));
        assertTrue(infos().contains("config.yml: moved chat-buttons to interface.chat-buttons."), infos().toString());
    }

    @Test
    void ambiguousOrAlreadySetKeysAreNotMoved() throws IOException {
        // "volume" exists under every sound: ambiguous. "menu" is already set under interface.
        File f = write("config.yml", resource("config.yml") + "volume: 0.1\nmenu: chat\n");
        ConfigFileUpdater.Result r = update(f, "config.yml");
        assertEquals(0, r.moved());
        assertEquals(List.of("volume", "menu"), r.unknownKeys());
        assertEquals("gui", r.config().getString("interface.menu"));
    }

    @Test
    void optionalKeysAreKeptWithoutAWarning() throws IOException {
        File f = write("config.yml", resource("config.yml").replace("  interval-hours: 12", "  interval-hours: 12\n  api-url: http://127.0.0.1:8099"));
        ConfigFileUpdater.Result r = update(f, "config.yml");
        assertEquals("http://127.0.0.1:8099", r.config().getString("updates.api-url"));
        assertTrue(r.unknownKeys().isEmpty());
        assertTrue(warnings().isEmpty(), warnings().toString());
    }

    @Test
    void aNewerFileIsNotMigratedOrDowngraded() throws IOException {
        File f = write("config.yml", resource("config.yml").replace("config-version: 1", "config-version: 7"));
        ConfigFileUpdater.Result r = update(f, "config.yml");
        assertFalse(r.saved());
        assertEquals(7, r.config().getInt(ConfigFileUpdater.VERSION_KEY));
        assertTrue(warnings().stream().anyMatch(w -> w.contains("newer than this Poker build")), warnings().toString());
    }

    @Test
    void oldMessagesGetNewDefaultsButCustomTextsStay() throws IOException {
        String original = resource("config-fixtures/messages-c2426cc.yml");
        File f = write("messages.yml", original);
        ConfigFileUpdater.Result r = update(f, "messages.yml");
        YamlConfiguration bundled = yaml(resource("messages.yml"));
        YamlConfiguration now = yaml(text(f));

        assertEquals("", now.getString("player-ready"), "the old chatty default is replaced by the muted one");
        assertEquals(bundled.getString("hand-started"), now.getString("hand-started"));
        assertEquals(bundled.getStringList("help-admin-lines"), now.getStringList("help-admin-lines"));
        assertEquals("&8[&bCasino&8] &r", now.getString("prefix"), "customised");
        assertTrue(text(f).contains("# our casino branding"));
        assertFalse(now.contains("turn-cards-preflop"), "a removed key with its old default is dropped");
        assertEquals("&7Cards %cards% | board %board%", now.getString("turn-cards"), "a customised removed key stays");
        assertEquals(List.of("turn-cards"), r.unknownKeys());
        assertEquals(1, now.getInt(ConfigFileUpdater.VERSION_KEY));
        assertEquals(original, text(new File(dir, "messages.yml.pre-update.bak")));
    }

    @Test
    void oldKoreanMessagesUpgrade() throws IOException {
        File f = write("messages_ko.yml", resource("config-fixtures/messages_ko-c2426cc.yml"));
        update(f, "messages_ko.yml");
        YamlConfiguration bundled = yaml(resource("messages_ko.yml"));
        YamlConfiguration now = yaml(text(f));
        assertEquals(bundled.getString("help-header"), now.getString("help-header"));
        assertEquals("", now.getString("player-ready"));
        for (String key : ConfigFileUpdater.leaves(bundled)) {
            assertTrue(now.contains(key), key);
        }
    }

    // ---------- custom languages ----------

    @Test
    void aCustomLanguageWithoutABundledCopyIsOnlyRescued() throws IOException {
        File f = write("messages_de.yml", "prefix: \"&8[Poker] \"\nreload-done: \"&aNeu geladen\nseated: \"&aDu sitzt\"\n");
        ConfigFileUpdater.Result r = ConfigFileUpdater.update(f, null, log, List.of(), Set.of());
        assertNotNull(r.brokenCopy());
        YamlConfiguration now = yaml(text(f));
        assertEquals("&8[Poker] ", now.getString("prefix"));
        assertNull(now.get("help-header"), "no English keys are pushed into a custom language");

        records.clear();
        File ok = write("messages_nl.yml", "prefix: \"x\"\n");
        ConfigFileUpdater.Result r2 = ConfigFileUpdater.update(ok, null, log, List.of(), Set.of());
        assertTrue(r2.saved(), "the version stamp is added once");
        assertFalse(ConfigFileUpdater.update(ok, null, log, List.of(), Set.of()).saved());
    }
}
