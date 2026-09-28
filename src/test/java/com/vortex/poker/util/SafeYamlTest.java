package com.vortex.poker.util;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SafeYamlTest {
    private static final Logger LOG = Logger.getLogger("SafeYamlTest");

    @TempDir
    File dir;

    private File write(String name, String text) throws Exception {
        File f = new File(dir, name);
        Files.writeString(f.toPath(), text, StandardCharsets.UTF_8);
        return f;
    }

    @Test
    void missingFileIsEmpty() {
        SafeYaml.LoadResult r = SafeYaml.load(new File(dir, "nope.yml"), LOG);
        assertEquals(SafeYaml.Status.MISSING, r.status());
        assertTrue(r.config().getKeys(false).isEmpty());
    }

    @Test
    void emptyFileIsOkNotBroken() throws Exception {
        SafeYaml.LoadResult r = SafeYaml.load(write("empty.yml", ""), LOG);
        assertEquals(SafeYaml.Status.OK, r.status());
    }

    @Test
    void brokenFileIsMovedAsideWithItsBytes() throws Exception {
        String text = "a: 1\nb: [unclosed\n";
        File f = write("x.yml", text);
        SafeYaml.LoadResult r = SafeYaml.load(f, LOG);
        assertEquals(SafeYaml.Status.BROKEN, r.status());
        assertFalse(f.exists());
        assertNotNull(r.brokenCopy());
        assertTrue(r.brokenCopy().getName().matches("x\\.yml\\.broken-\\d{8}-\\d{6}"), r.brokenCopy().getName());
        assertEquals(text, Files.readString(r.brokenCopy().toPath()));
        assertNotNull(r.error());
        assertFalse(r.error().contains("\n"));

        // a second broken file in the same second doesn't overwrite the first copy
        write("x.yml", "c: [x\n");
        SafeYaml.LoadResult r2 = SafeYaml.load(f, LOG);
        assertNotNull(r2.brokenCopy());
        assertFalse(r2.brokenCopy().equals(r.brokenCopy()));
        assertTrue(r.brokenCopy().exists());
    }

    @Test
    void salvageKeepsEverythingButTheBrokenEntries() {
        String text = String.join("\n",
            "# comment",
            "language: ko",
            "table:",
            "  max-seats: 8",
            "  big-blind: [oops",
            "  small-blind: 5",
            "game:",
            "  turn-timeout-seconds: 45",
            "broken line without colon",
            "sounds:",
            "  win:",
            "    sound: X",
            "    volume: \"unterminated",
            "list:",
            "- a",
            "- b",
            "");
        YamlConfiguration y = SafeYaml.salvage(text);
        assertEquals("ko", y.getString("language"));
        assertEquals(8, y.getInt("table.max-seats"));
        assertEquals(5, y.getInt("table.small-blind"));
        assertNull(y.get("table.big-blind"));
        assertEquals(45, y.getInt("game.turn-timeout-seconds"));
        assertEquals("X", y.getString("sounds.win.sound"));
        assertEquals(java.util.List.of("a", "b"), y.getStringList("list"));
    }

    @Test
    void salvageFixesTabs() {
        YamlConfiguration y = SafeYaml.salvage("table:\n\tmax-seats: 4\n\tbig-blind: 50\n");
        assertEquals(4, y.getInt("table.max-seats"));
        assertEquals(50, y.getInt("table.big-blind"));
    }

    @Test
    void salvageOfTruncatedFile() {
        YamlConfiguration y = SafeYaml.salvage("a: 1\nb:\n  c: 2\n  d: \"https://exa");
        assertEquals(1, y.getInt("a"));
        assertEquals(2, y.getInt("b.c"));
        assertFalse(y.contains("b.d"));
    }

    @Test
    void salvageOfGarbage() {
        YamlConfiguration y = SafeYaml.salvage("\u0000\u0001\u0002 ::: ][ {{");
        assertTrue(y.getKeys(false).isEmpty());
    }

    @Test
    void atomicSaveReplacesAndLeavesNoTemp() throws Exception {
        File f = write("s.yml", "old: 1\n");
        YamlConfiguration y = new YamlConfiguration();
        y.set("new", 2);
        SafeYaml.saveAtomically(y, f);
        assertEquals(2, YamlConfiguration.loadConfiguration(f).getInt("new"));
        assertFalse(new File(dir, "s.yml.tmp").exists());
    }
}
