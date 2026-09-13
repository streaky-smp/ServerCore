package com.servercore.playershop;

import com.servercore.data.Database;
import com.servercore.data.PlayerRecord;
import com.servercore.data.PlayerRepository;
import com.servercore.data.Schema;
import com.servercore.data.SchemaMigrator;
import com.servercore.data.ThreadGuard;
import org.bukkit.Material;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Player shop stock handling.
 *
 * <p>The item-handling half needs a live server, but the part that can duplicate
 * goods -- stock accounting under concurrent purchases -- is plain SQL and is
 * tested here properly.
 */
class PlayerShopRepositoryTest {

    private static final Logger LOGGER = Logger.getLogger("PlayerShopRepositoryTest");

    private Database database;
    private PlayerShopRepository shops;
    private PlayerRepository players;
    private UUID owner;

    @BeforeEach
    void setUp(@TempDir Path temp) throws Exception {
        database = new Database(new File(temp.toFile(), "data"), LOGGER,
                ThreadGuard.offMainThread(), "shops.db", 8, 5_000L);
        database.onEnable();
        new SchemaMigrator(database, LOGGER, Schema.migrations()).migrate();

        shops = new PlayerShopRepository(database);
        players = new PlayerRepository(database);

        owner = UUID.randomUUID();
        players.touch(owner, "Owner", PlayerRecord.Platform.JAVA);
    }

    @AfterEach
    void tearDown() {
        if (database != null) {
            database.onDisable();
        }
    }

    /** Reads current stock for the fixture shop. */
    private int stockOf(Material material) {
        Integer value = database.withConnection(c ->
                PlayerShopRepository.stockOf(c, "s1", material));
        return value;
    }

    /** Adds stock to the fixture shop, discarding the boolean result. */
    private void addStock(String shopId, Material material, int quantity) {
        database.inTransaction(c -> PlayerShopRepository.addStock(c, shopId, material, quantity));
    }

    private PlayerShop newShop(String id, String name) {
        PlayerShop shop = new PlayerShop(id, owner, name, "A test stall",
                "world", 10, 64, 20, "claim1", null, PlayerShopStatus.OPEN,
                System.currentTimeMillis(), 0L, List.of());
        shops.save(shop);
        return shop;
    }

    @Test
    @DisplayName("a shop round-trips through the database with its offers")
    void shopPersists() {
        newShop("s1", "Iron Works");
        shops.saveOffer("s1", Material.IRON_INGOT, 20_00L, 8_00L);

        PlayerShop loaded = shops.byId("s1").orElseThrow();
        assertEquals("Iron Works", loaded.name());
        assertEquals(PlayerShopStatus.OPEN, loaded.status());
        assertEquals(1, loaded.offers().size());

        ShopOffer offer = loaded.offer(Material.IRON_INGOT).orElseThrow();
        assertEquals(20_00L, offer.buyPrice());
        assertEquals(8_00L, offer.sellPrice());
        assertEquals(0, offer.stock(), "a new offer starts empty");
    }

    @Test
    @DisplayName("a shop is findable by the block customers click")
    void findableByLocation() {
        newShop("s1", "Corner Stall");
        assertTrue(shops.byLocation("world", 10, 64, 20).isPresent());
        assertTrue(shops.byLocation("world", 11, 64, 20).isEmpty());
        assertTrue(shops.byLocation("nether", 10, 64, 20).isEmpty());
    }

    @Test
    @DisplayName("updating prices leaves stock alone")
    void repricingKeepsStock() {
        newShop("s1", "Stall");
        shops.saveOffer("s1", Material.DIAMOND, 100_00L, 40_00L);
        addStock("s1", Material.DIAMOND, 32);

        shops.saveOffer("s1", Material.DIAMOND, 150_00L, 50_00L);

        ShopOffer offer = shops.byId("s1").orElseThrow().offer(Material.DIAMOND).orElseThrow();
        assertEquals(150_00L, offer.buyPrice());
        assertEquals(32, offer.stock(), "changing a price must not destroy the owner's goods");
    }

    @Test
    @DisplayName("stock cannot be taken below zero")
    void stockCannotGoNegative() {
        newShop("s1", "Stall");
        shops.saveOffer("s1", Material.DIAMOND, 100_00L, ShopOffer.UNAVAILABLE);
        addStock("s1", Material.DIAMOND, 5);

        boolean tookTooMany = database.inTransaction(c ->
                PlayerShopRepository.tryTakeStock(c, "s1", Material.DIAMOND, 6));
        assertFalse(tookTooMany, "taking more than is held must be refused");

        int remaining = stockOf(Material.DIAMOND);
        assertEquals(5, remaining);

        boolean tookAll = database.inTransaction(c ->
                PlayerShopRepository.tryTakeStock(c, "s1", Material.DIAMOND, 5));
        assertTrue(tookAll);
        assertEquals(0, stockOf(Material.DIAMOND));
    }

