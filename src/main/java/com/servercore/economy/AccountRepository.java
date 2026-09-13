package com.servercore.economy;

import com.servercore.data.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Balance storage.
 *
 * <p>The mutating methods take an explicit {@link Connection} so several of them
 * can be composed inside one transaction -- a transfer is a debit and a credit
 * and a ledger write, and either all of it happens or none of it does.
 *
 * <p>Debits and credits are expressed as <em>conditional</em> updates whose
 * affected-row count is the answer. That is the single most important detail in
 * the economy: the check and the change are one atomic statement, so two
 * concurrent withdrawals cannot both observe a sufficient balance and both
 * proceed. Reading a balance and then deciding would be a race no amount of
 * application-level locking fixes cleanly.
 */
public final class AccountRepository {

    private final Database database;

    public AccountRepository(Database database) {
        this.database = database;
    }

    /** A balance with its owner, for leaderboards. */
    public record BalanceEntry(UUID uuid, String name, long balance) {
    }

    // ---------------------------------------------------- connection-scoped

    /**
     * Creates the account if absent, seeded with the starting balance.
     *
     * <p>{@code INSERT OR IGNORE} makes this idempotent, which matters because it
     * runs on every join: the starting balance is granted exactly once, and
     * raising it later in configuration does not re-grant it to existing players.
     */
    public static void ensureAccount(Connection connection, UUID uuid, long startingBalance)
            throws SQLException {
        long now = System.currentTimeMillis();
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT OR IGNORE INTO sc_account (uuid, balance, created_at, updated_at)
                VALUES (?, ?, ?, ?)
                """)) {
            ps.setString(1, uuid.toString());
            ps.setLong(2, startingBalance);
            ps.setLong(3, now);
            ps.setLong(4, now);
            ps.executeUpdate();
        }
    }

    /** Reads a balance inside an existing transaction. Returns 0 if there is no account. */
    public static long balance(Connection connection, UUID uuid) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT balance FROM sc_account WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    public static boolean accountExists(Connection connection, UUID uuid) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT 1 FROM sc_account WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * Removes {@code amount} only if the balance covers it.
     *
     * @return true if the debit happened; false means insufficient funds and
     *         nothing was changed
     */
    public static boolean tryDebit(Connection connection, UUID uuid, long amount) throws SQLException {
        if (amount < 0) {
            throw new IllegalArgumentException("Debit amount must not be negative: " + amount);
        }
        if (amount == 0) {
            return true;
        }
        try (PreparedStatement ps = connection.prepareStatement("""
                UPDATE sc_account
                SET balance = balance - ?, updated_at = ?
                WHERE uuid = ? AND balance >= ?
                """)) {
            ps.setLong(1, amount);
            ps.setLong(2, System.currentTimeMillis());
            ps.setString(3, uuid.toString());
            ps.setLong(4, amount);
            return ps.executeUpdate() == 1;
        }
    }

    /**
     * Adds {@code amount} unless it would push the account past {@code maxBalance}.
     *
     * <p>The ceiling is enforced in the same statement for the same reason debits
     * are: checking separately would let two concurrent credits both pass the
     * check and jointly exceed it.
     *
     * @return true if the credit happened
     */
    public static boolean tryCredit(Connection connection, UUID uuid, long amount, long maxBalance)
            throws SQLException {
        if (amount < 0) {
            throw new IllegalArgumentException("Credit amount must not be negative: " + amount);
        }
        if (amount == 0) {
            return true;
        }
        try (PreparedStatement ps = connection.prepareStatement("""
                UPDATE sc_account
                SET balance = balance + ?, updated_at = ?
                WHERE uuid = ? AND balance + ? <= ?
                """)) {
            ps.setLong(1, amount);
            ps.setLong(2, System.currentTimeMillis());
            ps.setString(3, uuid.toString());
            ps.setLong(4, amount);
            ps.setLong(5, maxBalance);
            return ps.executeUpdate() == 1;
        }
    }

    /** Overwrites a balance outright. Administrative use only. */
    public static void setBalance(Connection connection, UUID uuid, long balance) throws SQLException {
        if (balance < 0) {
            throw new IllegalArgumentException("Balance must not be negative: " + balance);
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE sc_account SET balance = ?, updated_at = ? WHERE uuid = ?")) {
            ps.setLong(1, balance);
            ps.setLong(2, System.currentTimeMillis());
            ps.setString(3, uuid.toString());
            ps.executeUpdate();
        }
    }

    // ---------------------------------------------------------- standalone

    public long getBalance(UUID uuid) {
        return database.withConnection(connection -> balance(connection, uuid));
    }

    public boolean hasAccount(UUID uuid) {
        return database.withConnection(connection -> accountExists(connection, uuid));
    }

    /** Creates the account if absent, in its own transaction. */
    public void createAccount(UUID uuid, long startingBalance) {
        database.inTransaction(connection -> {
            ensureAccount(connection, uuid, startingBalance);
            return null;
        });
    }

    /** Richest accounts first, for the money leaderboard. */
    public List<BalanceEntry> topBalances(int limit) {
        return database.withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement("""
                    SELECT a.uuid, p.name, a.balance
                    FROM sc_account a
                    JOIN sc_player p ON p.uuid = a.uuid
                    ORDER BY a.balance DESC, p.name ASC
                    LIMIT ?
                    """)) {
                ps.setInt(1, Math.max(1, limit));
                List<BalanceEntry> out = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new BalanceEntry(
                                UUID.fromString(rs.getString("uuid")),
                                rs.getString("name"),
                                rs.getLong("balance")));
                    }
                }
                return List.copyOf(out);
            }
        });
    }

    /**
     * A player's position in the balance ranking, 1-based.
     *
     * @return the rank, or 0 if the player has no account
     */
    public int rankOf(UUID uuid) {
        return database.withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement("""
                    SELECT COUNT(*) + 1
                    FROM sc_account
                    WHERE balance > (SELECT balance FROM sc_account WHERE uuid = ?)
                    """)) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        });
    }

    /** Total money in circulation, for economy diagnostics. */
    public long totalSupply() {
        return database.withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT COALESCE(SUM(balance), 0) FROM sc_account");
                 ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        });
    }

    public int accountCount() {
        return database.withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT COUNT(*) FROM sc_account");
                 ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        });
    }
}
