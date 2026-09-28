package com.vortex.poker.table;

import com.vortex.poker.util.SafeYaml;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * tables.yml on disk. Knows nothing about worlds or blocks: it reads what it can, says exactly what
 * it couldn't, migrates old formats and saves atomically. TableManager decides what to build.
 *
 * <ul>
 *   <li>Missing file: no tables (it is written on the first save).</li>
 *   <li>Broken YAML: the file is renamed to tables.yml.broken-&lt;time&gt; (never deleted), every
 *       table entry that still parses on its own is kept, and tables.yml is rewritten from those.</li>
 *   <li>Invalid entry (no world, bad coordinates, bad seat count...): not loaded, but left in the
 *       file untouched, since only the entries of loaded tables are ever rewritten.</li>
 *   <li>Older format: migrated step by step (the old file is kept as tables.yml.format-N-backup).
 *       Tables with an older block layout are rebuilt by TableManager when their world loads.</li>
 * </ul>
 */
final class TableStore {
    /**
     * The file format, written as format-version. 0 (absent): 1.0 and the round-2/3/4 builds, where
     * an entry without "layout" is the 5x3 layout. 1: every entry states its layout.
     */
    static final int FORMAT_VERSION = 1;
    /** Minecraft's world border: coordinates beyond it can't be a table. */
    static final int MAX_XZ = 30_000_000;
    /** Wider than any world's build height; TableManager checks the real world's limits. */
    static final int MIN_Y = -2048, MAX_Y = 2048;

    enum FileState { OK, MISSING, BROKEN }

    /** A table entry that parsed and passed the checks that don't need a world. */
    record Entry(int id, String world, int x, int y, int z, BlockFace facing, int layout, TableSettings settings) {}

    /** An entry that isn't loaded, and why. Unless the file was broken, it is still in tables.yml. */
    record Skipped(String key, String reason) {}

    record Loaded(FileState state, File brokenCopy, List<Entry> entries, List<Skipped> skipped) {}

    /** One format step: turns a document of version {@code from} into {@code from + 1}. */
    private record Migration(int from, String what, Consumer<YamlConfiguration> apply) {}

    private static final List<Migration> MIGRATIONS = List.of(
        new Migration(0, "every table now states its block layout (none meant the 5x3 layout)", doc -> {
            ConfigurationSection tables = doc.getConfigurationSection("tables");
            if (tables == null) return;
            for (String key : tables.getKeys(false)) {
                ConfigurationSection entry = tables.getConfigurationSection(key);
                if (entry != null && !entry.contains("layout")) entry.set("layout", 1);
            }
        }));

    private static final List<String> HEADER = List.of(
        "Poker tables, by ID. Managed by the plugin: use /poker createtable, /poker settable and",
        "/poker removetable instead of editing this while the server is running.",
        "Settings left out of a table follow config.yml. Buy-ins are in big blinds.",
        "A table that can't be loaded (its world is missing, or the entry is wrong) is skipped with a",
        "warning in the log and left here untouched.");

    private final File file;
    private final Logger log;
    private YamlConfiguration doc = new YamlConfiguration();
    /** A tables.yml from a newer build is copied aside once before we first overwrite it. */
    private int newerFormat = -1;
    /** The file is broken and couldn't be moved aside: never overwrite it. */
    private boolean frozen;

    TableStore(File file, Logger log) {
        this.file = file;
        this.log = log;
    }

    // ---------------------------------------------------------------------------------------
    // Loading
    // ---------------------------------------------------------------------------------------

