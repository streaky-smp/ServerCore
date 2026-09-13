package com.servercore.statistics;

import com.servercore.core.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Persistent player statistics.
 *
 * <p>Reads block and must run off the main thread. Writes are fire-and-forget and
 * may be called from anywhere: a kill or a block break must never wait on the
 * database to be counted.
 */
public interface StatisticsService extends Service {

    /** Stored value of a counter. */
    long value(UUID player, StatisticType type);

    /** Every stored counter for a player. */
    Map<StatisticType, Long> allFor(UUID player);

    /**
     * Adds to a counter asynchronously.
     *
     * <p>Safe from the main thread. Returns immediately; the write lands shortly
     * afterwards.
     */
    void increment(UUID player, StatisticType type, long delta);

    List<StatisticsRepository.Ranked> top(StatisticType type, int limit);

    int rankOf(UUID player, StatisticType type);

    /**
     * Playtime including the session in progress.
     *
     * <p>The stored counter only advances when a session flushes, so a player who
     * has been online for ten minutes would otherwise appear not to have gained
     * any playtime at all.
     */
    long livePlaytimeSeconds(UUID player);

    /** Kills divided by deaths, with deaths of zero treated as one. */
    static double killDeathRatio(long kills, long deaths) {
        return deaths <= 0 ? kills : (double) kills / deaths;
    }
}
