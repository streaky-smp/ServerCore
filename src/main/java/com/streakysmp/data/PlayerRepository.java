package com.streakysmp.data;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for {@link PlayerRecord}.
 *
 * <p>All methods block and must be called off the main thread; {@link Database}
 * enforces that.
 */
public final class PlayerRepository {

    private final Database database;

    public PlayerRepository(Database database) {
        this.database = database;
    }

    /**
     * Records a player as seen now, inserting them if this is their first join.
     *
     * <p>Uses an upsert so a rename updates the stored name in place rather than
     * creating a second row for the same UUID.
     */
    public void touch(UUID uuid, String name, PlayerRecord.Platform platform) {
        long now = System.currentTimeMillis();
        database.inTransaction(connection -> {
            // SQLite/PostgreSQL upsert syntax. A MySQL backend would need
            // ON DUPLICATE KEY UPDATE here; this is one of the few places in the
            // repository layer where dialect matters.
            try (PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO sc_player (uuid, name, name_lower, first_seen, last_seen, platform)
                    VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT(uuid) DO UPDATE SET
                        name       = excluded.name,
                        name_lower = excluded.name_lower,
                        last_seen  = excluded.last_seen,
                        platform   = excluded.platform
                    """)) {
                ps.setString(1, uuid.toString());
                ps.setString(2, name);
                ps.setString(3, name.toLowerCase(Locale.ROOT));
                ps.setLong(4, now);
                ps.setLong(5, now);
                ps.setString(6, platform.name());
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Updates only the last-seen timestamp, used on quit. */
    public void markSeen(UUID uuid, long timestamp) {
        database.inTransaction(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE sc_player SET last_seen = ? WHERE uuid = ?")) {
                ps.setLong(1, timestamp);
                ps.setString(2, uuid.toString());
                ps.executeUpdate();
            }
            return null;
        });
    }

    public Optional<PlayerRecord> findByUuid(UUID uuid) {
        return database.withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT uuid, name, first_seen, last_seen, platform FROM sc_player WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(map(rs)) : Optional.<PlayerRecord>empty();
                }
            }
        });
    }

    /**
     * Looks up a player by name, case-insensitively.
     *
     * <p>Used by {@code /pay} and the admin tools so commands work for offline
     * players. Callers must treat the result as advisory: a name can be
     * reassigned by Mojang, so the returned UUID is the player who last used
     * that name on this server, not necessarily who owns it now.
     */
    public Optional<PlayerRecord> findByName(String name) {
        return database.withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement("""
                    SELECT uuid, name, first_seen, last_seen, platform
                    FROM sc_player
                    WHERE name_lower = ?
                    ORDER BY last_seen DESC
                    LIMIT 1
                    """)) {
                ps.setString(1, name.toLowerCase(Locale.ROOT));
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(map(rs)) : Optional.<PlayerRecord>empty();
                }
            }
        });
    }

    /** Name-prefix search for command tab-completion and admin player search. */
    public List<PlayerRecord> searchByNamePrefix(String prefix, int limit) {
        return database.withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement("""
                    SELECT uuid, name, first_seen, last_seen, platform
                    FROM sc_player
                    WHERE name_lower LIKE ? ESCAPE '\\'
                    ORDER BY last_seen DESC
                    LIMIT ?
                    """)) {
                ps.setString(1, escapeLike(prefix.toLowerCase(Locale.ROOT)) + "%");
                ps.setInt(2, Math.max(1, limit));
                return readAll(ps);
            }
        });
    }

    public int count() {
        return database.withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement("SELECT COUNT(*) FROM sc_player");
                 ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        });
    }

    private static List<PlayerRecord> readAll(PreparedStatement ps) throws SQLException {
        List<PlayerRecord> out = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(map(rs));
            }
        }
        return List.copyOf(out);
    }

    private static PlayerRecord map(ResultSet rs) throws SQLException {
        return new PlayerRecord(
                UUID.fromString(rs.getString("uuid")),
                rs.getString("name"),
                rs.getLong("first_seen"),
                rs.getLong("last_seen"),
                parsePlatform(rs.getString("platform")));
    }

    /**
     * Tolerates unknown platform values rather than throwing.
     *
     * <p>A row written by a newer build should not break a lookup on an older
     * one; defaulting to JAVA only affects GUI layout hints.
     */
    private static PlayerRecord.Platform parsePlatform(String raw) {
        if (raw == null) {
            return PlayerRecord.Platform.JAVA;
        }
        try {
            return PlayerRecord.Platform.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return PlayerRecord.Platform.JAVA;
        }
    }

    /**
     * Escapes LIKE wildcards in player-supplied search text.
     *
     * <p>Without this, a search for {@code %} matches every player and turns a
     * tab-completion into a full table scan.
     */
    static String escapeLike(String input) {
        return input.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }
}
