package com.streakysmp.auction;

/**
 * The outcome of an auction operation.
 *
 * @param amount money moved, in minor units
 */
public record AuctionResult(Outcome outcome, AuctionListing listing, long amount) {

    public enum Outcome {
        SUCCESS,

        DISABLED,
        NOT_FOUND,
        NOT_PERMITTED,

        /**
         * Somebody else bought it, or it expired, between the menu rendering and
         * the click landing.
         */
        NO_LONGER_AVAILABLE,

        INSUFFICIENT_FUNDS,

        /** The seller's balance cannot cover the listing fee. */
        CANNOT_AFFORD_FEE,

        /** Buying your own listing, where configuration forbids it. */
        OWN_LISTING,

        /** Nothing in hand, or an item that cannot be listed. */
        NO_ITEM,

        /** Price below the minimum or above the maximum. */
        INVALID_PRICE,

        /** The seller already has the maximum number of active listings. */
        TOO_MANY_LISTINGS,

        /** No room to receive the item; it stays available to collect. */
        INVENTORY_FULL,

        /** The item was already collected. */
        ALREADY_COLLECTED,

        FAILED
    }

    public boolean isSuccess() {
        return outcome == Outcome.SUCCESS;
    }

    public static AuctionResult success(AuctionListing listing, long amount) {
        return new AuctionResult(Outcome.SUCCESS, listing, amount);
    }

    public static AuctionResult failure(Outcome outcome) {
        return new AuctionResult(outcome, null, 0L);
    }

    public String messageKey() {
        return switch (outcome) {
            case SUCCESS -> "auction.success";
            case DISABLED -> "auction.error.disabled";
            case NOT_FOUND -> "auction.error.not-found";
            case NOT_PERMITTED -> "auction.error.not-permitted";
            case NO_LONGER_AVAILABLE -> "auction.error.gone";
            case INSUFFICIENT_FUNDS -> "auction.error.insufficient-funds";
            case CANNOT_AFFORD_FEE -> "auction.error.cannot-afford-fee";
            case OWN_LISTING -> "auction.error.own-listing";
            case NO_ITEM -> "auction.error.no-item";
            case INVALID_PRICE -> "auction.error.invalid-price";
            case TOO_MANY_LISTINGS -> "auction.error.too-many";
            case INVENTORY_FULL -> "auction.error.inventory-full";
            case ALREADY_COLLECTED -> "auction.error.already-collected";
            case FAILED -> "error.internal";
        };
    }
}
