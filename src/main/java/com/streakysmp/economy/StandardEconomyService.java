package com.streakysmp.economy;

import com.streakysmp.config.ConfigManager;
import com.streakysmp.data.Database;
import com.streakysmp.log.AuditAction;
import com.streakysmp.log.AuditEntry;
import com.streakysmp.log.AuditLog;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Default {@link EconomyService}.
 *
 * <h2>Why there is no balance cache</h2>
 * Caching balances would be the obvious optimisation and is a trap. Every
 * money-moving decision here is made by a conditional UPDATE evaluated by the
 * database, not by a value read beforehand, so a cache could not participate in
 * the decision anyway -- it could only serve reads, while adding a window in
 * which a player sees a stale figure and a real risk that some future call site
 * mistakes the cached value for authoritative. SQLite is a local file; a balance
 * read is microseconds. The complexity buys nothing and risks the one invariant
 * that matters.
 */
public final class StandardEconomyService implements EconomyService, ConfigManager.Reloadable {

    private final Database database;
    private final AccountRepository accounts;
    private final TransactionRepository transactions;
    private final AuditLog auditLog;
    private final Logger logger;

    /**
     * Last successful payment per player.
     *
     * <p>Deliberately in memory. Cooldowns are measured in seconds or minutes, and
     * a restart clearing them is harmless -- persisting them would mean a database
     * write on the hot path of every payment to enforce a courtesy limit.
     */
    private final Map<UUID, Long> lastPayment = new ConcurrentHashMap<>();

    private volatile EconomySettings settings;

    public StandardEconomyService(Database database,
                                  AccountRepository accounts,
                                  TransactionRepository transactions,
                                  AuditLog auditLog,
                                  Logger logger) {
        this.database = database;
        this.accounts = accounts;
        this.transactions = transactions;
        this.auditLog = auditLog;
        this.logger = logger;
        this.settings = null;
    }

    @Override
    public void load(ConfigManager.ConfigBundle configs) {
        this.settings = EconomySettings.from(configs.main().section("economy"));
    }

    @Override
    public EconomySettings settings() {
        EconomySettings snapshot = settings;
        if (snapshot == null) {
            throw new IllegalStateException("Economy settings accessed before configuration was loaded");
        }
        return snapshot;
    }

    @Override
    public long getBalance(UUID player) {
        return accounts.getBalance(player);
    }

    @Override
    public boolean canAfford(UUID player, long amount) {
        return amount >= 0 && accounts.getBalance(player) >= amount;
    }

    @Override
    public void ensureAccount(UUID player) {
        accounts.createAccount(player, settings().startingBalance());
    }

    @Override
    public void ensureAccountWithin(Connection connection, UUID player) throws SQLException {
        AccountRepository.ensureAccount(connection, player, settings().startingBalance());
    }

    @Override
    public EconomyResult deposit(UUID player, long amount, TransactionType type, String description) {
        if (amount <= 0) {
            return EconomyResult.failure(EconomyResult.Outcome.INVALID_AMOUNT, getBalance(player));
        }
        EconomySettings config = settings();
        return runGuarded(() -> database.inTransaction(connection -> {
            ensureAccountWithin(connection, player);
            if (!AccountRepository.tryCredit(connection, player, amount, config.maxBalance())) {
                return EconomyResult.failure(EconomyResult.Outcome.WOULD_EXCEED_MAX_BALANCE,
                        AccountRepository.balance(connection, player));
            }
            long id = TransactionRepository.record(connection, type, null, player, amount, 0L, description);
            return EconomyResult.success(AccountRepository.balance(connection, player), id);
        }), player);
    }

    @Override
    public EconomyResult withdraw(UUID player, long amount, TransactionType type, String description) {
        if (amount <= 0) {
            return EconomyResult.failure(EconomyResult.Outcome.INVALID_AMOUNT, getBalance(player));
        }
        return runGuarded(() -> database.inTransaction(connection -> {
            ensureAccountWithin(connection, player);
            if (!AccountRepository.tryDebit(connection, player, amount)) {
                return EconomyResult.insufficientFunds(
                        AccountRepository.balance(connection, player), amount);
            }
            long id = TransactionRepository.record(connection, type, player, null, amount, 0L, description);
            return EconomyResult.success(AccountRepository.balance(connection, player), id);
        }), player);
    }

