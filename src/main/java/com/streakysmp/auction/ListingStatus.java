package com.streakysmp.auction;

import java.util.Locale;
import java.util.Optional;

/**
 * Where a listing is in its life.
 *
 * <p>Persisted by name in {@code sc_auction_listing.status}; names are frozen.
 *
 * <p>The status also decides <em>who</em> may collect the item, which is what
 * lets one {@code collected} flag serve both delivery and return:
 *
 * <ul>
 *   <li>{@link #SOLD} -- the item belongs to the buyer.</li>
 *   <li>{@link #EXPIRED} and {@link #CANCELLED} -- it returns to the seller.</li>
 * </ul>
 */
public enum ListingStatus {

    /** On sale. */
    ACTIVE("Active"),

    /** Bought. The item is the buyer's to collect. */
    SOLD("Sold"),

    /** Ran out of time unsold. The item returns to the seller. */
    EXPIRED("Expired"),

    /** Withdrawn by the seller, or removed by an administrator. */
    CANCELLED("Cancelled");

    private final String displayName;

    ListingStatus(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public boolean isActive() {
        return this == ACTIVE;
    }

    /** Whether a listing in this state still has an item waiting for somebody. */
    public boolean collectable() {
        return this == SOLD || this == EXPIRED || this == CANCELLED;
    }

    /** Whether the buyer, rather than the seller, is the one owed the item. */
    public boolean buyerOwnsItem() {
        return this == SOLD;
    }

    public static Optional<ListingStatus> byName(String name) {
        if (name == null) {
            return Optional.empty();
        }
        String needle = name.trim().toUpperCase(Locale.ROOT);
        for (ListingStatus status : values()) {
            if (status.name().equals(needle)) {
                return Optional.of(status);
            }
        }
        return Optional.empty();
    }
}
