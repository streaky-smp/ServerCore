package com.streakysmp.statistics;

import com.streakysmp.data.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Persistence for player counters and the kill log.
 */
public final class StatisticsRepository {

    private final Database database;

    public StatisticsRepository(Database database) {
        this.database = database;
    }

    /** One player's position on a leaderboard. */
    public record Ranked(UUID uuid, String name, long value) {
    }

    /**
     * Adds {@code delta} to a counter, creating the row if absent.
     *
     * <p>The increment happens inside the UPDATE rather than as read-then-write,
     * so two events landing at once both count. Losing a kill to a lost update
     * would be minor; using the same sloppy pattern in the economy would not be,
     * and consistency between the two is worth more than the microseconds saved.
     */
    public static void increment(Connection connection, UUID player, StatisticType type, long delta)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO sc_statistic (player_uuid, stat_key, value, updated_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(player_uuid, stat_key) DO UPDATE SET
                    value      = value + excluded.value,
                    updated_at = excluded.updated_at
                """)) {
            ps.setString(1, player.toString());
            ps.setString(2, type.key());
            ps.setLong(3, delta);
            ps.setLong(4, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    public void increment(UUID player, StatisticType type, long delta) {
        if (delta == 0) {
            return;
        }
        database.inTransaction(connection -> {
            increment(connection, player, type, delta);
            return null;
        });
    }

    /** Applies several counter changes for one player in a single transaction. */
    public void incrementAll(UUID player, Map<StatisticType, Long> deltas) {
        if (deltas.isEmpty()) {
            return;
        }
        database.inTransaction(connection -> {
            for (Map.Entry<StatisticType, Long> entry : deltas.entrySet()) {
                if (entry.getValue() != 0) {
                    increment(connection, player, entry.getKey(), entry.getValue());
                }
            }
            return null;
        });
    }

    public long value(UUID player, StatisticType type) {
        return database.withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT value FROM sc_statistic WHERE player_uuid = ? AND stat_key = ?")) {
                ps.setString(1, player.toString());
                ps.setString(2, type.key());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : 0L;
                }
            }
        });
    }

    /** Every counter for one player, for the profile screen. */
    public Map<StatisticType, Long> allFor(UUID player) {
        return database.withConnection(connection -> {
            Map<StatisticType, Long> out = new EnumMap<>(StatisticType.class);
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT stat_key, value FROM sc_statistic WHERE player_uuid = ?")) {
                ps.setString(1, player.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        StatisticType.byKey(rs.getString("stat_key"))
                                .ifPresent(type -> out.put(type, readValue(rs)));
                    }
                }
            }
            return Map.copyOf(out);
        });
    }

    private static long readValue(ResultSet rs) {
        try {
            return rs.getLong("value");
        } catch (SQLException e) {
            return 0L;
        }
    }

    /** Highest values first, joined to player names for display. */
    public List<Ranked> top(StatisticType type, int limit) {
        return database.withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement("""
                    SELECT s.player_uuid, p.name, s.value
                    FROM sc_statistic s
                    JOIN sc_player p ON p.uuid = s.player_uuid
                    WHERE s.stat_key = ? AND s.value > 0
                    ORDER BY s.value DESC, p.name ASC
                    LIMIT ?
                    """)) {
                ps.setString(1, type.key());
                ps.setInt(2, Math.max(1, limit));
                List<Ranked> out = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new Ranked(
                                UUID.fromString(rs.getString("player_uuid")),
                                rs.getString("name"),
                                rs.getLong("value")));
                    }
                }
                return List.copyOf(out);
            }
        });
    }

    public int rankOf(UUID player, StatisticType type) {
        return database.withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement("""
                    SELECT COUNT(*) + 1 FROM sc_statistic
                    WHERE stat_key = ? AND value > COALESCE(
                        (SELECT value FROM sc_statistic WHERE player_uuid = ? AND stat_key = ?), 0)
                    """)) {
                ps.setString(1, type.key());
                ps.setString(2, player.toString());
                ps.setString(3, type.key());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        });
    }

    // ------------------------------------------------------------ kill log

    /** Records a kill, whether or not it counted towards the statistic. */
    public void recordKill(UUID killer, UUID victim, boolean counted) {
        database.inTransaction(connection -> {
            try (PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO sc_kill (killer_uuid, victim_uuid, killed_at, counted)
                    VALUES (?, ?, ?, ?)
                    """)) {
                ps.setString(1, killer.toString());
                ps.setString(2, victim.toString());
                ps.setLong(3, System.currentTimeMillis());
                ps.setInt(4, counted ? 1 : 0);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /**
     * How many times {@code killer} has killed {@code victim} since {@code since}.
     *
     * <p>The anti-farming check. Counts every kill, not just counted ones, so a
     * player cannot reset their own cooldown by farming through it.
     */
    public int killsOfVictimSince(UUID killer, UUID victim, long since) {
        return database.withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement("""
                    SELECT COUNT(*) FROM sc_kill
                    WHERE killer_uuid = ? AND victim_uuid = ? AND killed_at >= ?
                    """)) {
                ps.setString(1, killer.toString());
                ps.setString(2, victim.toString());
                ps.setLong(3, since);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        });
    }

    /** Drops kill-log rows older than the cutoff. The log is a window, not a history. */
    public int pruneKillsBefore(long cutoff) {
        return database.inTransaction(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM sc_kill WHERE killed_at < ?")) {
                ps.setLong(1, cutoff);
                return ps.executeUpdate();
            }
        });
    }
}
