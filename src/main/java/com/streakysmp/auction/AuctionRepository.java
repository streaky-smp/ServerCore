package com.streakysmp.auction;

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
 * Persistence for auction listings.
 *
 * <p>The methods that matter are {@link #trySell} and {@link #tryCollect}: both
 * are conditional updates whose affected-row count decides the outcome, which is
 * how two simultaneous buyers -- or two fast clicks on a collect button -- are
 * resolved without either duplicating an item.
 */
public final class AuctionRepository {

    private final Database database;

    public AuctionRepository(Database database) {
        this.database = database;
    }

    // ------------------------------------------------------------- writing

    /** Inserts a new listing inside the caller's transaction. */
    public static void insertWithin(Connection connection, AuctionListing listing)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO sc_auction_listing
                    (id, seller_uuid, item_data, material, quantity, price, display_name,
                     created_at, expires_at, status, buyer_uuid, sold_at, collected)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, 0)
                """)) {
            ps.setString(1, listing.id());
            ps.setString(2, listing.seller().toString());
            ps.setBytes(3, listing.itemData());
            ps.setString(4, listing.material());
            ps.setInt(5, listing.quantity());
            ps.setLong(6, listing.price());
            if (listing.displayName() == null) {
                ps.setNull(7, Types.VARCHAR);
            } else {
                ps.setString(7, listing.displayName());
            }
            ps.setLong(8, listing.createdAt());
            ps.setLong(9, listing.expiresAt());
            ps.setString(10, listing.status().name());
            ps.executeUpdate();
        }
    }

    /**
     * Marks a listing sold, but only if it is still on sale and unexpired.
     *
     * <p>The single most important statement in the auction house. The status
     * check and the status change are one atomic operation, so exactly one of two
     * simultaneous buyers can win. Reading the status and then deciding would sell
     * the same item twice.
     *
     * @return true if this caller is the one that bought it
     */
    public static boolean trySell(Connection connection, String listingId, UUID buyer, long now)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                UPDATE sc_auction_listing
                SET status = 'SOLD', buyer_uuid = ?, sold_at = ?
                WHERE id = ? AND status = 'ACTIVE' AND expires_at > ?
                """)) {
            ps.setString(1, buyer.toString());
            ps.setLong(2, now);
            ps.setString(3, listingId);
            ps.setLong(4, now);
            return ps.executeUpdate() == 1;
        }
    }

    /**
     * Cancels a listing, but only while it is still active and unsold.
     *
     * <p>Conditional for the same reason as selling: a seller clicking cancel at
     * the exact moment a buyer clicks buy must not be able to reclaim an item that
     * has already been paid for.
     */
    public static boolean tryCancel(Connection connection, String listingId, UUID seller)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                UPDATE sc_auction_listing
                SET status = 'CANCELLED'
                WHERE id = ? AND seller_uuid = ? AND status = 'ACTIVE'
                """)) {
            ps.setString(1, listingId);
            ps.setString(2, seller.toString());
            return ps.executeUpdate() == 1;
        }
    }

    /** Administrative cancellation, ignoring ownership. */
    public boolean forceCancel(String listingId) {
        return database.inTransaction(connection -> {
            try (PreparedStatement ps = connection.prepareStatement("""
                    UPDATE sc_auction_listing SET status = 'CANCELLED'
                    WHERE id = ? AND status = 'ACTIVE'
                    """)) {
                ps.setString(1, listingId);
                return ps.executeUpdate() == 1;
            }
        });
    }

    /**
     * Claims an item for collection, once.
     *
     * <p>Conditional on {@code collected = 0}, so a double click cannot hand the
     * same item over twice. The caller only gives the item to the player after
     * this returns true.
     */
    public boolean tryCollect(String listingId, UUID claimant) {
        return database.inTransaction(connection -> {
            try (PreparedStatement ps = connection.prepareStatement("""
                    UPDATE sc_auction_listing
                    SET collected = 1
                    WHERE id = ?
                      AND collected = 0
                      AND status IN ('SOLD', 'EXPIRED', 'CANCELLED')
                      AND ((status = 'SOLD' AND buyer_uuid = ?)
                           OR (status <> 'SOLD' AND seller_uuid = ?))
                    """)) {
                ps.setString(1, listingId);
                ps.setString(2, claimant.toString());
                ps.setString(3, claimant.toString());
                return ps.executeUpdate() == 1;
            }
        });
    }

    /** Marks a newly sold listing already delivered, when the buyer had room. */
    public static void markCollectedWithin(Connection connection, String listingId)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE sc_auction_listing SET collected = 1 WHERE id = ?")) {
            ps.setString(1, listingId);
            ps.executeUpdate();
        }
    }

    /**
     * Expires listings whose time has run out.
     *
     * <p>One statement rather than a read-modify-write loop, so a sweep running
     * alongside a purchase cannot expire something that has just been bought.
     *
     * @return how many were expired
     */
    public int expireOverdue(long now) {
        return database.inTransaction(connection -> {
            try (PreparedStatement ps = connection.prepareStatement("""
                    UPDATE sc_auction_listing
                    SET status = 'EXPIRED'
                    WHERE status = 'ACTIVE' AND expires_at <= ?
                    """)) {
                ps.setLong(1, now);
                return ps.executeUpdate();
            }
        });
    }

    // ------------------------------------------------------------- reading

    public Optional<AuctionListing> byId(String listingId) {
        return database.withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT * FROM sc_auction_listing WHERE id = ?")) {
                ps.setString(1, listingId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(map(rs)) : Optional.<AuctionListing>empty();
                }
            }
        });
    }

    public static Optional<AuctionListing> byIdWithin(Connection connection, String listingId)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM sc_auction_listing WHERE id = ?")) {
            ps.setString(1, listingId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    /** Everything currently on sale, newest first. */
    public List<AuctionListing> active(long now, int limit) {
        return database.withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement("""
                    SELECT * FROM sc_auction_listing
                    WHERE status = 'ACTIVE' AND expires_at > ?
                    ORDER BY created_at DESC
                    LIMIT ?
                    """)) {
                ps.setLong(1, now);
                ps.setInt(2, Math.max(1, limit));
                return collect(ps);
            }
        });
    }

    /** A seller's own listings, active first. */
    public List<AuctionListing> bySeller(UUID seller) {
        return database.withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement("""
                    SELECT * FROM sc_auction_listing
                    WHERE seller_uuid = ?
                    ORDER BY CASE status WHEN 'ACTIVE' THEN 0 ELSE 1 END, created_at DESC
                    """)) {
                ps.setString(1, seller.toString());
                return collect(ps);
            }
        });
    }

    public int countActiveBySeller(UUID seller) {
        return database.withConnection(connection -> countActiveWithin(connection, seller));
    }

    public static int countActiveWithin(Connection connection, UUID seller) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM sc_auction_listing WHERE seller_uuid = ? AND status = 'ACTIVE'")) {
            ps.setString(1, seller.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /**
     * Everything a player is owed: items they bought, and their own listings that
     * expired or were cancelled.
     */
    public List<AuctionListing> awaitingCollection(UUID player) {
        return database.withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement("""
                    SELECT * FROM sc_auction_listing
                    WHERE collected = 0
                      AND ((status = 'SOLD' AND buyer_uuid = ?)
                           OR (status IN ('EXPIRED', 'CANCELLED') AND seller_uuid = ?))
                    ORDER BY created_at
                    """)) {
                ps.setString(1, player.toString());
                ps.setString(2, player.toString());
                return collect(ps);
            }
        });
    }

    public int awaitingCollectionCount(UUID player) {
        return database.withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement("""
                    SELECT COUNT(*) FROM sc_auction_listing
                    WHERE collected = 0
                      AND ((status = 'SOLD' AND buyer_uuid = ?)
                           OR (status IN ('EXPIRED', 'CANCELLED') AND seller_uuid = ?))
                    """)) {
                ps.setString(1, player.toString());
                ps.setString(2, player.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        });
    }

    public int activeCount(long now) {
        return database.withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT COUNT(*) FROM sc_auction_listing "
                            + "WHERE status = 'ACTIVE' AND expires_at > ?")) {
                ps.setLong(1, now);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        });
    }

    /**
     * Removes fully settled listings older than a cutoff.
     *
     * <p>Only rows whose item has been collected, so nothing anybody is owed can
     * ever be pruned.
     */
    public int purgeSettledBefore(long cutoff) {
        return database.inTransaction(connection -> {
            try (PreparedStatement ps = connection.prepareStatement("""
                    DELETE FROM sc_auction_listing
                    WHERE collected = 1 AND status <> 'ACTIVE' AND created_at < ?
                    """)) {
                ps.setLong(1, cutoff);
                return ps.executeUpdate();
            }
        });
    }

    // ------------------------------------------------------------- mapping

    private static List<AuctionListing> collect(PreparedStatement ps) throws SQLException {
        List<AuctionListing> listings = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                listings.add(map(rs));
            }
        }
        return List.copyOf(listings);
    }

    private static AuctionListing map(ResultSet rs) throws SQLException {
        String buyerRaw = rs.getString("buyer_uuid");
        long soldAt = rs.getLong("sold_at");
        boolean soldAtNull = rs.wasNull();
        return new AuctionListing(
                rs.getString("id"),
                UUID.fromString(rs.getString("seller_uuid")),
                rs.getBytes("item_data"),
                rs.getString("material"),
                rs.getInt("quantity"),
                rs.getLong("price"),
                rs.getString("display_name"),
                rs.getLong("created_at"),
                rs.getLong("expires_at"),
                ListingStatus.byName(rs.getString("status")).orElse(ListingStatus.CANCELLED),
                buyerRaw == null ? null : UUID.fromString(buyerRaw),
                soldAtNull ? null : soldAt,
                rs.getInt("collected") != 0);
    }
}
