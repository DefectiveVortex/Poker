package com.vortex.poker.table;

import com.vortex.poker.config.ConfigManager;

import java.util.Locale;

/**
 * Per-table overrides. A null field follows the global value in config.yml.
 */
public class TableSettings {

    private Integer maxSeats;
    private Long smallBlind;
    private Long bigBlind;
    private Integer minBuyInBB;
    private Integer maxBuyInBB;
    private Double maxJoinDistance;

    /** Every field follows config.yml. */
    public TableSettings() {}

    public TableSettings(Integer maxSeats, Long smallBlind, Long bigBlind,
                         Integer minBuyInBB, Integer maxBuyInBB, Double maxJoinDistance) {
        this.maxSeats = maxSeats;
        this.smallBlind = smallBlind;
        this.bigBlind = bigBlind;
        this.minBuyInBB = minBuyInBB;
        this.maxBuyInBB = maxBuyInBB;
        this.maxJoinDistance = maxJoinDistance;
    }

    // -------------------------------------------------------------------------
    // Resolved getters: always a usable value
    // -------------------------------------------------------------------------

    public int getMaxSeats(ConfigManager cfg) {
        return TableLayout.clampSeats(maxSeats != null ? maxSeats : cfg.getMaxSeats());
    }

    public long getSmallBlind(ConfigManager cfg) {
        return Math.max(1, smallBlind != null ? smallBlind : cfg.getDefaultSmallBlind());
    }

    /** Never below the small blind. */
    public long getBigBlind(ConfigManager cfg) {
        long bb = bigBlind != null ? bigBlind : cfg.getDefaultBigBlind();
        return Math.max(getSmallBlind(cfg), bb);
    }

    public int getMinBuyInBB(ConfigManager cfg) {
        return Math.max(1, minBuyInBB != null ? minBuyInBB : cfg.getDefaultMinBuyInBB());
    }

    /** Never below the minimum buy-in. */
    public int getMaxBuyInBB(ConfigManager cfg) {
        int max = maxBuyInBB != null ? maxBuyInBB : cfg.getDefaultMaxBuyInBB();
        return Math.max(getMinBuyInBB(cfg), max);
    }

    /** Minimum buy-in in currency. */
    public long getMinBuyIn(ConfigManager cfg) {
        return getMinBuyInBB(cfg) * getBigBlind(cfg);
    }

    /** Maximum buy-in (and the most a stack can be topped up to) in currency. */
    public long getMaxBuyIn(ConfigManager cfg) {
        return getMaxBuyInBB(cfg) * getBigBlind(cfg);
    }

    public double getMaxJoinDistance(ConfigManager cfg) {
        return maxJoinDistance != null ? maxJoinDistance : cfg.getMaxJoinDistance();
    }

    // -------------------------------------------------------------------------
    // Raw getters for tables.yml (null = not set)
    // -------------------------------------------------------------------------

    public Integer getRawMaxSeats()        { return maxSeats; }
    public Long    getRawSmallBlind()      { return smallBlind; }
    public Long    getRawBigBlind()        { return bigBlind; }
    public Integer getRawMinBuyInBB()      { return minBuyInBB; }
    public Integer getRawMaxBuyInBB()      { return maxBuyInBB; }
    public Double  getRawMaxJoinDistance() { return maxJoinDistance; }

    public void setMaxSeats(Integer v)        { this.maxSeats = v; }
    public void setSmallBlind(Long v)         { this.smallBlind = v; }
    public void setBigBlind(Long v)           { this.bigBlind = v; }
    public void setMinBuyInBB(Integer v)      { this.minBuyInBB = v; }
    public void setMaxBuyInBB(Integer v)      { this.maxBuyInBB = v; }
    public void setMaxJoinDistance(Double v)  { this.maxJoinDistance = v; }

    /** Copy every field that is set in {@code other} over this one. */
    public void apply(TableSettings other) {
        if (other.maxSeats != null) maxSeats = other.maxSeats;
        if (other.smallBlind != null) smallBlind = other.smallBlind;
        if (other.bigBlind != null) bigBlind = other.bigBlind;
        if (other.minBuyInBB != null) minBuyInBB = other.minBuyInBB;
        if (other.maxBuyInBB != null) maxBuyInBB = other.maxBuyInBB;
        if (other.maxJoinDistance != null) maxJoinDistance = other.maxJoinDistance;
    }

