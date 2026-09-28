package com.vortex.poker.table;

import org.bukkit.block.BlockFace;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Round 5 (Vortex): tables.yml must survive being missing, broken, invalid or old. */
class TableStoreTest {
    private static final Logger LOG = Logger.getLogger("TableStoreTest");

    @TempDir
    File dir;

    private File tablesFile() {
        return new File(dir, "tables.yml");
    }

    private void write(String text) throws IOException {
        Files.writeString(tablesFile().toPath(), text, StandardCharsets.UTF_8);
    }

    private String read(File f) throws IOException {
        return Files.readString(f.toPath(), StandardCharsets.UTF_8);
    }

    private List<File> files(String prefix) {
        File[] all = dir.listFiles((d, name) -> name.startsWith(prefix));
        return all == null ? List.of() : Arrays.asList(all);
    }

    private static Map<String, String> reasons(TableStore.Loaded loaded) {
        return loaded.skipped().stream().collect(Collectors.toMap(TableStore.Skipped::key, TableStore.Skipped::reason));
    }

    private static String entry(String key, String body) {
        return "  '" + key + "':\n" + body.lines().map(l -> "    " + l + "\n").collect(Collectors.joining());
    }

    private static final String GOOD_BODY = "world: world\nx: 10\ny: 64\nz: -20\nfacing: EAST\nlayout: 2\nseats: 6\n";

    @Test
    void aMissingFileMeansNoTablesAndIsWrittenOnTheFirstSave() throws IOException {
        TableStore store = new TableStore(tablesFile(), LOG);
        TableStore.Loaded loaded = store.load();
        assertEquals(TableStore.FileState.MISSING, loaded.state());
        assertTrue(loaded.entries().isEmpty());
        assertFalse(tablesFile().exists(), "nothing written just for loading");

        store.put(store.nextId(), "world", 1, 64, 2, BlockFace.SOUTH, TableManager.LAYOUT_VERSION, new TableSettings());
        assertTrue(store.save());
        YamlConfiguration saved = YamlConfiguration.loadConfiguration(tablesFile());
        assertEquals(TableStore.FORMAT_VERSION, saved.getInt("format-version"));
        assertEquals("world", saved.getString("tables.1.world"));
        assertTrue(files("tables.yml.tmp").isEmpty(), "no temp file left behind");
    }

    @Test
    void tablesRoundTrip() {
        TableStore store = new TableStore(tablesFile(), LOG);
        store.load();
        store.put(store.nextId(), "world", 10, 64, -20, BlockFace.EAST, 2, new TableSettings(6, 5L, 10L, 20, 100, 12.5));
        store.put(store.nextId(), "nether", -5, 30, 7, BlockFace.WEST, 2, new TableSettings());
        assertTrue(store.save());

        TableStore.Loaded loaded = new TableStore(tablesFile(), LOG).load();
        assertEquals(TableStore.FileState.OK, loaded.state());
        assertTrue(loaded.skipped().isEmpty(), loaded.skipped().toString());
        assertEquals(2, loaded.entries().size());
        TableStore.Entry first = loaded.entries().get(0);
        assertEquals(1, first.id());
        assertEquals("world", first.world());
        assertEquals(10, first.x());
        assertEquals(-20, first.z());
        assertEquals(BlockFace.EAST, first.facing());
        assertEquals(2, first.layout());
        assertEquals(6, first.settings().getRawMaxSeats());
        assertEquals(5L, first.settings().getRawSmallBlind());
        assertEquals(100, first.settings().getRawMaxBuyInBB());
        assertEquals(12.5, first.settings().getRawMaxJoinDistance());
        assertNull(loaded.entries().get(1).settings().getRawMaxSeats(), "unset stays unset (follows config.yml)");
    }

