package com.streakysmp.economy;

/**
 * The outcome of an economy operation.
 *
 * <p>Returned rather than thrown. A player having too little money is an ordinary
 * expected condition, not an exceptional one, and modelling it as a return value
 * forces every caller to handle it instead of letting it propagate past a
 * half-finished operation.
 *
 * @param newBalance the payer's balance after the operation, or their current
 *                   balance when it failed
 * @param shortfall  on {@link Outcome#INSUFFICIENT_FUNDS}, how much more was
 *                   needed; zero otherwise
 */
public record EconomyResult(
        Outcome outcome,
        long newBalance,
        long transactionId,
        long shortfall) {

    public enum Outcome {
        SUCCESS,

        /** The payer does not have enough. See {@link #shortfall()}. */
        INSUFFICIENT_FUNDS,

        /** Zero, negative, or unparseable. */
        INVALID_AMOUNT,

        /** Below the configured minimum payment. */
        BELOW_MINIMUM,

        /** Above the configured maximum payment. */
        ABOVE_MAXIMUM,

        /** The payer has paid too recently. */
        COOLDOWN_ACTIVE,

        /** Paying yourself, where configuration forbids it. */
        SELF_PAYMENT,

        /** The target has never been seen on this server. */
        UNKNOWN_ACCOUNT,

        /** The recipient's balance would exceed the configured maximum. */
        WOULD_EXCEED_MAX_BALANCE,

        /** The database refused or failed. Always logged. */
        FAILED
    }

    public boolean isSuccess() {
        return outcome == Outcome.SUCCESS;
    }

    public static EconomyResult success(long newBalance, long transactionId) {
        return new EconomyResult(Outcome.SUCCESS, newBalance, transactionId, 0L);
    }

    public static EconomyResult insufficientFunds(long currentBalance, long required) {
        return new EconomyResult(Outcome.INSUFFICIENT_FUNDS, currentBalance, -1L,
                Math.max(0L, required - currentBalance));
    }

    public static EconomyResult failure(Outcome outcome, long currentBalance) {
        return new EconomyResult(outcome, currentBalance, -1L, 0L);
    }

    /** The message key used to tell the player what happened. */
    public String messageKey() {
        return switch (outcome) {
            case SUCCESS -> "economy.pay.sent";
            case INSUFFICIENT_FUNDS -> "economy.error.insufficient-funds";
            case INVALID_AMOUNT -> "economy.error.invalid-amount";
            case BELOW_MINIMUM -> "economy.error.below-minimum";
            case ABOVE_MAXIMUM -> "economy.error.above-maximum";
            case COOLDOWN_ACTIVE -> "economy.error.cooldown";
            case SELF_PAYMENT -> "economy.error.self-payment";
            case UNKNOWN_ACCOUNT -> "error.unknown-player";
            case WOULD_EXCEED_MAX_BALANCE -> "economy.error.max-balance";
            case FAILED -> "error.internal";
        };
    }
}