    Loaded load() {
        SafeYaml.LoadResult result = SafeYaml.load(file, log);
        List<Skipped> skipped = new ArrayList<>();
        FileState state;
        switch (result.status()) {
            case MISSING -> {
                state = FileState.MISSING;
                doc = new YamlConfiguration();
                doc.set("format-version", FORMAT_VERSION);
                log.info("tables.yml doesn't exist yet: starting with no poker tables.");
            }
            case BROKEN -> {
                state = FileState.BROKEN;
                if (result.brokenCopy() == null) {
                    // it couldn't be moved aside: read what we can, but never write over it
                    frozen = true;
                    doc = salvage(file, skipped);
                    log.severe("tables.yml is not valid YAML (" + result.error() + ") and could not be moved aside. "
                        + "Loading the table entries that can still be read; tables.yml will NOT be saved until it is "
                        + "fixed or removed and the server restarted.");
                    break;
                }
                doc = salvage(result.brokenCopy(), skipped);
                log.severe("tables.yml was not valid YAML (" + result.error() + "). The original is kept as "
                    + (result.brokenCopy() == null ? "?" : result.brokenCopy().getName())
                    + "; tables.yml has been rewritten from the table entries that could still be read.");
            }
            default -> {
                state = FileState.OK;
                doc = result.config();
            }
        }
        if (frozen) {
            // salvaged in memory only
        } else if (!migrate() && state == FileState.BROKEN) {
            save();
        }

        List<Entry> entries = new ArrayList<>();
        ConfigurationSection tables = doc.getConfigurationSection("tables");
        if (tables == null && doc.contains("tables")) {
            skipped.add(new Skipped("tables", "'tables' is not a list of table entries"));
        }
        if (tables != null) {
            for (String key : tables.getKeys(false)) {
                Object parsed = parseEntry(key, tables.get(key));
                if (parsed instanceof Entry e) {
                    entries.add(e);
                } else {
                    skipped.add((Skipped) parsed);
                }
            }
        }
        return new Loaded(state, result.brokenCopy(), entries, skipped);
    }

    /** Bring an older document up to FORMAT_VERSION, keeping the old file as a backup; true if it saved. */
    private boolean migrate() {
        int version = doc.getInt("format-version", 0);
        if (version > FORMAT_VERSION) {
            newerFormat = version;
            log.warning("tables.yml is format " + version + ", from a newer Poker than this one (format "
                + FORMAT_VERSION + "). Loading what this version understands; the file is copied to "
                + backupName(version) + " before it is first changed.");
            return false;
        }
        if (version == FORMAT_VERSION) return false;
        backup(version);
        for (Migration m : MIGRATIONS) {
            if (m.from() >= version && m.from() < FORMAT_VERSION) {
                m.apply().accept(doc);
                log.info("tables.yml: upgraded format " + m.from() + " to " + (m.from() + 1) + ": " + m.what());
            }
        }
        doc.set("format-version", FORMAT_VERSION);
        save();
        return true;
    }

    private String backupName(int version) {
        return file.getName() + ".format-" + version + "-backup";
    }

