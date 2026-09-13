package com.servercore.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Adventure component helpers.
 *
 * <p>All user-facing text is authored as MiniMessage in config and rendered
 * here, so operators can restyle every message without touching code.
 */
public final class Text {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private Text() {
    }

    /** Renders MiniMessage markup into a component. */
    public static Component mm(String miniMessage) {
        return MINI.deserialize(miniMessage);
    }

    /** Renders MiniMessage markup with {@code <name>} placeholders substituted. */
    public static Component mm(String miniMessage, Map<String, String> placeholders) {
        TagResolver[] resolvers = placeholders.entrySet().stream()
                .map(e -> (TagResolver) Placeholder.parsed(e.getKey(), e.getValue()))
                .toArray(TagResolver[]::new);
        return MINI.deserialize(miniMessage, resolvers);
    }

    /**
     * Renders text for use as an item name or lore line.
     *
     * <p>Minecraft italicises item display names and lore by default. Clearing
     * the decoration explicitly is what stops every GUI label in the plugin from
     * rendering in italics.
     */
    public static Component item(String miniMessage) {
        return mm(miniMessage).decoration(TextDecoration.ITALIC, false);
    }

    public static Component item(String miniMessage, Map<String, String> placeholders) {
        return mm(miniMessage, placeholders).decoration(TextDecoration.ITALIC, false);
    }

    /** Renders each line as non-italic lore. */
    public static List<Component> lore(List<String> lines) {
        List<Component> out = new ArrayList<>(lines.size());
        for (String line : lines) {
            out.add(item(line));
        }
        return out;
    }

    public static List<Component> lore(List<String> lines, Map<String, String> placeholders) {
        List<Component> out = new ArrayList<>(lines.size());
        for (String line : lines) {
            out.add(item(line, placeholders));
        }
        return out;
    }

    /** Serialises a component back to plain text, for logs and Bedrock form titles. */
    public static String plain(Component component) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(component);
    }

    /**
     * Escapes text that came from a player so it cannot inject MiniMessage tags.
     *
     * <p>Shop names, claim names and auction descriptions are all player-authored
     * and all end up inside MiniMessage templates. Without escaping, a player
     * could name a shop {@code <rainbow>} or embed a click event that runs a
     * command on whoever views it.
     */
    public static String escape(String playerInput) {
        return MINI.escapeTags(playerInput);
    }
}
