package com.streakysmp.claim;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Claim geometry and the spatial index.
 *
 * <p>These rules decide whether a player can break someone else's blocks, so the
 * edge cases matter more than the happy path: exact boundaries, claims that touch
 * without overlapping, and claims spanning several chunks.
 */
class ClaimIndexTest {

    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();
    private static final String WORLD = "world";

    private ClaimIndex index;

    @BeforeEach
    void setUp() {
        index = new ClaimIndex();
    }

    private static Claim claim(String id, UUID owner, int x1, int z1, int x2, int z2) {
        return Claim.of(id, owner, WORLD, x1, z1, x2, z2, id, 0L, 0L);
    }

    @Nested
    @DisplayName("geometry")
    class Geometry {

        @Test
        @DisplayName("corners are normalised whichever order they arrive in")
        void cornersAreNormalised() {
            Claim a = claim("a", ALICE, 100, 100, 0, 0);
            assertEquals(0, a.minX());
            assertEquals(0, a.minZ());
            assertEquals(100, a.maxX());
            assertEquals(100, a.maxZ());
        }

        @Test
        @DisplayName("bounds are inclusive, so a 1x1 claim has area 1")
        void boundsAreInclusive() {
            Claim single = claim("s", ALICE, 5, 5, 5, 5);
            assertEquals(1, single.width());
            assertEquals(1, single.depth());
            assertEquals(1L, single.area());

            Claim square = claim("q", ALICE, 0, 0, 31, 31);
            assertEquals(32, square.width());
            assertEquals(1024L, square.area());
        }

        @Test
        @DisplayName("containment includes the boundary blocks")
        void containmentIncludesEdges() {
            Claim a = claim("a", ALICE, 0, 0, 10, 10);
            assertTrue(a.contains(WORLD, 0, 0), "the minimum corner is inside");
            assertTrue(a.contains(WORLD, 10, 10), "the maximum corner is inside");
            assertTrue(a.contains(WORLD, 5, 5));
            assertFalse(a.contains(WORLD, 11, 5), "one past the edge is outside");
            assertFalse(a.contains(WORLD, -1, 5));
            assertFalse(a.contains("nether", 5, 5), "a different world never matches");
        }

        @Test
        @DisplayName("claims that merely touch do not overlap")
        void adjacentClaimsDoNotOverlap() {
            Claim a = claim("a", ALICE, 0, 0, 10, 10);
            Claim b = claim("b", BOB, 11, 0, 20, 10);

            assertFalse(a.overlaps(b),
                    "players expect to be able to build right up against a neighbour");
            assertFalse(b.overlaps(a));
        }

        @Test
        @DisplayName("sharing a single block counts as overlapping")
        void oneSharedBlockOverlaps() {
            Claim a = claim("a", ALICE, 0, 0, 10, 10);
            Claim b = claim("b", BOB, 10, 10, 20, 20);
            assertTrue(a.overlaps(b));
            assertTrue(b.overlaps(a), "overlap must be symmetric");
        }

        @Test
        @DisplayName("a claim in another world never overlaps")
        void differentWorldsNeverOverlap() {
            Claim a = claim("a", ALICE, 0, 0, 10, 10);
            Claim b = Claim.of("b", BOB, "nether", 0, 0, 10, 10, "b", 0L, 0L);
            assertFalse(a.overlaps(b));
        }

        @Test
        void enclosureIsDirectional() {
            Claim big = claim("big", ALICE, 0, 0, 100, 100);
            Claim small = claim("small", ALICE, 10, 10, 20, 20);
            assertTrue(big.encloses(small));
            assertFalse(small.encloses(big));
        }

        @Test
        @DisplayName("negative coordinates behave the same as positive ones")
        void negativeCoordinatesWork() {
            Claim a = claim("a", ALICE, -100, -100, -50, -50);
            assertTrue(a.contains(WORLD, -75, -75));
            assertFalse(a.contains(WORLD, -49, -75));
            assertEquals(51, a.width());
        }
    }

    @Nested
    @DisplayName("index lookup")
    class Lookup {

        @Test
        void findsClaimAtAPoint() {
            index.put(claim("a", ALICE, 0, 0, 15, 15));
            assertTrue(index.claimAt(WORLD, 5, 5).isPresent());
            assertEquals("a", index.claimAt(WORLD, 5, 5).orElseThrow().id());
        }

