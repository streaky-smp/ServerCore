package com.servercore.economy;

import com.servercore.config.ConfigManager;
import com.servercore.core.Scheduling;
import com.servercore.data.Database;
import com.servercore.data.PlayerRecord;
import com.servercore.data.PlayerRepository;
import com.servercore.data.Schema;
import com.servercore.data.SchemaMigrator;
import com.servercore.data.ThreadGuard;
import com.servercore.log.AuditLog;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
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
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The economy's correctness tests.
 *
 * <p>The question these exist to answer is not "does {@code /pay} work" but
 * "can the economy be made to create money". Several of them therefore assert on
 * the total supply rather than on individual balances.
 */
class EconomyServiceTest {

    private static final Logger LOGGER = Logger.getLogger("EconomyServiceTest");

    /** Two-decimal currency, so 100_00 is one hundred units. */
    private static final long UNIT = 100L;

    private Database database;
    private AccountRepository accounts;
    private TransactionRepository ledger;
    private StandardEconomyService economy;
    private PlayerRepository players;

    private UUID alice;
    private UUID bob;

    @BeforeEach
    void setUp(@TempDir Path temp) throws Exception {
        database = new Database(new File(temp.toFile(), "data"), LOGGER,
                ThreadGuard.offMainThread(), "economy.db", 8, 5_000L);
        database.onEnable();
        new SchemaMigrator(database, LOGGER, Schema.migrations()).migrate();

        accounts = new AccountRepository(database);
        ledger = new TransactionRepository(database);
        players = new PlayerRepository(database);

        // The audit log's record() only enqueues; it never touches the scheduler,
        // which is why it is safe to construct one here with no running server.
        AuditLog auditLog = new AuditLog(database, new Scheduling(null), LOGGER, 100L, false);

        economy = new StandardEconomyService(database, accounts, ledger, auditLog, LOGGER);
        configure(economy, """
                economy:
                  currency:
                    symbol: "$"
                    fraction-digits: 2
                  starting-balance: 100
                  minimum-payment: 1
                  maximum-payment: 1000000
                  max-balance: 1000000000
                  payment-cooldown: 0
                  transfer-fee-percent: 0
                  transfer-fee-flat: 0
                  large-payment-confirm-threshold: 5000
                """);

        alice = newPlayer("Alice");
        bob = newPlayer("Bob");
    }

    @AfterEach
    void tearDown() {
        if (database != null) {
            database.onDisable();
        }
    }

    /** Feeds YAML straight through the real config path, so parsing is tested too. */
    private static void configure(ConfigManager.Reloadable target, String yaml) {
        FileConfiguration parsed = YamlConfiguration.loadConfiguration(new StringReader(yaml));
        target.load(new ConfigManager.ConfigBundle(List.of("config.yml"), List.of(parsed)));
    }

    private UUID newPlayer(String name) {
        UUID id = UUID.randomUUID();
        players.touch(id, name, PlayerRecord.Platform.JAVA);
        economy.ensureAccount(id);
        return id;
    }

    // ------------------------------------------------------------- accounts

    @Test
    @DisplayName("a new account receives the starting balance exactly once")
    void startingBalanceGrantedOnce() {
        assertEquals(100 * UNIT, economy.getBalance(alice));

        // Simulates rejoining: ensureAccount runs on every join.
        economy.ensureAccount(alice);
        economy.ensureAccount(alice);

        assertEquals(100 * UNIT, economy.getBalance(alice),
                "re-running account creation must not top the player up again");
    }

    @Test
    @DisplayName("an unknown account reads as zero rather than failing")
    void unknownAccountIsZero() {
        assertEquals(0L, economy.getBalance(UUID.randomUUID()));
    }

    // ------------------------------------------------------------- payments

    @Test
    @DisplayName("a payment moves exactly the stated amount")
    void paymentMovesExactAmount() {
        EconomyResult result = economy.pay(alice, bob, 25 * UNIT);

        assertTrue(result.isSuccess());
        assertEquals(75 * UNIT, economy.getBalance(alice));
        assertEquals(125 * UNIT, economy.getBalance(bob));
        assertEquals(200 * UNIT, totalSupply(), "a transfer must not change the money supply");
    }

