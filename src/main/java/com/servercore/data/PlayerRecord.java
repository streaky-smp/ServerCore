package com.servercore.data;

import java.util.UUID;

/**
 * A known player.
 *
 * <p>Names are stored for display and for offline lookup by name, but the UUID
 * is the only identity the plugin ever keys on. Names change; balances, claims
 * and shops must not follow them to a different person.
 *
 * @param platform how the player connects, used to adapt GUI layouts for Bedrock
 */
public record PlayerRecord(
        UUID uuid,
        String name,
        long firstSeen,
        long lastSeen,
        Platform platform) {

    public enum Platform {
        JAVA,
        BEDROCK
    }

    public boolean isBedrock() {
        return platform == Platform.BEDROCK;
    }
}
