package com.streakysmp.shop;

/**
 * The outcome of a shop transaction.
 *
 * @param quantity   how many actually changed hands, which may be fewer than
 *                   requested because the server clamps rather than rejects
 * @param amount     money moved, in minor units
 * @param newBalance the player's balance afterwards
 */
public record ShopResult(Outcome outcome, int quantity, long amount, long newBalance) {

    public enum Outcome {
        SUCCESS,

        /** Not enough money for the quantity requested. */
        INSUFFICIENT_FUNDS,

        /** The player does not hold enough of the item, or holds only modified copies. */
        NOT_ENOUGH_ITEMS,

        /** No room to receive the purchase. */
        INVENTORY_FULL,

        /** The shop does not buy this item from players. */
        NOT_SELLABLE,

        /** The shop does not sell this item to players. */
        NOT_BUYABLE,

        /** Quantity was zero, negative, or above the configured maximum. */
        INVALID_QUANTITY,

        /**
         * The material is not in the catalogue at all.
         *
         * <p>Not reachable through the GUI, so it indicates a stale menu or a
         * fabricated interaction. Logged as a security violation.
         */
        UNKNOWN_ITEM,

        /**
         * Money was taken and then returned because delivery failed.
         *
         * <p>The player is left exactly as they started. Surfaced as its own
         * outcome rather than a generic failure so operators can see it in the
         * audit log -- a run of these means something is wrong with inventory
         * handling, not with one player's luck.
         */
        REFUNDED,

        /** The database refused or an unexpected error occurred. Always logged. */
        FAILED
    }

    public boolean isSuccess() {
        return outcome == Outcome.SUCCESS;
    }

    public static ShopResult success(int quantity, long amount, long newBalance) {
        return new ShopResult(Outcome.SUCCESS, quantity, amount, newBalance);
    }

    public static ShopResult failure(Outcome outcome) {
        return new ShopResult(outcome, 0, 0L, 0L);
    }

    public static ShopResult failure(Outcome outcome, long newBalance) {
        return new ShopResult(outcome, 0, 0L, newBalance);
    }

    /** Message key used to tell the player what happened. */
    public String messageKey() {
        return switch (outcome) {
            case SUCCESS -> "shop.success";
            case INSUFFICIENT_FUNDS -> "shop.error.insufficient-funds";
            case NOT_ENOUGH_ITEMS -> "shop.error.not-enough-items";
            case INVENTORY_FULL -> "shop.error.inventory-full";
            case NOT_SELLABLE -> "shop.error.not-sellable";
            case NOT_BUYABLE -> "shop.error.not-buyable";
            case INVALID_QUANTITY -> "shop.error.invalid-quantity";
            case UNKNOWN_ITEM -> "shop.error.unknown-item";
            case REFUNDED -> "shop.error.refunded";
            case FAILED -> "error.internal";
        };
    }
}
