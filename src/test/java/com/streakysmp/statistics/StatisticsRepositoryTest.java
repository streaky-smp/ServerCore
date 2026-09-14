package com.streakysmp.statistics;

import com.streakysmp.data.Database;
import com.streakysmp.data.PlayerRecord;
import com.streakysmp.data.PlayerRepository;
import com.streakysmp.data.Schema;
import com.streakysmp.data.SchemaMigrator;
import com.streakysmp.data.ThreadGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatisticsRepositoryTest {

    private static final Logger LOGGER = Logger.getLogger("StatisticsRepositoryTest");

    private Database database;
    private StatisticsRepository statistics;
    private PlayerRepository players;

    private UUID alice;
    private UUID bob;

    @BeforeEach
    void setUp(@TempDir Path temp) throws Exception {
        database = new Database(new File(temp.toFile(), "data"), LOGGER,
                ThreadGuard.offMainThread(), "stats.db", 8, 5_000L);
        database.onEnable();
        new SchemaMigrator(database, LOGGER, Schema.migrations()).migrate();

        statistics = new StatisticsRepository(database);
        players = new PlayerRepository(database);

        alice = newPlayer("Alice");
        bob = newPlayer("Bob");
    }

    @AfterEach
    void tearDown() {
        if (database != null) {
            database.onDisable();
        }
    }

    private UUID newPlayer(String name) {
        UUID id = UUID.randomUUID();
        players.touch(id, name, PlayerRecord.Platform.JAVA);
        return id;
    }

    @Test
    @DisplayName("an unrecorded statistic reads as zero")
    void missingStatisticIsZero() {
        assertEquals(0L, statistics.value(alice, StatisticType.KILLS));
    }

    @Test
    @DisplayName("increments accumulate")
    void incrementsAccumulate() {
        statistics.increment(alice, StatisticType.KILLS, 1);
        statistics.increment(alice, StatisticType.KILLS, 4);
        assertEquals(5L, statistics.value(alice, StatisticType.KILLS));
    }

    @Test
    @DisplayName("statistics survive closing and reopening the database")
    void statisticsPersist() throws Exception {
        statistics.increment(alice, StatisticType.PLAYTIME, 3_600L);
        database.onDisable();
        database.onEnable();

        assertEquals(3_600L, new StatisticsRepository(database).value(alice, StatisticType.PLAYTIME),
                "playtime that does not survive a restart is worthless");
    }

    @Test
    @DisplayName("several counters can be written together")
    void incrementAllWritesEveryCounter() {
        statistics.incrementAll(alice, Map.of(
                StatisticType.KILLS, 3L,
                StatisticType.DEATHS, 1L,
                StatisticType.MOBS_KILLED, 42L));

        Map<StatisticType, Long> all = statistics.allFor(alice);
        assertEquals(3L, all.get(StatisticType.KILLS));
        assertEquals(1L, all.get(StatisticType.DEATHS));
        assertEquals(42L, all.get(StatisticType.MOBS_KILLED));
    }

    @Test
    @DisplayName("leaderboards order by value and exclude zeroes")
    void leaderboardOrdersByValue() {
        statistics.increment(alice, StatisticType.KILLS, 10);
        statistics.increment(bob, StatisticType.KILLS, 25);
        UUID carol = newPlayer("Carol");
        statistics.increment(carol, StatisticType.KILLS, 0);

        List<StatisticsRepository.Ranked> top = statistics.top(StatisticType.KILLS, 10);
        assertEquals(2, top.size(), "a player on zero should not occupy a leaderboard slot");
        assertEquals(bob, top.getFirst().uuid());
        assertEquals(25L, top.getFirst().value());
        assertEquals(1, statistics.rankOf(bob, StatisticType.KILLS));
        assertEquals(2, statistics.rankOf(alice, StatisticType.KILLS));
    }

    @Test
    @DisplayName("concurrent increments do not lose counts")
    void concurrentIncrementsAllLand() throws Exception {
        int threads = 12;
        int each = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startLine = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                startLine.await();
                for (int j = 0; j < each; j++) {
                    statistics.increment(alice, StatisticType.MOBS_KILLED, 1);
                }
                return null;
            }));
        }
        startLine.countDown();
        for (Future<?> future : futures) {
            future.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        assertEquals((long) threads * each, statistics.value(alice, StatisticType.MOBS_KILLED),
                "the increment must happen inside the UPDATE, not as read-then-write");
    }

    // ------------------------------------------------------------- anti-farm

    @Test
    @DisplayName("repeated kills of the same victim are visible to the anti-farm check")
    void killLogTracksRepeatKills() {
        long windowStart = System.currentTimeMillis() - Duration.ofMinutes(10).toMillis();

        assertEquals(0, statistics.killsOfVictimSince(alice, bob, windowStart));
        statistics.recordKill(alice, bob, true);
        assertEquals(1, statistics.killsOfVictimSince(alice, bob, windowStart));
        statistics.recordKill(alice, bob, false);
        assertEquals(2, statistics.killsOfVictimSince(alice, bob, windowStart),
                "uncounted kills must still count towards the window, or the "
                        + "cooldown resets itself by being farmed through");
    }

    @Test
    @DisplayName("the anti-farm window is per killer-victim pair")
    void killLogIsPerPair() {
        long windowStart = System.currentTimeMillis() - Duration.ofMinutes(10).toMillis();
        UUID carol = newPlayer("Carol");

        statistics.recordKill(alice, bob, true);
        statistics.recordKill(alice, bob, true);

        assertEquals(2, statistics.killsOfVictimSince(alice, bob, windowStart));
        assertEquals(0, statistics.killsOfVictimSince(alice, carol, windowStart),
                "killing a different player must not be blocked by another pair's window");
        assertEquals(0, statistics.killsOfVictimSince(bob, alice, windowStart),
                "the window is directional: being killed does not consume your own allowance");
    }

    @Test
    @DisplayName("kills outside the window no longer count against it")
    void oldKillsFallOutOfTheWindow() {
        statistics.recordKill(alice, bob, true);
        long future = System.currentTimeMillis() + Duration.ofMinutes(1).toMillis();
        assertEquals(0, statistics.killsOfVictimSince(alice, bob, future));
    }

    @Test
    @DisplayName("pruning removes old kill rows but leaves the counters alone")
    void pruningKeepsCounters() {
        statistics.increment(alice, StatisticType.KILLS, 7);
        statistics.recordKill(alice, bob, true);

        int removed = statistics.pruneKillsBefore(System.currentTimeMillis() + 1_000L);
        assertEquals(1, removed);
        assertEquals(7L, statistics.value(alice, StatisticType.KILLS),
                "the kill log is a sliding window; the kill counter is permanent");
    }

    @Test
    @DisplayName("kill/death ratio treats zero deaths as the kill count")
    void killDeathRatioHandlesZeroDeaths() {
        assertEquals(5.0, StatisticsService.killDeathRatio(5, 0));
        assertEquals(2.5, StatisticsService.killDeathRatio(5, 2));
        assertEquals(0.0, StatisticsService.killDeathRatio(0, 3));
    }
}
