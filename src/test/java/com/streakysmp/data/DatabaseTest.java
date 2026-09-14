package com.streakysmp.data;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the transaction guarantees the economy is built on.
 *
 * <p>These run against a real SQLite file rather than a mock, because the
 * properties under test -- rollback, and what happens when two writers collide --
 * are properties of SQLite and its locking, not of our code alone.
 */
class DatabaseTest {

    private Database database;

    @BeforeEach
    void setUp(@TempDir Path temp) throws Exception {
        database = new Database(
                new File(temp.toFile(), "data"),
                Logger.getLogger("DatabaseTest"),
                ThreadGuard.offMainThread(),
                "test.db",
                8,
                5_000L);
        database.onEnable();

        database.inTransaction(connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE balance (id TEXT PRIMARY KEY, amount INTEGER NOT NULL)");
                statement.execute("INSERT INTO balance (id, amount) VALUES ('alice', 100)");
            }
            return null;
        });
    }

    @AfterEach
    void tearDown() {
        if (database != null) {
            database.onDisable();
        }
    }

    @Test
    @DisplayName("a committed transaction persists")
    void commitPersists() {
        database.inTransaction(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE balance SET amount = amount + 50 WHERE id = 'alice'")) {
                ps.executeUpdate();
            }
            return null;
        });
        assertEquals(150L, balanceOf("alice"));
    }

    @Test
    @DisplayName("a transaction that throws leaves no trace")
    void rollbackDiscardsPartialWork() {
        assertThrows(RuntimeException.class, () -> database.inTransaction(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE balance SET amount = amount - 100 WHERE id = 'alice'")) {
                ps.executeUpdate();
            }
            // Stand-in for the second half of a transfer failing: the money has
            // left one side and must not stay gone.
            throw new IllegalStateException("simulated failure after the debit");
        }));

        assertEquals(100L, balanceOf("alice"), "the debit should have been rolled back");
    }

    @Test
    @DisplayName("main-thread access is refused")
    void refusesMainThreadAccess() throws Exception {
        Database onMainThread = new Database(
                new File(System.getProperty("java.io.tmpdir"), "sc-guard-test"),
                Logger.getLogger("DatabaseTest"),
                () -> true,
                "guard.db",
                2,
                1_000L);
        try {
            onMainThread.onEnable();
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> onMainThread.withConnection(connection -> null));
            assertTrue(failure.getMessage().contains("main server thread"));
        } finally {
            onMainThread.onDisable();
        }
    }

    /**
     * The double-spend test.
     *
     * <p>Twenty threads race to withdraw 30 from a balance of 100. The guard is a
     * conditional UPDATE -- {@code WHERE amount >= 30} -- evaluated inside an
     * immediate transaction, with success determined by the affected row count.
     * Exactly three may succeed. Any other result means the pattern the economy
     * relies on does not actually hold, and concurrent purchases could mint money.
     */
    @Test
    @DisplayName("concurrent conditional withdrawals cannot overspend")
    void concurrentWithdrawalsCannotOverspend() throws Exception {
        int threads = 20;
        long withdrawal = 30L;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startLine = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();

        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> {
                startLine.await();
                boolean ok = database.inTransaction(connection -> {
                    try (PreparedStatement ps = connection.prepareStatement(
                            "UPDATE balance SET amount = amount - ? WHERE id = 'alice' AND amount >= ?")) {
                        ps.setLong(1, withdrawal);
                        ps.setLong(2, withdrawal);
                        return ps.executeUpdate() == 1;
                    }
                });
                if (ok) {
                    succeeded.incrementAndGet();
                }
                return null;
            });
        }

        List<Future<Void>> futures = new ArrayList<>();
        for (Callable<Void> task : tasks) {
            futures.add(pool.submit(task));
        }
        startLine.countDown();

        for (Future<Void> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        assertEquals(3, succeeded.get(), "only three withdrawals of 30 fit inside a balance of 100");
        assertEquals(10L, balanceOf("alice"), "the remainder must be exactly what arithmetic predicts");
    }

    @Test
    @DisplayName("concurrent increments do not lose updates")
    void concurrentIncrementsAllLand() throws Exception {
        int threads = 16;
        int perThread = 25;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startLine = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                startLine.await();
                for (int j = 0; j < perThread; j++) {
                    database.inTransaction(connection -> {
                        try (PreparedStatement ps = connection.prepareStatement(
                                "UPDATE balance SET amount = amount + 1 WHERE id = 'alice'")) {
                            ps.executeUpdate();
                        }
                        return null;
                    });
                }
                return null;
            }));
        }
        startLine.countDown();
        for (Future<?> future : futures) {
            future.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        assertEquals(100L + (long) threads * perThread, balanceOf("alice"),
                "a lost update here would mean money vanishing under load");
    }

    private long balanceOf(String id) {
        return database.withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT amount FROM balance WHERE id = ?")) {
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : -1L;
                }
            }
        });
    }
}
