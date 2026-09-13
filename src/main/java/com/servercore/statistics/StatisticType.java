package com.servercore.statistics;

import java.util.Locale;
import java.util.Optional;

/**
 * A tracked counter.
 *
 * <p>{@link #key()} is what is persisted, so keys are frozen once shipped. New
 * statistics are new constants; the storage shape absorbs them without a
 * migration.
 *
 * @param unit how the raw value should be rendered
 */
public enum StatisticType {

    KILLS("kills", "Kills", Unit.COUNT),
    DEATHS("deaths", "Deaths", Unit.COUNT),

    /**
     * Playtime in seconds.
     *
     * <p>Seconds rather than ticks: a tick counter drifts whenever the server
     * lags, which it does exactly when players are most active, and the spec
     * explicitly rules out relying on one.
     */
    PLAYTIME("playtime_seconds", "Playtime", Unit.SECONDS),

    // --- Reserved for later phases. Declared now so the keys are settled. ----
    BLOCKS_BROKEN("blocks_broken", "Blocks broken", Unit.COUNT),
    BLOCKS_PLACED("blocks_placed", "Blocks placed", Unit.COUNT),
    ITEMS_BOUGHT("items_bought", "Items bought", Unit.COUNT),
    ITEMS_SOLD("items_sold", "Items sold", Unit.COUNT),
    MONEY_EARNED("money_earned", "Money earned", Unit.MONEY),
    MONEY_SPENT("money_spent", "Money spent", Unit.MONEY),
    MOBS_KILLED("mobs_killed", "Mobs killed", Unit.COUNT);

    public enum Unit {
        COUNT,
        SECONDS,
        MONEY
    }

    private final String key;
    private final String displayName;
    private final Unit unit;

    StatisticType(String key, String displayName, Unit unit) {
        this.key = key;
        this.displayName = displayName;
        this.unit = unit;
    }

    /** The persisted key. Never change one that has shipped. */
    public String key() {
        return key;
    }

    public String displayName() {
        return displayName;
    }

    public Unit unit() {
        return unit;
    }

    public static Optional<StatisticType> byKey(String key) {
        if (key == null) {
            return Optional.empty();
        }
        String needle = key.trim().toLowerCase(Locale.ROOT);
        for (StatisticType type : values()) {
            if (type.key.equals(needle) || type.name().toLowerCase(Locale.ROOT).equals(needle)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}
