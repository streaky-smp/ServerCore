package com.servercore.claim;

import java.util.Locale;
import java.util.Optional;

/**
 * What a member of a claim is allowed to do.
 *
 * <p>Strictly ordered: each level includes everything below it. That ordering is
 * the whole point -- a protection check becomes "is this player's level at least
 * the level this action needs", which is one comparison rather than a matrix of
 * per-action grants to keep consistent.
 *
 * <p>The owner is not a level. Ownership is checked separately and always wins.
 */
public enum TrustLevel {

    /** Walk through doors and gates, use buttons and levers. */
    ACCESS(1, "Access", "Doors, buttons and levers"),

    /** Everything above, plus chests, furnaces, hoppers and other containers. */
    CONTAINER(2, "Container", "Open chests and other containers"),

    /** Everything above, plus placing and breaking blocks. */
    BUILD(3, "Build", "Place and break blocks"),

    /**
     * Everything above, plus adding and removing other members.
     *
     * <p>Cannot delete the claim, transfer it, or resize it. Those stay with the
     * owner, because a co-manager who can give the claim away is not a manager,
     * they are a second owner.
     */
    MANAGE(4, "Manage", "Add and remove other members");

    private final int rank;
    private final String displayName;
    private final String description;

    TrustLevel(int rank, String displayName, String description) {
        this.rank = rank;
        this.displayName = displayName;
        this.description = description;
    }

    public int rank() {
        return rank;
    }

    public String displayName() {
        return displayName;
    }

    public String description() {
        return description;
    }

    /** Whether this level satisfies a requirement of {@code required}. */
    public boolean atLeast(TrustLevel required) {
        return this.rank >= required.rank;
    }

    public static Optional<TrustLevel> byName(String name) {
        if (name == null) {
            return Optional.empty();
        }
        String needle = name.trim().toUpperCase(Locale.ROOT);
        for (TrustLevel level : values()) {
            if (level.name().equals(needle)) {
                return Optional.of(level);
            }
        }
        return Optional.empty();
    }
}
