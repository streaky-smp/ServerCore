package com.servercore.gui;

import org.bukkit.inventory.ItemStack;

import java.util.function.Consumer;

/**
 * A clickable slot in a {@link Menu}.
 *
 * <p>The icon and the action are bound together server-side. A click is always
 * resolved by looking up the button registered for that slot, never by reading
 * the {@link ItemStack} the client believes is there, so a client that fabricates
 * or edits an item cannot invoke an action it was not offered.
 *
 * @param icon       what the player sees
 * @param onClick    invoked on the main thread when clicked; null for a pure label
 * @param permission required to see and use the button; null for no requirement
 */
public record Button(ItemStack icon, Consumer<ClickContext> onClick, String permission) {

    /** A non-interactive label or decoration. */
    public static Button display(ItemStack icon) {
        return new Button(icon, null, null);
    }

    public static Button of(ItemStack icon, Consumer<ClickContext> onClick) {
        return new Button(icon, onClick, null);
    }

    public static Button of(ItemStack icon, String permission, Consumer<ClickContext> onClick) {
        return new Button(icon, onClick, permission);
    }

    public boolean isInteractive() {
        return onClick != null;
    }

    public Button withPermission(String permission) {
        return new Button(icon, onClick, permission);
    }
}
