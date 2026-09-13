package com.servercore.playershop;

import org.bukkit.Material;

/**
 * One item a player shop trades, with its own prices and stock.
 *
 * <p>The two prices are independent and either may be absent:
 * <ul>
 *   <li>{@code buyPrice} is what a customer pays to take one <em>from</em> the
 *       shop. Limited by {@link #stock}.</li>
 *   <li>{@code sellPrice} is what the shop pays a customer to take one
 *       <em>in</em>. Limited by the owner's balance, and it increases stock.</li>
 * </ul>
 *
 * <p>An offer with only a sell price is a buying station -- a shop that purchases
 * cobblestone and never resells it -- which is a legitimate and common setup.
 *
 * @param stock how many the shop currently holds
 */
public record ShopOffer(
        long id,
        String shopId,
        Material material,
        long buyPrice,
        long sellPrice,
        int stock,
        long createdAt) {

    /** Sentinel meaning "not traded in this direction". */
    public static final long UNAVAILABLE = -1L;

    public ShopOffer {
        if (material == null) {
            throw new IllegalArgumentException("Offer material must not be null");
        }
        if (stock < 0) {
            throw new IllegalArgumentException("Offer stock must not be negative: " + stock);
        }
        if (buyPrice != UNAVAILABLE && buyPrice < 0) {
            throw new IllegalArgumentException("buy price must be >= 0 or absent: " + buyPrice);
        }
        if (sellPrice != UNAVAILABLE && sellPrice < 0) {
            throw new IllegalArgumentException("sell price must be >= 0 or absent: " + sellPrice);
        }
        if (buyPrice == UNAVAILABLE && sellPrice == UNAVAILABLE) {
            throw new IllegalArgumentException(
                    "Offer for " + material + " has neither price, so it can never trade");
        }
    }

    /** Whether customers can buy this from the shop right now. */
    public boolean customerCanBuy() {
        return buyPrice != UNAVAILABLE && stock > 0;
    }

    /** Whether the shop is set up to buy this from customers. */
    public boolean customerCanSell() {
        return sellPrice != UNAVAILABLE;
    }

    public boolean hasBuyPrice() {
        return buyPrice != UNAVAILABLE;
    }

    public boolean hasSellPrice() {
        return sellPrice != UNAVAILABLE;
    }

    public boolean inStock() {
        return stock > 0;
    }

    public ShopOffer withStock(int newStock) {
        return new ShopOffer(id, shopId, material, buyPrice, sellPrice, newStock, createdAt);
    }

    public ShopOffer withPrices(long newBuyPrice, long newSellPrice) {
        return new ShopOffer(id, shopId, material, newBuyPrice, newSellPrice, stock, createdAt);
    }

    /** Identifier used in logs and transaction descriptions. */
    public String materialKey() {
        return material.name().toLowerCase(java.util.Locale.ROOT);
    }
}
