package com.servercore.auction;

import com.servercore.config.ConfigException;
import com.servercore.config.ConfigView;

import java.time.Duration;

/**
 * Immutable snapshot of the auction configuration.
 *
 * @param listingFeePercent  percentage of the asking price charged to list, 0-100
 * @param salesTaxPercent    percentage deducted from the seller's proceeds, 0-100
 * @param purgeAfter         how long a fully settled listing is kept for history
 */
public record AuctionSettings(
        boolean enabled,
        long listingFeeFlat,
        double listingFeePercent,
        double salesTaxPercent,
        Duration listingDuration,
        int maxListingsPerPlayer,
        long minPrice,
        long maxPrice,
        Duration sweepInterval,
        Duration purgeAfter,
        boolean allowBuyingOwnListings) {

    public static AuctionSettings from(ConfigView auction, int fractionDigits) {
        long minPrice = auction.getMoney("minimum-price", pow10(fractionDigits), fractionDigits);
        long maxPrice = auction.getMoney("maximum-price",
                10_000_000L * pow10(fractionDigits), fractionDigits);
        if (minPrice > maxPrice) {
            throw new ConfigException("auction.minimum-price",
                    "minimum-price exceeds maximum-price, which would reject every listing");
        }

        double feePercent = readPercent(auction, "listing-fee-percent");
        double taxPercent = readPercent(auction, "sales-tax-percent");

        return new AuctionSettings(
                auction.getBoolean("enabled", true),
                auction.getMoney("listing-fee-flat", 0L, fractionDigits),
                feePercent,
                taxPercent,
                auction.getDuration("listing-duration", Duration.ofDays(2)),
                auction.getInt("max-listings-per-player", 7, 1, 100),
                minPrice,
                maxPrice,
                auction.getDuration("sweep-interval", Duration.ofMinutes(5)),
                auction.getDuration("purge-settled-after", Duration.ofDays(30)),
                auction.getBoolean("allow-buying-own-listings", false));
    }

    private static double readPercent(ConfigView view, String key) {
        // Read through the money parser so "2.5" is accepted, then range-check.
        long raw = view.getMoney(key, 0L, 2);
        double percent = raw / 100.0d;
        if (percent > 100.0d) {
            throw new ConfigException("auction." + key,
                    "must be between 0 and 100, found " + percent);
        }
        return percent;
    }

    private static long pow10(int exponent) {
        long value = 1L;
        for (int i = 0; i < exponent; i++) {
            value *= 10L;
        }
        return value;
    }

    /**
     * Fee charged up front to create a listing.
     *
     * <p>Charged whether or not the item sells, which is what discourages
     * speculative listings at absurd prices. Rounds down so a fee can never
     * exceed the percentage implied.
     */
    public long listingFeeFor(long price) {
        long percentPart = (long) Math.floor(price * listingFeePercent / 100.0d);
        return Math.max(0L, percentPart + listingFeeFlat);
    }

    /** Tax deducted from the seller's proceeds when an item sells. */
    public long salesTaxFor(long price) {
        return Math.max(0L, (long) Math.floor(price * salesTaxPercent / 100.0d));
    }

    /** What the seller actually receives. */
    public long sellerProceeds(long price) {
        return Math.max(0L, price - salesTaxFor(price));
    }
}
