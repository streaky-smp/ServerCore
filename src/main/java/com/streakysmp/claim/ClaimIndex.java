package com.streakysmp.claim;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory spatial index over every claim.
 *
 * <p>A protection check runs on every block break, block place and container
 * open, so "which claim is this block in" has to be cheap. Scanning all claims
 * would be linear in the number of claims on the server -- fine at fifty, a
 * measurable tick cost at five thousand. Instead each claim is registered against
 * every chunk it touches, and a lookup becomes one map access plus a scan of the
 * handful of claims sharing that chunk.
 *
 * <p>Holds no Bukkit types, so every rule here -- containment, overlap detection,
 * multi-chunk registration -- is unit-testable without a server.
 *
 * <p>Thread-safe: claims are loaded and saved on worker threads while protection
 * checks read from the main thread.
 */
public final class ClaimIndex {

    /** world name to chunk key to the ids of claims touching that chunk. */
    private final Map<String, Map<Long, Set<String>>> byChunk = new ConcurrentHashMap<>();

    private final Map<String, Claim> byId = new ConcurrentHashMap<>();

    /** Packs a chunk coordinate pair into one key. */
    static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    /** Adds or replaces a claim. */
    public void put(Claim claim) {
        Claim previous = byId.put(claim.id(), claim);
        if (previous != null) {
            unregisterChunks(previous);
        }
        registerChunks(claim);
    }

    public void remove(String claimId) {
        Claim removed = byId.remove(claimId);
        if (removed != null) {
            unregisterChunks(removed);
        }
    }

    public void clear() {
        byChunk.clear();
        byId.clear();
    }

    public void putAll(Collection<Claim> claims) {
        for (Claim claim : claims) {
            put(claim);
        }
    }

    private void registerChunks(Claim claim) {
        Map<Long, Set<String>> world =
                byChunk.computeIfAbsent(claim.worldName(), ignored -> new ConcurrentHashMap<>());
        for (int cx = claim.minChunkX(); cx <= claim.maxChunkX(); cx++) {
            for (int cz = claim.minChunkZ(); cz <= claim.maxChunkZ(); cz++) {
                world.computeIfAbsent(chunkKey(cx, cz),
                        ignored -> ConcurrentHashMap.newKeySet()).add(claim.id());
            }
        }
    }

    private void unregisterChunks(Claim claim) {
        Map<Long, Set<String>> world = byChunk.get(claim.worldName());
        if (world == null) {
            return;
        }
        for (int cx = claim.minChunkX(); cx <= claim.maxChunkX(); cx++) {
            for (int cz = claim.minChunkZ(); cz <= claim.maxChunkZ(); cz++) {
                long key = chunkKey(cx, cz);
                Set<String> ids = world.get(key);
                if (ids != null) {
                    ids.remove(claim.id());
                    if (ids.isEmpty()) {
                        // Drop the empty set so the map does not grow forever as
                        // claims are created and deleted across a large world.
                        world.remove(key, ids);
                    }
                }
            }
        }
        if (world.isEmpty()) {
            byChunk.remove(claim.worldName(), world);
        }
    }

    /**
     * The claim covering a block, if any.
     *
     * <p>The hot path. Overlapping claims are prevented on creation, so at most
     * one can match and the first hit wins.
     */
    public Optional<Claim> claimAt(String worldName, int x, int z) {
        Map<Long, Set<String>> world = byChunk.get(worldName);
        if (world == null) {
            return Optional.empty();
        }
        Set<String> candidates = world.get(chunkKey(x >> 4, z >> 4));
        if (candidates == null || candidates.isEmpty()) {
            return Optional.empty();
        }
        for (String id : candidates) {
            Claim claim = byId.get(id);
            if (claim != null && claim.contains(worldName, x, z)) {
                return Optional.of(claim);
            }
        }
        return Optional.empty();
    }

    public Optional<Claim> byId(String claimId) {
        return Optional.ofNullable(byId.get(claimId));
    }

    /**
     * Every claim that would overlap {@code candidate}, excluding {@code ignoreId}.
     *
     * <p>{@code ignoreId} exists for resizing: a claim always overlaps itself, and
     * that must not block growing it.
     */
    public List<Claim> overlapping(Claim candidate, String ignoreId) {
        Map<Long, Set<String>> world = byChunk.get(candidate.worldName());
        if (world == null) {
            return List.of();
        }
        // Collect ids first: a claim spanning several chunks appears in each of
        // them, and reporting it once is what the caller expects.
        Set<String> seen = new java.util.HashSet<>();
        List<Claim> hits = new ArrayList<>();
        for (int cx = candidate.minChunkX(); cx <= candidate.maxChunkX(); cx++) {
            for (int cz = candidate.minChunkZ(); cz <= candidate.maxChunkZ(); cz++) {
                Set<String> ids = world.get(chunkKey(cx, cz));
                if (ids == null) {
                    continue;
                }
                for (String id : ids) {
                    if (id.equals(ignoreId) || !seen.add(id)) {
                        continue;
                    }
                    Claim existing = byId.get(id);
                    if (existing != null && existing.overlaps(candidate)) {
                        hits.add(existing);
                    }
                }
            }
        }
        return List.copyOf(hits);
    }

    public boolean wouldOverlap(Claim candidate, String ignoreId) {
        return !overlapping(candidate, ignoreId).isEmpty();
    }

    public List<Claim> ownedBy(UUID owner) {
        List<Claim> owned = new ArrayList<>();
        for (Claim claim : byId.values()) {
            if (claim.isOwner(owner)) {
                owned.add(claim);
            }
        }
        return List.copyOf(owned);
    }

    /** Claims where {@code player} is a member but not the owner. */
    public List<Claim> memberOf(UUID player) {
        List<Claim> claims = new ArrayList<>();
        for (Claim claim : byId.values()) {
            if (!claim.isOwner(player) && claim.trustOf(player) != null) {
                claims.add(claim);
            }
        }
        return List.copyOf(claims);
    }

    /**
     * Claims within {@code radius} blocks of a point, for the visualiser's
     * "nearby claims" display.
     */
    public List<Claim> near(String worldName, int x, int z, int radius) {
        Claim probe = Claim.of("probe", new UUID(0, 0), worldName,
                x - radius, z - radius, x + radius, z + radius, "probe", 0L, 0L);
        return overlapping(probe, null);
    }

    public int size() {
        return byId.size();
    }

    public Collection<Claim> all() {
        return List.copyOf(byId.values());
    }

    /** Chunk buckets currently held, for diagnostics. */
    public int indexedChunkCount() {
        int total = 0;
        for (Map<Long, Set<String>> world : byChunk.values()) {
            total += world.size();
        }
        return total;
    }
}
