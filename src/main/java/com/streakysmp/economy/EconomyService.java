package com.streakysmp.economy;

import com.streakysmp.core.Service;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

/**
 * The only way money moves.
 *
 * <p>No other module touches {@code sc_account}. Every balance change goes
 * through here so that validation, the ledger write and the audit entry cannot be
 * skipped by a module that only meant to take a shortcut.
 *
 * <h2>Threading</h2>
 * Every method blocks on the database and must be called off the main thread.
 * {@link com.streakysmp.data.Database} enforces this.
 *
 * <h2>Composition</h2>
 * Modules that must move money and something else atomically -- the auction house
 * transferring currency and an item, a shop taking payment and giving blocks --
 * use the {@code *Within} methods, which join the caller's existing transaction
 * instead of opening their own. The money still moves through this service; only
 * the transaction boundary belongs to the caller.
 */
public interface EconomyService extends Service {

    /** The active configuration snapshot. */
    EconomySettings settings();

    /** Convenience accessor for formatting. */
    default Money money() {
        return settings().money();
    }

    /** Current balance in minor units. Returns 0 for an account that does not exist. */
    long getBalance(UUID player);

    /**
     * Whether the account currently covers {@code amount}.
     *
     * <p><strong>Advisory only.</strong> Never gate a real payment on this: between
     * the check and the debit, another thread can spend the money. It exists to
     * grey out a button or phrase an error, and the authoritative check is the
     * conditional update inside the debit itself.
     */
    boolean canAfford(UUID player, long amount);

    /** Creates the account with the configured starting balance if it does not exist. */
    void ensureAccount(UUID player);

    /**
     * Adds money to an account, creating money in the process.
     *
     * <p>Use a {@link TransactionType} whose flow is {@code SOURCE} unless this is
     * the receiving half of a transfer, so economy reports stay honest.
     */
    EconomyResult deposit(UUID player, long amount, TransactionType type, String description);

    /** Removes money from an account, destroying it. */
    EconomyResult withdraw(UUID player, long amount, TransactionType type, String description);

    /**
     * Moves money between two accounts atomically, creating none.
     *
     * <p>No fee is applied; this is the primitive. Player-facing {@code /pay} rules
     * live in {@link #pay}.
     */
    EconomyResult transfer(UUID from, UUID to, long amount, TransactionType type, String description);

    /**
     * A player-initiated payment, applying every configured rule: minimum,
     * maximum, cooldown, self-payment, and the transfer fee.
     *
     * <p>The fee is destroyed rather than paid to anyone, making it a money sink.
     * The sender is debited {@code amount + fee}; the recipient receives
     * {@code amount}.
     */
    EconomyResult pay(UUID from, UUID to, long amount);

    /** Sets a balance outright, for administrators. Always audited. */
    EconomyResult setBalance(UUID player, long balance, UUID administrator, String reason);

    // ------------------------------------------------------- composition

    /**
     * Debits inside the caller's transaction.
     *
     * <p>Returns false if the balance does not cover it, having changed nothing.
     * The caller must roll its transaction back or handle the refusal; this method
     * does not throw for insufficient funds.
     */
    boolean debitWithin(Connection connection, UUID player, long amount,
                        TransactionType type, String description) throws SQLException;

    /** Credits inside the caller's transaction. False if it would exceed the balance ceiling. */
    boolean creditWithin(Connection connection, UUID player, long amount,
                         TransactionType type, String description) throws SQLException;

    /** Ensures an account exists inside the caller's transaction. */
    void ensureAccountWithin(Connection connection, UUID player) throws SQLException;

    /** Remaining payment cooldown in milliseconds, or 0 if the player may pay now. */
    long remainingCooldownMillis(UUID player);
}
