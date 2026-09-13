package com.servercore.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Safe parsing and arithmetic for untrusted numeric input.
 *
 * <p>Currency in this plugin is always a {@code long} count of minor units
 * ("cents"), never a {@code double}. Floating point silently loses precision
 * under repeated addition, and an economy that drifts by fractions of a cent per
 * transaction is an economy that can be farmed. Every conversion between player
 * input and internal representation goes through this class.
 */
public final class Numbers {

    /**
     * Rejects input that is not a plain decimal number before it reaches
     * {@link BigDecimal}, which would otherwise happily accept forms like
     * {@code 1e9999} and scientific notation that players have no reason to type.
     */
    private static final Pattern PLAIN_DECIMAL = Pattern.compile("^\\d{1,15}(\\.\\d{1,4})?$");

    private Numbers() {
    }

    /**
     * Parses a player-supplied money amount into minor units.
     *
     * <p>Accepts optional grouping separators and a leading currency symbol,
     * because players type what they see in the GUI. Rejects negatives outright:
     * no command in this plugin has a legitimate use for one, and permitting them
     * turns a "pay" into a "steal".
     *
     * @return the amount in minor units, or empty if the input is not a valid amount
     */
    public static Optional<Long> parseMoney(String raw, int fractionDigits) {
        if (raw == null) {
            return Optional.empty();
        }
        String cleaned = raw.trim().replace(",", "").replace("_", "");
        if (cleaned.startsWith("$")) {
            cleaned = cleaned.substring(1).trim();
        }
        if (cleaned.isEmpty() || !PLAIN_DECIMAL.matcher(cleaned).matches()) {
            return Optional.empty();
        }
        try {
            BigDecimal value = new BigDecimal(cleaned);
            if (value.signum() < 0) {
                return Optional.empty();
            }
            BigDecimal minor = value.movePointRight(fractionDigits);
            // Reject rather than round: a player typing more precision than the
            // currency supports should be told, not silently charged a different
            // amount than the one they entered.
            if (minor.stripTrailingZeros().scale() > 0) {
                return Optional.empty();
            }
            return Optional.of(minor.setScale(0, RoundingMode.UNNECESSARY).longValueExact());
        } catch (ArithmeticException | NumberFormatException e) {
            return Optional.empty();
        }
    }

    /** Parses a non-negative whole number, rejecting anything else. */
    public static Optional<Integer> parsePositiveInt(String raw, int min, int max) {
        if (raw == null) {
            return Optional.empty();
        }
        try {
            int value = Integer.parseInt(raw.trim());
            if (value < min || value > max) {
                return Optional.empty();
            }
            return Optional.of(value);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /**
     * Adds two minor-unit amounts, refusing to wrap around.
     *
     * <p>Silent overflow is the classic way a balance becomes negative and an
     * economy becomes infinite, so this throws instead.
     */
    public static long addExact(long a, long b) {
        try {
            return Math.addExact(a, b);
        } catch (ArithmeticException e) {
            throw new ArithmeticException("Monetary overflow adding " + a + " and " + b);
        }
    }

    public static long subtractExact(long a, long b) {
        try {
            return Math.subtractExact(a, b);
        } catch (ArithmeticException e) {
            throw new ArithmeticException("Monetary overflow subtracting " + b + " from " + a);
        }
    }

    public static long multiplyExact(long a, long b) {
        try {
            return Math.multiplyExact(a, b);
        } catch (ArithmeticException e) {
            throw new ArithmeticException("Monetary overflow multiplying " + a + " by " + b);
        }
    }

    /** Clamps {@code value} into {@code [min, max]}. */
    public static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    public static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
