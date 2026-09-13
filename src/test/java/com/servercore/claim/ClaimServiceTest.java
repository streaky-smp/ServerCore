package com.servercore.claim;

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
 * End-to-end claim purchasing against a real database.
 *
 * <p>Possible because the claim service holds no Bukkit types. The property under
 * test is the one that matters: land and money move together or not at all.
 */
class ClaimServiceTest {

    private static final Logger LOGGER = Logger.getLogger("ClaimServiceTest");
    private static final String WORLD = "world";
    private static final long UNIT = 100L;

    private Database database;
    private StandardClaimService claims;
    private StandardEconomyService economy;
    private AccountRepository accounts;
    private ClaimIndex index;
    private PlayerRepository players;

    private UUID alice;
    private UUID bob;

    @BeforeEach
    void setUp(@TempDir Path temp) throws Exception {
        database = new Database(new File(temp.toFile(), "data"), LOGGER,
                ThreadGuard.offMainThread(), "claims.db", 8, 5_000L);
        database.onEnable();
        new SchemaMigrator(database, LOGGER, Schema.migrations()).migrate();

        players = new PlayerRepository(database);
        accounts = new AccountRepository(database);
        TransactionRepository ledger = new TransactionRepository(database);
        AuditLog auditLog = new AuditLog(database, new Scheduling(null), LOGGER, 100L, false);

        economy = new StandardEconomyService(database, accounts, ledger, auditLog, LOGGER);
        index = new ClaimIndex();
        claims = new StandardClaimService(database, new ClaimRepository(database),
                index, economy, auditLog, LOGGER);

        // 1.00 per block, so a 10x10 claim costs 100.00 exactly.
        configure("""
                economy:
                  currency: {symbol: "$", fraction-digits: 2}
                  starting-balance: 1000
                  minimum-payment: 1
                  maximum-payment: 1000000
                  max-balance: 1000000000
                  payment-cooldown: 0
                claims:
                  enabled: true
                  price-per-block: 1.00
                  expansion-price-per-block: 1.00
                  delete-refund-percent: 50
                  min-side-length: 4
                  max-side-length: 64
                  max-area: 4096
                  max-claims-per-player: 3
                  visualise-seconds: 15
                  enabled-worlds: []
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

    private void configure(String yaml) {
        FileConfiguration parsed = YamlConfiguration.loadConfiguration(new StringReader(yaml));
        var bundle = new ConfigManager.ConfigBundle(List.of("config.yml"), List.of(parsed));
        economy.load(bundle);
        claims.load(bundle);
    }

    private UUID newPlayer(String name) {
        UUID id = UUID.randomUUID();
        players.touch(id, name, PlayerRecord.Platform.JAVA);
        economy.ensureAccount(id);
        return id;
    }

    // --------------------------------------------------------------- buying

    @Test
    @DisplayName("buying a claim charges exactly the listed price")
    void buyingChargesThePrice() {
        ClaimResult result = claims.create(alice, WORLD, 0, 0, 9, 9, "Home");

        assertTrue(result.isSuccess());
        assertEquals(100, result.claim().area());
        assertEquals(100 * UNIT, result.cost());
        assertEquals(900 * UNIT, economy.getBalance(alice), "1000 - 100 = 900");
    }

    @Test
    @DisplayName("a claim that cannot be afforded is not created")
    void unaffordableClaimIsNotCreated() {
        // 64x64 is 4096 blocks at 1.00 each, well past a 1000 balance.
        ClaimResult result = claims.create(alice, WORLD, 0, 0, 63, 63, "Estate");

        assertEquals(ClaimResult.Outcome.INSUFFICIENT_FUNDS, result.outcome());
        assertEquals(1000 * UNIT, economy.getBalance(alice), "nothing may be charged");
        assertEquals(0, index.size(), "and no land may be granted");
        assertTrue(claims.claimAt(WORLD, 5, 5).isEmpty());
    }

    /**
     * The atomicity test. The debit and the claim row are written in one
     * transaction, so a refused charge must leave no claim behind and a granted
     * claim must have been paid for.
     */
    @Test
    @DisplayName("land and money move together or not at all")
    void landAndMoneyAreAtomic() {
        long startingBalance = economy.getBalance(alice);

        // Affordable: both happen.
        ClaimResult ok = claims.create(alice, WORLD, 0, 0, 9, 9, "Paid");
        assertTrue(ok.isSuccess());
        assertEquals(startingBalance - 100 * UNIT, economy.getBalance(alice));
        assertEquals(1, claims.claimCount());

        // Unaffordable: neither happens.
        ClaimResult refused = claims.create(alice, WORLD, 1_000, 1_000, 1_063, 1_063, "Unpaid");
        assertFalse(refused.isSuccess());
        assertEquals(startingBalance - 100 * UNIT, economy.getBalance(alice),
                "a refused claim must not have been charged for");
        assertEquals(1, claims.claimCount(), "and must not exist");
    }

    @Test
    @DisplayName("claims survive a restart, including members and flags")
    void claimsPersistAcrossRestart() throws Exception {
        ClaimResult created = claims.create(alice, WORLD, 0, 0, 9, 9, "Home");
        String id = created.claim().id();
        claims.setMember(alice, id, bob, TrustLevel.CONTAINER);
        claims.setFlag(alice, id, ClaimFlag.DOOR_USE, true);

        database.onDisable();
        database.onEnable();
        claims.loadIndex();

        Claim reloaded = claims.byId(id).orElseThrow();
        assertEquals("Home", reloaded.name());
        assertEquals(TrustLevel.CONTAINER, reloaded.trustOf(bob),
                "trust that does not survive a restart would silently unprotect a build");
        assertTrue(reloaded.isPublic(ClaimFlag.DOOR_USE));
        assertTrue(reloaded.allows(bob, ClaimFlag.CONTAINER_ACCESS));
        assertFalse(reloaded.allows(bob, ClaimFlag.BLOCK_BREAK));
    }

    @Test
    @DisplayName("overlapping an existing claim is refused and costs nothing")
    void overlapRefused() {
        claims.create(alice, WORLD, 0, 0, 9, 9, "First");
        long balance = economy.getBalance(bob);

        ClaimResult result = claims.create(bob, WORLD, 5, 5, 14, 14, "Second");

        assertEquals(ClaimResult.Outcome.OVERLAPS, result.outcome());
        assertEquals(balance, economy.getBalance(bob));
    }

    @Test
    @DisplayName("size limits are enforced")
    void sizeLimitsEnforced() {
        assertEquals(ClaimResult.Outcome.TOO_SMALL,
                claims.create(alice, WORLD, 0, 0, 2, 2, "Tiny").outcome());
        assertEquals(ClaimResult.Outcome.TOO_LARGE,
                claims.create(alice, WORLD, 0, 0, 100, 100, "Huge").outcome());
        assertEquals(1000 * UNIT, economy.getBalance(alice));
    }

    @Test
    @DisplayName("the per-player claim limit is enforced")
    void claimLimitEnforced() {
        assertTrue(claims.create(alice, WORLD, 0, 0, 4, 4, "One").isSuccess());
        assertTrue(claims.create(alice, WORLD, 20, 0, 24, 4, "Two").isSuccess());
        assertTrue(claims.create(alice, WORLD, 40, 0, 44, 4, "Three").isSuccess());

        ClaimResult fourth = claims.create(alice, WORLD, 60, 0, 64, 4, "Four");
        assertEquals(ClaimResult.Outcome.TOO_MANY_CLAIMS, fourth.outcome());
    }

    // ------------------------------------------------------------- resizing

    @Test
    @DisplayName("expanding charges only for the blocks added")
    void expansionChargesTheDifference() {
        ClaimResult created = claims.create(alice, WORLD, 0, 0, 9, 9, "Home");
        long afterCreate = economy.getBalance(alice);

        // 10x10 (100) grows to 12x12 (144): 44 new blocks at 1.00 each.
        ClaimResult expanded = claims.resize(alice, created.claim().id(), -1, -1, 10, 10);

        assertTrue(expanded.isSuccess());
        assertEquals(144, expanded.claim().area());
        assertEquals(44 * UNIT, expanded.cost());
        assertEquals(afterCreate - 44 * UNIT, economy.getBalance(alice));
    }

    @Test
    @DisplayName("shrinking is free and refunds nothing")
    void shrinkingIsFree() {
        ClaimResult created = claims.create(alice, WORLD, 0, 0, 19, 19, "Home");
        long afterCreate = economy.getBalance(alice);

        ClaimResult shrunk = claims.resize(alice, created.claim().id(), 0, 0, 9, 9);

        assertTrue(shrunk.isSuccess());
        assertEquals(100, shrunk.claim().area());
        assertEquals(0L, shrunk.cost());
        assertEquals(afterCreate, economy.getBalance(alice),
                "refunding per block would let a player farm the price difference");
        assertTrue(claims.claimAt(WORLD, 15, 15).isEmpty(), "the released land must be free");
    }

    @Test
    @DisplayName("only the owner may resize")
    void onlyOwnerResizes() {
        ClaimResult created = claims.create(alice, WORLD, 0, 0, 9, 9, "Home");
        claims.setMember(alice, created.claim().id(), bob, TrustLevel.MANAGE);

        ClaimResult attempt = claims.resize(bob, created.claim().id(), -5, -5, 15, 15);
        assertEquals(ClaimResult.Outcome.NOT_PERMITTED, attempt.outcome(),
                "a manager who can annex land is a second owner");
    }

    @Test
    @DisplayName("an unaffordable expansion leaves the claim untouched")
    void unaffordableExpansionChangesNothing() {
        ClaimResult created = claims.create(alice, WORLD, 0, 0, 9, 9, "Home");
        String id = created.claim().id();
        economy.setBalance(alice, 5 * UNIT, null, "test");

        ClaimResult attempt = claims.resize(alice, id, -20, -20, 29, 29);

        assertEquals(ClaimResult.Outcome.INSUFFICIENT_FUNDS, attempt.outcome());
        assertEquals(100, claims.byId(id).orElseThrow().area(), "bounds must be unchanged");
        assertEquals(5 * UNIT, economy.getBalance(alice));
    }

    // -------------------------------------------------------------- deleting

    @Test
    @DisplayName("deleting refunds the configured share and releases the land")
    void deleteRefundsAndReleases() {
        ClaimResult created = claims.create(alice, WORLD, 0, 0, 9, 9, "Home");
        long afterCreate = economy.getBalance(alice);

        ClaimResult deleted = claims.delete(alice, created.claim().id());

        assertTrue(deleted.isSuccess());
        assertEquals(50 * UNIT, deleted.cost(), "50% of the 100.00 paid");
        assertEquals(afterCreate + 50 * UNIT, economy.getBalance(alice));
        assertTrue(claims.claimAt(WORLD, 5, 5).isEmpty());
        assertEquals(0, claims.claimCount());
    }

    @Test
    @DisplayName("a deleted claim is gone after a restart too")
    void deletionPersists() {
        ClaimResult created = claims.create(alice, WORLD, 0, 0, 9, 9, "Home");
        claims.delete(alice, created.claim().id());

        claims.loadIndex();
        assertEquals(0, claims.claimCount());
    }

    @Test
    @DisplayName("only the owner may delete")
    void onlyOwnerDeletes() {
        ClaimResult created = claims.create(alice, WORLD, 0, 0, 9, 9, "Home");
        assertEquals(ClaimResult.Outcome.NOT_PERMITTED,
                claims.delete(bob, created.claim().id()).outcome());
    }

    // ------------------------------------------------------- members, flags

    @Test
    @DisplayName("a manager cannot promote anyone else to manager")
    void managerCannotCreateManagers() {
        ClaimResult created = claims.create(alice, WORLD, 0, 0, 9, 9, "Home");
        String id = created.claim().id();
        claims.setMember(alice, id, bob, TrustLevel.MANAGE);

        UUID carol = newPlayer("Carol");
        assertEquals(ClaimResult.Outcome.NOT_PERMITTED,
                claims.setMember(bob, id, carol, TrustLevel.MANAGE).outcome());
        assertTrue(claims.setMember(bob, id, carol, TrustLevel.BUILD).isSuccess(),
                "a manager may still add ordinary members");
    }

    @Test
    @DisplayName("transferring moves ownership and drops the new owner as a member")
    void transferMovesOwnership() {
        ClaimResult created = claims.create(alice, WORLD, 0, 0, 9, 9, "Home");
        String id = created.claim().id();
        claims.setMember(alice, id, bob, TrustLevel.BUILD);

        assertTrue(claims.transfer(alice, id, bob).isSuccess());

        Claim after = claims.byId(id).orElseThrow();
        assertTrue(after.isOwner(bob));
        assertEquals(null, after.trustOf(bob),
                "the owner should not also be listed as one of their own members");
        assertEquals(1, claims.ownedBy(bob).size());
        assertEquals(0, claims.ownedBy(alice).size());
    }

    @Test
    @DisplayName("clearing a flag override restores the default")
    void clearingFlagRestoresDefault() {
        ClaimResult created = claims.create(alice, WORLD, 0, 0, 9, 9, "Home");
        String id = created.claim().id();

        claims.setFlag(alice, id, ClaimFlag.PVP, false);
        assertFalse(claims.byId(id).orElseThrow().isPublic(ClaimFlag.PVP));

        claims.setFlag(alice, id, ClaimFlag.PVP, null);
        assertTrue(claims.byId(id).orElseThrow().isPublic(ClaimFlag.PVP),
                "PVP is public by default, so clearing the override must restore that");
    }

    // ------------------------------------------------------------ protection

    @Test
    @DisplayName("unclaimed land permits everything")
    void unclaimedLandIsUnprotected() {
        for (ClaimFlag flag : ClaimFlag.values()) {
            assertTrue(claims.allows(bob, WORLD, 5_000, 5_000, flag),
                    "a claim plugin must not restrict unclaimed land: " + flag);
        }
    }

    @Test
    @DisplayName("a stranger is blocked inside someone else's claim")
    void strangerBlockedInsideClaim() {
        claims.create(alice, WORLD, 0, 0, 9, 9, "Home");

        assertFalse(claims.allows(bob, WORLD, 5, 5, ClaimFlag.BLOCK_BREAK));
        assertTrue(claims.allows(alice, WORLD, 5, 5, ClaimFlag.BLOCK_BREAK));
        assertTrue(claims.allows(bob, WORLD, 10, 10, ClaimFlag.BLOCK_BREAK),
                "one block outside the claim is unprotected");
    }

    // ------------------------------------------------------------ concurrency

    /**
     * Two players racing to claim the same land. Exactly one may win: if both
     * succeeded they would own overlapping land, and every protection check on
     * the shared blocks would then depend on map iteration order.
     */
    @Test
    @DisplayName("concurrent claims on the same land cannot both succeed")
    void concurrentOverlappingClaims() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startLine = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();

        List<Future<?>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int seed = i;
            futures.add(pool.submit(() -> {
                startLine.await();
                UUID claimant = seed % 2 == 0 ? alice : bob;
                if (claims.create(claimant, WORLD, 0, 0, 9, 9, "Race" + seed).isSuccess()) {
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

        assertEquals(1, succeeded.get(), "only one claim may cover a given block");
        assertEquals(1, claims.claimCount());
    }
}
