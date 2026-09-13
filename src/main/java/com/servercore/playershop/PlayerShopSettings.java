package com.servercore.playershop;

import com.servercore.config.ConfigView;

/**
 * Immutable snapshot of the player-shop configuration.
 *
 * @param creationFee     charged once when a shop is created, in minor units
 * @param maxShopsPerOwner 0 for unlimited
 * @param maxOffersPerShop distinct items one shop may list
 * @param maxStockPerOffer ceiling on a single offer's stock
 * @param spawnPlotOfferBonus extra offer slots granted to shops on a spawn plot
 */
public record PlayerShopSettings(
        boolean enabled,
        long creationFee,
        int maxShopsPerOwner,
        int maxOffersPerShop,
        int maxStockPerOffer,
        int spawnPlotOfferBonus,
        long maxItemPrice,
        boolean requireProtectedLocation) {

    public static PlayerShopSettings from(ConfigView shops, int fractionDigits) {
        return new PlayerShopSettings(
                shops.getBoolean("enabled", true),
                shops.getMoney("creation-fee", 500L * pow10(fractionDigits), fractionDigits),
                shops.getInt("max-shops-per-owner", 3, 0, 100),
                shops.getInt("max-offers-per-shop", 14, 1, 54),
                shops.getInt("max-stock-per-offer", 2_304, 1, 1_000_000),
                shops.getInt("spawn-plot-offer-bonus", 14, 0, 54),
                shops.getMoney("max-item-price", 1_000_000L * pow10(fractionDigits), fractionDigits),
                shops.getBoolean("require-protected-location", true));
    }

    private static long pow10(int exponent) {
        long value = 1L;
        for (int i = 0; i < exponent; i++) {
            value *= 10L;
        }
        return value;
    }

    public boolean hasShopLimit() {
        return maxShopsPerOwner > 0;
    }

    /**
     * Offer slots available to a shop.
     *
     * <p>Spawn-plot shops get more, which is one of the concrete advantages the
     * spec asks premium plots to have -- a real benefit that does not guarantee
     * customers.
     */
    public int offerLimitFor(PlayerShop shop) {
        return shop.onSpawnPlot() ? maxOffersPerShop + spawnPlotOfferBonus : maxOffersPerShop;
    }
}