    @Test
    @DisplayName("paying more than you have changes nothing")
    void insufficientFundsChangesNothing() {
        EconomyResult result = economy.pay(alice, bob, 500 * UNIT);

        assertFalse(result.isSuccess());
        assertEquals(EconomyResult.Outcome.INSUFFICIENT_FUNDS, result.outcome());
        assertEquals(400 * UNIT, result.shortfall(), "the shortfall tells the player what they need");
        assertEquals(100 * UNIT, economy.getBalance(alice));
        assertEquals(100 * UNIT, economy.getBalance(bob));
    }

    @Test
    @DisplayName("paying yourself is refused by default")
    void selfPaymentRefused() {
        EconomyResult result = economy.pay(alice, alice, 10 * UNIT);

        assertEquals(EconomyResult.Outcome.SELF_PAYMENT, result.outcome());
        assertEquals(100 * UNIT, economy.getBalance(alice),
                "a self-payment that slipped through would be a no-op at best and a duplicator at worst");
    }

    @Test
    @DisplayName("amounts outside the configured bounds are refused")
    void boundsAreEnforced() {
        configure(economy, """
                economy:
                  currency: {symbol: "$", fraction-digits: 2}
                  starting-balance: 100
                  minimum-payment: 10
                  maximum-payment: 50
                  max-balance: 1000000000
                  payment-cooldown: 0
                """);

        assertEquals(EconomyResult.Outcome.BELOW_MINIMUM, economy.pay(alice, bob, 5 * UNIT).outcome());
        assertEquals(EconomyResult.Outcome.ABOVE_MAXIMUM, economy.pay(alice, bob, 60 * UNIT).outcome());
        assertEquals(100 * UNIT, economy.getBalance(alice));
    }

    @Test
    @DisplayName("zero and negative amounts are refused")
    void nonPositiveAmountsRefused() {
        assertEquals(EconomyResult.Outcome.INVALID_AMOUNT, economy.pay(alice, bob, 0L).outcome());
        assertEquals(EconomyResult.Outcome.INVALID_AMOUNT, economy.pay(alice, bob, -50L).outcome());
        assertEquals(100 * UNIT, economy.getBalance(alice));
        assertEquals(100 * UNIT, economy.getBalance(bob));
    }

    // ------------------------------------------------------------------ fees

    @Test
    @DisplayName("a transfer fee is destroyed, not given to anyone")
    void feeIsDestroyed() {
        configure(economy, """
                economy:
                  currency: {symbol: "$", fraction-digits: 2}
                  starting-balance: 100
                  minimum-payment: 1
                  maximum-payment: 1000000
                  max-balance: 1000000000
                  payment-cooldown: 0
                  transfer-fee-percent: 10
                  transfer-fee-flat: 0
                """);

        EconomyResult result = economy.pay(alice, bob, 50 * UNIT);
        assertTrue(result.isSuccess());

        // Alice pays 50 plus a 5 fee; Bob receives 50; the 5 leaves circulation.
        assertEquals(45 * UNIT, economy.getBalance(alice));
        assertEquals(150 * UNIT, economy.getBalance(bob));
        assertEquals(195 * UNIT, totalSupply(),
                "the fee must be a sink -- if the supply is unchanged it went to someone");
    }

    @Test
    @DisplayName("the fee is included in the affordability check")
    void feeCountsTowardsAffordability() {
        configure(economy, """
                economy:
                  currency: {symbol: "$", fraction-digits: 2}
                  starting-balance: 100
                  minimum-payment: 1
                  maximum-payment: 1000000
                  max-balance: 1000000000
                  payment-cooldown: 0
                  transfer-fee-percent: 10
                """);

        // 100 exactly, plus a 10 fee, is more than Alice has.
        EconomyResult result = economy.pay(alice, bob, 100 * UNIT);

        assertEquals(EconomyResult.Outcome.INSUFFICIENT_FUNDS, result.outcome());
        assertEquals(100 * UNIT, economy.getBalance(alice), "nothing may move when the fee tips it over");
    }

