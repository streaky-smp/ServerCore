package com.streakysmp.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * A reusable yes/no confirmation screen.
 *
 * <p>Used before anything irreversible or expensive: large payments, plot
 * purchases, claim deletion, auction listings. Confirm and cancel sit at
 * opposite ends of the row so a mistimed tap on a phone lands on neither, which
 * matters more on Bedrock touch controls than with a mouse.
 *
 * <p>The action is a callback rather than a re-derived intent: whatever the
 * caller captured when it built the dialog is exactly what runs, so the screen
 * cannot be tricked into confirming a different operation than the one described.
 */
public final class ConfirmMenu extends Menu {

    private final List<String> description;
    private final String confirmLabel;
    private final String cancelLabel;
    private final Runnable onConfirm;
    private final Runnable onCancel;

    private ConfirmMenu(MenuManager menus,
                        Player viewer,
                        Component title,
                        List<String> description,
                        String confirmLabel,
                        String cancelLabel,
                        Runnable onConfirm,
                        Runnable onCancel) {
        super(menus, viewer, title, 3);
        this.description = List.copyOf(description);
        this.confirmLabel = confirmLabel;
        this.cancelLabel = cancelLabel;
        this.onConfirm = onConfirm;
        this.onCancel = onCancel;
    }

    /**
     * Builds and opens a confirmation dialog.
     *
     * @param description MiniMessage lines explaining exactly what will happen,
     *                    including the amount of money involved
     * @param onConfirm   run on the main thread if the player confirms
     * @param onCancel    run on the main thread if they decline or close the menu
     */
    public static void open(MenuManager menus,
                            Player viewer,
                            Component title,
                            List<String> description,
                            String confirmLabel,
                            Runnable onConfirm,
                            Runnable onCancel) {
        new ConfirmMenu(menus, viewer, title, description, confirmLabel, "Cancel", onConfirm, onCancel)
                .open();
    }

    /** Shorthand with a standard confirm label. */
    public static void open(MenuManager menus,
                            Player viewer,
                            Component title,
                            List<String> description,
                            Runnable onConfirm,
                            Runnable onCancel) {
        open(menus, viewer, title, description, "Confirm", onConfirm, onCancel);
    }

    private boolean decided;

    @Override
    protected void build() {
        fillEmpty(Material.GRAY_STAINED_GLASS_PANE);

        set(1, 2, Button.of(ItemBuilder.of(Material.LIME_CONCRETE)
                .name("<green><bold>" + confirmLabel + "</bold></green>")
                .lore("<gray>Click to proceed.</gray>")
                .clean().build(), click -> {
            decided = true;
            click.close();
            onConfirm.run();
        }));

        set(1, 4, Button.display(ItemBuilder.of(Material.BOOK)
                .name("<white><bold>Please confirm</bold></white>")
                .lore(description)
                .clean().build()));

        set(1, 6, Button.of(ItemBuilder.of(Material.RED_CONCRETE)
                .name("<red><bold>" + cancelLabel + "</bold></red>")
                .lore("<gray>Click to go back without changes.</gray>")
                .clean().build(), click -> {
            decided = true;
            click.close();
            onCancel.run();
        }));
    }

    /**
     * Treats closing the window as declining.
     *
     * <p>Without this, a player who presses Escape leaves the caller waiting on a
     * callback that never fires, and any state it was holding leaks.
     */
    @Override
    public void onClose() {
        if (!decided) {
            decided = true;
            onCancel.run();
        }
    }
}
