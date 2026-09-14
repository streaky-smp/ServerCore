package com.streakysmp.plot;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

/**
 * How often a plot's rent falls due.
 *
 * <p>Persisted by name in {@code sc_plot.rent_period}; names are frozen.
 *
 * <p>A month is treated as 30 days rather than a calendar month. Calendar
 * arithmetic would make rent fall on a different day each period and turn
 * "when is my rent due" into a question with a surprising answer.
 */
public enum RentPeriod {

    DAILY("Daily", Duration.ofDays(1)),
    WEEKLY("Weekly", Duration.ofDays(7)),
    MONTHLY("Monthly", Duration.ofDays(30));

    private final String displayName;
    private final Duration duration;

    RentPeriod(String displayName, Duration duration) {
        this.displayName = displayName;
        this.duration = duration;
    }

    public String displayName() {
        return displayName;
    }

    public Duration duration() {
        return duration;
    }

    /** Short suffix for price displays, e.g. {@code /week}. */
    public String suffix() {
        return switch (this) {
            case DAILY -> "/day";
            case WEEKLY -> "/week";
            case MONTHLY -> "/month";
        };
    }

    public static Optional<RentPeriod> byName(String name) {
        if (name == null) {
            return Optional.empty();
        }
        String needle = name.trim().toUpperCase(Locale.ROOT);
        for (RentPeriod period : values()) {
            if (period.name().equals(needle)) {
                return Optional.of(period);
            }
        }
        return Optional.empty();
    }
}
