package com.servercore.claim;

import com.servercore.config.ConfigException;
import com.servercore.config.ConfigView;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Immutable snapshot of the claim configuration.
 *
 * @param pricePerBlock          cost of each block of a new claim, in minor units
 * @param expansionPricePerBlock cost of each additional block when growing
 * @param deleteRefundPercent    share of the paid total returned on deletion, 0-100
 * @param maxClaimsPerPlayer     0 for unlimited
 * @param enabledWorlds          empty means every world
 */
public record ClaimSettings(
        boolean enabled,
        long pricePerBlock,
        long expansionPricePerBlock,
        int deleteRefundPercent,
        int minSideLength,
        int maxSideLength,
        long maxArea,
        int maxClaimsPerPlayer,
        int visualiseSeconds,
        Set<String> enabledWorlds) {

    public static ClaimSettings from(ConfigView claims, int fractionDigits) {
        int minSide = claims.getInt("min-side-length", 4, 1, 1_000);
        int maxSide = claims.getInt("max-side-length", 256, 1, 30_000);
        if (minSide > maxSide) {
            throw new ConfigException("claims.min-side-length",
                    "min-side-length (" + minSide + ") exceeds max-side-length (" + maxSide
                            + "), which would reject every claim");
        }

        long maxArea = claims.getLong("max-area", 65_536L, 1L, 900_000_000L);
        if (maxArea < (long) minSide * minSide) {
            throw new ConfigException("claims.max-area",
                    "max-area (" + maxArea + ") is smaller than the smallest permitted claim ("
                            + ((long) minSide * minSide) + " blocks)");
        }

        Set<String> worlds = claims.getStringList("enabled-worlds", List.of()).stream()
                .map(world -> world.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());

        return new ClaimSettings(
                claims.getBoolean("enabled", true),
                claims.getMoney("price-per-block", 2L * pow10(fractionDigits) / 10, fractionDigits),
                claims.getMoney("expansion-price-per-block",
                        2L * pow10(fractionDigits) / 10, fractionDigits),
                claims.getInt("delete-refund-percent", 50, 0, 100),
                minSide,
                maxSide,
                maxArea,
                claims.getInt("max-claims-per-player", 5, 0, 1_000),
                claims.getInt("visualise-seconds", 15, 1, 120),
                worlds);
    }

    private static long pow10(int exponent) {
        long value = 1L;
        for (int i = 0; i < exponent; i++) {
            value *= 10L;
        }
        return value;
    }

    /** Whether claiming is permitted in a world. */
    public boolean allowsWorld(String worldName) {
        return enabledWorlds.isEmpty() || enabledWorlds.contains(worldName.toLowerCase(Locale.ROOT));
    }

    public boolean hasClaimLimit() {
        return maxClaimsPerPlayer > 0;
    }

    /** Cost of a brand-new claim of {@code area} blocks. */
    public long priceForNew(long area) {
        return Math.multiplyExact(pricePerBlock, area);
    }

    /** Cost of adding {@code extraBlocks} to an existing claim. */
    public long priceForExpansion(long extraBlocks) {
        return Math.multiplyExact(expansionPricePerBlock, extraBlocks);
    }

    /** What deleting a claim returns, given what was paid for it. */
    public long refundFor(long paid) {
        if (deleteRefundPercent <= 0 || paid <= 0) {
            return 0L;
        }
        return paid / 100L * deleteRefundPercent;
    }
}
