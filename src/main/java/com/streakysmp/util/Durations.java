package com.streakysmp.util;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Human-readable duration formatting and parsing. */
public final class Durations {

    private static final Pattern TOKEN = Pattern.compile("(\\d+)\\s*([smhdw])", Pattern.CASE_INSENSITIVE);

    private Durations() {
    }

    /**
     * Formats playtime the way the spec's profile screen shows it, e.g.
     * {@code 127h 42m} or {@code 5d 7h 42m}.
     *
     * @param compact when true, collapses to the two largest non-zero units
     */
    public static String playtime(Duration duration, boolean compact) {
        long totalSeconds = Math.max(0, duration.getSeconds());
        long days = totalSeconds / 86_400;
        long hours = (totalSeconds % 86_400) / 3_600;
        long minutes = (totalSeconds % 3_600) / 60;

        if (days > 0) {
            return compact
                    ? days + "d " + hours + "h"
                    : days + "d " + hours + "h " + minutes + "m";
        }
        if (hours > 0) {
            return hours + "h " + minutes + "m";
        }
        return minutes + "m";
    }

    /** Formats a countdown such as a rent grace period, e.g. {@code 1d 4h 12m}. */
    public static String remaining(Duration duration) {
        if (duration.isNegative() || duration.isZero()) {
            return "0m";
        }
        long totalSeconds = duration.getSeconds();
        long days = totalSeconds / 86_400;
        long hours = (totalSeconds % 86_400) / 3_600;
        long minutes = (totalSeconds % 3_600) / 60;
        long seconds = totalSeconds % 60;

        StringBuilder sb = new StringBuilder();
        if (days > 0) {
            sb.append(days).append("d ");
        }
        if (days > 0 || hours > 0) {
            sb.append(hours).append("h ");
        }
        if (days == 0) {
            sb.append(minutes).append("m");
            if (days == 0 && hours == 0) {
                sb.append(' ').append(seconds).append('s');
            }
        } else {
            sb.append(minutes).append('m');
        }
        return sb.toString().trim();
    }

    /**
     * Parses a config duration such as {@code 48h}, {@code 7d} or {@code 30m}.
     *
     * <p>Accepts several tokens in sequence ({@code 1d12h}) so operators can
     * express periods naturally.
     */
    public static Optional<Duration> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        Matcher matcher = TOKEN.matcher(raw.trim().toLowerCase(Locale.ROOT));
        Duration total = Duration.ZERO;
        int matchedChars = 0;
        while (matcher.find()) {
            long amount = Long.parseLong(matcher.group(1));
            total = switch (matcher.group(2)) {
                case "s" -> total.plusSeconds(amount);
                case "m" -> total.plusMinutes(amount);
                case "h" -> total.plusHours(amount);
                case "d" -> total.plusDays(amount);
                case "w" -> total.plusDays(amount * 7);
                default -> total;
            };
            matchedChars += matcher.group().length();
        }
        // Require the whole string to be consumed so a typo like "7dd" or "7x"
        // fails loudly at config load instead of silently becoming 7 days.
        if (matchedChars != raw.trim().length() || total.isZero()) {
            return Optional.empty();
        }
        return Optional.of(total);
    }

    /** Converts a duration to Minecraft ticks, saturating rather than overflowing. */
    public static long toTicks(Duration duration) {
        long millis = duration.toMillis();
        return millis / 50L;
    }
}
