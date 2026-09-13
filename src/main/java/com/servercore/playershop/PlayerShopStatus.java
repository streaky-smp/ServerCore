package com.servercore.playershop;

import java.util.Locale;
import java.util.Optional;

/**
 * Whether a player shop is trading.
 *
 * <p>Persisted by name in {@code sc_player_shop.status}; names are frozen.
 */
public enum PlayerShopStatus {

    /** Trading normally. */
    OPEN("Open", true),

    /** Closed by its owner. Stock is untouched and the shop can reopen. */
    CLOSED("Closed", false),

    /**
     * Closed by an administrator.
     *
     * <p>Distinct from {@link #CLOSED} so the owner cannot simply reopen a shop a
     * moderator shut for a reason.
     */
    SUSPENDED("Suspended", false),

    /**
     * The shop's plot rent lapsed.
     *
     * <p>Set by the spawn-plot system in Phase 8. Stock is preserved for
     * collection; nothing is deleted.
     */
    RENT_OVERDUE("Rent overdue", false);

    private final String displayName;
    private final boolean trading;

    PlayerShopStatus(String displayName, boolean trading) {
        this.displayName = displayName;
        this.trading = trading;
    }

    public String displayName() {
        return displayName;
    }

    /** Whether customers may buy and sell here. */
    public boolean trading() {
        return trading;
    }

    /** Whether the owner may reopen it themselves. */
    public boolean ownerCanReopen() {
        return this == CLOSED;
    }

    public static Optional<PlayerShopStatus> byName(String name) {
        if (name == null) {
            return Optional.empty();
        }
        String needle = name.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        for (PlayerShopStatus status : values()) {
            if (status.name().equals(needle)) {
                return Optional.of(status);
            }
        }
        return Optional.empty();
    }
}
