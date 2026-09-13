package com.servercore.plot;

import com.servercore.config.ConfigManager;
import com.servercore.core.Scheduling;
import com.servercore.data.Database;
import com.servercore.data.PlayerRecord;
import com.servercore.data.PlayerRepository;
import com.servercore.data.Schema;
import com.servercore.data.SchemaMigrator;
import com.servercore.data.ThreadGuard;
import com.servercore.economy.AccountRepository;
import com.servercore.economy.StandardEconomyService;
import com.servercore.economy.TransactionRepository;
import com.servercore.log.AuditLog;
import com.servercore.notify.NotificationService;
import com.servercore.playershop.PlayerShop;
import com.servercore.playershop.PlayerShopRepository;
import com.servercore.playershop.PlayerShopStatus;
import com.servercore.playershop.ShopOffer;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plot purchase and the rent sweep, against a real database.
 *
 * <p>Covers the behaviour a tenant would notice if it were wrong: being charged
 * for a plot they did not get, losing a shop without a grace period, or having
 * their stock destroyed when a plot expired.
 */
class PlotServiceTest {

    private static final Logger LOGGER = Logger.getLogger("PlotServiceTest");
    private static final long UNIT = 100L;

    private Database database;
    private PlotService plots;
    private PlotRepository plotRepository;
    private PlayerShopRepository shops;
    private StandardEconomyService economy;

    private UUID alice;

    /** Records notifications instead of sending them; no server is running. */
    private final List<String> sent = new ArrayList<>();

    @BeforeEach
    void setUp(@TempDir Path temp) throws Exception {
        database = new Database(new File(temp.toFile(), "data"), LOGGER,
                ThreadGuard.offMainThread(), "plots.db", 8, 5_000L);
        database.onEnable();
        new SchemaMigrator(database, LOGGER, Schema.migrations()).migrate();

        PlayerRepository players = new PlayerRepository(database);
        AccountRepository accounts = new AccountRepository(database);
        TransactionRepository ledger = new TransactionRepository(database);
        AuditLog auditLog = new AuditLog(database, new Scheduling(null), LOGGER, 100L, false);

        economy = new StandardEconomyService(database, accounts, ledger, auditLog, LOGGER);
        plotRepository = new PlotRepository(database);
        shops = new PlayerShopRepository(database);

        plots = new PlotService(database, plotRepository, shops, economy,
                new RecordingNotifier(), auditLog, new Scheduling(null), LOGGER);

        configure("""
                economy:
                  currency: {symbol: "$", fraction-digits: 2}
                  starting-balance: 100000
                  minimum-payment: 1
                  maximum-payment: 100000000
                  max-balance: 100000000000
                  payment-cooldown: 0
                spawn-plots:
                  enabled: true
                  default-purchase-price: 25000
                  default-rent-price: 5000
                  default-rent-period: weekly
                  grace-period: 48h
                  cleanup-period: 7d
                  rent-check-interval: 15m
                  max-plots-per-player: 1
                  warn-on-login: true
                  warn-before-due: 24h
                """);

        alice = UUID.randomUUID();
        players.touch(alice, "Alice", PlayerRecord.Platform.JAVA);
        economy.ensureAccount(alice);
    }

    @AfterEach
    void tearDown() {
        if (database != null) {
            database.onDisable();
        }
    }

    private void configure(String yaml) {
        FileConfiguration parsed = YamlConfiguration.loadConfiguration(new StringReader(yaml));
        var bundle = new ConfigManager.ConfigBundle(List.of("config.yml"), List.of(parsed));
        economy.load(bundle);
        plots.load(bundle);
    }

    private SpawnPlot createPlot(String id) {
        PlotResult result = plots.createPlot(id, "world", 0, 0, 9, 9, null, null, null);
        assertTrue(result.isSuccess(), "fixture plot should be created");
        return result.plot();
    }

    // -------------------------------------------------------------- purchase

    @Test
    @DisplayName("buying a plot charges the price and sets the first rent a period out")
    void purchaseChargesAndSchedulesRent() {
        createPlot("p1");
        long before = economy.getBalance(alice);

        PlotResult result = plots.purchase(alice, "p1");

        assertTrue(result.isSuccess());
        assertEquals(25_000 * UNIT, result.amount());
        assertEquals(before - 25_000 * UNIT, economy.getBalance(alice));

        SpawnPlot owned = plots.byId("p1").orElseThrow();
        assertEquals(PlotStatus.OWNED, owned.status());
        assertTrue(owned.isOwner(alice));
        assertNotNull(owned.rentDueAt());
        assertFalse(owned.rentDue(System.currentTimeMillis()),
                "rent must not fall due the instant a plot is bought");
    }

