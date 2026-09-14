package com.streakysmp.auction;

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
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The auction house's concurrency guards.
 *
 * <p>Every duplication risk in an auction house is a race: two buyers on one
 * listing, a cancel landing at the same moment as a purchase, two clicks on a
 * collect button. All three are resolved by conditional updates, and all three are
 * tested here against a real database.
 */
class AuctionRepositoryTest {

    private static final Logger LOGGER = Logger.getLogger("AuctionRepositoryTest");

    private Database database;
    private AuctionRepository auctions;
    private PlayerRepository players;

    private UUID seller;
    private UUID buyer;

    @BeforeEach
    void setUp(@TempDir Path temp) throws Exception {
        database = new Database(new File(temp.toFile(), "data"), LOGGER,
                ThreadGuard.offMainThread(), "auction.db", 8, 5_000L);
        database.onEnable();
        new SchemaMigrator(database, LOGGER, Schema.migrations()).migrate();

        auctions = new AuctionRepository(database);
        players = new PlayerRepository(database);

        seller = newPlayer("Seller");
        buyer = newPlayer("Buyer");
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

    /** Sells inside a transaction, returning a plain boolean for assertions. */
    private boolean sell(String listingId, UUID who, long now) {
        Boolean result = database.inTransaction(c ->
                AuctionRepository.trySell(c, listingId, who, now));
        return result;
    }

    private boolean sell(String listingId, UUID who) {
        return sell(listingId, who, System.currentTimeMillis());
    }

    private boolean cancel(String listingId, UUID who) {
        Boolean result = database.inTransaction(c ->
                AuctionRepository.tryCancel(c, listingId, who));
        return result;
    }

    /** Item bytes are opaque here; the status machine is what is under test. */
    private AuctionListing insert(String id, long price, long expiresInMillis) {
        long now = System.currentTimeMillis();
        AuctionListing listing = new AuctionListing(id, seller,
                new byte[]{1, 2, 3, 4}, "diamond", 16, price, "16x Diamond",
                now, now + expiresInMillis, ListingStatus.ACTIVE, null, null, false);
        database.inTransaction(connection -> {
            AuctionRepository.insertWithin(connection, listing);
            return null;
        });
        return listing;
    }

    // -------------------------------------------------------------- basics

    @Test
    @DisplayName("a listing round-trips with its item bytes intact")
    void listingPersists() {
        insert("l1", 500_00L, Duration.ofDays(2).toMillis());

        AuctionListing loaded = auctions.byId("l1").orElseThrow();
        assertEquals(seller, loaded.seller());
        assertEquals(16, loaded.quantity());
        assertEquals(500_00L, loaded.price());
        assertEquals(ListingStatus.ACTIVE, loaded.status());
        assertFalse(loaded.collected());
        assertNull(loaded.buyer());
        org.junit.jupiter.api.Assertions.assertArrayEquals(
                new byte[]{1, 2, 3, 4}, loaded.itemData(),
                "item bytes must survive exactly, or enchantments are silently stripped");
    }

    @Test
    @DisplayName("unit price is derived, not stored")
    void unitPriceIsDerived() {
        AuctionListing listing = insert("l1", 1_600_00L, Duration.ofDays(2).toMillis());
        assertEquals(100_00L, listing.unitPrice(), "1600 for 16 items is 100 each");
    }

    @Test
    @DisplayName("a listing is only purchasable while active and unexpired")
    void purchasabilityDependsOnTimeAndStatus() {
        long now = System.currentTimeMillis();
        AuctionListing active = insert("l1", 100L, Duration.ofHours(1).toMillis());
        assertTrue(active.purchasableAt(now));
        assertFalse(active.hasExpired(now));

        AuctionListing overdue = insert("l2", 100L, -1_000L);
        assertFalse(overdue.purchasableAt(now));
        assertTrue(overdue.hasExpired(now));
    }

    // -------------------------------------------------------- the buy race

    /**
     * The headline test. Twenty buyers click the same listing simultaneously.
     * Exactly one may succeed; if two did, the item would be handed out twice.
     */
    @Test
    @DisplayName("concurrent buyers: exactly one wins the listing")
    void concurrentBuyersExactlyOneWins() throws Exception {
        insert("race", 100_00L, Duration.ofDays(1).toMillis());

        int threads = 20;
        List<UUID> buyers = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            buyers.add(newPlayer("Buyer" + i));
        }

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startLine = new CountDownLatch(1);
        AtomicInteger won = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();

        for (UUID candidate : buyers) {
            futures.add(pool.submit(() -> {
                startLine.await();
                if (sell("race", candidate)) {
                    won.incrementAndGet();
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

        assertEquals(1, won.get(), "two winners would mean the item is handed out twice");

        AuctionListing sold = auctions.byId("race").orElseThrow();
        assertEquals(ListingStatus.SOLD, sold.status());
        assertTrue(buyers.contains(sold.buyer()), "the recorded buyer must be one of the racers");
    }

    @Test
    @DisplayName("an expired listing cannot be bought, even if still marked active")
    void expiredListingCannotBeBought() {
        insert("l1", 100L, -1_000L);

        boolean bought = sell("l1", buyer);

        assertFalse(bought, "the time check lives in the same statement as the claim");
        assertEquals(ListingStatus.ACTIVE, auctions.byId("l1").orElseThrow().status());
    }

    @Test
    @DisplayName("a sold listing cannot be bought again")
    void soldListingCannotBeResold() {
        insert("l1", 100L, Duration.ofDays(1).toMillis());
        long now = System.currentTimeMillis();

        assertTrue(sell("l1", buyer, now));
        UUID other = newPlayer("Other");
        assertFalse(sell("l1", other, now));

        assertEquals(buyer, auctions.byId("l1").orElseThrow().buyer());
    }

    /**
     * A seller cancelling at the exact moment a buyer purchases. Only one may
     * take effect, or the seller reclaims an item somebody has paid for.
     */
    @Test
    @DisplayName("cancel and buy cannot both succeed on one listing")
    void cancelAndBuyAreMutuallyExclusive() throws Exception {
        insert("contested", 100L, Duration.ofDays(1).toMillis());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch startLine = new CountDownLatch(1);
        AtomicInteger bought = new AtomicInteger();
        AtomicInteger cancelled = new AtomicInteger();

        Future<?> buyTask = pool.submit(() -> {
            startLine.await();
            if (sell("contested", buyer)) {
                bought.incrementAndGet();
            }
            return null;
        });
        Future<?> cancelTask = pool.submit(() -> {
            startLine.await();
            if (cancel("contested", seller)) {
                cancelled.incrementAndGet();
            }
            return null;
        });

        startLine.countDown();
        buyTask.get(30, TimeUnit.SECONDS);
        cancelTask.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertEquals(1, bought.get() + cancelled.get(),
                "exactly one of buy and cancel may take effect");
    }

    @Test
    @DisplayName("only the seller may cancel")
    void onlySellerCancels() {
        insert("l1", 100L, Duration.ofDays(1).toMillis());
        assertFalse(cancel("l1", buyer));
        assertTrue(cancel("l1", seller));
    }

    // ----------------------------------------------------- collection race

    @Test
    @DisplayName("a sold item is owed to the buyer, an expired one to the seller")
    void ownershipFollowsStatus() {
        insert("sold", 100L, Duration.ofDays(1).toMillis());
        sell("sold", buyer);

        AuctionListing soldListing = auctions.byId("sold").orElseThrow();
        assertEquals(buyer, soldListing.owedTo());
        assertTrue(soldListing.isOwedTo(buyer));
        assertFalse(soldListing.isOwedTo(seller));

        insert("expired", 100L, -1_000L);
        auctions.expireOverdue(System.currentTimeMillis());
        AuctionListing expiredListing = auctions.byId("expired").orElseThrow();
        assertEquals(ListingStatus.EXPIRED, expiredListing.status());
        assertEquals(seller, expiredListing.owedTo(),
                "an unsold item must come back to whoever listed it");
    }

    /**
     * Two clicks on a collect button. Only the first may claim, or a shop's
     * entire consignment is duplicated.
     */
    @Test
    @DisplayName("an item can only be collected once")
    void collectionIsSingleUse() {
        insert("l1", 100L, Duration.ofDays(1).toMillis());
        sell("l1", buyer);

        assertTrue(auctions.tryCollect("l1", buyer), "the first claim wins");
        assertFalse(auctions.tryCollect("l1", buyer), "the second must fail");
        assertTrue(auctions.byId("l1").orElseThrow().collected());
    }

    @Test
    @DisplayName("the wrong player cannot collect someone else's item")
    void wrongPlayerCannotCollect() {
        insert("l1", 100L, Duration.ofDays(1).toMillis());
        sell("l1", buyer);

        assertFalse(auctions.tryCollect("l1", seller),
                "the seller has been paid; the item is the buyer's");
        assertTrue(auctions.tryCollect("l1", buyer));
    }

    @Test
    @DisplayName("concurrent collect attempts yield exactly one claim")
    void concurrentCollectYieldsOneClaim() throws Exception {
        insert("l1", 100L, Duration.ofDays(1).toMillis());
        sell("l1", buyer);

        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startLine = new CountDownLatch(1);
        AtomicInteger claimed = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                startLine.await();
                if (auctions.tryCollect("l1", buyer)) {
                    claimed.incrementAndGet();
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

        assertEquals(1, claimed.get());
    }

    // ------------------------------------------------------- sweep, queries

    @Test
    @DisplayName("the sweep expires overdue listings and leaves current ones alone")
    void sweepExpiresOnlyOverdue() {
        insert("current", 100L, Duration.ofDays(1).toMillis());
        insert("overdue", 100L, -1_000L);

        assertEquals(1, auctions.expireOverdue(System.currentTimeMillis()));
        assertEquals(ListingStatus.ACTIVE, auctions.byId("current").orElseThrow().status());
        assertEquals(ListingStatus.EXPIRED, auctions.byId("overdue").orElseThrow().status());
    }

    @Test
    @DisplayName("purging never removes anything still owed to somebody")
    void purgeSparesUncollected() {
        insert("uncollected", 100L, -1_000L);
        auctions.expireOverdue(System.currentTimeMillis());

        int purged = auctions.purgeSettledBefore(System.currentTimeMillis() + 1_000L);
        assertEquals(0, purged, "an uncollected item must never be pruned");
        assertTrue(auctions.byId("uncollected").isPresent());

        auctions.tryCollect("uncollected", seller);
        assertEquals(1, auctions.purgeSettledBefore(System.currentTimeMillis() + 1_000L));
    }

    @Test
    @DisplayName("active browse excludes sold and expired listings")
    void browseShowsOnlyActive() {
        insert("active", 100L, Duration.ofDays(1).toMillis());
        insert("sold", 100L, Duration.ofDays(1).toMillis());
        insert("overdue", 100L, -1_000L);

        sell("sold", buyer);

        List<AuctionListing> active = auctions.active(System.currentTimeMillis(), 50);
        assertEquals(1, active.size());
        assertEquals("active", active.getFirst().id());
        assertEquals(1, auctions.activeCount(System.currentTimeMillis()));
    }

    @Test
    @DisplayName("the listing cap counts only active listings")
    void listingCapCountsActiveOnly() {
        insert("a", 100L, Duration.ofDays(1).toMillis());
        insert("b", 100L, Duration.ofDays(1).toMillis());
        assertEquals(2, auctions.countActiveBySeller(seller));

        cancel("a", seller);
        assertEquals(1, auctions.countActiveBySeller(seller),
                "a cancelled listing must free up a slot");
    }

    @Test
    @DisplayName("the collection list covers purchases and returns together")
    void collectionListCoversBothDirections() {
        insert("bought", 100L, Duration.ofDays(1).toMillis());
        sell("bought", buyer);

        insert("returned", 100L, -1_000L);
        auctions.expireOverdue(System.currentTimeMillis());

        assertEquals(1, auctions.awaitingCollectionCount(buyer));
        assertEquals(1, auctions.awaitingCollectionCount(seller));
        assertEquals("bought", auctions.awaitingCollection(buyer).getFirst().id());
        assertEquals("returned", auctions.awaitingCollection(seller).getFirst().id());
    }

    @Test
    @DisplayName("fees and tax are computed by the settings, and the tax is a sink")
    void feeAndTaxArithmetic() {
        AuctionSettings settings = new AuctionSettings(true, 100L, 2.0d, 5.0d,
                Duration.ofDays(2), 7, 1L, 1_000_000L,
                Duration.ofMinutes(5), Duration.ofDays(30), false);

        // 2% of 10000 plus a flat 100.
        assertEquals(300L, settings.listingFeeFor(10_000L));
        // 5% of 10000.
        assertEquals(500L, settings.salesTaxFor(10_000L));
        assertEquals(9_500L, settings.sellerProceeds(10_000L),
                "the buyer pays the full price; the difference leaves circulation");
    }
}
