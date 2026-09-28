package com.vortex.poker.util;

import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.UUID;

/**
 * Runtime feature checks for APIs newer than the oldest server we load on (1.20).
 * The plugin is compiled against 1.21.4, so every call to a newer method has to be
 * guarded by one of these flags or the JVM throws NoSuchMethodError on old servers.
 */
public final class ServerCompat {
    /** ItemMeta#setItemModel arrived in 1.21.2; the card textures depend on it. */
    public static final boolean ITEM_MODELS = hasMethod(ItemMeta.class, "setItemModel", NamespacedKey.class);

    /** Player#addResourcePack (stacking packs instead of replacing) arrived in 1.20.3. */
    public static final boolean STACKED_RESOURCE_PACKS = hasMethod(Player.class, "addResourcePack",
        UUID.class, String.class, byte[].class, String.class, boolean.class);

    /**
     * 1.20.5 moved riders onto entity attachment points. A player riding a marker armor stand now
     * has their feet 0.6 below the stand; before that it was 0.35.
     */
    public static final double STAND_ABOVE_RIDER_FEET = isAtLeast(1, 20, 5) ? 0.6 : 0.35;

    /** Height of a seated player's hips above their feet position. */
    public static final double RIDER_HIP_HEIGHT = 0.6;

    private ServerCompat() {
    }

    /** Compare against Bukkit's version string ("1.20.1-R0.1-SNAPSHOT", "26.3-..."). */
    static boolean isAtLeast(int major, int minor, int patch) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(\\d+)\\.(\\d+)(?:\\.(\\d+))?")
            .matcher(org.bukkit.Bukkit.getBukkitVersion());
        if (!m.find()) {
            return true; // unknown format: assume a current server
        }
        int[] have = {Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
            m.group(3) == null ? 0 : Integer.parseInt(m.group(3))};
        int[] want = {major, minor, patch};
        for (int i = 0; i < 3; i++) {
            if (have[i] != want[i]) {
                return have[i] > want[i];
            }
        }
        return true;
    }

    /**
     * Resolve a particle by name, trying each candidate in order. Several particles were
     * renamed in 1.20.5 (VILLAGER_HAPPY became HAPPY_VILLAGER), and referencing the new
     * constant directly would fail to link on older servers.
     */
    public static Particle particle(String... names) {
        for (String name : names) {
            if (name == null || name.isBlank()) {
                continue;
            }
            String upper = name.trim().toUpperCase(java.util.Locale.ROOT);
            for (String candidate : new String[] {upper, PARTICLE_RENAMES.get(upper)}) {
                if (candidate == null) {
                    continue;
                }
                try {
                    return Particle.valueOf(candidate);
                } catch (IllegalArgumentException ignored) {
                    // try its other name, then the next candidate
                }
            }
        }
        return null;
    }

    /** Particle enum names that changed in 1.20.5, both ways, so a config written for either era works on both. */
    static final java.util.Map<String, String> PARTICLE_RENAMES = renames(
        "EXPLOSION_NORMAL", "POOF", "EXPLOSION_LARGE", "EXPLOSION", "EXPLOSION_HUGE", "EXPLOSION_EMITTER",
        "FIREWORKS_SPARK", "FIREWORK", "WATER_BUBBLE", "BUBBLE", "WATER_SPLASH", "SPLASH", "WATER_WAKE", "FISHING",
        "SUSPENDED", "UNDERWATER", "CRIT_MAGIC", "ENCHANTED_HIT", "SMOKE_NORMAL", "SMOKE", "SMOKE_LARGE", "LARGE_SMOKE",
        "SPELL", "EFFECT", "SPELL_INSTANT", "INSTANT_EFFECT", "SPELL_MOB", "ENTITY_EFFECT", "SPELL_WITCH", "WITCH",
        "DRIP_WATER", "DRIPPING_WATER", "DRIP_LAVA", "DRIPPING_LAVA", "VILLAGER_ANGRY", "ANGRY_VILLAGER",
        "VILLAGER_HAPPY", "HAPPY_VILLAGER", "TOWN_AURA", "MYCELIUM", "ENCHANTMENT_TABLE", "ENCHANT", "REDSTONE", "DUST",
        "SNOWBALL", "ITEM_SNOWBALL", "SLIME", "ITEM_SLIME", "ITEM_CRACK", "ITEM", "BLOCK_CRACK", "BLOCK",
        "WATER_DROP", "RAIN", "MOB_APPEARANCE", "ELDER_GUARDIAN", "TOTEM", "TOTEM_OF_UNDYING");

    private static java.util.Map<String, String> renames(String... pairs) {
        java.util.Map<String, String> map = new java.util.HashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
            map.put(pairs[i + 1], pairs[i]);
        }
        return java.util.Map.copyOf(map);
    }

    private static boolean hasMethod(Class<?> type, String name, Class<?>... parameters) {
        try {
            type.getMethod(name, parameters);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }
}
