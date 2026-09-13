package com.servercore.leaderboard;

import com.servercore.statistics.StatisticType;

import java.util.Locale;
import java.util.Optional;

/**
 * Something a leaderboard can rank players by.
 *
 * <p>Separate from {@link StatisticType} because money is not a statistic -- it
 * lives in the economy's account table, not the counter table -- yet it is one of
 * the four leaderboards the spec requires. This enum is the seam that lets both
 * be ranked by the same framework.
 *
 * <p>Constants are persisted in {@code sc_leaderboard.stat}, so their names are
 * frozen once shipped.
 */
public enum LeaderboardStat {

    KILLS("Top Kills", StatisticType.KILLS, Format.COUNT),
    DEATHS("Top Deaths", StatisticType.DEATHS, Format.COUNT),
    PLAYTIME("Top Playtime", StatisticType.PLAYTIME, Format.DURATION),

    /** Ranked from the economy's accounts rather than the statistics table. */
    MONEY("Top Balance", null, Format.MONEY),

    MOBS_KILLED("Top Mob Hunters", StatisticType.MOBS_KILLED, Format.COUNT);

    /** How a raw value should be rendered on the sign. */
    public enum Format {
        COUNT,
        DURATION,
        MONEY
    }

    private final String defaultTitle;
    private final StatisticType statistic;
    private final Format format;

    LeaderboardStat(String defaultTitle, StatisticType statistic, Format format) {
        this.defaultTitle = defaultTitle;
        this.statistic = statistic;
        this.format = format;
    }

    public String defaultTitle() {
        return defaultTitle;
    }

    public Format format() {
        return format;
    }

    /** The backing counter, or empty when this ranks something else (money). */
    public Optional<StatisticType> statistic() {
        return Optional.ofNullable(statistic);
    }

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<LeaderboardStat> byKey(String key) {
        if (key == null) {
            return Optional.empty();
        }
        String needle = key.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        for (LeaderboardStat stat : values()) {
            if (stat.name().equals(needle)) {
                return Optional.of(stat);
            }
        }
        return Optional.empty();
    }
}
