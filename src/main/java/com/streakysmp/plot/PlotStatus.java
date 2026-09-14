package com.streakysmp.plot;

import java.util.Locale;
import java.util.Optional;

/**
 * Where a plot is in its lifecycle.
 *
 * <p>Persisted by name in {@code sc_plot.status}; names are frozen.
 *
 * <p>The progression on missed rent is deliberately gradual, because the spec is
 * explicit that a shop must not disappear the moment a payment fails:
 * {@code OWNED} to {@code RENT_OVERDUE} (grace period, shop still open) to
 * {@code EXPIRED} (shop closed, stock preserved) to {@code AVAILABLE} (released).
 */
public enum PlotStatus {

    /** Nobody owns it; it can be bought. */
    AVAILABLE("Available", false),

    /** Owned and paid up. */
    OWNED("Owned", true),

    /**
     * A rent payment failed and the grace period is running.
     *
     * <p>The shop keeps trading. Taking it offline immediately would punish a
     * player for being briefly short of money, which is not the point of rent.
     */
    RENT_OVERDUE("Rent overdue", true),

    /**
     * The grace period ran out.
     *
     * <p>Shops on the plot are closed and their stock is preserved for
     * collection. The plot is not yet released, so the owner can still pay and
     * recover it.
     */
    EXPIRED("Expired", true),

    /** Withdrawn from sale by an administrator. */
    DISABLED("Disabled", false);

    private final String displayName;
    private final boolean hasOwner;

    PlotStatus(String displayName, boolean hasOwner) {
        this.displayName = displayName;
        this.hasOwner = hasOwner;
    }

    public String displayName() {
        return displayName;
    }

    /** Whether a plot in this state is expected to have an owner. */
    public boolean hasOwner() {
        return hasOwner;
    }

    public boolean purchasable() {
        return this == AVAILABLE;
    }

    /** Whether shops on the plot may trade. */
    public boolean shopsMayTrade() {
        return this == OWNED || this == RENT_OVERDUE;
    }

    /** Whether rent should be charged for a plot in this state. */
    public boolean rentApplies() {
        return this == OWNED || this == RENT_OVERDUE || this == EXPIRED;
    }

    public static Optional<PlotStatus> byName(String name) {
        if (name == null) {
            return Optional.empty();
        }
        String needle = name.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        for (PlotStatus status : values()) {
            if (status.name().equals(needle)) {
                return Optional.of(status);
            }
        }
        return Optional.empty();
    }
}