    /**
     * The duplication test. Twenty customers race for five items; exactly five
     * may succeed. If the check and the decrement were separate statements they
     * would all read a stock of five and all proceed.
     */
    @Test
    @DisplayName("concurrent buyers cannot take more stock than exists")
    void concurrentBuyersCannotOversell() throws Exception {
        newShop("s1", "Stall");
        shops.saveOffer("s1", Material.DIAMOND, 100_00L, ShopOffer.UNAVAILABLE);
        addStock("s1", Material.DIAMOND, 5);

        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startLine = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                startLine.await();
                boolean got = database.inTransaction(c ->
                        PlayerShopRepository.tryTakeStock(c, "s1", Material.DIAMOND, 1));
                if (got) {
                    succeeded.incrementAndGet();
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

        assertEquals(5, succeeded.get(), "exactly the stock that existed may be sold");
        assertEquals(0, stockOf(Material.DIAMOND));
    }

    @Test
    @DisplayName("revenue accumulates and can be reversed")
    void revenueAccumulates() {
        newShop("s1", "Stall");
        database.inTransaction(c -> {
            PlayerShopRepository.addRevenue(c, "s1", 500_00L);
            return null;
        });
        assertEquals(500_00L, shops.byId("s1").orElseThrow().revenue());

        // A reversed sale subtracts, so lifetime takings stay honest.
        database.inTransaction(c -> {
            PlayerShopRepository.addRevenue(c, "s1", -200_00L);
            return null;
        });
        assertEquals(300_00L, shops.byId("s1").orElseThrow().revenue());
    }

    @Test
    @DisplayName("the directory lists every shop with its owner's name")
    void directoryListsShops() {
        newShop("s1", "Iron Works");
        newShop2("s2", "Gold Exchange");
        shops.saveOffer("s1", Material.IRON_INGOT, 20_00L, ShopOffer.UNAVAILABLE);

        List<PlayerShopRepository.DirectoryEntry> directory = shops.directory();
        assertEquals(2, directory.size());
        assertTrue(directory.stream().anyMatch(e -> e.ownerName().equals("Owner")));
    }

    private void newShop2(String id, String name) {
        PlayerShop shop = new PlayerShop(id, owner, name, null,
                "world", 40, 64, 20, "claim1", null, PlayerShopStatus.OPEN,
                System.currentTimeMillis(), 0L, List.of());
        shops.save(shop);
    }

    @Test
    @DisplayName("material search finds only shops that stock it and sell it")
    void materialSearchFiltersProperly() {
        newShop("s1", "Iron Works");
        shops.saveOffer("s1", Material.IRON_INGOT, 20_00L, ShopOffer.UNAVAILABLE);

        // Priced but empty: should not appear as a place to buy iron.
        assertTrue(shops.shopIdsStocking(Material.IRON_INGOT).isEmpty());

        addStock("s1", Material.IRON_INGOT, 10);
        assertEquals(List.of("s1"), shops.shopIdsStocking(Material.IRON_INGOT));

        // A buy-only-from-customers offer is not a place to buy either.
        newShop2("s2", "Scrap Buyer");
        shops.saveOffer("s2", Material.IRON_INGOT, ShopOffer.UNAVAILABLE, 5_00L);
        addStock("s2", Material.IRON_INGOT, 64);
        assertEquals(List.of("s1"), shops.shopIdsStocking(Material.IRON_INGOT),
                "a shop that only buys is not somewhere a customer can buy");
    }

    @Test
    @DisplayName("deleting a shop removes its offers too")
    void deleteCascades() {
        newShop("s1", "Stall");
        shops.saveOffer("s1", Material.DIAMOND, 100_00L, ShopOffer.UNAVAILABLE);

        assertTrue(shops.delete("s1"));
        assertTrue(shops.byId("s1").isEmpty());
        assertTrue(shops.shopIdsStocking(Material.DIAMOND).isEmpty());
    }

    @Test
    @DisplayName("plot status changes apply to every shop on that plot")
    void plotStatusCascades() {
        PlayerShop onPlot = new PlayerShop("p1", owner, "Spawn Stall", null,
                "world", 100, 64, 100, null, "plot7", PlayerShopStatus.OPEN,
                System.currentTimeMillis(), 0L, List.of());
        shops.save(onPlot);
        newShop("s1", "Home Stall");

        assertEquals(1, shops.setStatusForPlot("plot7", PlayerShopStatus.RENT_OVERDUE));
        assertEquals(PlayerShopStatus.RENT_OVERDUE, shops.byId("p1").orElseThrow().status());
        assertEquals(PlayerShopStatus.OPEN, shops.byId("s1").orElseThrow().status(),
                "a shop on the owner's own land is unaffected by plot rent");
    }

    @Test
    @DisplayName("an overdue shop is not trading and its owner cannot simply reopen it")
    void overdueShopIsLocked() {
        assertFalse(PlayerShopStatus.RENT_OVERDUE.trading());
        assertFalse(PlayerShopStatus.RENT_OVERDUE.ownerCanReopen());
        assertFalse(PlayerShopStatus.SUSPENDED.ownerCanReopen());
        assertTrue(PlayerShopStatus.CLOSED.ownerCanReopen());
        assertTrue(PlayerShopStatus.OPEN.trading());
    }

    @Test
    @DisplayName("an offer with neither price is rejected outright")
    void offerNeedsAtLeastOnePrice() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new ShopOffer(1L, "s1", Material.DIAMOND,
                        ShopOffer.UNAVAILABLE, ShopOffer.UNAVAILABLE, 0, 0L));
    }
}