    @Test
    void invalidEntriesAreSkippedWithAReasonAndLeftInTheFile() throws IOException {
        write("format-version: 1\nnext-id: 2\ntables:\n"
            + entry("1", GOOD_BODY)
            + entry("2", "x: 1\ny: 64\nz: 1\n")
            + entry("3", "world: world\nx: abc\ny: 64\nz: 1\n")
            + entry("4", "world: world\nx: 1\ny: 64\nz: 1\nseats: 12\n")
            + entry("5", "world: world\nx: 1\ny: 64\nz: 1\nfacing: UP\n")
            + entry("6", "world: world\nx: 1\ny: 64\nz: 1\nlayout: 9\n")
            + entry("7", "world: world\nx: 1\ny: 99999\nz: 1\n")
            + entry("8", "world: world\nx: 1\ny: 64\n")
            + entry("9", "world: world\nx: 1\ny: 64\nz: 1\nsmall-blind: -5\n")
            + entry("abc", GOOD_BODY));
        String before = read(tablesFile());
        TableStore store = new TableStore(tablesFile(), LOG);
        TableStore.Loaded loaded = store.load();
        assertEquals(TableStore.FileState.OK, loaded.state());
        assertEquals(List.of(1), loaded.entries().stream().map(TableStore.Entry::id).toList());
        Map<String, String> why = reasons(loaded);
        assertEquals("no world", why.get("2"));
        assertTrue(why.get("3").contains("x is not a whole number"), why.get("3"));
        assertTrue(why.get("4").contains("seats must be 2 to 8"), why.get("4"));
        assertTrue(why.get("5").contains("facing"), why.get("5"));
        assertTrue(why.get("6").contains("newer Poker"), why.get("6"));
        assertTrue(why.get("7").contains("y is out of range"), why.get("7"));
        assertEquals("no z coordinate", why.get("8"));
        assertTrue(why.get("9").contains("small-blind"), why.get("9"));
        assertTrue(why.get("abc").contains("not a number"), why.get("abc"));
        assertEquals(before, read(tablesFile()), "loading alone changes nothing");

        // a new table takes a fresh ID and the skipped entries survive the save untouched
        int id = store.nextId();
        assertEquals(10, id, "next-id goes past every ID in the file, loaded or not");
        store.put(id, "world", 100, 64, 100, BlockFace.NORTH, 2, new TableSettings());
        assertTrue(store.save());
        YamlConfiguration after = YamlConfiguration.loadConfiguration(tablesFile());
        YamlConfiguration original = new YamlConfiguration();
        try {
            original.loadFromString(before);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        for (String key : List.of("2", "3", "4", "5", "6", "7", "8", "9", "abc")) {
            assertEquals(original.getConfigurationSection("tables." + key).getValues(true),
                after.getConfigurationSection("tables." + key).getValues(true), "entry " + key + " kept as it was");
        }
    }

    @Test
    void brokenYamlIsKeptAsideAndEveryReadableTableSurvives() throws IOException {
        String text = "format-version: 1\nnext-id: 4\ntables:\n"
            + entry("1", GOOD_BODY)
            + "  '2':\n    world: \"unterminated\n    x: 5\n    y: 64\n    z: 5\n"
            + entry("3", GOOD_BODY.replace("x: 10", "x: 40"));
        write(text);
        TableStore store = new TableStore(tablesFile(), LOG);
        TableStore.Loaded loaded = store.load();
        assertEquals(TableStore.FileState.BROKEN, loaded.state());
        assertNotNull(loaded.brokenCopy());
        assertTrue(loaded.brokenCopy().getName().startsWith("tables.yml.broken-"), loaded.brokenCopy().getName());
        assertEquals(text, read(loaded.brokenCopy()), "the broken original is kept byte for byte");

        assertEquals(List.of(1, 3), loaded.entries().stream().map(TableStore.Entry::id).toList());
        String why = reasons(loaded).get("2");
        assertNotNull(why, loaded.skipped().toString());
        assertTrue(why.contains("not valid YAML") && why.contains("lines"), why);

        // tables.yml itself is valid again, with the readable tables
        YamlConfiguration rewritten = new YamlConfiguration();
        try {
            rewritten.loadFromString(read(tablesFile()));
        } catch (Exception e) {
            throw new AssertionError("tables.yml should parse after the repair", e);
        }
        assertTrue(rewritten.contains("tables.1") && rewritten.contains("tables.3") && !rewritten.contains("tables.2"));
        assertTrue(store.nextId() >= 4, "the lost entry's ID isn't reused");
    }

    @Test
    void aBrokenFileThatCantBeMovedAsideIsReadButNeverOverwritten() throws IOException {
        String text = "tables:\n" + entry("1", GOOD_BODY) + "  '2':\n    world: \"unterminated\n    x: 5\n";
        write(text);
        assertTrue(dir.setWritable(false), "needs a non-root test user");
        try {
            TableStore store = new TableStore(tablesFile(), LOG);
            TableStore.Loaded loaded = store.load();
            assertEquals(TableStore.FileState.BROKEN, loaded.state());
            assertNull(loaded.brokenCopy());
            assertEquals(List.of(1), loaded.entries().stream().map(TableStore.Entry::id).toList());
            store.put(store.nextId(), "world", 0, 64, 0, BlockFace.NORTH, 2, new TableSettings());
            assertFalse(store.save(), "refuses to write over the only copy");
        } finally {
            dir.setWritable(true);
        }
        assertEquals(text, read(tablesFile()));
    }

    @Test
    void garbageStillStartsWithNoTablesAndKeepsTheOriginal() throws IOException {
        write("{{{ this: is: not [yaml\n\t- ]]\n");
        TableStore store = new TableStore(tablesFile(), LOG);
        TableStore.Loaded loaded = store.load();
        assertEquals(TableStore.FileState.BROKEN, loaded.state());
        assertTrue(loaded.entries().isEmpty());
        assertEquals(1, files("tables.yml.broken-").size());
        YamlConfiguration rewritten = YamlConfiguration.loadConfiguration(tablesFile());
        assertEquals(TableStore.FORMAT_VERSION, rewritten.getInt("format-version"));
    }

    @Test
    void anOldFileIsMigratedWithABackupAndItsTablesMarkedForARebuild() throws IOException {
        // round 1: no format-version, no layout key (the 5x3 felt)
        String old = "next-id: 3\ntables:\n"
            + entry("1", "world: world\nx: 0\ny: 64\nz: 0\nfacing: NORTH\nseats: 4\n")
            + entry("2", "world: world\nx: 30\ny: 64\nz: 0\nfacing: SOUTH\nlayout: 2\n");
        write(old);
        TableStore.Loaded loaded = new TableStore(tablesFile(), LOG).load();
        assertEquals(TableStore.FileState.OK, loaded.state());
        assertEquals(1, loaded.entries().get(0).layout(), "no layout key meant the 5x3 table: rebuild it");
        assertEquals(2, loaded.entries().get(1).layout());

        File backup = new File(dir, "tables.yml.format-0-backup");
        assertTrue(backup.isFile());
        assertEquals(old, read(backup));
        YamlConfiguration migrated = YamlConfiguration.loadConfiguration(tablesFile());
        assertEquals(TableStore.FORMAT_VERSION, migrated.getInt("format-version"));
        assertEquals(1, migrated.getInt("tables.1.layout"), "the layout is now explicit");
        assertEquals(4, migrated.getInt("tables.1.seats"));

        // loading again is a no-op
        String now = read(tablesFile());
        new TableStore(tablesFile(), LOG).load();
        assertEquals(now, read(tablesFile()));
    }

    @Test
    void aFileFromANewerBuildIsBackedUpBeforeItIsFirstChanged() throws IOException {
        String newer = "format-version: 7\nnext-id: 2\nsomething-new: true\ntables:\n" + entry("1", GOOD_BODY);
        write(newer);
        TableStore store = new TableStore(tablesFile(), LOG);
        TableStore.Loaded loaded = store.load();
        assertEquals(1, loaded.entries().size());
        assertFalse(new File(dir, "tables.yml.format-7-backup").exists(), "untouched until something changes");
        store.remove(1);
        assertTrue(store.save());
        assertEquals(newer, read(new File(dir, "tables.yml.format-7-backup")));
    }

    @Test
    void aStaleTempFileDoesNotBlockSaving() throws IOException {
        Files.writeString(new File(dir, "tables.yml.tmp").toPath(), "half a file", StandardCharsets.UTF_8);
        TableStore store = new TableStore(tablesFile(), LOG);
        store.load();
        store.put(store.nextId(), "world", 0, 64, 0, BlockFace.NORTH, 2, new TableSettings());
        assertTrue(store.save());
        assertEquals("world", YamlConfiguration.loadConfiguration(tablesFile()).getString("tables.1.world"));
        assertFalse(new File(dir, "tables.yml.tmp").exists());
    }

    @Test
    void numbersAreReadLeniently() {
        assertEquals(10L, TableStore.wholeNumber(10));
        assertEquals(10L, TableStore.wholeNumber(10L));
        assertEquals(10L, TableStore.wholeNumber(10.0));
        assertEquals(-3L, TableStore.wholeNumber(" -3 "));
        assertEquals(12L, TableStore.wholeNumber("12.0"));
        assertNull(TableStore.wholeNumber(10.5));
        assertNull(TableStore.wholeNumber("abc"));
        assertNull(TableStore.wholeNumber(true));
        assertEquals(BlockFace.WEST, TableStore.parseFacing(" west "));
        assertNull(TableStore.parseFacing("UP"));
    }
}
