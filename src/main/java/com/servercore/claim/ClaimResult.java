package com.servercore.claim;

/**
 * The outcome of a claim operation.
 *
 * @param claim the resulting claim on success, else null
 * @param cost  money moved, in minor units
 */
public record ClaimResult(Outcome outcome, Claim claim, long cost, long shortfall) {

    public enum Outcome {
        SUCCESS,

        /** Claiming is switched off, or off in this world. */
        DISABLED,

        /** Overlaps somebody else's claim. */
        OVERLAPS,

        /** Below the configured minimum side length. */
        TOO_SMALL,

        /** Above the configured maximum side length or area. */
        TOO_LARGE,

        /** The player already owns the maximum number of claims. */
        TOO_MANY_CLAIMS,

        INSUFFICIENT_FUNDS,

        /** The claim does not exist, or no claim covers the location. */
        NOT_FOUND,

        /** The player is not the owner and lacks the trust for this action. */
        NOT_PERMITTED,

        /** A name that is empty, too long, or already used by this owner. */
        INVALID_NAME,

        /** An expansion that would not actually change anything. */
        NO_CHANGE,

        FAILED
    }

    public boolean isSuccess() {
        return outcome == Outcome.SUCCESS;
    }

    public static ClaimResult success(Claim claim, long cost) {
        return new ClaimResult(Outcome.SUCCESS, claim, cost, 0L);
    }

    public static ClaimResult failure(Outcome outcome) {
        return new ClaimResult(outcome, null, 0L, 0L);
    }

    public static ClaimResult insufficientFunds(long cost, long shortfall) {
        return new ClaimResult(Outcome.INSUFFICIENT_FUNDS, null, cost, shortfall);
    }

    public String messageKey() {
        return switch (outcome) {
            case SUCCESS -> "claim.created";
            case DISABLED -> "claim.error.disabled";
            case OVERLAPS -> "claim.error.overlaps";
            case TOO_SMALL -> "claim.error.too-small";
            case TOO_LARGE -> "claim.error.too-large";
            case TOO_MANY_CLAIMS -> "claim.error.too-many";
            case INSUFFICIENT_FUNDS -> "claim.error.insufficient-funds";
            case NOT_FOUND -> "claim.error.not-found";
            case NOT_PERMITTED -> "claim.error.not-permitted";
            case INVALID_NAME -> "claim.error.invalid-name";
            case NO_CHANGE -> "claim.error.no-change";
            case FAILED -> "error.internal";
        };
    }
}