    // ----------------------------------------------------------- boundaries

    @Test
    @DisplayName("the balance ceiling is enforced and rolls the whole payment back")
    void maxBalanceRollsBackTheTransfer() {
        configure(economy, """
                economy:
                  currency: {symbol: "$", fraction-digits: 2}
                  starting-balance: 100
                  minimum-payment: 1
                  maximum-payment: 1000000
                  max-balance: 120
                  payment-cooldown: 0
                """);

        EconomyResult result = economy.pay(alice, bob, 50 * UNIT);

        assertEquals(EconomyResult.Outcome.WOULD_EXCEED_MAX_BALANCE, result.outcome());
        // The debit happened before the credit was refused. If the rollback did
        // not work, Alice would be 50 poorer and Bob no richer.
        assertEquals(100 * UNIT, economy.getBalance(alice), "the debit must have been rolled back");
        assertEquals(100 * UNIT, economy.getBalance(bob));
        assertEquals(200 * UNIT, totalSupply());
    }

    @Test
    @DisplayName("a cooldown blocks a second payment")
    void cooldownBlocksRepeatPayment() {
        configure(economy, """
                economy:
                  currency: {symbol: "$", fraction-digits: 2}
                  starting-balance: 100
                  minimum-payment: 1
                  maximum-payment: 1000000
                  max-balance: 1000000000
                  payment-cooldown: 1h
                """);

        assertTrue(economy.pay(alice, bob, 10 * UNIT).isSuccess());
        assertEquals(EconomyResult.Outcome.COOLDOWN_ACTIVE, economy.pay(alice, bob, 10 * UNIT).outcome());
        assertTrue(economy.remainingCooldownMillis(alice) > 0);
    }

    // ------------------------------------------------------------ the ledger

    @Test
    @DisplayName("a payment writes a matching ledger entry")
    void paymentIsRecorded() {
        EconomyResult result = economy.pay(alice, bob, 25 * UNIT);

        Transaction entry = ledger.byId(result.transactionId()).orElseThrow();
        assertEquals(TransactionType.PAY, entry.type());
        assertEquals(alice, entry.from());
        assertEquals(bob, entry.to());
        assertEquals(25 * UNIT, entry.amount());
        assertEquals(-(25 * UNIT), entry.signedAmountFor(alice));
        assertEquals(25 * UNIT, entry.signedAmountFor(bob));
    }

    @Test
    @DisplayName("a refused payment writes nothing to the ledger")
    void refusedPaymentIsNotRecorded() {
        int before = ledger.historyCountFor(alice);
        economy.pay(alice, bob, 5_000 * UNIT);
        assertEquals(before, ledger.historyCountFor(alice),
                "a failed payment in the ledger would make every audit unreliable");
    }

    @Test
    @DisplayName("history shows both sides of a payment")
    void historyIncludesIncomingAndOutgoing() {
        economy.pay(alice, bob, 10 * UNIT);
        economy.pay(bob, alice, 5 * UNIT);

        assertEquals(2, ledger.historyFor(alice, 50, 0).size());
        assertEquals(2, ledger.historyFor(bob, 50, 0).size());
    }

    // --------------------------------------------------------- concurrency

    /**
     * The headline test.
     *
     * <p>Sixteen threads pay concurrently out of a balance that cannot cover all
     * of them. Whatever the interleaving, two things must hold: no balance is
     * negative, and the total money in the system is exactly what it started with.
     * A failure here is money being created or destroyed by a race.
     */
    @Test
    @DisplayName("concurrent payments never create or destroy money")
    void concurrentPaymentsConserveMoney() throws Exception {
        List<UUID> wallets = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            wallets.add(newPlayer("Player" + i));
        }
        long expectedSupply = totalSupply();

