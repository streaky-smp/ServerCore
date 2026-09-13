package com.servercore.notify;

import com.servercore.data.Database;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Storage for notifications addressed to offline players.
 *
 * <p>Messages are stored as MiniMessage markup rather than rendered text so they
 * still respect the operator's styling when eventually shown.
 */
public final class NotificationRepository {

    /**
     * Caps how many queued messages a single player is shown at once. A player
     * returning after a month of auction sales should get a readable digest, not
     * three hundred lines of chat.
     */
    public static final int MAX_DELIVERED_AT_ONCE = 15;

    private final Database database;

    public NotificationRepository(Database database) {
        this.database = database;
    }

    /** Queues a message for later delivery. */
    public void queue(UUID playerId, String miniMessage) {
        database.inTransaction(connection -> {
            try (PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO sc_notification (player_uuid, created_at, message, delivered)
                    VALUES (?, ?, ?, 0)
                    """)) {
                ps.setString(1, playerId.toString());
                ps.setLong(2, System.currentTimeMillis());
                ps.setString(3, miniMessage);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /**
     * Reads and marks delivered in one transaction.
     *
     * <p>Doing both atomically is what prevents a player who joins twice in quick
     * succession, or joins during a lagging tick, from seeing the same backlog
     * twice.
     *
     * @return the messages to show, oldest first
     */
    public List<String> takePending(UUID playerId) {
        return database.inTransaction(connection -> {
            List<Long> ids = new ArrayList<>();
            List<String> messages = new ArrayList<>();

            try (PreparedStatement ps = connection.prepareStatement("""
                    SELECT id, message FROM sc_notification
                    WHERE player_uuid = ? AND delivered = 0
                    ORDER BY created_at ASC
                    LIMIT ?
                    """)) {
                ps.setString(1, playerId.toString());
                ps.setInt(2, MAX_DELIVERED_AT_ONCE);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        ids.add(rs.getLong("id"));
                        messages.add(rs.getString("message"));
                    }
                }
            }

            if (ids.isEmpty()) {
                return List.<String>of();
            }

            StringBuilder placeholders = new StringBuilder();
            for (int i = 0; i < ids.size(); i++) {
                placeholders.append(i == 0 ? "?" : ",?");
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE sc_notification SET delivered = 1 WHERE id IN (" + placeholders + ")")) {
                for (int i = 0; i < ids.size(); i++) {
                    ps.setLong(i + 1, ids.get(i));
                }
                ps.executeUpdate();
            }
            return List.copyOf(messages);
        });
    }

    /** How many messages are still waiting, for the "you have N unread" line. */
    public int pendingCount(UUID playerId) {
        return database.withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT COUNT(*) FROM sc_notification WHERE player_uuid = ? AND delivered = 0")) {
                ps.setString(1, playerId.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        });
    }

    /** Removes delivered notifications older than the given cutoff. */
    public int purgeDeliveredBefore(long cutoffMillis) {
        return database.inTransaction(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM sc_notification WHERE delivered = 1 AND created_at < ?")) {
                ps.setLong(1, cutoffMillis);
                return ps.executeUpdate();
            }
        });
    }
}