        @Test
        void unclaimedLandIsEmpty() {
            index.put(claim("a", ALICE, 0, 0, 15, 15));
            assertTrue(index.claimAt(WORLD, 500, 500).isEmpty());
            assertTrue(index.claimAt("nether", 5, 5).isEmpty());
        }

        /**
         * The index buckets claims by chunk. A claim larger than one chunk must be
         * findable from every chunk it touches, or protection silently stops
         * working partway across a big claim.
         */
        @Test
        @DisplayName("a claim spanning many chunks is found in all of them")
        void multiChunkClaimIsFoundEverywhere() {
            // 0..79 spans chunks 0 through 4 on both axes.
            index.put(claim("big", ALICE, 0, 0, 79, 79));

            for (int x = 0; x <= 79; x += 16) {
                for (int z = 0; z <= 79; z += 16) {
                    assertTrue(index.claimAt(WORLD, x, z).isPresent(),
                            "claim should cover block " + x + "," + z);
                }
            }
            assertTrue(index.claimAt(WORLD, 79, 79).isPresent(), "the far corner must be covered");
            assertTrue(index.claimAt(WORLD, 80, 80).isEmpty(), "one block past must not be");
        }

        @Test
        @DisplayName("claims crossing the origin work with negative chunk coordinates")
        void claimAcrossOriginWorks() {
            index.put(claim("origin", ALICE, -20, -20, 20, 20));

            assertTrue(index.claimAt(WORLD, -20, -20).isPresent());
            assertTrue(index.claimAt(WORLD, 0, 0).isPresent());
            assertTrue(index.claimAt(WORLD, 20, 20).isPresent());
            assertTrue(index.claimAt(WORLD, -21, 0).isEmpty());
        }

        @Test
        void removingAClaimStopsProtectingIt() {
            index.put(claim("a", ALICE, 0, 0, 15, 15));
            index.remove("a");

            assertTrue(index.claimAt(WORLD, 5, 5).isEmpty());
            assertEquals(0, index.size());
            assertEquals(0, index.indexedChunkCount(),
                    "empty chunk buckets must be dropped or the index leaks as claims churn");
        }

        @Test
        @DisplayName("replacing a claim with smaller bounds releases the land it left")
        void resizingReleasesOldLand() {
            index.put(claim("a", ALICE, 0, 0, 79, 79));
            assertTrue(index.claimAt(WORLD, 70, 70).isPresent());

            index.put(claim("a", ALICE, 0, 0, 15, 15));

            assertTrue(index.claimAt(WORLD, 5, 5).isPresent(), "the kept land is still protected");
            assertTrue(index.claimAt(WORLD, 70, 70).isEmpty(),
                    "shrinking must actually release the land, not leave it indexed");
            assertEquals(1, index.size());
        }
    }

    @Nested
    @DisplayName("overlap detection")
    class Overlap {

        @Test
        void detectsAnOverlappingCandidate() {
            index.put(claim("a", ALICE, 0, 0, 20, 20));
            assertTrue(index.wouldOverlap(claim("new", BOB, 10, 10, 30, 30), null));
        }

        @Test
        void allowsAnAdjacentCandidate() {
            index.put(claim("a", ALICE, 0, 0, 20, 20));
            assertFalse(index.wouldOverlap(claim("new", BOB, 21, 0, 40, 20), null));
        }

        /**
         * Resizing a claim always "overlaps" its own old footprint. Without the
         * ignore parameter no claim could ever be expanded.
         */
        @Test
        @DisplayName("a claim being resized does not block itself")
        void ignoresItselfWhenResizing() {
            index.put(claim("a", ALICE, 0, 0, 20, 20));
            Claim expanded = claim("a", ALICE, 0, 0, 40, 40);

            assertTrue(index.wouldOverlap(expanded, null), "without the ignore it collides");
            assertFalse(index.wouldOverlap(expanded, "a"), "with the ignore it is free to grow");
        }

        @Test
        @DisplayName("a claim spanning several chunks is reported once, not once per chunk")
        void overlapReportsEachClaimOnce() {
            index.put(claim("big", ALICE, 0, 0, 79, 79));
            List<Claim> hits = index.overlapping(claim("new", BOB, 0, 0, 79, 79), null);
            assertEquals(1, hits.size());
        }

        @Test
        void reportsEveryColliding() {
            index.put(claim("a", ALICE, 0, 0, 10, 10));
            index.put(claim("b", BOB, 20, 0, 30, 10));
            assertEquals(2, index.overlapping(claim("new", ALICE, 0, 0, 30, 10), null).size());
        }
    }