        int threads = 16;
        int paymentsPerThread = 30;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startLine = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();

        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                startLine.await();
                for (int i = 0; i < paymentsPerThread; i++) {
                    var random = ThreadLocalRandom.current();
                    UUID from = wallets.get(random.nextInt(wallets.size()));
                    UUID to = wallets.get(random.nextInt(wallets.size()));
                    if (from.equals(to)) {
                        continue;
                    }
                    long amount = (random.nextInt(30) + 1) * UNIT;
                    if (economy.pay(from, to, amount).isSuccess()) {
                        succeeded.incrementAndGet();
                    }
                }
                return null;
            }));
        }
        startLine.countDown();
        for (Future<?> future : futures) {
            future.get(120, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        assertTrue(succeeded.get() > 0, "the test is meaningless if nothing succeeded");
        assertEquals(expectedSupply, totalSupply(),
                "money was created or destroyed by concurrent transfers");

        for (UUID wallet : wallets) {
            assertTrue(economy.getBalance(wallet) >= 0,
                    "a negative balance means an unguarded debit slipped through");
        }
    }

    @Test
    @DisplayName("concurrent payments out of one account cannot overspend it")
    void concurrentPaymentsFromOneAccountCannotOverspend() throws Exception {
        // Alice has 100. Twenty threads each try to send 30. Three can fit.
        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startLine = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                startLine.await();
                if (economy.pay(alice, bob, 30 * UNIT).isSuccess()) {
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

        assertEquals(3, succeeded.get());
        assertEquals(10 * UNIT, economy.getBalance(alice));
        assertEquals(190 * UNIT, economy.getBalance(bob));
    }

    // ------------------------------------------------------------ admin ops

    @Test
    @DisplayName("an administrative adjustment is recorded as such")
    void adminAdjustmentIsAudited() {
        UUID admin = UUID.randomUUID();
        EconomyResult result = economy.setBalance(alice, 500 * UNIT, admin, "Compensation");

        assertTrue(result.isSuccess());
        assertEquals(500 * UNIT, economy.getBalance(alice));

        Transaction entry = ledger.byId(result.transactionId()).orElseThrow();
        assertEquals(TransactionType.ADMIN, entry.type());
        assertEquals(TransactionType.Flow.ADMIN, entry.type().flow(),
                "admin flow must be excluded from organic economy figures");
    }

    @Test
    @DisplayName("a balance cannot be set negative")
    void balanceCannotGoNegative() {
        assertEquals(EconomyResult.Outcome.INVALID_AMOUNT,
                economy.setBalance(alice, -1L, null, "bad").outcome());
        assertEquals(100 * UNIT, economy.getBalance(alice));
    }

    @Test
    @DisplayName("deposits and withdrawals move the money supply as expected")
    void depositAndWithdrawAdjustSupply() {
        long before = totalSupply();

        economy.deposit(alice, 50 * UNIT, TransactionType.SHOP_SELL, "sold ore");
        assertEquals(before + 50 * UNIT, totalSupply(), "selling to the server creates money");

        economy.withdraw(alice, 20 * UNIT, TransactionType.SHOP_BUY, "bought blocks");
        assertEquals(before + 30 * UNIT, totalSupply(), "buying from the server destroys money");
    }

    @Test
    @DisplayName("withdrawing more than the balance is refused")
    void withdrawRespectsBalance() {
        EconomyResult result = economy.withdraw(alice, 500 * UNIT, TransactionType.SHOP_BUY, null);
        assertEquals(EconomyResult.Outcome.INSUFFICIENT_FUNDS, result.outcome());
        assertEquals(100 * UNIT, economy.getBalance(alice));
    }

    // -------------------------------------------------------------- ranking

    @Test
    @DisplayName("ranking orders by balance")
    void rankingOrdersByBalance() {
        economy.setBalance(alice, 900 * UNIT, null, "test");
        economy.setBalance(bob, 100 * UNIT, null, "test");

        List<AccountRepository.BalanceEntry> top = accounts.topBalances(10);
        assertEquals(alice, top.getFirst().uuid());
        assertEquals(1, accounts.rankOf(alice));
        assertEquals(2, accounts.rankOf(bob));
    }

    private long totalSupply() {
        return accounts.totalSupply();
    }
}
