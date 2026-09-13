package com.servercore.gui;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

/**
 * Everything a button handler needs about a click.
 *
 * <p>Handlers run on the main thread. Anything that touches the database must be
 * dispatched off-thread and its result delivered back with
 * {@link com.servercore.core.Scheduling#thenSync}.
 *
 * @param clickType the raw click; note that Bedrock clients cannot produce every
 *                  variant, so no action may be reachable only via shift-click or
 *                  middle-click. See {@link #isSecondary()}.
 */
public record ClickContext(Player player, Menu menu, int slot, ClickType clickType) {

    /**
     * True for right-click and shift-right-click.
     *
     * <p>Safe to use for a secondary action only when a primary path to the same
     * action also exists, because right-click maps to a long-press on Bedrock
     * touch controls and is not discoverable.
     */
    public boolean isSecondary() {
        return clickType == ClickType.RIGHT || clickType == ClickType.SHIFT_RIGHT;
    }

    public boolean isShift() {
        return clickType.isShiftClick();
    }

    /** Re-renders the current menu in place. */
    public void refresh() {
        menu.refresh();
    }

    /** Closes the menu on the next tick, which is safe from inside a click handler. */
    public void close() {
        menu.closeLater();
    }
}