    // -------------------------------------------------------------------------
    // Validation
    // -------------------------------------------------------------------------

    /** A translated description of what's wrong, or null if the settings are usable. */
    public String validate(ConfigManager cfg) {
        if (maxSeats != null && (maxSeats < TableLayout.MIN_SEATS || maxSeats > TableLayout.MAX_SEATS)) {
            return cfg.formatMessage("table-error-max-seats-range", "max", TableLayout.MAX_SEATS);
        }
        // Blind and buy-in pairs are checked unclamped, so a typo isn't silently "fixed"
        long sb = smallBlind != null ? smallBlind : cfg.getDefaultSmallBlind();
        long bb = bigBlind != null ? bigBlind : cfg.getDefaultBigBlind();
        if (sb > bb) {
            return cfg.formatMessage("table-error-min-exceeds-max", "min", sb, "max", bb);
        }
        int lo = minBuyInBB != null ? minBuyInBB : cfg.getDefaultMinBuyInBB();
        int hi = maxBuyInBB != null ? maxBuyInBB : cfg.getDefaultMaxBuyInBB();
        if (lo > hi) {
            return cfg.formatMessage("table-error-min-exceeds-max", "min", lo, "max", hi);
        }
        if (maxJoinDistance != null && maxJoinDistance < 1.0) {
            return cfg.getMessage("table-error-min-distance");
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Parsing
    // -------------------------------------------------------------------------

    /** Setting names accepted by {@link #parseArgs}, for tab completion. */
    public static final java.util.List<String> KEYS = java.util.List.of(
        "seats", "small-blind", "big-blind", "min-buy-in", "max-buy-in", "max-join-distance");

    /**
     * Parse "key:value" tokens from {@code startIndex} on (see {@link #KEYS}; buy-ins are in big
     * blinds). On error, writes a description into {@code errorOut} and returns null.
     */
    public static TableSettings parseArgs(String[] tokens, int startIndex,
                                          ConfigManager cfg, StringBuilder errorOut) {
        TableSettings s = new TableSettings();
        for (int i = startIndex; i < tokens.length; i++) {
            String tok = tokens[i];
            int colon = tok.indexOf(':');
            if (colon < 0) {
                errorOut.append(cfg.formatMessage("table-error-invalid-format", "arg", tok));
                return null;
            }
            String key = tok.substring(0, colon).toLowerCase(Locale.ROOT);
            String val = tok.substring(colon + 1);
            try {
                switch (key) {
                    case "seats", "max-seats"  -> s.setMaxSeats(parsePositiveInt(val));
                    case "small-blind", "sb"   -> s.setSmallBlind(parsePositiveLong(val));
                    case "big-blind", "bb"     -> s.setBigBlind(parsePositiveLong(val));
                    case "min-buy-in"          -> s.setMinBuyInBB(parsePositiveInt(val));
                    case "max-buy-in"          -> s.setMaxBuyInBB(parsePositiveInt(val));
                    case "max-join-distance"   -> s.setMaxJoinDistance(parsePositiveDouble(val));
                    default -> {
                        errorOut.append(cfg.formatMessage("table-error-unknown-setting", "setting", key));
                        return null;
                    }
                }
            } catch (NumberFormatException e) {
                errorOut.append(cfg.formatMessage("table-error-invalid-value", "setting", key, "value", val));
                return null;
            }
        }
        String err = s.validate(cfg);
        if (err != null) {
            errorOut.append(err);
            return null;
        }
        return s;
    }

    private static int parsePositiveInt(String s) {
        int v = Integer.parseInt(s);
        if (v <= 0) throw new NumberFormatException("must be positive");
        return v;
    }

    private static long parsePositiveLong(String s) {
        long v = Long.parseLong(s);
        if (v <= 0) throw new NumberFormatException("must be positive");
        return v;
    }

    private static double parsePositiveDouble(String s) {
        double v = Double.parseDouble(s);
        if (v <= 0 || Double.isNaN(v) || Double.isInfinite(v)) throw new NumberFormatException("must be positive");
        return v;
    }
}
