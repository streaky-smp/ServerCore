package com.streakysmp.gui;

import com.streakysmp.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Fluent construction of GUI icons.
 *
 * <p>Icons built here are display only. Nothing in this plugin ever reads state
 * back off a menu item: a click is resolved against server-side state keyed by
 * slot, never against what the player appears to be holding. That distinction is
 * what makes a modified client unable to fabricate a purchase by editing an
 * item's lore.
 */
public final class ItemBuilder {

    private final ItemStack stack;

    private ItemBuilder(Material material, int amount) {
        this.stack = new ItemStack(material, amount);
    }

    public static ItemBuilder of(Material material) {
        return new ItemBuilder(material, 1);
    }

    public static ItemBuilder of(Material material, int amount) {
        return new ItemBuilder(material, Math.max(1, Math.min(amount, material.getMaxStackSize())));
    }

    /** Starts from an existing stack, copying it so the original is untouched. */
    public static ItemBuilder copyOf(ItemStack existing) {
        ItemBuilder builder = new ItemBuilder(existing.getType(), existing.getAmount());
        builder.stack.setItemMeta(existing.getItemMeta());
        return builder;
    }

    /** Sets the display name from MiniMessage markup. */
    public ItemBuilder name(String miniMessage) {
        stack.editMeta(meta -> meta.displayName(Text.item(miniMessage)));
        return this;
    }

    public ItemBuilder name(String miniMessage, Map<String, String> placeholders) {
        stack.editMeta(meta -> meta.displayName(Text.item(miniMessage, placeholders)));
        return this;
    }

    public ItemBuilder name(Component component) {
        stack.editMeta(meta -> meta.displayName(component));
        return this;
    }

    /** Replaces the lore with the given MiniMessage lines. */
    public ItemBuilder lore(List<String> miniMessageLines) {
        stack.editMeta(meta -> meta.lore(Text.lore(miniMessageLines)));
        return this;
    }

    public ItemBuilder lore(List<String> miniMessageLines, Map<String, String> placeholders) {
        stack.editMeta(meta -> meta.lore(Text.lore(miniMessageLines, placeholders)));
        return this;
    }

    public ItemBuilder lore(String... miniMessageLines) {
        return lore(Arrays.asList(miniMessageLines));
    }

    /** Appends lines to the existing lore. */
    public ItemBuilder addLore(List<String> miniMessageLines) {
        stack.editMeta(meta -> {
            List<Component> lines = meta.lore();
            List<Component> combined = lines == null ? new ArrayList<>() : new ArrayList<>(lines);
            combined.addAll(Text.lore(miniMessageLines));
            meta.lore(combined);
        });
        return this;
    }

    public ItemBuilder amount(int amount) {
        stack.setAmount(Math.max(1, Math.min(amount, stack.getType().getMaxStackSize())));
        return this;
    }

    /** Adds the enchanted shimmer without an actual enchantment, for "selected" states. */
    public ItemBuilder glow(boolean glowing) {
        stack.editMeta(meta -> meta.setEnchantmentGlintOverride(glowing));
        return this;
    }

    /**
     * Hides the vanilla attribute and enchantment text.
     *
     * <p>Worth doing on every icon: on Bedrock the extra lines push the real lore
     * out of view on small screens, and the spec requires the important text to
     * be readable there.
     */
    public ItemBuilder clean() {
        stack.editMeta(meta -> meta.addItemFlags(ItemFlag.values()));
        return this;
    }

    /** Starts a player head icon, for profiles and leaderboards. */
    public static ItemBuilder head(UUID owner) {
        return of(Material.PLAYER_HEAD).skullOwner(owner);
    }

    /**
     * Points this head at a player's skin.
     *
     * <p>Only valid on a {@link Material#PLAYER_HEAD}; use {@link #head(UUID)} to
     * start one. Resolving the skin is asynchronous inside the server, so the
     * head renders as the default until it arrives.
     */
    public ItemBuilder skullOwner(UUID owner) {
        if (stack.getType() != Material.PLAYER_HEAD) {
            throw new IllegalStateException(
                    "skullOwner requires a PLAYER_HEAD, but this icon is " + stack.getType());
        }
        stack.editMeta(SkullMeta.class, meta ->
                meta.setOwningPlayer(org.bukkit.Bukkit.getOfflinePlayer(owner)));
        return this;
    }

    public ItemStack build() {
        return stack.clone();
    }
}
