package com.servercore.shop;

/**
 * Price arithmetic for shop transactions.
 *
 * <p>Pure integer maths over minor units, separated from anything Bukkit so it
 * can be tested exhaustively. Every figure a player is shown or charged comes
 * from here.
 *
 * <p>Prices are <em>always</em> recomputed server-side from the catalogue. A
 * quantity arrives from a click; a price never does.
 */
public final class ShopPricing {

    private ShopPricing() {
    }

    /**
     * Total cost of {@code quantity} at {@code unitPrice}.
     *
     * @throws ArithmeticException if the total would overflow, rather than
     *                             wrapping to a negative price that pays the player
     */
    public static long total(long unitPrice, int quantity) {
        if (unitPrice < 0) {
            throw new IllegalArgumentException("Unit price must not be negative: " + unitPrice);
        }
        if (quantity < 0) {
            throw new IllegalArgumentException("Quantity must not be negative: " + quantity);
        }
        return Math.multiplyExact(unitPrice, quantity);
    }

    /**
     * The most of an item a balance can buy, capped.
     *
     * @param cap the smaller of the item's configured maximum and what will fit
     * @return a quantity in {@code [0, cap]}
     */
    public static int maxAffordable(long balance, long unitPrice, int cap) {
        if (cap <= 0 || balance <= 0) {
            return 0;
        }
        if (unitPrice <= 0) {
            // A free item is limited only by the cap; dividing by zero here would
            // be the more obvious bug.
            return cap;
        }
        long affordable = balance / unitPrice;
        return (int) Math.min(affordable, cap);
    }

    /**
     * Clamps a requested quantity into what is actually permitted.
     *
     * <p>Clamping rather than rejecting is deliberate for the GUI: a player who
     * clicks "64" while able to afford 50 gets 50, not an error. Commands that
     * name an explicit quantity check the bounds themselves and report instead.
     */
    public static int clampQuantity(int requested, int maximum) {
        if (requested < 1) {
            return 0;
        }
        return Math.min(requested, Math.max(0, maximum));
    }
}
