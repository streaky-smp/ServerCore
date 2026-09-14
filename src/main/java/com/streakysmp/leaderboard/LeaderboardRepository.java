package com.streakysmp.leaderboard;

import com.streakysmp.data.Database;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence for leaderboard placements. */
public final class LeaderboardRepository {

    private final Database database;

    public LeaderboardRepository(Database database) {
        this.database = database;
    }

    public void save(Leaderboard leaderboard) {
        database.inTransaction(connection -> {
            try (PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO sc_leaderboard
                        (id, stat, world, x, y, z, size, title, created_at, created_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(id) DO UPDATE SET
                        stat = excluded.stat, world = excluded.world,
                        x = excluded.x, y = excluded.y, z = excluded.z,
                        size = excluded.size, title = excluded.title
                    """)) {
                ps.setString(1, leaderboard.id());
                ps.setString(2, leaderboard.stat().name());
                ps.setString(3, leaderboard.worldName());
                ps.setDouble(4, leaderboard.x());
                ps.setDouble(5, leaderboard.y());
                ps.setDouble(6, leaderboard.z());
                ps.setInt(7, leaderboard.size());
                if (leaderboard.title() == null) {
                    ps.setNull(8, Types.VARCHAR);
                } else {
                    ps.setString(8, leaderboard.title());
                }
                ps.setLong(9, leaderboard.createdAt());
                if (leaderboard.createdBy() == null) {
                    ps.setNull(10, Types.VARCHAR);
                } else {
                    ps.setString(10, leaderboard.createdBy().toString());
                }
                ps.executeUpdate();
            }
            return null;
        });
    }

    public boolean delete(String id) {
        return database.inTransaction(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM sc_leaderboard WHERE id = ?")) {
                ps.setString(1, id);
                return ps.executeUpdate() > 0;
            }
        });
    }

    public Optional<Leaderboard> byId(String id) {
        return database.withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT * FROM sc_leaderboard WHERE id = ?")) {
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(map(rs)) : Optional.<Leaderboard>empty();
                }
            }
        });
    }

    public List<Leaderboard> all() {
        return database.withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT * FROM sc_leaderboard ORDER BY created_at");
                 ResultSet rs = ps.executeQuery()) {
                List<Leaderboard> out = new ArrayList<>();
                while (rs.next()) {
                    // A row whose statistic no longer exists in this build is
                    // skipped rather than fatal: the placement stays in the
                    // database in case a later version restores the statistic.
                    try {
                        out.add(map(rs));
                    } catch (IllegalArgumentException ignored) {
                        // Unknown stat or out-of-range size; leave it alone.
                    }
                }
                return List.copyOf(out);
            }
        });
    }

    public boolean exists(String id) {
        return database.withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT 1 FROM sc_leaderboard WHERE id = ?")) {
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        });
    }

    private static Leaderboard map(ResultSet rs) throws SQLException {
        // Read before the lambda: ResultSet accessors throw a checked exception,
        // which a Supplier cannot propagate.
        String statKey = rs.getString("stat");
        LeaderboardStat stat = LeaderboardStat.byKey(statKey)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown leaderboard statistic: " + statKey));
        String createdBy = rs.getString("created_by");
        return new Leaderboard(
                rs.getString("id"),
                stat,
                rs.getString("world"),
                rs.getDouble("x"),
                rs.getDouble("y"),
                rs.getDouble("z"),
                rs.getInt("size"),
                rs.getString("title"),
                rs.getLong("created_at"),
                createdBy == null ? null : UUID.fromString(createdBy));
    }
}
