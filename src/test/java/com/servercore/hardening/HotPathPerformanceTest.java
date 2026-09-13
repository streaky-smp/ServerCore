package com.servercore.hardening;

import com.servercore.claim.Claim;
import com.servercore.claim.ClaimIndex;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The claim lookup on the block-event path, measured rather than asserted.
 *
 * <p>{@link ClaimIndex#claimAt} runs on the main thread for every block break,
 * block place and interaction on the server. It is the one piece of this plugin
 * whose cost is multiplied by player activity rather than by player count, so it
 * is the one worth measuring at a size no test server will ever reach.
 *
 * <p>The budgets below are deliberately loose -- roughly two orders of magnitude
 * above what the chunk-bucketed index actually costs. They are not benchmarks and
 * they are not tuned to this machine. They exist to fail loudly if the lookup
 * ever degrades into a scan over every claim on the server, which is the specific
 * regression that would take a busy server down and which would still pass every
 * other test in this suite.
 */
class HotPathPerformanceTest {

    /** Far more claims than a real server carries; a scan here would be obvious. */
    private static final int CLAIMS = 50_000;
    /** Roughly a minute of block events on a busy server, done in one burst. */
    private static final int LOOKUPS = 200_000;
    private static final String WORLD = "world";

    private static ClaimIndex index;

    @BeforeAll
    static void buildIndex() {
        index = new ClaimIndex();
        // A 250x200 grid of 32x32 claims with an 8-block gap, so lookups land
        // both inside claims and in the unclaimed gaps between them.
        int placed = 0;
        for (int gx = 0; placed < CLAIMS && gx < 250; gx++) {
            for (int gz = 0; placed < CLAIMS && gz < 200; gz++) {
                int minX = gx * 40;
                int minZ = gz * 40;
                index.put(Claim.of("claim-" + placed, UUID.randomUUID(), WORLD,
                        minX, minZ, minX + 31, minZ + 31, "Claim " + placed, 0L, 0L));
                placed++;
            }
        }
        assertEquals(CLAIMS, index.size());
    }

    @Test
    @DisplayName("a block-event lookup stays constant-time as claims scale")
    void lookupIsNotAScan() {
        // Warm the JIT; the first few thousand calls are interpreted.
        for (int i = 0; i < 20_000; i++) {
            index.claimAt(WORLD, i % 10_000, (i * 7) % 8_000);
        }

        int hits = 0;
        long start = System.nanoTime();
        for (int i = 0; i < LOOKUPS; i++) {
            // Deliberately scattered, so no single chunk bucket stays hot in cache.
            int x = (i * 37) % 10_000;
            int z = (i * 91) % 8_000;
            if (index.claimAt(WORLD, x, z).isPresent()) {
                hits++;
            }
        }
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000L;

        // Both outcomes must occur, or the loop is not exercising the real path.
        assertTrue(hits > 0, "some lookups should land inside a claim");
        assertTrue(hits < LOOKUPS, "some lookups should land in an unclaimed gap");

        // A scan over 50,000 claims per lookup would need minutes here.
        assertTrue(elapsedMillis < 2_000L,
                LOOKUPS + " lookups against " + CLAIMS + " claims took " + elapsedMillis
                        + "ms; the chunk index has probably degraded into a scan");
    }

    @Test
    @DisplayName("a miss in an unindexed world costs nothing")
    void missingWorldIsCheap() {
        long start = System.nanoTime();
        for (int i = 0; i < LOOKUPS; i++) {
            assertTrue(index.claimAt("nether", i, i).isEmpty());
        }
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000L;
        assertTrue(elapsedMillis < 1_000L,
                "lookups in an unclaimed world took " + elapsedMillis + "ms");
    }

    @Test
    @DisplayName("concurrent reads during a write do not corrupt or throw")
    void readsSurviveConcurrentWrites() throws Exception {
        // Claims are written by the async database thread and read by the main
        // thread on every block event. The index must tolerate that overlap; a
        // plain HashMap here would throw or silently return nonsense.
        ClaimIndex live = new ClaimIndex();
        for (int i = 0; i < 1_000; i++) {
            int minX = i * 40;
            live.put(Claim.of("live-" + i, UUID.randomUUID(), WORLD,
                    minX, 0, minX + 31, 31, "Live " + i, 0L, 0L));
        }

        ExecutorService pool = Executors.newFixedThreadPool(5);
        AtomicInteger failures = new AtomicInteger();
        try {
            Future<?> writer = pool.submit(() -> {
                for (int i = 1_000; i < 3_000; i++) {
                    int minX = i * 40;
                    live.put(Claim.of("live-" + i, UUID.randomUUID(), WORLD,
                            minX, 0, minX + 31, 31, "Live " + i, 0L, 0L));
                    live.remove("live-" + (i - 1_000));
                }
            });

            for (int t = 0; t < 4; t++) {
                pool.submit(() -> {
                    try {
                        for (int i = 0; i < 50_000; i++) {
                            // The result is allowed to change under us; throwing is not.
                            live.claimAt(WORLD, (i * 13) % 120_000, i % 32);
                        }
                    } catch (RuntimeException e) {
                        failures.incrementAndGet();
                    }
                });
            }
            writer.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "readers did not finish");
        }

        assertEquals(0, failures.get(), "a concurrent read threw while the index was written");
    }
}
