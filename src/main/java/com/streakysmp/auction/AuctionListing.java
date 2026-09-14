package com.streakysmp.auction;

import java.time.Duration;
import java.util.UUID;

/**
 * One fixed-price auction listing.
 *
 * <p>Identified by a generated {@link #id}, never by a GUI slot. The spec calls
 * that out specifically, and for good reason: a slot index is a property of
 * whatever the viewer happens to be looking at, so acting on one lets a stale
 * menu buy a listing the player never saw.
 *
 * @param itemData Paper's serialised form of the item, so enchantments, custom
 *                 names, durability and data components survive intact
 * @param material the base material, denormalised so search and filtering are
 *                 indexed queries rather than a deserialise-everything scan
 * @param price    total price for the whole listing, not per item
 */
public record AuctionListing(
        String id,
        UUID seller,
        byte[] itemData,
        String material,
        int quantity,
        long price,
        String displayName,
        long createdAt,
        long expiresAt,
        ListingStatus status,
        UUID buyer,
        Long soldAt,
        boolean collected) {

    public AuctionListing {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Listing quantity must be positive: " + quantity);
        }
        if (price < 0) {
            throw new IllegalArgumentException("Listing price must not be negative: " + price);
        }
        if (itemData == null || itemData.length == 0) {
            throw new IllegalArgumentException("Listing must carry item data");
        }
    }

    public boolean isSeller(UUID player) {
        return seller.equals(player);
    }

    public boolean isBuyer(UUID player) {
        return buyer != null && buyer.equals(player);
    }

    /** Whether the listing is on sale and has not run out of time. */
    public boolean purchasableAt(long now) {
        return status.isActive() && now < expiresAt;
    }

    public boolean hasExpired(long now) {
        return status.isActive() && now >= expiresAt;
    }

    public Duration timeRemaining(long now) {
        return Duration.ofMillis(Math.max(0L, expiresAt - now));
    }

    /** Price for one item, rounded down, for display alongside the total. */
    public long unitPrice() {
        return quantity <= 0 ? price : price / quantity;
    }

    /**
     * Who is owed the item, or null if nobody is.
     *
     * <p>Centralised here so the collection screen and the service cannot
     * disagree about it.
     */
    public UUID owedTo() {
        if (collected || !status.collectable()) {
            return null;
        }
        return status.buyerOwnsItem() ? buyer : seller;
    }

    public boolean isOwedTo(UUID player) {
        UUID owed = owedTo();
        return owed != null && owed.equals(player);
    }

    // Records with array components need these spelled out; identity is the id.

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof AuctionListing listing && id.equals(listing.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "AuctionListing[id=" + id + ", material=" + material
                + ", quantity=" + quantity + ", price=" + price + ", status=" + status + "]";
    }
}
