package com.streakysmp.plot;

/**
 * The outcome of a plot operation.
 *
 * @param amount money moved, in minor units
 */
public record PlotResult(Outcome outcome, SpawnPlot plot, long amount, long shortfall) {

    public enum Outcome {
        SUCCESS,

        DISABLED,
        NOT_FOUND,
        NOT_PERMITTED,

        /** Someone already owns it, or it is withdrawn from sale. */
        NOT_AVAILABLE,

        INSUFFICIENT_FUNDS,

        /** The buyer already holds the maximum number of plots. */
        TOO_MANY_PLOTS,

        /** The proposed bounds overlap another plot. */
        OVERLAPS,

        /** Nothing is owed right now. */
        NOTHING_DUE,

        /** An id that is already taken, or otherwise unusable. */
        INVALID_ID,

        FAILED
    }

    public boolean isSuccess() {
        return outcome == Outcome.SUCCESS;
    }

    public static PlotResult success(SpawnPlot plot, long amount) {
        return new PlotResult(Outcome.SUCCESS, plot, amount, 0L);
    }

    public static PlotResult failure(Outcome outcome) {
        return new PlotResult(outcome, null, 0L, 0L);
    }

    public static PlotResult insufficientFunds(long needed, long shortfall) {
        return new PlotResult(Outcome.INSUFFICIENT_FUNDS, null, needed, shortfall);
    }

    public String messageKey() {
        return switch (outcome) {
            case SUCCESS -> "plot.purchased";
            case DISABLED -> "plot.error.disabled";
            case NOT_FOUND -> "plot.error.not-found";
            case NOT_PERMITTED -> "plot.error.not-permitted";
            case NOT_AVAILABLE -> "plot.error.not-available";
            case INSUFFICIENT_FUNDS -> "plot.error.insufficient-funds";
            case TOO_MANY_PLOTS -> "plot.error.too-many";
            case OVERLAPS -> "plot.error.overlaps";
            case NOTHING_DUE -> "plot.error.nothing-due";
            case INVALID_ID -> "plot.error.invalid-id";
            case FAILED -> "error.internal";
        };
    }
}
