package com.servercore.economy;

import com.servercore.data.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The append-only transaction ledger.
 *
 * <p>Entries are never updated or deleted. A correction is a new, opposing entry,
 * so the ledger always explains how the current balances came to be. That is what
 * makes a duplication claim investigable rather than a matter of opinion.
 */
public final class TransactionRepository {

    private final Database database;

    public TransactionRepository(Database database) {
        this.database = database;
    }

    /**
     * Appends an entry inside an existing transaction.
     *
     * <p>Takes the connection so the ledger write commits or rolls back together
     * with the balance changes it describes. A ledger that can disagree with the
     * balances is worse than no ledger.
     *
     * @return the generated transaction id
     */
    public static long record(Connection connection,
                              TransactionType type,
                              UUID from,
                              UUID to,
                              long amount,
                              long fee,
                              String description) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO sc_transaction
                    (created_at, type, from_uuid, to_uuid, amount, fee, description)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setString(2, type.name());
            setNullableUuid(ps, 3, from);
            setNullableUuid(ps, 4, to);
            ps.setLong(5, amount);
            ps.setLong(6, fee);
            if (description == null) {
                ps.setNull(7, Types.VARCHAR);
            } else {
                ps.setString(7, description);
            }
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : -1L;
            }
        }
    }

    /** Appends an entry in its own transaction. */
    public long record(TransactionType type, UUID from, UUID to, long amount, long fee, String description) {
        return database.inTransaction(connection ->
                record(connection, type, from, to, amount, fee, description));
    }

    /**
     * A player's transaction history, newest first.
     *
     * <p>Matches entries where the player is either side, since a history that
     * showed only outgoing payments would be useless.
     */
    public List<Transaction> historyFor(UUID uuid, int limit, int offset) {
        return database.withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement("""
                    SELECT id, created_at, type, from_uuid, to_uuid, amount, fee, description
                    FROM sc_transaction
                    WHERE from_uuid = ? OR to_uuid = ?
                    ORDER BY created_at DESC, id DESC
                    LIMIT ? OFFSET ?
                    """)) {
                String id = uuid.toString();
                ps.setString(1, id);
                ps.setString(2, id);
                ps.setInt(3, Math.max(1, limit));
                ps.setInt(4, Math.max(0, offset));
                return readAll(ps);
            }
        });
    }

    public int historyCountFor(UUID uuid) {
        return database.withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT COUNT(*) FROM sc_transaction WHERE from_uuid = ? OR to_uuid = ?")) {
                String id = uuid.toString();
                ps.setString(1, id);
                ps.setString(2, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        });
    }

    public Optional<Transaction> byId(long transactionId) {
        return database.withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement("""
                    SELECT id, created_at, type, from_uuid, to_uuid, amount, fee, description
                    FROM sc_transaction WHERE id = ?
                    """)) {
                ps.setLong(1, transactionId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(map(rs)) : Optional.<Transaction>empty();
                }
            }
        });
    }

    /** Server-wide recent activity, for the admin economy screen. */
    public List<Transaction> recent(int limit, int offset) {
        return database.withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement("""
                    SELECT id, created_at, type, from_uuid, to_uuid, amount, fee, description
                    FROM sc_transaction
                    ORDER BY created_at DESC, id DESC
                    LIMIT ? OFFSET ?
                    """)) {
                ps.setInt(1, Math.max(1, limit));
                ps.setInt(2, Math.max(0, offset));
                return readAll(ps);
            }
        });
    }

    /**
     * Total money moved per transaction type since {@code since}.
     *
     * <p>Grouped by type so an operator can see sources against sinks and tell
     * whether the economy is inflating. This is the question the spec's
     * money-creation/money-sink split exists to answer.
     */
    public Map<TransactionType, Long> volumeByTypeSince(long since) {
        return database.withConnection(connection -> {
            Map<TransactionType, Long> out = new EnumMap<>(TransactionType.class);
            try (PreparedStatement ps = connection.prepareStatement("""
                    SELECT type, COALESCE(SUM(amount), 0) AS total
                    FROM sc_transaction
                    WHERE created_at >= ?
                    GROUP BY type
                    """)) {
                ps.setLong(1, since);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        parseType(rs.getString("type"))
                                .ifPresent(type -> out.put(type, rs2Long(rs)));
                    }
                }
            }
            return Map.copyOf(out);
        });
    }

    private static long rs2Long(ResultSet rs) {
        try {
            return rs.getLong("total");
        } catch (SQLException e) {
            return 0L;
        }
    }

    private static List<Transaction> readAll(PreparedStatement ps) throws SQLException {
        List<Transaction> out = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(map(rs));
            }
        }
        return List.copyOf(out);
    }

    private static Transaction map(ResultSet rs) throws SQLException {
        return new Transaction(
                rs.getLong("id"),
                rs.getLong("created_at"),
                parseType(rs.getString("type")).orElse(TransactionType.OTHER),
                readNullableUuid(rs, "from_uuid"),
                readNullableUuid(rs, "to_uuid"),
                rs.getLong("amount"),
                rs.getLong("fee"),
                rs.getString("description"));
    }

    /**
     * Tolerates unknown type names.
     *
     * <p>A ledger row written by a newer build must not break history for an older
     * one. The entry still displays, categorised as OTHER.
     */
    private static Optional<TransactionType> parseType(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(TransactionType.valueOf(raw));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static void setNullableUuid(PreparedStatement ps, int index, UUID uuid) throws SQLException {
        if (uuid == null) {
            ps.setNull(index, Types.VARCHAR);
        } else {
            ps.setString(index, uuid.toString());
        }
    }

    private static UUID readNullableUuid(ResultSet rs, String column) throws SQLException {
        String raw = rs.getString(column);
        return raw == null ? null : UUID.fromString(raw);
    }
}
