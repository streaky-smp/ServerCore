package com.servercore.claim;

import java.util.Locale;
import java.util.Optional;

/**
 * A protectable action inside a claim.
 *
 * <p>Each flag declares the {@link TrustLevel} a non-owner needs by default. A
 * claim may override any flag to "public", which lets anyone perform it -- useful
 * for a shop that wants visitors to open its doors, or a public farm.
 *
 * <p>Constants are persisted in {@code sc_claim_flag.flag}; names are frozen once
 * shipped.
 */
public enum ClaimFlag {

    BLOCK_BREAK("Break blocks", TrustLevel.BUILD, false),
    BLOCK_PLACE("Place blocks", TrustLevel.BUILD, false),

    /** Chests, barrels, furnaces, hoppers, shulkers, and anything else with an inventory. */
    CONTAINER_ACCESS("Open containers", TrustLevel.CONTAINER, false),

    DOOR_USE("Use doors and gates", TrustLevel.ACCESS, false),
    BUTTON_USE("Use buttons and levers", TrustLevel.ACCESS, false),

    /** Right-clicking armour stands, item frames, chiselled bookshelves and so on. */
    ENTITY_INTERACT("Interact with entities", TrustLevel.CONTAINER, false),

    /** Hitting animals and villagers. Player-versus-player is separate. */
    ENTITY_DAMAGE("Damage animals", TrustLevel.BUILD, false),

    ITEM_PICKUP("Pick up dropped items", TrustLevel.ACCESS, false),

    /** Trampling crops, using bone meal, harvesting. */
    FARMLAND_USE("Use farmland", TrustLevel.BUILD, false),

    /**
     * Whether players may fight each other inside the claim.
     *
     * <p>Public by default: a claim is protection against griefing, not a
     * permanent safe zone, and silently disabling combat everywhere would change
     * the server's character without an operator asking for it.
     */
    PVP("Player combat", TrustLevel.ACCESS, true);

    private final String displayName;
    private final TrustLevel requiredTrust;
    private final boolean publicByDefault;

    ClaimFlag(String displayName, TrustLevel requiredTrust, boolean publicByDefault) {
        this.displayName = displayName;
        this.requiredTrust = requiredTrust;
        this.publicByDefault = publicByDefault;
    }

    public String displayName() {
        return displayName;
    }

    /** Minimum trust a non-owner needs, when the flag is not public. */
    public TrustLevel requiredTrust() {
        return requiredTrust;
    }

    public boolean publicByDefault() {
        return publicByDefault;
    }

    public static Optional<ClaimFlag> byName(String name) {
        if (name == null) {
            return Optional.empty();
        }
        String needle = name.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        for (ClaimFlag flag : values()) {
            if (flag.name().equals(needle)) {
                return Optional.of(flag);
            }
        }
        return Optional.empty();
    }
}
