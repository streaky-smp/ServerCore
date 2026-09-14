package com.streakysmp.leaderboard;

import com.streakysmp.economy.Money;
import com.streakysmp.util.Durations;
import com.streakysmp.util.Text;
import net.kyori.adventure.text.Component;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * Turns ranked entries into the text a leaderboard displays.
 *
 * <p>Separated from {@link LeaderboardService} so the formatting can be tested
 * without a server. The service does the entity plumbing; this does the part with
 * decisions in it.
 *
 * <h2>Why entries are not column-aligned</h2>
 * The specification sketches leaderboards with right-aligned value columns.
 * Minecraft's default font is proportional, so padding with spaces gives
 * alignment that shifts with the characters in each name -- {@code IIII} and
 * {@code WWWW} are wildly different widths -- and shifts differently again in
 * Bedrock's font. Rather than ship an approximation that looks broken for half of
 * all names, each entry is one line of {@code #1 Name - value}, which renders
 * correctly in every font on both platforms.
 */
public final class LeaderboardRenderer {

    private LeaderboardRenderer() {
    }

    /** Builds the MiniMessage markup for a board. Exposed for testing. */
    public static String markup(String title,
                                LeaderboardStat stat,
                                List<LeaderboardService.Entry> entries,
                                Money money) {
        StringBuilder markup = new StringBuilder();
        markup.append("<gold><bold>").append(Text.escape(title)).append("</bold></gold>");

        if (entries.isEmpty()) {
            markup.append("\n<gray>No entries yet</gray>");
            return markup.toString();
        }

        for (LeaderboardService.Entry entry : entries) {
            String colour = positionColour(entry.position());
            markup.append('\n')
                    .append('<').append(colour).append('>')
                    .append('#').append(entry.position())
                    .append("</").append(colour).append("> ")
                    // Player names are escaped: a name cannot inject markup into
                    // a sign every player at spawn is looking at.
                    .append("<white>").append(Text.escape(entry.name())).append("</white>")
                    .append(" <dark_gray>-</dark_gray> ")
                    .append("<yellow>").append(formatValue(stat, entry.value(), money))
                    .append("</yellow>");
        }
        return markup.toString();
    }

    public static Component render(String title,
                                   LeaderboardStat stat,
                                   List<LeaderboardService.Entry> entries,
                                   Money money) {
        return Text.mm(markup(title, stat, entries, money));
    }

    /** Renders a raw value according to what the statistic measures. */
    public static String formatValue(LeaderboardStat stat, long value, Money money) {
        return switch (stat.format()) {
            case MONEY -> money.formatCompact(value);
            case DURATION -> Durations.playtime(Duration.ofSeconds(value), true);
            case COUNT -> String.format(Locale.ROOT, "%,d", value);
        };
    }

    static String positionColour(int position) {
        return switch (position) {
            case 1 -> "gold";
            case 2 -> "gray";
            case 3 -> "dark_red";
            default -> "dark_gray";
        };
    }
}
