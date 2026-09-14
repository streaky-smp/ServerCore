package com.streakysmp.teleport;

import java.time.Duration;
import java.util.UUID;

/**
 * A pending teleport request.
 *
 * <p>Held in memory only. Requests expire in under a minute, and a request that
 * survived a restart would be stranger than one that did not: the players may be
 * anywhere, or offline.
 *
 * @param direction which way the teleport goes if accepted
 */
public record TeleportRequest(
        UUID requester,
        UUID target,
        Direction direction,
        long createdAt,
        long expiresAt) {

    /**
     * Who moves when the request is accepted.
     *
     * <p>Both directions are needed: {@code /tpa} asks to come to someone,
     * {@code /tpahere} asks someone to come to you. Confusing them teleports the
     * wrong player, which is worse than not working at all -- so the direction is
     * part of the request rather than inferred at accept time.
     */
    public enum Direction {
        /** The requester travels to the target. */
        TO_TARGET,
        /** The target travels to the requester. */
        TO_REQUESTER
    }

    public boolean hasExpired(long now) {
        return now >= expiresAt;
    }

    public Duration remaining(long now) {
        return Duration.ofMillis(Math.max(0L, expiresAt - now));
    }

    /** The player who will actually be teleported. */
    public UUID traveller() {
        return direction == Direction.TO_TARGET ? requester : target;
    }

    /** The player whose location is the destination. */
    public UUID destination() {
        return direction == Direction.TO_TARGET ? target : requester;
    }
}
