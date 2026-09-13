package com.servercore.teleport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which player actually moves.
 *
 * <p>{@code /tpa} and {@code /tpahere} differ only in direction, and getting it
 * backwards teleports the wrong person -- a failure that looks like the plugin
 * working until somebody ends up somewhere they did not ask to be. The rest of
 * the teleport system needs a running server; this part does not, so it is
 * pinned down here.
 */
class TeleportRequestTest {

    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();

    private static TeleportRequest request(TeleportRequest.Direction direction, long expiresAt) {
        return new TeleportRequest(ALICE, BOB, direction, 0L, expiresAt);
    }

    @Test
    @DisplayName("/tpa moves the requester to the target")
    void toTargetMovesTheRequester() {
        TeleportRequest tpa = request(TeleportRequest.Direction.TO_TARGET, 1_000L);

        assertEquals(ALICE, tpa.traveller(), "the person who asked is the one who travels");
        assertEquals(BOB, tpa.destination(), "they arrive where the target is");
    }

    @Test
    @DisplayName("/tpahere moves the target to the requester")
    void toRequesterMovesTheTarget() {
        TeleportRequest tpaHere = request(TeleportRequest.Direction.TO_REQUESTER, 1_000L);

        assertEquals(BOB, tpaHere.traveller(), "the summoned player is the one who travels");
        assertEquals(ALICE, tpaHere.destination(), "they arrive where the requester is");
    }

    @Test
    @DisplayName("traveller and destination are never the same player")
    void travellerAndDestinationDiffer() {
        for (TeleportRequest.Direction direction : TeleportRequest.Direction.values()) {
            TeleportRequest pending = request(direction, 1_000L);
            assertFalse(pending.traveller().equals(pending.destination()),
                    direction + " must move somebody towards somebody else");
        }
    }

    @Test
    @DisplayName("a request expires at its deadline, not after it")
    void expiryIsInclusive() {
        TeleportRequest pending = request(TeleportRequest.Direction.TO_TARGET, 1_000L);

        assertFalse(pending.hasExpired(999L));
        assertTrue(pending.hasExpired(1_000L));
        assertTrue(pending.hasExpired(1_001L));
    }

    @Test
    @DisplayName("remaining time counts down and never goes negative")
    void remainingNeverNegative() {
        TeleportRequest pending = request(TeleportRequest.Direction.TO_TARGET, 10_000L);

        assertEquals(Duration.ofSeconds(10), pending.remaining(0L));
        assertEquals(Duration.ofSeconds(4), pending.remaining(6_000L));
        assertEquals(Duration.ZERO, pending.remaining(20_000L),
                "an expired request must report zero, not a negative countdown");
    }
}
