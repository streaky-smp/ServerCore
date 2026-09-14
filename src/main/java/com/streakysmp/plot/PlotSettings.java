package com.streakysmp.plot;

import com.streakysmp.config.ConfigException;
import com.streakysmp.config.ConfigView;

import java.time.Duration;

/**
 * Immutable snapshot of the spawn-plot configuration.
 *
 * @param gracePeriod   how long after a failed payment before the shop closes
 * @param cleanupPeriod how long an expired plot is held before being released
 * @param rentCheckInterval how often the rent sweep runs
 */
public record PlotSettings(
        boolean enabled,
        long defaultPurchasePrice,
        long defaultRentPrice,
        RentPeriod defaultRentPeriod,
        Duration gracePeriod,
        Duration cleanupPeriod,
        Duration rentCheckInterval,
        int maxPlotsPerPlayer,
        boolean warnOnLogin,
        Duration warnBeforeDue) {

    public static PlotSettings from(ConfigView plots, int fractionDigits) {
        Duration grace = plots.getDuration("grace-period", Duration.ofHours(48));
        Duration cleanup = plots.getDuration("cleanup-period", Duration.ofDays(7));
        Duration interval = plots.getDuration("rent-check-interval", Duration.ofMinutes(15));

        RentPeriod period = plots.getEnum("default-rent-period", RentPeriod.class, RentPeriod.WEEKLY);

        // A grace period longer than the rent period means a tenant can stay
        // permanently overdue without ever losing the plot, which makes rent
        // optional in practice.
        if (grace.compareTo(period.duration()) >= 0) {
            throw new ConfigException("spawn-plots.grace-period",
                    "the grace period (" + grace.toHours() + "h) is at least as long as the rent "
                            + "period (" + period.duration().toHours() + "h), so a tenant could stay "
                            + "permanently overdue without ever losing the plot");
        }

        return new PlotSettings(
                plots.getBoolean("enabled", true),
                plots.getMoney("default-purchase-price", 25_000L * pow10(fractionDigits), fractionDigits),
                plots.getMoney("default-rent-price", 5_000L * pow10(fractionDigits), fractionDigits),
                period,
                grace,
                cleanup,
                interval,
                plots.getInt("max-plots-per-player", 1, 0, 100),
                plots.getBoolean("warn-on-login", true),
                plots.getDuration("warn-before-due", Duration.ofHours(24)));
    }

    private static long pow10(int exponent) {
        long value = 1L;
        for (int i = 0; i < exponent; i++) {
            value *= 10L;
        }
        return value;
    }

    public boolean hasPlotLimit() {
        return maxPlotsPerPlayer > 0;
    }
}
