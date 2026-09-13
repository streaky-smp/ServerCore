package com.servercore.leaderboard;

import com.servercore.economy.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a leaderboard actually says.
 *
 * <p>The entity plumbing needs a live server, but the text does not, and the text
 * is where the decisions are. Verifying the markup here is what makes the live
 * check a matter of "did an entity appear" rather than squinting at truncated NBT.
 */
class LeaderboardRendererTest {

    private static final Money MONEY = new Money("$", 2, true, true);

    private static List<LeaderboardService.Entry> entries() {
        return List.of(
                new LeaderboardService.Entry(1, "Steve", 1_245L),
                new LeaderboardService.Entry(2, "Alex", 982L),
                new LeaderboardService.Entry(3, "Notch", 841L),
                new LeaderboardService.Entry(4, "Jeb", 734L));
    }

    @Test
    @DisplayName("the title leads and every entry gets its own line")
    void layoutIsTitleThenOneLinePerEntry() {
        String markup = LeaderboardRenderer.markup(
                "Top Kills", LeaderboardStat.KILLS, entries(), MONEY);

        assertTrue(markup.startsWith("<gold><bold>Top Kills</bold></gold>"));
        assertEquals(4, markup.chars().filter(c -> c == '\n').count(),
                "one newline per entry, none after the last");
        assertTrue(markup.contains("Steve"));
        assertTrue(markup.contains("Jeb"));
    }

    @Test
    @DisplayName("positions are numbered and the podium is coloured")
    void podiumIsColoured() {
        String markup = LeaderboardRenderer.markup(
                "Top Kills", LeaderboardStat.KILLS, entries(), MONEY);

        assertTrue(markup.contains("<gold>#1</gold>"));
        assertTrue(markup.contains("<gray>#2</gray>"));
        assertTrue(markup.contains("<dark_red>#3</dark_red>"));
        assertTrue(markup.contains("<dark_gray>#4</dark_gray>"),
                "everyone below third shares a neutral colour");
    }

    @Test
    @DisplayName("an empty board says so instead of rendering a bare title")
    void emptyBoardIsExplicit() {
        String markup = LeaderboardRenderer.markup(
                "Top Kills", LeaderboardStat.KILLS, List.of(), MONEY);
        assertTrue(markup.contains("No entries yet"));
    }

    @Test
    @DisplayName("counts are grouped with thousands separators")
    void countsAreGrouped() {
        assertEquals("1,245", LeaderboardRenderer.formatValue(LeaderboardStat.KILLS, 1_245L, MONEY));
        assertEquals("999", LeaderboardRenderer.formatValue(LeaderboardStat.KILLS, 999L, MONEY));
    }

    @Test
    @DisplayName("money is abbreviated so a sign stays readable")
    void moneyIsAbbreviated() {
        // 125,430 major units. A full figure would overflow the display width.
        assertEquals("$125.4k",
                LeaderboardRenderer.formatValue(LeaderboardStat.MONEY, 125_430_00L, MONEY));
        assertEquals("$1.2M",
                LeaderboardRenderer.formatValue(LeaderboardStat.MONEY, 1_250_000_00L, MONEY));
    }

    @Test
    @DisplayName("playtime renders as a duration, not a second count")
    void playtimeIsADuration() {
        // 459,720 seconds is 5 days 7 hours.
        assertEquals("5d 7h",
                LeaderboardRenderer.formatValue(LeaderboardStat.PLAYTIME, 459_720L, MONEY));
        assertEquals("2h 0m",
                LeaderboardRenderer.formatValue(LeaderboardStat.PLAYTIME, 7_200L, MONEY));
    }

    /**
     * A leaderboard at spawn is the single most visible piece of text on a
     * server. A player named {@code <rainbow>} must not be able to restyle it,
     * and one named with a click event must not be able to make every passer-by
     * run a command.
     */
    @Test
    @DisplayName("player names cannot inject markup into the sign")
    void playerNamesAreEscaped() {
        List<LeaderboardService.Entry> hostile = List.of(
                new LeaderboardService.Entry(1, "<rainbow>pwned", 10L));

        var component = LeaderboardRenderer.render(
                "Top Kills", LeaderboardStat.KILLS, hostile, MONEY);

        // MiniMessage escapes by prefixing a backslash, so the raw markup still
        // contains the characters. What matters is the rendered result: the tag
        // must survive as literal text rather than becoming styling.
        String plain = com.servercore.util.Text.plain(component);
        assertTrue(plain.contains("<rainbow>pwned"),
                "the tag must render as literal text, not restyle a sign the whole server sees");
        assertEquals(0, component.children().stream()
                        .filter(child -> child.color() != null
                                && "rainbow".equals(String.valueOf(child.color())))
                        .count(),
                "no rainbow styling should have been applied");
    }

    @Test
    @DisplayName("an operator-supplied title cannot inject a click action")
    void titleIsEscaped() {
        var component = LeaderboardRenderer.render(
                "<click:run_command:'/op me'>Title", LeaderboardStat.KILLS, List.of(), MONEY);

        String plain = com.servercore.util.Text.plain(component);
        assertTrue(plain.contains("<click:run_command:'/op me'>Title"),
                "the tag must render literally rather than arming a command for every viewer");
        assertNoClickEvent(component);
    }

    /** A click event anywhere in the tree would be executable by any viewer. */
    private static void assertNoClickEvent(net.kyori.adventure.text.Component component) {
        assertEquals(null, component.clickEvent(), "no click event may survive escaping");
        for (var child : component.children()) {
            assertNoClickEvent(child);
        }
    }

    @Test
    @DisplayName("rendering produces a usable component")
    void rendersToAComponent() {
        var component = LeaderboardRenderer.render(
                "Top Kills", LeaderboardStat.KILLS, entries(), MONEY);
        String plain = com.servercore.util.Text.plain(component);

        assertTrue(plain.contains("Top Kills"));
        assertTrue(plain.contains("Steve"));
        assertTrue(plain.contains("1,245"));
        assertTrue(plain.contains("\n"), "entries must be on separate lines");
    }

    @Test
    @DisplayName("every statistic has a default title and a format")
    void everyStatIsFullyDescribed() {
        for (LeaderboardStat stat : LeaderboardStat.values()) {
            assertFalse(stat.defaultTitle().isBlank(),
                    stat + " needs a default title for boards created without one");
            assertEquals(stat, LeaderboardStat.byKey(stat.key()).orElseThrow(),
                    stat + " must round-trip through its persisted key");
        }
    }

    @Test
    @DisplayName("money is the one statistic not backed by a counter")
    void moneyIsNotACounter() {
        assertTrue(LeaderboardStat.MONEY.statistic().isEmpty(),
                "money lives in the account table, not the statistics table");
        assertTrue(LeaderboardStat.KILLS.statistic().isPresent());
    }
}
