package com.streakysmp.plot;

import com.streakysmp.data.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for spawn plots and the recovery mailbox.
 */
public final class PlotRepository {

    private final Database database;

    public PlotRepository(Database database) {
        this.database = database;
    }

    /** Items held for a player whose shop was closed. */
    public record MailboxEntry(long id, UUID player, String plotId, byte[] itemData, long storedAt) {
    }

    // ------------------------------------------------------------- writing

    public void save(SpawnPlot plot) {
        database.inTransaction(connection -> {
            saveWithin(connection, plot);
            return null;
        });
    }

    public void saveWithin(Connection connection, SpawnPlot plot) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO sc_plot
                    (id, world, min_x, min_z, max_x, max_z, purchase_price, rent_price,
                     rent_period, owner_uuid, status, purchased_at, rent_due_at,
                     grace_ends_at, rent_paid, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    world          = excluded.world,
                    min_x          = excluded.min_x,
                    min_z          = excluded.min_z,
                    max_x          = excluded.max_x,
                    max_z          = excluded.max_z,
                    purchase_price = excluded.purchase_price,
                    rent_price     = excluded.rent_price,
                    rent_period    = excluded.rent_period,
                    owner_uuid     = excluded.owner_uuid,
                    status         = excluded.status,
                    purchased_at   = excluded.purchased_at,
                    rent_due_at    = excluded.rent_due_at,
                    grace_ends_at  = excluded.grace_ends_at,
                    rent_paid      = excluded.rent_paid
                """)) {
            ps.setString(1, plot.id());
            ps.setString(2, plot.worldName());
            ps.setInt(3, plot.minX());
            ps.setInt(4, plot.minZ());
            ps.setInt(5, plot.maxX());
            ps.setInt(6, plot.maxZ());
            ps.setLong(7, plot.purchasePrice());
            ps.setLong(8, plot.rentPrice());
            ps.setString(9, plot.rentPeriod().name());
            setNullableUuid(ps, 10, plot.owner());
            ps.setString(11, plot.status().name());
            setNullableLong(ps, 12, plot.purchasedAt());
            setNullableLong(ps, 13, plot.rentDueAt());
            setNullableLong(ps, 14, plot.graceEndsAt());
            ps.setLong(15, plot.rentPaid());
            ps.setLong(16, plot.createdAt());
            ps.executeUpdate();
        }
    }

    public boolean delete(String plotId) {
        return database.inTransaction(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM sc_plot WHERE id = ?")) {
                ps.setString(1, plotId);
                return ps.executeUpdate() > 0;
            }
        });
    }

    // ------------------------------------------------------------- reading

    public Optional<SpawnPlot> byId(String plotId) {
        return database.withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT * FROM sc_plot WHERE id = ?")) {
                ps.setString(1, plotId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(map(rs)) : Optional.<SpawnPlot>empty();
                }
            }
        });
    }

    /** Reads a plot inside the caller's transaction, for re-checking before a purchase. */
    public static Optional<SpawnPlot> byIdWithin(Connection connection, String plotId)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM sc_plot WHERE id = ?")) {
            ps.setString(1, plotId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    public List<SpawnPlot> all() {
        return database.withConnection(connection -> readAll(connection,
                "SELECT * FROM sc_plot ORDER BY created_at"));
    }

    public List<SpawnPlot> ownedBy(UUID owner) {
        return database.withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT * FROM sc_plot WHERE owner_uuid = ? ORDER BY purchased_at")) {
                ps.setString(1, owner.toString());
                return collect(ps);
            }
        });
    }

    public int countOwnedBy(UUID owner) {
        return database.withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT COUNT(*) FROM sc_plot WHERE owner_uuid = ?")) {
                ps.setString(1, owner.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        });
    }

    public static int countOwnedWithin(Connection connection, UUID owner) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM sc_plot WHERE owner_uuid = ?")) {
            ps.setString(1, owner.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /**
     * Plots whose rent is due, in due-date order.
     *
     * <p>Indexed on {@code rent_due_at}, so the sweep costs one range scan rather
     * than a walk over every plot on the server.
     */
    public List<SpawnPlot> withRentDue(long now) {
        return database.withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement("""
                    SELECT * FROM sc_plot
                    WHERE rent_due_at IS NOT NULL
                      AND rent_due_at <= ?
                      AND owner_uuid IS NOT NULL
                      AND status IN ('OWNED', 'RENT_OVERDUE', 'EXPIRED')
                    ORDER BY rent_due_at
                    """)) {
                ps.setLong(1, now);
                return collect(ps);
            }
        });
    }

    /** Expired plots whose cleanup period has elapsed and which should be released. */
    public List<SpawnPlot> readyForRelease(long cutoff) {
        return database.withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement("""
                    SELECT * FROM sc_plot
                    WHERE status = 'EXPIRED'
                      AND grace_ends_at IS NOT NULL
                      AND grace_ends_at <= ?
                    ORDER BY grace_ends_at
                    """)) {
                ps.setLong(1, cutoff);
                return collect(ps);
            }
        });
    }

    public List<SpawnPlot> available() {
        return database.withConnection(connection -> readAll(connection,
                "SELECT * FROM sc_plot WHERE status = 'AVAILABLE' ORDER BY purchase_price"));
    }

    /** Whether any plot overlaps the given bounds, checked inside a transaction. */
    public static boolean overlapsWithin(Connection connection, SpawnPlot candidate, String ignoreId)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT 1 FROM sc_plot
                WHERE world = ?
                  AND id <> ?
                  AND min_x <= ? AND max_x >= ?
                  AND min_z <= ? AND max_z >= ?
                LIMIT 1
                """)) {
            ps.setString(1, candidate.worldName());
            ps.setString(2, ignoreId == null ? "" : ignoreId);
            ps.setInt(3, candidate.maxX());
            ps.setInt(4, candidate.minX());
            ps.setInt(5, candidate.maxZ());
            ps.setInt(6, candidate.minZ());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    // ------------------------------------------------------------- mailbox

    /**
     * Stores items for later collection.
     *
     * <p>Serialised with Paper's own item serialisation, so enchantments, custom
     * names and data components all survive. The spec forbids silently destroying
     * shop inventory, and anything less faithful than a full round-trip would be
     * destroying part of it.
     */
    public void storeInMailbox(UUID player, String plotId, byte[] itemData) {
        database.inTransaction(connection -> {
            try (PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO sc_plot_mailbox (player_uuid, plot_id, item_data, stored_at, collected)
                    VALUES (?, ?, ?, ?, 0)
                    """)) {
                ps.setString(1, player.toString());
                ps.setString(2, plotId);
                ps.setBytes(3, itemData);
                ps.setLong(4, System.currentTimeMillis());
                ps.executeUpdate();
            }
            return null;
        });
    }

    public List<MailboxEntry> pendingMail(UUID player) {
        return database.withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement("""
                    SELECT id, player_uuid, plot_id, item_data, stored_at
                    FROM sc_plot_mailbox
                    WHERE player_uuid = ? AND collected = 0
                    ORDER BY stored_at
                    """)) {
                ps.setString(1, player.toString());
                List<MailboxEntry> entries = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        entries.add(new MailboxEntry(
                                rs.getLong("id"),
                                UUID.fromString(rs.getString("player_uuid")),
                                rs.getString("plot_id"),
                                rs.getBytes("item_data"),
                                rs.getLong("stored_at")));
                    }
                }
                return List.copyOf(entries);
            }
        });
    }

    /**
     * Marks one mailbox entry collected.
     *
     * <p>Conditional on it still being uncollected, so two clicks in quick
     * succession cannot hand the same items over twice.
     *
     * @return true if this call is the one that claimed it
     */
    public boolean markCollected(long entryId) {
        return database.inTransaction(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE sc_plot_mailbox SET collected = 1 WHERE id = ? AND collected = 0")) {
                ps.setLong(1, entryId);
                return ps.executeUpdate() == 1;
            }
        });
    }

    public int pendingMailCount(UUID player) {
        return database.withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT COUNT(*) FROM sc_plot_mailbox WHERE player_uuid = ? AND collected = 0")) {
                ps.setString(1, player.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        });
    }

    // ------------------------------------------------------------- mapping

    private static List<SpawnPlot> readAll(Connection connection, String sql) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            return collect(ps);
        }
    }

    private static List<SpawnPlot> collect(PreparedStatement ps) throws SQLException {
        List<SpawnPlot> plots = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                plots.add(map(rs));
            }
        }
        return List.copyOf(plots);
    }

    private static SpawnPlot map(ResultSet rs) throws SQLException {
        String ownerRaw = rs.getString("owner_uuid");
        String periodRaw = rs.getString("rent_period");
        return new SpawnPlot(
                rs.getString("id"),
                rs.getString("world"),
                rs.getInt("min_x"),
                rs.getInt("min_z"),
                rs.getInt("max_x"),
                rs.getInt("max_z"),
                rs.getLong("purchase_price"),
                rs.getLong("rent_price"),
                RentPeriod.byName(periodRaw).orElse(RentPeriod.WEEKLY),
                ownerRaw == null ? null : UUID.fromString(ownerRaw),
                PlotStatus.byName(rs.getString("status")).orElse(PlotStatus.DISABLED),
                readNullableLong(rs, "purchased_at"),
                readNullableLong(rs, "rent_due_at"),
                readNullableLong(rs, "grace_ends_at"),
                rs.getLong("rent_paid"),
                rs.getLong("created_at"));
    }

    private static Long readNullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static void setNullableLong(PreparedStatement ps, int index, Long value)
            throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.INTEGER);
        } else {
            ps.setLong(index, value);
        }
    }

    private static void setNullableUuid(PreparedStatement ps, int index, UUID value)
            throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.VARCHAR);
        } else {
            ps.setString(index, value.toString());
        }
    }
}