    private void backup(int version) {
        if (!file.isFile()) return;
        File to = new File(file.getParentFile(), backupName(version));
        try {
            Files.copy(file.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.warning("Could not back up tables.yml to " + to.getName() + ": " + e.getMessage());
        }
    }

    /**
     * Check one table entry without a world: the ID, the world name, whole-block coordinates, the
     * facing, the layout version and the setting overrides. Returns an {@link Entry} or a
     * {@link Skipped} saying what is wrong.
     */
    static Object parseEntry(String key, Object value) {
        int id;
        try {
            id = Integer.parseInt(key.trim());
        } catch (NumberFormatException e) {
            return new Skipped(key, "the table ID '" + key + "' is not a number");
        }
        if (id < 1) return new Skipped(key, "the table ID must be 1 or more");
        if (!(value instanceof ConfigurationSection sec)) {
            return new Skipped(key, "not a table entry (expected world, x, y, z, facing...)");
        }
        String world = sec.getString("world", "").trim();
        if (world.isEmpty()) return new Skipped(key, "no world");

        int[] xyz = new int[3];
        String[] axes = {"x", "y", "z"};
        for (int i = 0; i < 3; i++) {
            Object raw = sec.get(axes[i]);
            if (raw == null) return new Skipped(key, "no " + axes[i] + " coordinate");
            Long v = wholeNumber(raw);
            if (v == null) return new Skipped(key, axes[i] + " is not a whole number: " + raw);
            boolean inRange = i == 1 ? v >= MIN_Y && v <= MAX_Y : Math.abs(v) <= MAX_XZ;
            if (!inRange) return new Skipped(key, axes[i] + " is out of range: " + v);
            xyz[i] = v.intValue();
        }

        BlockFace facing = BlockFace.NORTH;
        if (sec.contains("facing")) {
            facing = parseFacing(String.valueOf(sec.get("facing")));
            if (facing == null) return new Skipped(key, "facing must be NORTH, EAST, SOUTH or WEST, not " + sec.get("facing"));
        }

        int layout = 1;
        if (sec.contains("layout")) {
            Long v = wholeNumber(sec.get("layout"));
            if (v == null || v < 1) return new Skipped(key, "layout is not a version number: " + sec.get("layout"));
            if (v > TableManager.LAYOUT_VERSION) {
                return new Skipped(key, "layout " + v + " is from a newer Poker; this one builds layout " + TableManager.LAYOUT_VERSION);
            }
            layout = v.intValue();
        }

        Object settings = parseSettings(key, sec);
        if (settings instanceof Skipped s) return s;
        return new Entry(id, world, xyz[0], xyz[1], xyz[2], facing, layout, (TableSettings) settings);
    }

    private static Object parseSettings(String key, ConfigurationSection sec) {
        Long seats = optionalPositive(sec, "seats");
        if (seats == BAD) return new Skipped(key, "seats is not a whole number: " + sec.get("seats"));
        if (seats != null && (seats < TableLayout.MIN_SEATS || seats > TableLayout.MAX_SEATS)) {
            return new Skipped(key, "seats must be " + TableLayout.MIN_SEATS + " to " + TableLayout.MAX_SEATS + ", not " + seats);
        }
        String[] names = {"small-blind", "big-blind", "min-buy-in-bb", "max-buy-in-bb"};
        Long[] values = new Long[names.length];
        for (int i = 0; i < names.length; i++) {
            values[i] = optionalPositive(sec, names[i]);
            if (values[i] == BAD || (values[i] != null && values[i] < 1)) {
                return new Skipped(key, names[i] + " must be a whole number of 1 or more, not " + sec.get(names[i]));
            }
            if (i >= 2 && values[i] != null && values[i] > Integer.MAX_VALUE) {
                return new Skipped(key, names[i] + " is too large: " + values[i]);
            }
        }
        Double distance = null;
        if (sec.contains("max-join-distance") && sec.get("max-join-distance") != null) {
            Object raw = sec.get("max-join-distance");
            distance = raw instanceof Number n ? n.doubleValue() : parseDouble(String.valueOf(raw));
            if (distance == null || !(distance >= 1.0) || distance.isInfinite()) {
                return new Skipped(key, "max-join-distance must be a number of 1 or more, not " + raw);
            }
        }
        return new TableSettings(seats == null ? null : seats.intValue(), values[0], values[1],
            values[2] == null ? null : values[2].intValue(), values[3] == null ? null : values[3].intValue(), distance);
    }

    /** Marker for "present but not a whole number". */
    private static final Long BAD = Long.MIN_VALUE;

    private static Long optionalPositive(ConfigurationSection sec, String name) {
        if (!sec.contains(name) || sec.get(name) == null) return null;
        Long v = wholeNumber(sec.get(name));
        return v == null ? BAD : v;
    }

    /** An int, a long, a whole double (10.0) or a numeric string; anything else is null. */
    static Long wholeNumber(Object raw) {
        if (raw instanceof Integer || raw instanceof Long || raw instanceof Short || raw instanceof Byte) {
            return ((Number) raw).longValue();
        }
        double d;
        if (raw instanceof Number n) {
            d = n.doubleValue();
        } else if (raw instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException e) {
                Double parsed = parseDouble(s);
                if (parsed == null) return null;
                d = parsed;
            }
        } else {
            return null;
        }
        if (Double.isNaN(d) || Double.isInfinite(d) || d != Math.rint(d) || Math.abs(d) > 9e15) return null;
        return (long) d;
    }

