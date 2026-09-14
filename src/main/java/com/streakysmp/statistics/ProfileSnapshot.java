package com.streakysmp.statistics;

import java.util.Map;
import java.util.UUID;

/**
 * Everything the profile screen shows, gathered in one worker-thread pass.
 *
 * @param playtimeSeconds includes the session in progress, not just what is stored
 * @param ranks           1-based position per statistic, 0 where unranked
 */
public record ProfileSnapshot(
        UUID uuid,
        String name,
        long balance,
        int balanceRank,
        long playtimeSeconds,
        Map<StatisticType, Long> statistics,
        Map<StatisticType, Integer> ranks) {

    public ProfileSnapshot {
        statistics = Map.copyOf(statistics);
        ranks = Map.copyOf(ranks);
    }

    public long statistic(StatisticType type) {
        return statistics.getOrDefault(type, 0L);
    }

    public int rank(StatisticType type) {
        return ranks.getOrDefault(type, 0);
    }

    public double killDeathRatio() {
        return StatisticsService.killDeathRatio(
                statistic(StatisticType.KILLS), statistic(StatisticType.DEATHS));
    }
}
