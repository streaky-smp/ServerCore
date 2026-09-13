package com.servercore.playershop;

/**
 * The outcome of a player-shop operation.
 *
 * @param quantity how many actually changed hands
 * @param amount   money moved, in minor units
 */
public record PlayerShopResult(Outcome outcome, PlayerShop shop, int quantity, long amount) {

    public enum Outcome {
        SUCCESS,

        NOT_FOUND,
        NOT_PERMITTED,

        /** The shop is closed, suspended, or its rent has lapsed. */
        NOT_TRADING,

        /** The shop does not trade this item in this direction. */
        NO_SUCH_OFFER,

        /** The shop has run out. */
        OUT_OF_STOCK,

        /** The customer cannot afford it. */
        INSUFFICIENT_FUNDS,

        /** The shop owner cannot afford to buy from the customer. */
        OWNER_CANNOT_PAY,

        /** The customer does not hold enough of the item to sell. */
        NOT_ENOUGH_ITEMS,

        /** No room in the receiving inventory. */
        INVENTORY_FULL,

        INVALID_QUANTITY,
        INVALID_PRICE,

        /** Shop name empty, too long, or already used by this owner. */
        INVALID_NAME,

        /** The owner already has the maximum number of shops. */
        TOO_MANY_SHOPS,

        /** The location is not inside the owner's claim or a plot they rent. */
        UNPROTECTED_LOCATION,

        /** Another shop already occupies this block. */
        LOCATION_TAKEN,

        /** Stock must be collected before the shop or offer can be removed. */
        STOCK_REMAINING,

        /** Money moved and was returned because delivery failed. */
        REFUNDED,

        FAILED
    }

    public boolean isSuccess() {
        return outcome == Outcome.SUCCESS;
    }

    public static PlayerShopResult success(PlayerShop shop, int quantity, long amount) {
        return new PlayerShopResult(Outcome.SUCCESS, shop, quantity, amount);
    }

    public static PlayerShopResult failure(Outcome outcome) {
        return new PlayerShopResult(outcome, null, 0, 0L);
    }

    public String messageKey() {
        return switch (outcome) {
            case SUCCESS -> "playershop.success";
            case NOT_FOUND -> "playershop.error.not-found";
            case NOT_PERMITTED -> "playershop.error.not-permitted";
            case NOT_TRADING -> "playershop.error.not-trading";
            case NO_SUCH_OFFER -> "playershop.error.no-offer";
            case OUT_OF_STOCK -> "playershop.error.out-of-stock";
            case INSUFFICIENT_FUNDS -> "playershop.error.insufficient-funds";
            case OWNER_CANNOT_PAY -> "playershop.error.owner-cannot-pay";
            case NOT_ENOUGH_ITEMS -> "playershop.error.not-enough-items";
            case INVENTORY_FULL -> "playershop.error.inventory-full";
            case INVALID_QUANTITY -> "playershop.error.invalid-quantity";
            case INVALID_PRICE -> "playershop.error.invalid-price";
            case INVALID_NAME -> "playershop.error.invalid-name";
            case TOO_MANY_SHOPS -> "playershop.error.too-many";
            case UNPROTECTED_LOCATION -> "playershop.error.unprotected";
            case LOCATION_TAKEN -> "playershop.error.location-taken";
            case STOCK_REMAINING -> "playershop.error.stock-remaining";
            case REFUNDED -> "playershop.error.refunded";
            case FAILED -> "error.internal";
        };
    }
}