    private static Double parseDouble(String s) {
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** NORTH/EAST/SOUTH/WEST in any case; null for anything else. */
    static BlockFace parseFacing(String name) {
        if (name == null) return null;
        try {
            BlockFace face = BlockFace.valueOf(name.trim().toUpperCase(Locale.ROOT));
            return face == BlockFace.NORTH || face == BlockFace.EAST || face == BlockFace.SOUTH || face == BlockFace.WEST
                ? face : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------------------------------
    // Salvaging a broken file
    // ---------------------------------------------------------------------------------------

    private static final Pattern TOP_KEY = Pattern.compile("^([A-Za-z0-9_-]+):\\s*(.*?)\\s*$");
    private static final Pattern CHILD_KEY = Pattern.compile("^\\s+['\"]?([^'\":#]+?)['\"]?\\s*:\\s*(#.*)?$");

    /** Read what we can from a broken tables.yml: every table entry that parses on its own. */
    private YamlConfiguration salvage(File broken, List<Skipped> skipped) {
        String text;
        try {
            text = Files.readString(broken.toPath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            skipped.add(new Skipped("tables.yml", "could not read the broken copy: " + e.getMessage()));
            text = "";
        }
        return salvage(text, skipped);
    }

    /**
     * Split the raw text into the top-level keys and the entries under "tables:", and parse each
     * table entry on its own. Entries that still fail are listed in {@code skipped} with the
     * parser's message and their line numbers in the broken file.
     */
    static YamlConfiguration salvage(String text, List<Skipped> skipped) {
        YamlConfiguration out = new YamlConfiguration();
        String[] lines = text.split("\\r?\\n", -1);
        int maxId = 0;
        long nextId = 0;
        int format = 0;
        for (int i = 0; i < lines.length; i++) {
            Matcher top = TOP_KEY.matcher(lines[i]);
            if (!top.matches()) continue;
            if (top.group(1).equals("next-id")) {
                Long v = wholeNumber(stripComment(top.group(2)));
                if (v != null) nextId = v;
            } else if (top.group(1).equals("format-version")) {
                Long v = wholeNumber(stripComment(top.group(2)));
                if (v != null) format = v.intValue();
            } else if (top.group(1).equals("tables")) {
                maxId = Math.max(maxId, salvageTables(lines, i + 1, out, skipped));
            }
        }
        out.set("format-version", format);
        out.set("next-id", (int) Math.max(Math.min(nextId, Integer.MAX_VALUE), maxId + 1L));
        return out;
    }

    /** Parse the entries under "tables:" (starting at line {@code from}) one by one; returns the highest ID seen. */
    private static int salvageTables(String[] lines, int from, YamlConfiguration out, List<Skipped> skipped) {
        // the block ends at the next line that starts in column 0 (another top-level key)
        int end = from;
        while (end < lines.length && (lines[end].isBlank() || lines[end].startsWith(" ")
            || lines[end].startsWith("\t") || lines[end].trim().startsWith("#"))) {
            end++;
        }
        // entries start at the indentation of the first key line
        int indent = -1;
        List<Integer> starts = new ArrayList<>();
        for (int i = from; i < end; i++) {
            String line = lines[i];
            if (line.isBlank() || line.trim().startsWith("#")) continue;
            int lead = line.length() - line.stripLeading().length();
            if (indent < 0) indent = lead;
            if (lead == indent && CHILD_KEY.matcher(line).matches()) starts.add(i);
        }
        int maxId = 0;
        for (int s = 0; s < starts.size(); s++) {
            int a = starts.get(s), b = s + 1 < starts.size() ? starts.get(s + 1) : end;
            Matcher m = CHILD_KEY.matcher(lines[a]);
            String key = m.matches() ? m.group(1).trim() : "?";
            try {
                maxId = Math.max(maxId, Integer.parseInt(key));
            } catch (NumberFormatException ignored) {
                // reported by parseEntry once the entry is loaded
            }
            String where = " (lines " + (a + 1) + "-" + b + " of the broken file)";
            if (out.contains("tables." + key)) {
                skipped.add(new Skipped(key, "a second entry with the same ID" + where + "; the first one is kept"));
                continue;
            }
            StringBuilder chunk = new StringBuilder("tables:\n");
            for (int i = a; i < b; i++) chunk.append(lines[i]).append('\n');
            YamlConfiguration one = new YamlConfiguration();
            try {
                one.loadFromString(chunk.toString());
            } catch (InvalidConfigurationException | RuntimeException e) {
                skipped.add(new Skipped(key, "not valid YAML" + where + ": " + firstLine(e.getMessage())));
                continue;
            }
            ConfigurationSection tables = one.getConfigurationSection("tables");
            Object value = tables == null ? null : tables.get(key);
            if (value == null) {
                skipped.add(new Skipped(key, "could not be read" + where));
                continue;
            }
            out.set("tables." + key, value);
        }
        return maxId;
    }

    private static String stripComment(String value) {
        int hash = value.indexOf(" #");
        return (hash >= 0 ? value.substring(0, hash) : value).trim();
    }

    private static String firstLine(String message) {
        if (message == null) return "unknown error";
        String line = message.strip().split("\\r?\\n", 2)[0];
        return line.length() > 200 ? line.substring(0, 200) + "..." : line;
    }

    // ---------------------------------------------------------------------------------------
    // Writing
    // ---------------------------------------------------------------------------------------

    /** The next free table ID: past next-id and past every ID in the file, loaded or not. */
    int nextId() {
        int id = Math.max(1, doc.getInt("next-id", 1));
        ConfigurationSection tables = doc.getConfigurationSection("tables");
        if (tables != null) {
            for (String key : tables.getKeys(false)) {
                try {
                    id = Math.max(id, Integer.parseInt(key.trim()) + 1);
                } catch (NumberFormatException ignored) {
                    // not a table ID
                }
            }
        }
        doc.set("next-id", id + 1);
        return id;
    }

    /** Write (replace) one table's entry; nothing else in the file changes. */
    void put(int id, String world, int x, int y, int z, BlockFace facing, int layout, TableSettings settings) {
        String path = "tables." + id;
        doc.set(path, null);
        doc.set(path + ".world", world);
        doc.set(path + ".x", x);
        doc.set(path + ".y", y);
        doc.set(path + ".z", z);
        doc.set(path + ".facing", facing.name());
        doc.set(path + ".layout", layout);
        // only overrides are written; anything unset follows config.yml
        doc.set(path + ".seats", settings.getRawMaxSeats());
        doc.set(path + ".small-blind", settings.getRawSmallBlind());
        doc.set(path + ".big-blind", settings.getRawBigBlind());
        doc.set(path + ".min-buy-in-bb", settings.getRawMinBuyInBB());
        doc.set(path + ".max-buy-in-bb", settings.getRawMaxBuyInBB());
        doc.set(path + ".max-join-distance", settings.getRawMaxJoinDistance());
    }

    void remove(int id) {
        doc.set("tables." + id, null);
    }

    /** Save atomically (temp file, then a move), so a crash mid-write never leaves half a file. */
    boolean save() {
        if (frozen) {
            log.severe("Not saving tables.yml: it is broken and could not be moved aside (see the error at startup). "
                + "Changes to poker tables are kept until the server stops but not written.");
            return false;
        }
        if (newerFormat >= 0) {
            backup(newerFormat);
            newerFormat = -1;
        }
        doc.options().setHeader(HEADER);
        try {
            File dir = file.getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
                throw new IOException("could not create " + dir);
            }
            SafeYaml.saveAtomically(doc, file);
            return true;
        } catch (IOException | RuntimeException e) {
            log.severe("Could not save tables.yml: " + e.getMessage() + " (the previous file is unchanged)");
            return false;
        }
    }

    /** For tests. */
    YamlConfiguration document() {
        return doc;
    }
}
