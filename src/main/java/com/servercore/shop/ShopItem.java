package com.servercore.shop;

import org.bukkit.Material;

/**
 * One tradeable line in the server shop.
 *
 * <p>Prices are minor units per single item. A price of {@link #UNAVAILABLE}
 * means the shop does not trade in that direction -- many items are worth buying
 * but should never be sellable, or the shop becomes a money printer.
 *
 * @param maxBuyQuantity  most a player may buy in one transaction
 * @param maxSellQuantity most a player may sell in one transaction
 */
public record ShopItem(
        String categoryId,
        Material material,
        long buyPrice,
        long sellPrice,
        int maxBuyQuantity,
        int maxSellQuantity) {

    /** Sentinel meaning "not traded in this direction". */
    public static final long UNAVAILABLE = -1L;

    public ShopItem {
        if (material == null || !material.isItem()) {
            throw new IllegalArgumentException("Shop material must be an obtainable item: " + material);
        }
        if (buyPrice != UNAVAILABLE && buyPrice < 0) {
            throw new IllegalArgumentException("buy-price must be >= 0 or absent, got " + buyPrice);
        }
        if (sellPrice != UNAVAILABLE && sellPrice < 0) {
            throw new IllegalArgumentException("sell-price must be >= 0 or absent, got " + sellPrice);
        }
        if (buyPrice == UNAVAILABLE && sellPrice == UNAVAILABLE) {
            throw new IllegalArgumentException(
                    material + " has neither a buy nor a sell price, so it can never be traded");
        }
    }

    public boolean buyable() {
        return buyPrice != UNAVAILABLE;
    }

    public boolean sellable() {
        return sellPrice != UNAVAILABLE;
    }

    /** The item's own stack limit, which caps any single delivery. */
    public int maxStackSize() {
        return material.getMaxStackSize();
    }

    /** Identifier used in logs and transaction descriptions. */
    public String key() {
        return material.getKey().toString();
    }
}