    @Nested
    @DisplayName("ownership and trust")
    class Trust {

        @Test
        void listsClaimsByOwner() {
            index.put(claim("a", ALICE, 0, 0, 10, 10));
            index.put(claim("b", ALICE, 20, 0, 30, 10));
            index.put(claim("c", BOB, 40, 0, 50, 10));

            assertEquals(2, index.ownedBy(ALICE).size());
            assertEquals(1, index.ownedBy(BOB).size());
        }

        @Test
        void listsClaimsWhereAPlayerIsATrustedMember() {
            Claim shared = claim("a", ALICE, 0, 0, 10, 10)
                    .withMembers(Map.of(BOB, TrustLevel.BUILD));
            index.put(shared);

            assertEquals(1, index.memberOf(BOB).size());
            assertTrue(index.memberOf(ALICE).isEmpty(), "the owner is not listed as a member");
        }

        @Test
        @DisplayName("the owner may do anything regardless of flags")
        void ownerAlwaysAllowed() {
            Claim a = claim("a", ALICE, 0, 0, 10, 10);
            for (ClaimFlag flag : ClaimFlag.values()) {
                assertTrue(a.allows(ALICE, flag), "owner should be allowed " + flag);
            }
        }

        @Test
        @DisplayName("a stranger is refused everything that is not public by default")
        void strangerRefused() {
            Claim a = claim("a", ALICE, 0, 0, 10, 10);
            assertFalse(a.allows(BOB, ClaimFlag.BLOCK_BREAK));
            assertFalse(a.allows(BOB, ClaimFlag.CONTAINER_ACCESS));
            assertFalse(a.allows(BOB, ClaimFlag.DOOR_USE));
            assertTrue(a.allows(BOB, ClaimFlag.PVP), "combat is public by default");
        }

        @Test
        @DisplayName("trust levels are inclusive of everything below them")
        void trustIsHierarchical() {
            Claim build = claim("a", ALICE, 0, 0, 10, 10)
                    .withMembers(Map.of(BOB, TrustLevel.BUILD));

            assertTrue(build.allows(BOB, ClaimFlag.BLOCK_BREAK), "BUILD covers building");
            assertTrue(build.allows(BOB, ClaimFlag.CONTAINER_ACCESS), "BUILD covers containers");
            assertTrue(build.allows(BOB, ClaimFlag.DOOR_USE), "BUILD covers access");

            Claim access = claim("b", ALICE, 20, 0, 30, 10)
                    .withMembers(Map.of(BOB, TrustLevel.ACCESS));
            assertTrue(access.allows(BOB, ClaimFlag.DOOR_USE));
            assertFalse(access.allows(BOB, ClaimFlag.CONTAINER_ACCESS),
                    "ACCESS must not open chests");
            assertFalse(access.allows(BOB, ClaimFlag.BLOCK_BREAK));
        }

        @Test
        @DisplayName("a flag made public admits anyone")
        void publicFlagAdmitsEveryone() {
            Claim shop = claim("shop", ALICE, 0, 0, 10, 10)
                    .withPublicFlags(Map.of(ClaimFlag.DOOR_USE, true));

            assertTrue(shop.allows(BOB, ClaimFlag.DOOR_USE));
            assertFalse(shop.allows(BOB, ClaimFlag.BLOCK_BREAK),
                    "making one flag public must not open the rest");
        }

        @Test
        @DisplayName("a public-by-default flag can be locked down")
        void defaultPublicFlagCanBeDisabled() {
            Claim safe = claim("safe", ALICE, 0, 0, 10, 10)
                    .withPublicFlags(Map.of(ClaimFlag.PVP, false));
            assertFalse(safe.allows(BOB, ClaimFlag.PVP));
        }

        @Test
        void managerCannotBeCreatedWithBadBounds() {
            assertThrows(IllegalArgumentException.class,
                    () -> new Claim("x", ALICE, WORLD, 10, 0, 0, 10, "x", 0L, 0L,
                            Map.of(), Map.of()));
        }
    }

    @Nested
    @DisplayName("nearby search")
    class Nearby {

        @Test
        void findsClaimsWithinRadius() {
            index.put(claim("close", ALICE, 30, 30, 40, 40));
            index.put(claim("far", BOB, 5_000, 5_000, 5_010, 5_010));

            List<Claim> near = index.near(WORLD, 35, 35, 64);
            assertEquals(1, near.size());
            assertEquals("close", near.getFirst().id());
        }
    }
}
