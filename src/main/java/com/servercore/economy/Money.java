package com.servercore.economy;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/**
 * Formats integer minor units for display.
 *
 * <p>The plugin holds currency as a {@code long} count of minor units everywhere.
 * This class is the only place that converts to something a player reads, and it
 * never converts back -- parsing is {@link com.servercore.util.Numbers#parseMoney}.
 * Keeping the two directions apart is what stops a formatted string from being
 * re-parsed and quietly losing precision on the round trip.
 */
public final class Money {

    private final String symbol;
    private final int fractionDigits;
    private final long minorPerMajor;
    private final DecimalFormat plainFormat;
    private final boolean symbolBeforeAmount;

    public Money(String symbol, int fractionDigits, boolean symbolBeforeAmount, boolean groupDigits) {
        if (fractionDigits < 0 || fractionDigits > 4) {
            throw new IllegalArgumentException("fraction-digits must be 0-4, got " + fractionDigits);
        }
        this.symbol = symbol;
        this.fractionDigits = fractionDigits;
        this.symbolBeforeAmount = symbolBeforeAmount;
        this.minorPerMajor = (long) Math.pow(10, fractionDigits);

        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.ROOT);
        symbols.setGroupingSeparator(',');
        symbols.setDecimalSeparator('.');

        StringBuilder pattern = new StringBuilder(groupDigits ? "#,##0" : "0");
        if (fractionDigits > 0) {
            pattern.append('.').append("0".repeat(fractionDigits));
        }
        this.plainFormat = new DecimalFormat(pattern.toString(), symbols);
        this.plainFormat.setRoundingMode(RoundingMode.UNNECESSARY);
    }

    /** A sensible default used when no configuration has been loaded yet. */
    public static Money defaults() {
        return new Money("$", 2, true, true);
    }

    /** Formats with the currency symbol, e.g. {@code $25,000.00}. */
    public String format(long minorUnits) {
        String amount = formatAmount(minorUnits);
        return symbolBeforeAmount ? symbol + amount : amount + symbol;
    }

    /** Formats without the symbol, e.g. {@code 25,000.00}. */
    public String formatAmount(long minorUnits) {
        BigDecimal value = BigDecimal.valueOf(minorUnits, fractionDigits);
        return plainFormat.format(value);
    }

    /**
     * Formats abbreviated, e.g. {@code $25.0k} or {@code $1.2M}.
     *
     * <p>For leaderboard signs and item names, where a full figure would overflow
     * the available width. Rounds, so never use it anywhere a player is agreeing
     * to an amount -- confirmation dialogs use {@link #format}.
     */
    public String formatCompact(long minorUnits) {
        long major = minorUnits / minorPerMajor;
        long abs = Math.abs(major);

        String rendered;
        if (abs < 1_000L) {
            rendered = String.valueOf(major);
        } else if (abs < 1_000_000L) {
            rendered = scaled(major, 1_000L) + "k";
        } else if (abs < 1_000_000_000L) {
            rendered = scaled(major, 1_000_000L) + "M";
        } else if (abs < 1_000_000_000_000L) {
            rendered = scaled(major, 1_000_000_000L) + "B";
        } else {
            rendered = scaled(major, 1_000_000_000_000L) + "T";
        }
        return symbolBeforeAmount ? symbol + rendered : rendered + symbol;
    }

    private static String scaled(long value, long divisor) {
        BigDecimal scaled = BigDecimal.valueOf(value)
                .divide(BigDecimal.valueOf(divisor), new MathContext(4, RoundingMode.DOWN))
                .setScale(1, RoundingMode.DOWN);
        return scaled.stripTrailingZeros().toPlainString();
    }

    public String symbol() {
        return symbol;
    }

    public int fractionDigits() {
        return fractionDigits;
    }

    /** Minor units in one major unit, e.g. 100 for a two-decimal currency. */
    public long minorPerMajor() {
        return minorPerMajor;
    }
}
