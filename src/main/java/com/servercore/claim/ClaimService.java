package com.servercore.claim;

import com.servercore.core.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Land ownership and protection.
 *
 * <p>Read operations resolve against an in-memory index and are safe to call from
 * the main thread -- protection checks depend on that. Every mutating operation
 * blocks on the database and must run off the main thread.
 */
public interface ClaimService extends Service {

    ClaimSettings settings();

    // --- Queries (main-thread safe) ----------------------------------------

    /** The claim covering a block, if any. */
    Optional<Claim> claimAt(String worldName, int x, int z);

    Optional<Claim> byId(String claimId);

    List<Claim> ownedBy(UUID owner);

    List<Claim> memberOf(UUID player);

    List<Claim> near(String worldName, int x, int z, int radius);

    /**
     * Whether {@code player} may perform {@code flag} at a location.
     *
     * <p>Unclaimed land always permits everything; a claim system that denied
     * actions outside claims would be a different feature entirely.
     */
    boolean allows(UUID player, String worldName, int x, int z, ClaimFlag flag);

    int claimCount();

    // --- Mutations (must run off the main thread) --------------------------

    /**
     * Buys a new claim.
     *
     * <p>The charge and the claim are written in one database transaction, so a
     * player is never billed for a claim that does not exist, nor given one they
     * did not pay for.
     */
    ClaimResult create(UUID owner, String worldName, int x1, int z1, int x2, int z2, String name);

    /** Grows or shrinks a claim to new bounds, charging for any added blocks. */
    ClaimResult resize(UUID actor, String claimId, int x1, int z1, int x2, int z2);

    /** Deletes a claim, refunding the configured share of what was paid. */
    ClaimResult delete(UUID actor, String claimId);

    ClaimResult rename(UUID actor, String claimId, String newName);

    ClaimResult transfer(UUID actor, String claimId, UUID newOwner);

    ClaimResult setMember(UUID actor, String claimId, UUID member, TrustLevel trust);

    ClaimResult removeMember(UUID actor, String claimId, UUID member);

    /** Sets a flag public or private, or clears the override to fall back to the default. */
    ClaimResult setFlag(UUID actor, String claimId, ClaimFlag flag, Boolean publicAccess);
}
