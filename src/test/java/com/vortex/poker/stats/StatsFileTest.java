package com.vortex.poker.stats;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** stats.yml survives damage: a broken file is moved aside and every readable player recovered. */
class StatsFileTest {
    private static final String A = "11111111-1111-1111-1111-111111111111";
    private static final String B = "22222222-2222-2222-2222-222222222222";
    private static final String C = "33333333-3333-3333-3333-333333333333";

    @TempDir
    File dir;

    private final List<String> messages = new ArrayList<>();
    private final Logger log = Logger.getAnonymousLogger();

    {
        log.setUseParentHandlers(false);
        log.addHandler(new Handler() {
            @Override public void publish(LogRecord r) { messages.add(r.getMessage()); }
            @Override public void flush() { }
            @Override public void close() { }
        });
    }

    @Test
    void missingFileIsEmpty() {
        assertTrue(StatsManager.read(new File(dir, "stats.yml"), log).isEmpty());
        assertTrue(messages.isEmpty());
    }

    @Test
    void brokenFileIsMovedAsideAndReadablePlayersRecovered() throws Exception {
        String text = String.join("\n",
            "config-version: 1",
            A + ":",
            "  name: Alice",
            "  hands-played: 40",
            "  hands-won: 12",
            "  net-winnings: -300",
            B + ":",
            "  name: \"Bob",           // unterminated: this line is lost
            "  hands-played: 7",
            C + ":",
            "  name: Cara",
            "  hands-played: lots",
            "  biggest-pot: -5",
            "");
        File f = new File(dir, "stats.yml");
        Files.writeString(f.toPath(), text, StandardCharsets.UTF_8);

        Map<UUID, PlayerStats> stats = StatsManager.read(f, log);

        assertEquals(3, stats.size());
        PlayerStats a = stats.get(UUID.fromString(A));
        assertEquals("Alice", a.name);
        assertEquals(40, a.handsPlayed);
        assertEquals(-300, a.netWinnings, "net winnings may be negative");
        assertEquals(7, stats.get(UUID.fromString(B)).handsPlayed, "Bob's other fields survive his broken name");
        assertEquals(0, stats.get(UUID.fromString(C)).handsPlayed);
        assertEquals(0, stats.get(UUID.fromString(C)).biggestPot);

        File[] copies = dir.listFiles((d, n) -> n.startsWith("stats.yml.broken-"));
        assertEquals(1, copies.length);
        assertEquals(text, Files.readString(copies[0].toPath()));
        YamlConfiguration rewritten = YamlConfiguration.loadConfiguration(f);
        assertEquals(40, rewritten.getInt(A + ".hands-played"), "the recovered file is written back at once");
        assertEquals(1, rewritten.getInt("config-version"));
        assertTrue(messages.stream().anyMatch(m -> m.startsWith("stats.yml could not be read (")
            && m.contains("Moved it to " + copies[0].getName() + "; recovered 3 player(s).")), messages.toString());
        assertTrue(messages.stream().anyMatch(m -> m.contains(C + ".hands-played is not a whole number")), messages.toString());
    }

    @Test
    void junkTopLevelKeysAreSkipped() throws Exception {
        File f = new File(dir, "stats.yml");
        Files.writeString(f.toPath(), "not-a-uuid:\n  hands-played: 3\n" + A + ":\n  hands-played: 2\n");
        Map<UUID, PlayerStats> stats = StatsManager.read(f, log);
        assertEquals(1, stats.size());
        assertTrue(messages.stream().anyMatch(m -> m.contains("skipping 'not-a-uuid'")));
    }

    @Test
    void anUnreadableFileIsNeverOverwritten() throws Exception {
        File f = new File(dir, "stats.yml");
        Files.writeString(f.toPath(), "a: [broken\n");
        // a read-only directory: the broken file can't be moved aside
        assertTrue(dir.setWritable(false));
        try {
            assertThrows(IllegalStateException.class, () -> StatsManager.read(f, log));
            assertTrue(f.exists());
        } finally {
            dir.setWritable(true);
        }
    }
}