    @Test
    @DisplayName("a plot that cannot be afforded is neither charged for nor transferred")
    void unaffordablePurchaseChangesNothing() {
        createPlot("p1");
        economy.setBalance(alice, 100 * UNIT, null, "test");

        PlotResult result = plots.purchase(alice, "p1");

        assertEquals(PlotResult.Outcome.INSUFFICIENT_FUNDS, result.outcome());
        assertEquals(100 * UNIT, economy.getBalance(alice));
        assertEquals(PlotStatus.AVAILABLE, plots.byId("p1").orElseThrow().status());
    }

    @Test
    @DisplayName("an already-owned plot cannot be bought again")
    void ownedPlotIsNotForSale() {
        createPlot("p1");
        plots.purchase(alice, "p1");

        UUID bob = UUID.randomUUID();
        new PlayerRepository(database).touch(bob, "Bob", PlayerRecord.Platform.JAVA);
        economy.ensureAccount(bob);
        economy.setBalance(bob, 100_000 * UNIT, null, "test");

        assertEquals(PlotResult.Outcome.NOT_AVAILABLE, plots.purchase(bob, "p1").outcome());
        assertTrue(plots.byId("p1").orElseThrow().isOwner(alice));
    }

    @Test
    @DisplayName("the per-player plot limit is enforced")
    void plotLimitEnforced() {
        createPlot("p1");
        PlotResult second = plots.createPlot("p2", "world", 100, 100, 109, 109, null, null, null);
        assertTrue(second.isSuccess());

        assertTrue(plots.purchase(alice, "p1").isSuccess());
        assertEquals(PlotResult.Outcome.TOO_MANY_PLOTS, plots.purchase(alice, "p2").outcome());
    }

    @Test
    @DisplayName("overlapping plots cannot be created")
    void overlappingPlotsRefused() {
        createPlot("p1");
        assertEquals(PlotResult.Outcome.OVERLAPS,
                plots.createPlot("p2", "world", 5, 5, 14, 14, null, null, null).outcome());
    }

