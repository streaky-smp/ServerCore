package com.servercore.economy;

import com.servercore.config.ConfigException;
import com.servercore.config.ConfigView;

import java.time.Duration;

/**
 * Immutable snapshot of the economy configuration.
 *
 * <p>Rebuilt wholesale on reload and swapped in atomically, so a running
 * transaction can never see half-old, half-new settings.
 *
 * @param transferFeePercent  percentage of the amount taken as a fee, 0-100
 * @param transferFeeFlat     flat fee in minor units, added to the percentage fee
 * @param confirmThreshold    payments at or above this open a confirmation dialog
 * @param maxBalance          ceiling on any single account, in minor units
 */
public record EconomySettings(
        Money money,
        long startingBalance,
        long minimumPayment,
        long maximumPayment,
        Duration paymentCooldown,
        double transferFeePercent,
        long transferFeeFlat,
        long confirmThreshold,
        long maxBalance,
        boolean allowSelfPayment,
        boolean allowOfflinePayment,
        int historyPageSize) {

    public static EconomySettings from(ConfigView economy) {
        ConfigView currency = economy.section("currency");
        int fractionDigits = currency.getInt("fraction-digits", 2, 0, 4);
        Money money = new Money(
                currency.getString("symbol", "$"),
                fractionDigits,
                currency.getBoolean("symbol-before-amount", true),
                currency.getBoolean("group-digits", true));

        long minimum = economy.getMoney("minimum-payment", money.minorPerMajor(), fractionDigits);
        long maximum = economy.getMoney("maximum-payment", 1_000_000L * money.minorPerMajor(), fractionDigits);
        if (minimum > maximum) {
            throw new ConfigException("economy.minimum-payment",
                    "minimum-payment (" + money.format(minimum) + ") is greater than maximum-payment ("
                            + money.format(maximum) + "), which would reject every payment");
        }

        double feePercent = readFeePercent(economy);
        long maxBalance = economy.getMoney("max-balance",
                1_000_000_000L * money.minorPerMajor(), fractionDigits);
        long startingBalance = economy.getMoney("starting-balance",
                100L * money.minorPerMajor(), fractionDigits);
        if (startingBalance > maxBalance) {
            throw new ConfigException("economy.starting-balance",
                    "starting-balance exceeds max-balance, so no account could ever be created");
        }

        return new EconomySettings(
                money,
                startingBalance,
                minimum,
                maximum,
                economy.getDuration("payment-cooldown", Duration.ZERO),
                feePercent,
                economy.getMoney("transfer-fee-flat", 0L, fractionDigits),
                economy.getMoney("large-payment-confirm-threshold",
                        5_000L * money.minorPerMajor(), fractionDigits),
                maxBalance,
                economy.getBoolean("allow-self-payment", false),
                economy.getBoolean("allow-offline-payment", true),
                economy.getInt("history-page-size", 45, 9, 45));
    }

    private static double readFeePercent(ConfigView economy) {
        // Read as a money value so "2.5" is accepted, then validate the range.
        // A fee over 100% would debit more than the payer agreed to send.
        long raw = economy.getMoney("transfer-fee-percent", 0L, 2);
        double percent = raw / 100.0d;
        if (percent > 100.0d) {
            throw new ConfigException("economy.transfer-fee-percent",
                    "must be between 0 and 100, found " + percent);
        }
        return percent;
    }

    /**
     * The fee charged on top of {@code amount}.
     *
     * <p>Rounds down, so the fee can never exceed what the percentage implies and
     * a one-unit payment is never charged more than it is worth.
     */
    public long feeFor(long amount) {
        long percentPart = (long) Math.floor(amount * transferFeePercent / 100.0d);
        return Math.max(0L, percentPart + transferFeeFlat);
    }

    public boolean needsConfirmation(long amount) {
        return confirmThreshold > 0 && amount >= confirmThreshold;
    }

    public boolean hasCooldown() {
        return !paymentCooldown.isZero() && !paymentCooldown.isNegative();
    }
}
