package com.servercore.statistics;

import com.servercore.config.ConfigManager;
import com.servercore.core.Scheduling;
import com.servercore.core.Service;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Default {@link StatisticsService}, and the owner of playtime accounting.
 *
 * <h2>Why playtime is flushed on a timer</h2>
 * The obvious implementation records a join time and adds the difference on quit.
 * It loses everything if the server crashes, which is precisely when sessions are
 * longest and players most annoyed. Instead each online player's elapsed time is
 * written every flush interval and the marker reset, so a crash costs at most one
 * interval rather than an entire evening.
 *
 * <p>Wall-clock milliseconds are used rather than a tick counter. Ticks drift
 * whenever the server lags, which is exactly when the most players are online.
 */
public final class StandardStatisticsService implements StatisticsService, ConfigManager.Reloadable {

    private final StatisticsRepository repository;
    private final Scheduling scheduling;
    private final Logger logger;

    /** Epoch millis from which each online player's playtime has not yet been banked. */
    private final Map<UUID, Long> unbankedSince = new ConcurrentHashMap<>();

    /**
     * Counter changes accumulated in memory, written on the flush timer.
     *
     * <p>For statistics that fire often enough that one database write each would
     * matter -- mobs killed, blocks broken, blocks placed. A player mining for an
     * hour generates tens of thousands of events, and the spec is explicit that
     * constant database writes are to be avoided.
     */
    private final Map<UUID, Map<StatisticType, Long>> buffered = new ConcurrentHashMap<>();

    private volatile Duration flushInterval = Duration.ofMinutes(1);
    private volatile Duration killLogRetention = Duration.ofDays(7);

    private BukkitTask flushTask;
    private BukkitTask pruneTask;

    public StandardStatisticsService(StatisticsRepository repository,
                                     Scheduling scheduling,
                                     Logger logger) {
        this.repository = repository;
        this.scheduling = scheduling;
        this.logger = logger;
    }

    @Override
    public void load(ConfigManager.ConfigBundle configs) {
        var statistics = configs.main().section("statistics");
        this.flushInterval = statistics.getDuration("playtime-flush-interval", Duration.ofMinutes(1));
        this.killLogRetention = statistics.getDuration("kill-log-retention", Duration.ofDays(7));
    }

    @Override
    public void onEnable() {
        long intervalTicks = Math.max(20L, flushInterval.toSeconds() * 20L);
        flushTask = scheduling.syncTimer(this::bankAllOnline, intervalTicks, intervalTicks);

        // Hourly, well off the hot path.
        pruneTask = scheduling.asyncTimer(this::pruneKillLog, 20L * 60L * 5L, 20L * 60L * 60L);

        // A reload can re-enable the plugin with players already connected.
        for (Player player : Bukkit.getOnlinePlayers()) {
            beginSession(player.getUniqueId());
        }
    }

    @Override
    public void onDisable() {
        if (flushTask != null) {
            flushTask.cancel();
            flushTask = null;
        }
        if (pruneTask != null) {
            pruneTask.cancel();
            pruneTask = null;
        }
        // Bank synchronously: this is a clean shutdown and the time is owed.
        try {
            bankAllOnlineBlocking();
        } catch (Exception e) {
            logger.log(Level.WARNING, "Could not bank playtime during shutdown", e);
        }
        unbankedSince.clear();
    }

    // ------------------------------------------------------------ playtime

    /** Starts counting for a player who has just joined. */
    public void beginSession(UUID player) {
        unbankedSince.put(player, System.currentTimeMillis());
    }

    /** Banks the final slice for a player who is leaving. */
    public void endSession(UUID player) {
        Long since = unbankedSince.remove(player);
        if (since == null) {
            return;
        }
        long seconds = elapsedSeconds(since);
        if (seconds > 0) {
            increment(player, StatisticType.PLAYTIME, seconds);
        }
    }

    /**
     * Adds to a counter in memory, to be written on the next flush.
     *
     * <p>Use for anything that fires more than a few times a minute. The value
     * read back by {@link #value} will lag by up to one flush interval, which is
     * acceptable for a statistic and would not be for a balance.
     */
    public void incrementBuffered(UUID player, StatisticType type, long delta) {
        if (delta == 0) {
            return;
        }
        buffered.computeIfAbsent(player, ignored -> new ConcurrentHashMap<>())
                .merge(type, delta, Long::sum);
    }

