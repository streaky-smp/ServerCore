package com.servercore.gui;

import org.bukkit.Material;

import java.util.function.IntConsumer;

/**
 * The shared quantity picker used by every buy/sell screen.
 *
 * <p>Six discrete step buttons and a readout, rather than scrolling or dragging.
 * Every control is a plain left click, which is the only interaction that behaves
 * identically on a mouse, a touchscreen through Geyser, and a controller.
 *
 * <p>Extracted so the server shop, player shops and the auction house all offer
 * the same picker. Three near-identical copies is how one of them ends up with an
 * off-by-one in its clamping that the others do not have.
 */
public final class QuantityControls {

    /** Slot offsets within the picker row, relative to the row's first slot. */
    private static final int[] DECREASE_SLOTS = {0, 1, 2};
    private static final int[] INCREASE_SLOTS = {4, 5, 6};
    private static final int READOUT_SLOT = 3;

    private static final int[] DECREASE_STEPS = {-64, -8, -1};
    private static final int[] INCREASE_STEPS = {1, 8, 64};

    private QuantityControls() {
    }

    /**
     * Renders the picker into {@code menu} starting at {@code baseSlot}.
     *
     * <p>Occupies seven consecutive slots. {@code onChange} receives the new
     * quantity, already clamped into {@code [1, maximum]}.
     */
    public static void render(Menu menu, int baseSlot, int current, int maximum, IntConsumer onChange) {
        for (int i = 0; i < DECREASE_STEPS.length; i++) {
            step(menu, baseSlot + DECREASE_SLOTS[i], DECREASE_STEPS[i], current, maximum, onChange);
        }
        for (int i = 0; i < INCREASE_STEPS.length; i++) {
            step(menu, baseSlot + INCREASE_SLOTS[i], INCREASE_STEPS[i], current, maximum, onChange);
        }

        menu.set(baseSlot + READOUT_SLOT, Button.display(
                ItemBuilder.of(Material.PAPER, Math.max(1, Math.min(64, current)))
                        .name("<yellow>Quantity: <white>" + current + "</white></yellow>")
                        .lore("<gray>Maximum here:</gray> <white>" + maximum + "</white>",
                                "<gray>Use the arrows to change.</gray>")
                        .clean().build()));
    }

    private static void step(Menu menu, int slot, int delta, int current, int maximum,
                             IntConsumer onChange) {
        boolean increase = delta > 0;
        Material icon = increase ? Material.LIME_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE;

        menu.set(slot, Button.of(ItemBuilder.of(icon, Math.min(64, Math.abs(delta)))
                .name((increase ? "<green>+" : "<red>-") + Math.abs(delta)
                        + (increase ? "</green>" : "</red>"))
                .clean().build(), click -> {
            onChange.accept(clamp(current + delta, maximum));
            click.refresh();
        }));
    }

    /** Clamps into {@code [1, maximum]}, or 1 when nothing is available. */
    public static int clamp(int value, int maximum) {
        if (maximum < 1) {
            return 1;
        }
        return Math.max(1, Math.min(value, maximum));
    }
}