    /**
     * Two players clicking the same plot at the same instant. Only one may own
     * it, and only one may be charged.
     */
    @Test
    @DisplayName("concurrent purchases of one plot cannot both succeed")
    void concurrentPurchasesCannotBothSucceed() throws Exception {
        // No per-player limit here, so the limit is not what rejects the losers.
        configure("""
                economy:
                  currency: {symbol: "$", fraction-digits: 2}
                  starting-balance: 100000
                  minimum-payment: 1
                  maximum-payment: 100000000
                  max-balance: 100000000000
                  payment-cooldown: 0
                spawn-plots:
                  enabled: true
                  default-purchase-price: 1000
                  default-rent-price: 100
                  default-rent-period: weekly
                  grace-period: 48h
                  cleanup-period: 7d
                  rent-check-interval: 15m
                  max-plots-per-player: 0
                  warn-on-login: false
                  warn-before-due: 24h
                """);
        createPlot("race");

        int threads = 10;
        List<UUID> buyers = new ArrayList<>();
        PlayerRepository players = new PlayerRepository(database);
        for (int i = 0; i < threads; i++) {
            UUID id = UUID.randomUUID();
            players.touch(id, "Buyer" + i, PlayerRecord.Platform.JAVA);
            economy.ensureAccount(id);
            economy.setBalance(id, 100_000 * UNIT, null, "test");
            buyers.add(id);
        }

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startLine = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();

        for (UUID buyer : buyers) {
            futures.add(pool.submit(() -> {
                startLine.await();
                if (plots.purchase(buyer, "race").isSuccess()) {
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

        assertEquals(1, succeeded.get(), "only one player may own a plot");

        // Exactly one buyer should have been charged.
        int charged = 0;
        for (UUID buyer : buyers) {
            if (economy.getBalance(buyer) < 100_000 * UNIT) {
                charged++;
            }
        }
        assertEquals(1, charged, "nobody who lost the race may have been charged");
    }

    // ------------------------------------------------------------ rent sweep

    @Test
    @DisplayName("rent is collected when it falls due")
    void rentIsCollectedWhenDue() {
        createPlot("p1");
        plots.purchase(alice, "p1");
        long afterPurchase = economy.getBalance(alice);

        // Backdate the due date so the sweep sees it.
        forceRentDue("p1");
        plots.sweep();

        assertEquals(afterPurchase - 5_000 * UNIT, economy.getBalance(alice));
        SpawnPlot after = plots.byId("p1").orElseThrow();
        assertEquals(PlotStatus.OWNED, after.status());
        assertEquals(5_000 * UNIT, after.rentPaid());
        assertTrue(after.rentDueAt() > System.currentTimeMillis(),
                "the next due date must move into the future");
    }

    @Test
    @DisplayName("the sweep does not charge twice for one period")
    void sweepIsIdempotentWithinAPeriod() {
        createPlot("p1");
        plots.purchase(alice, "p1");
        forceRentDue("p1");

        plots.sweep();
        long afterFirst = economy.getBalance(alice);
        plots.sweep();

        assertEquals(afterFirst, economy.getBalance(alice),
                "a second sweep in the same period must not collect again");
    }

    @Test
    @DisplayName("a failed payment starts a grace period and leaves shops trading")
    void failedRentEntersGrace() {
        createPlot("p1");
        plots.purchase(alice, "p1");
        economy.setBalance(alice, 10 * UNIT, null, "test");
        forceRentDue("p1");

        plots.sweep();

        SpawnPlot after = plots.byId("p1").orElseThrow();
        assertEquals(PlotStatus.RENT_OVERDUE, after.status());
        assertTrue(after.inGracePeriod());
        assertTrue(after.status().shopsMayTrade(),
                "the shop must keep trading through the grace period");
        assertTrue(after.isOwner(alice), "the plot is still theirs");
        assertEquals(10 * UNIT, economy.getBalance(alice), "no partial charge may be taken");
    }

    @Test
    @DisplayName("paying during the grace period restores the plot and reopens shops")
    void payingDuringGraceRestores() {
        createPlot("p1");
        plots.purchase(alice, "p1");
        newShopOnPlot("s1", "p1");

        economy.setBalance(alice, 10 * UNIT, null, "test");
        forceRentDue("p1");
        plots.sweep();
        assertEquals(PlotStatus.RENT_OVERDUE, plots.byId("p1").orElseThrow().status());

        economy.setBalance(alice, 50_000 * UNIT, null, "test");
        PlotResult paid = plots.payRent(alice, "p1");

        assertTrue(paid.isSuccess());
        SpawnPlot after = plots.byId("p1").orElseThrow();
        assertEquals(PlotStatus.OWNED, after.status());
        assertFalse(after.inGracePeriod());
        assertEquals(PlayerShopStatus.OPEN, shops.byId("s1").orElseThrow().status());
    }

    /**
     * The plot must expire and stop trading even when its stock cannot be
     * serialised.
     *
     * <p>Item serialisation needs a running server, so this test covers a shop
     * with no stock. The serialisation path itself is exercised on a live server;
     * what is verified here is that the status transitions and the shop closure
     * happen regardless, which is the part that would strand a tenant.
     */
    @Test
    @DisplayName("an expired plot stops trading and stays with its owner during cleanup")
    void expiryClosesShopsButKeepsOwnership() {
        createPlot("p1");
        plots.purchase(alice, "p1");
        newShopOnPlot("s1", "p1");

        economy.setBalance(alice, 10 * UNIT, null, "test");
        forceRentDue("p1");
        plots.sweep();
        forceGraceExpired("p1");
        plots.sweep();

        SpawnPlot after = plots.byId("p1").orElseThrow();
        assertEquals(PlotStatus.EXPIRED, after.status());
        assertFalse(after.status().shopsMayTrade(), "trading stops");
        assertTrue(after.isOwner(alice), "the owner keeps the plot during cleanup");

        PlayerShopStatus shopStatus = shops.byId("s1").orElseThrow().status();
        assertFalse(shopStatus.trading(), "shops on the plot must close");
        assertFalse(shopStatus.ownerCanReopen(),
                "the owner must not be able to simply reopen a shop closed by unpaid rent");
    }

    @Test
    @DisplayName("mailbox entries can only be collected once")
    void mailboxCollectionIsSingleUse() {
        plotRepository.storeInMailbox(alice, "p1", new byte[]{1, 2, 3});
        var mail = plots.pendingMail(alice);
        assertEquals(1, mail.size());

        long id = mail.getFirst().id();
        assertTrue(plots.claimMail(id), "the first claim wins");
        assertFalse(plots.claimMail(id),
                "a second claim must fail, or a double click duplicates a whole shop");
        assertEquals(0, plots.pendingMailCount(alice));
    }

    @Test
    @DisplayName("an expired plot is released once the cleanup period elapses")
    void expiredPlotIsReleasedAfterCleanup() {
        createPlot("p1");
        plots.purchase(alice, "p1");

        economy.setBalance(alice, 10 * UNIT, null, "test");
        forceRentDue("p1");
        plots.sweep();
        forceGraceExpired("p1");
        plots.sweep();
        assertEquals(PlotStatus.EXPIRED, plots.byId("p1").orElseThrow().status());

        // Backdate the grace end beyond the cleanup window.
        SpawnPlot expired = plots.byId("p1").orElseThrow();
        plotRepository.save(new SpawnPlot(expired.id(), expired.worldName(),
                expired.minX(), expired.minZ(), expired.maxX(), expired.maxZ(),
                expired.purchasePrice(), expired.rentPrice(), expired.rentPeriod(),
                expired.owner(), expired.status(), expired.purchasedAt(), expired.rentDueAt(),
                System.currentTimeMillis() - java.time.Duration.ofDays(8).toMillis(),
                expired.rentPaid(), expired.createdAt()));

        plots.sweep();

        SpawnPlot released = plots.byId("p1").orElseThrow();
        assertEquals(PlotStatus.AVAILABLE, released.status());
        assertNull(released.owner());
        assertTrue(released.status().purchasable(), "it is back on the market");
    }

    @Test
    @DisplayName("a disabled plot is never billed")
    void disabledPlotIsNotBilled() {
        createPlot("p1");
        plots.purchase(alice, "p1");
        forceRentDue("p1");
        plots.setStatus("p1", PlotStatus.DISABLED);
        long before = economy.getBalance(alice);

        plots.sweep();

        assertEquals(before, economy.getBalance(alice),
                "a plot withdrawn by an operator must not keep charging its former tenant");
    }

    @Test
    @DisplayName("a grace period at least as long as the rent period is refused at load")
    void graceLongerThanPeriodIsRejected() {
        org.junit.jupiter.api.Assertions.assertThrows(
                com.servercore.config.ConfigException.class,
                () -> configure("""
                        economy:
                          currency: {symbol: "$", fraction-digits: 2}
                        spawn-plots:
                          default-rent-period: daily
                          grace-period: 48h
                        """),
                "otherwise a tenant stays permanently overdue and rent becomes optional");
    }

    // -------------------------------------------------------------- helpers

    /** Backdates a plot's due date so the sweep treats rent as owed. */
    private void forceRentDue(String plotId) {
        SpawnPlot plot = plots.byId(plotId).orElseThrow();
        plotRepository.save(new SpawnPlot(plot.id(), plot.worldName(),
                plot.minX(), plot.minZ(), plot.maxX(), plot.maxZ(),
                plot.purchasePrice(), plot.rentPrice(), plot.rentPeriod(),
                plot.owner(), plot.status(), plot.purchasedAt(),
                System.currentTimeMillis() - 1_000L, plot.graceEndsAt(),
                plot.rentPaid(), plot.createdAt()));
    }

    /** Backdates the grace end so the next sweep expires the plot. */
    private void forceGraceExpired(String plotId) {
        SpawnPlot plot = plots.byId(plotId).orElseThrow();
        plotRepository.save(new SpawnPlot(plot.id(), plot.worldName(),
                plot.minX(), plot.minZ(), plot.maxX(), plot.maxZ(),
                plot.purchasePrice(), plot.rentPrice(), plot.rentPeriod(),
                plot.owner(), plot.status(), plot.purchasedAt(),
                System.currentTimeMillis() - 1_000L,
                System.currentTimeMillis() - 1_000L,
                plot.rentPaid(), plot.createdAt()));
    }

    private void newShopOnPlot(String shopId, String plotId) {
        shops.save(new PlayerShop(shopId, alice, "Stall", null, "world", 5, 64, 5,
                null, plotId, PlayerShopStatus.OPEN, System.currentTimeMillis(), 0L, List.of()));
    }

    /** Minimal notifier: no server is running, so nothing may touch Bukkit. */
    private final class RecordingNotifier implements NotificationService {
        @Override
        public void info(CommandSender target, String key, Map<String, String> placeholders) {
            sent.add(key);
        }

        @Override
        public void success(CommandSender target, String key, Map<String, String> placeholders) {
            sent.add(key);
        }

        @Override
        public void error(CommandSender target, String key, Map<String, String> placeholders) {
            sent.add(key);
        }

        @Override
        public void raw(CommandSender target, Component component) {
        }

        @Override
        public void actionBar(Player player, Component component) {
        }

        @Override
        public void title(Player player, Component title, Component subtitle) {
        }

        @Override
        public void sound(Player player, String soundKey) {
        }

        @Override
        public void notifyPlayer(UUID playerId, Component message) {
        }

        @Override
        public void notifyPlayer(UUID playerId, String key, Map<String, String> placeholders) {
            sent.add(key);
        }
    }
}