    private void bankAllOnline() {
        Map<UUID, Long> playtime = collectDue();
        Map<UUID, Map<StatisticType, Long>> counters = drainBuffered();
        if (playtime.isEmpty() && counters.isEmpty()) {
            return;
        }
        scheduling.runAsync(() -> writeAll(playtime, counters))
                .exceptionally(error -> {
                    logger.log(Level.WARNING, "Statistics flush failed", error);
                    return null;
                });
    }

    private void bankAllOnlineBlocking() {
        writeAll(collectDue(), drainBuffered());
    }

    private void writeAll(Map<UUID, Long> playtime, Map<UUID, Map<StatisticType, Long>> counters) {
        playtime.forEach((player, seconds) ->
                repository.increment(player, StatisticType.PLAYTIME, seconds));
        counters.forEach(repository::incrementAll);
    }

    /**
     * Takes everything buffered and clears it.
     *
     * <p>Removing as it reads means a concurrent increment either lands in this
     * batch or in the next one, never in both and never in neither.
     */
    private Map<UUID, Map<StatisticType, Long>> drainBuffered() {
        if (buffered.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Map<StatisticType, Long>> drained = new HashMap<>();
        for (UUID player : List.copyOf(buffered.keySet())) {
            Map<StatisticType, Long> counters = buffered.remove(player);
            if (counters != null && !counters.isEmpty()) {
                drained.put(player, Map.copyOf(counters));
            }
        }
        return drained;
    }

    /**
     * Takes the elapsed time for every online player and resets their markers.
     *
     * <p>Resetting as the value is taken is what makes this safe to call from a
     * timer: a slow flush cannot double-count, because the time it banked is no
     * longer owed.
     */
    private Map<UUID, Long> collectDue() {
        Map<UUID, Long> due = new HashMap<>();
        long now = System.currentTimeMillis();
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID id = player.getUniqueId();
            Long since = unbankedSince.get(id);
            if (since == null) {
                unbankedSince.put(id, now);
                continue;
            }
            long seconds = Math.max(0L, (now - since) / 1_000L);
            if (seconds > 0) {
                due.put(id, seconds);
                // Carry the sub-second remainder forward instead of discarding it.
                unbankedSince.put(id, since + seconds * 1_000L);
            }
        }
        return due;
    }

    private static long elapsedSeconds(long since) {
        return Math.max(0L, (System.currentTimeMillis() - since) / 1_000L);
    }

    @Override
    public long livePlaytimeSeconds(UUID player) {
        long stored = repository.value(player, StatisticType.PLAYTIME);
        Long since = unbankedSince.get(player);
        return since == null ? stored : stored + elapsedSeconds(since);
    }

    // ---------------------------------------------------------- statistics

    @Override
    public long value(UUID player, StatisticType type) {
        return repository.value(player, type);
    }

    @Override
    public Map<StatisticType, Long> allFor(UUID player) {
        return repository.allFor(player);
    }

    @Override
    public void increment(UUID player, StatisticType type, long delta) {
        if (delta == 0) {
            return;
        }
        scheduling.runAsync(() -> repository.increment(player, type, delta))
                .exceptionally(error -> {
                    logger.log(Level.WARNING,
                            "Could not record " + type.key() + " for " + player, error);
                    return null;
                });
    }

    @Override
    public List<StatisticsRepository.Ranked> top(StatisticType type, int limit) {
        return repository.top(type, limit);
    }

    @Override
    public int rankOf(UUID player, StatisticType type) {
        return repository.rankOf(player, type);
    }

    private void pruneKillLog() {
        try {
            long cutoff = System.currentTimeMillis() - killLogRetention.toMillis();
            int removed = repository.pruneKillsBefore(cutoff);
            if (removed > 0) {
                logger.fine("Pruned " + removed + " expired kill-log rows.");
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "Kill-log prune failed", e);
        }
    }

    public StatisticsRepository repository() {
        return repository;
    }

    /** Services this one implements, for the registry. */
    public static Class<? extends Service> serviceType() {
        return StatisticsService.class;
    }
}