    @Override
    public EconomyResult transfer(UUID from, UUID to, long amount,
                                  TransactionType type, String description) {
        if (amount <= 0) {
            return EconomyResult.failure(EconomyResult.Outcome.INVALID_AMOUNT, getBalance(from));
        }
        EconomySettings config = settings();
        return runGuarded(() -> database.inTransaction(connection -> {
            ensureAccountWithin(connection, from);
            ensureAccountWithin(connection, to);

            if (!AccountRepository.tryDebit(connection, from, amount)) {
                return EconomyResult.insufficientFunds(
                        AccountRepository.balance(connection, from), amount);
            }
            if (!AccountRepository.tryCredit(connection, to, amount, config.maxBalance())) {
                // The debit already happened in this transaction. Throwing rolls
                // the whole thing back, which is the only correct outcome: the
                // alternative is money leaving one account and reaching no other.
                throw new TransferRolledBack(EconomyResult.Outcome.WOULD_EXCEED_MAX_BALANCE);
            }
            long id = TransactionRepository.record(connection, type, from, to, amount, 0L, description);
            return EconomyResult.success(AccountRepository.balance(connection, from), id);
        }), from);
    }

    @Override
    public EconomyResult pay(UUID from, UUID to, long amount) {
        EconomySettings config = settings();
        long balance = getBalance(from);

        if (amount <= 0) {
            return EconomyResult.failure(EconomyResult.Outcome.INVALID_AMOUNT, balance);
        }
        if (from.equals(to) && !config.allowSelfPayment()) {
            return EconomyResult.failure(EconomyResult.Outcome.SELF_PAYMENT, balance);
        }
        if (amount < config.minimumPayment()) {
            return EconomyResult.failure(EconomyResult.Outcome.BELOW_MINIMUM, balance);
        }
        if (amount > config.maximumPayment()) {
            return EconomyResult.failure(EconomyResult.Outcome.ABOVE_MAXIMUM, balance);
        }
        if (remainingCooldownMillis(from) > 0) {
            return EconomyResult.failure(EconomyResult.Outcome.COOLDOWN_ACTIVE, balance);
        }

        long fee = config.feeFor(amount);
        long total;
        try {
            total = Math.addExact(amount, fee);
        } catch (ArithmeticException e) {
            return EconomyResult.failure(EconomyResult.Outcome.INVALID_AMOUNT, balance);
        }

        EconomyResult result = runGuarded(() -> database.inTransaction(connection -> {
            ensureAccountWithin(connection, from);
            ensureAccountWithin(connection, to);

            // One statement debits amount + fee and proves affordability. The fee
            // is not credited anywhere, so it leaves circulation entirely.
            if (!AccountRepository.tryDebit(connection, from, total)) {
                return EconomyResult.insufficientFunds(
                        AccountRepository.balance(connection, from), total);
            }
            if (!AccountRepository.tryCredit(connection, to, amount, config.maxBalance())) {
                throw new TransferRolledBack(EconomyResult.Outcome.WOULD_EXCEED_MAX_BALANCE);
            }

            long id = TransactionRepository.record(connection, TransactionType.PAY,
                    from, to, amount, fee, null);
            if (fee > 0) {
                TransactionRepository.record(connection, TransactionType.TRANSFER_FEE,
                        from, null, fee, 0L, "Fee on transaction #" + id);
            }
            return EconomyResult.success(AccountRepository.balance(connection, from), id);
        }), from);

        if (result.isSuccess()) {
            lastPayment.put(from, System.currentTimeMillis());
            auditLog.record(AuditEntry.builder(AuditAction.PAY)
                    .actor(from, null)
                    .target(to, null)
                    .amount(amount)
                    .object("tx:" + result.transactionId())
                    .details(fee > 0 ? "fee=" + fee : null)
                    .build());
        }
        return result;
    }

