package com.servercore.claim;

import java.util.Map;
import java.util.UUID;

/**
 * A rectangular, full-height area of protected land.
 *
 * <p>Holds no Bukkit types. Coordinates are plain integers and the world is a
 * name, which is what allows the spatial index and every containment and overlap
 * rule to be unit-tested without a server -- and those rules are where the bugs
 * would be.
 *
 * <p>Bounds are <strong>inclusive</strong> and normalised on construction, so
 * {@code minX <= maxX} always holds and containment needs no sorting.
 *
 * @param paid what the owner paid in total, including expansions, in minor units
 */
public record Claim(
        String id,
        UUID owner,
        String worldName,
        int minX,
        int minZ,
        int maxX,
        int maxZ,
        String name,
        long createdAt,
        long paid,
        Map<UUID, TrustLevel> members,
        Map<ClaimFlag, Boolean> publicFlags) {

    public Claim {
        if (minX > maxX || minZ > maxZ) {
            throw new IllegalArgumentException(
                    "Claim bounds must be normalised: got x " + minX + ".." + maxX
                            + ", z " + minZ + ".." + maxZ);
        }
        members = Map.copyOf(members);
        publicFlags = Map.copyOf(publicFlags);
    }

    /** Builds a claim from any two corners, in any order. */
    public static Claim of(String id, UUID owner, String worldName,
                           int x1, int z1, int x2, int z2,
                           String name, long createdAt, long paid) {
        return new Claim(id, owner, worldName,
                Math.min(x1, x2), Math.min(z1, z2),
                Math.max(x1, x2), Math.max(z1, z2),
                name, createdAt, paid, Map.of(), Map.of());
    }

    public int width() {
        return maxX - minX + 1;
    }

    public int depth() {
        return maxZ - minZ + 1;
    }

    /** Area in blocks, as a long so a huge claim cannot overflow the count. */
    public long area() {
        return (long) width() * depth();
    }

    public boolean contains(String world, int x, int z) {
        return worldName.equals(world) && x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }

    /**
     * Whether this claim shares any block with {@code other}.
     *
     * <p>Standard separating-axis test on inclusive bounds. Two claims touching
     * edge-to-edge do <em>not</em> overlap: {@code maxX == other.minX - 1} is
     * adjacent, not overlapping, and players expect to be able to claim right up
     * against a neighbour.
     */
    public boolean overlaps(Claim other) {
        return worldName.equals(other.worldName)
                && minX <= other.maxX && maxX >= other.minX
                && minZ <= other.maxZ && maxZ >= other.minZ;
    }

    /** Whether this claim fully encloses {@code other}. */
    public boolean encloses(Claim other) {
        return worldName.equals(other.worldName)
                && minX <= other.minX && maxX >= other.maxX
                && minZ <= other.minZ && maxZ >= other.maxZ;
    }

    public boolean isOwner(UUID player) {
        return owner.equals(player);
    }

    /** The member's trust level, or null if they are not a member. */
    public TrustLevel trustOf(UUID player) {
        return members.get(player);
    }

    /**
     * Whether {@code player} may perform {@code flag} here.
     *
     * <p>The owner may always act. Otherwise a public flag admits anyone, and a
     * non-public flag requires the member's trust to meet the flag's requirement.
     */
    public boolean allows(UUID player, ClaimFlag flag) {
        if (isOwner(player)) {
            return true;
        }
        if (isPublic(flag)) {
            return true;
        }
        TrustLevel trust = members.get(player);
        return trust != null && trust.atLeast(flag.requiredTrust());
    }

    public boolean isPublic(ClaimFlag flag) {
        return publicFlags.getOrDefault(flag, flag.publicByDefault());
    }

    // --- Chunk bounds, for the spatial index --------------------------------

    public int minChunkX() {
        return minX >> 4;
    }

    public int minChunkZ() {
        return minZ >> 4;
    }

    public int maxChunkX() {
        return maxX >> 4;
    }

    public int maxChunkZ() {
        return maxZ >> 4;
    }

    /** A copy with different bounds, preserving members, flags and identity. */
    public Claim withBounds(int x1, int z1, int x2, int z2, long totalPaid) {
        return new Claim(id, owner, worldName,
                Math.min(x1, x2), Math.min(z1, z2),
                Math.max(x1, x2), Math.max(z1, z2),
                name, createdAt, totalPaid, members, publicFlags);
    }

    public Claim withName(String newName) {
        return new Claim(id, owner, worldName, minX, minZ, maxX, maxZ,
                newName, createdAt, paid, members, publicFlags);
    }

    public Claim withOwner(UUID newOwner) {
        return new Claim(id, newOwner, worldName, minX, minZ, maxX, maxZ,
                name, createdAt, paid, members, publicFlags);
    }

    public Claim withMembers(Map<UUID, TrustLevel> newMembers) {
        return new Claim(id, owner, worldName, minX, minZ, maxX, maxZ,
                name, createdAt, paid, newMembers, publicFlags);
    }

    public Claim withPublicFlags(Map<ClaimFlag, Boolean> newFlags) {
        return new Claim(id, owner, worldName, minX, minZ, maxX, maxZ,
                name, createdAt, paid, members, newFlags);
    }
}
