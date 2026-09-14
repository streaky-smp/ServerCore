package com.streakysmp.util;

import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What {@link Text#escape} actually protects against.
 *
 * <p>Player-authored names reach MiniMessage templates from half a dozen places:
 * claim names, shop names, search terms, leaderboard entries. The risk is not
 * cosmetic -- a click event on a leaderboard at spawn would arm a command for
 * every passer-by.
 *
 * <p>These tests assert on the <em>rendered</em> component rather than the
 * escaped string, because the escaped form is an implementation detail of
 * MiniMessage and the guarantee that matters is "no styling, no events".
 */
class TextEscapeTest {

    /** Inputs a hostile player might actually try. */
    private static final List<String> HOSTILE = List.of(
            "<rainbow>pretty",
            "<red>fake warning",
            "<bold>LOUD",
            "<click:run_command:'/op me'>Free diamonds",
            "<click:suggest_command:'/pay them 10000'>Click here",
            "<hover:show_text:'gotcha'>hover me",
            "<insert:text>insert",
            "<gradient:red:blue>gradient",
            "<#ff0000>hex",
            "<newline>second line",
            "<lang:block.minecraft.stone>",
            "<key:key.jump>",
            "<score:name:objective>",
            "<selector:@a>",
            "<reset>plain",
            "<font:minecraft:uniform>font");

    @Test
    @DisplayName("no escaped player text produces a click event")
    void noClickEventsSurvive() {
        for (String hostile : HOSTILE) {
            Component rendered = Text.mm(Text.escape(hostile));
            assertNoClickEvent(rendered, hostile);
        }
    }

    @Test
    @DisplayName("no escaped player text produces a hover event")
    void noHoverEventsSurvive() {
        for (String hostile : HOSTILE) {
            Component rendered = Text.mm(Text.escape(hostile));
            assertNoHoverEvent(rendered, hostile);
        }
    }

    @Test
    @DisplayName("escaped text renders as exactly the characters the player typed")
    void textSurvivesLiterally() {
        for (String hostile : HOSTILE) {
            String plain = Text.plain(Text.mm(Text.escape(hostile)));
            assertTrue(plain.contains(hostile),
                    "escaping '" + hostile + "' should render it literally, got '" + plain + "'");
        }
    }

    @Test
    @DisplayName("escaping survives being embedded in a surrounding template")
    void safeInsideATemplate() {
        // The real usage: escaped text concatenated into our own markup.
        for (String hostile : HOSTILE) {
            Component rendered = Text.mm(
                    "<gray>Owner:</gray> <white>" + Text.escape(hostile) + "</white>");
            assertNoClickEvent(rendered, hostile);
            assertNoHoverEvent(rendered, hostile);
            assertTrue(Text.plain(rendered).contains(hostile),
                    "the literal text should still appear for '" + hostile + "'");
        }
    }

    @Test
    @DisplayName("a closing tag in player text cannot terminate our styling early")
    void closingTagsCannotEscapeTheTemplate() {
        // Without escaping, "</white><red>" would end our span and start theirs.
        String hostile = "</white><red>injected";
        Component rendered = Text.mm(
                "<white>" + Text.escape(hostile) + "</white>");

        assertTrue(Text.plain(rendered).contains(hostile),
                "the tags must render as text rather than taking effect");
    }

    private static void assertNoClickEvent(Component component, String source) {
        assertNull(component.clickEvent(),
                "a click event survived escaping of '" + source + "'");
        for (Component child : component.children()) {
            assertNoClickEvent(child, source);
        }
    }

    private static void assertNoHoverEvent(Component component, String source) {
        assertNull(component.hoverEvent(),
                "a hover event survived escaping of '" + source + "'");
        for (Component child : component.children()) {
            assertNoHoverEvent(child, source);
        }
    }
}