    @Override
    public EconomyResult setBalance(UUID player, long balance, UUID administrator, String reason) {
        if (balance < 0) {
            return EconomyResult.failure(EconomyResult.Outcome.INVALID_AMOUNT, getBalance(player));
        }
        EconomySettings config = settings();
        if (balance > config.maxBalance()) {
            return EconomyResult.failure(EconomyResult.Outcome.WOULD_EXCEED_MAX_BALANCE, getBalance(player));
        }

        EconomyResult result = runGuarded(() -> database.inTransaction(connection -> {
            ensureAccountWithin(connection, player);
            long previous = AccountRepository.balance(connection, player);
            AccountRepository.setBalance(connection, player, balance);

            long delta = balance - previous;
            long id = TransactionRepository.record(connection, TransactionType.ADMIN,
                    delta < 0 ? player : null,
                    delta > 0 ? player : null,
                    Math.abs(delta), 0L,
                    reason == null ? "Administrative adjustment" : reason);
            return EconomyResult.success(balance, id);
        }), player);

        if (result.isSuccess()) {
            auditLog.record(AuditEntry.builder(AuditAction.ADMIN_BALANCE_CHANGE)
                    .actor(administrator, null)
                    .target(player, null)
                    .amount(balance)
                    .object("tx:" + result.transactionId())
                    .details(reason)
                    .build());
        }
        return result;
    }

    @Override
    public boolean debitWithin(Connection connection, UUID player, long amount,
                               TransactionType type, String description) throws SQLException {
        if (amount < 0) {
            throw new IllegalArgumentException("Debit amount must not be negative: " + amount);
        }
        ensureAccountWithin(connection, player);
        if (!AccountRepository.tryDebit(connection, player, amount)) {
            return false;
        }
        TransactionRepository.record(connection, type, player, null, amount, 0L, description);
        return true;
    }

    @Override
    public boolean creditWithin(Connection connection, UUID player, long amount,
                                TransactionType type, String description) throws SQLException {
        if (amount < 0) {
            throw new IllegalArgumentException("Credit amount must not be negative: " + amount);
        }
        ensureAccountWithin(connection, player);
        if (!AccountRepository.tryCredit(connection, player, amount, settings().maxBalance())) {
            return false;
        }
        TransactionRepository.record(connection, type, null, player, amount, 0L, description);
        return true;
    }

    @Override
    public long remainingCooldownMillis(UUID player) {
        EconomySettings config = settings();
        if (!config.hasCooldown()) {
            return 0L;
        }
        Long last = lastPayment.get(player);
        if (last == null) {
            return 0L;
        }
        long elapsed = System.currentTimeMillis() - last;
        long cooldown = config.paymentCooldown().toMillis();
        return Math.max(0L, cooldown - elapsed);
    }

    /** Signals that a transaction must roll back and why. */
    private static final class TransferRolledBack extends RuntimeException {
        private final EconomyResult.Outcome outcome;

        TransferRolledBack(EconomyResult.Outcome outcome) {
            super(outcome.name(), null, false, false);
            this.outcome = outcome;
        }
    }

    /**
     * Converts a rolled-back transfer or an unexpected failure into a result.
     *
     * <p>A database failure must never surface to a player as a stack trace, and
     * must never be mistaken for success.
     */
    private EconomyResult runGuarded(java.util.function.Supplier<EconomyResult> work, UUID subject) {
        try {
            return work.get();
        } catch (TransferRolledBack rolledBack) {
            return EconomyResult.failure(rolledBack.outcome, getBalance(subject));
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Economy operation failed and was rolled back", e);
            return EconomyResult.failure(EconomyResult.Outcome.FAILED, safeBalance(subject));
        }
    }

    private long safeBalance(UUID player) {
        try {
            return accounts.getBalance(player);
        } catch (Exception e) {
            return 0L;
        }
    }

    /** Clears a player's cooldown when they disconnect, freeing the map entry. */
    public void forgetCooldown(UUID player) {
        lastPayment.remove(player);
    }

    public AccountRepository accounts() {
        return accounts;
    }

    public TransactionRepository transactions() {
        return transactions;
    }
}
