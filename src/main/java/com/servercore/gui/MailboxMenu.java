package com.servercore.gui;

import com.servercore.core.Scheduling;
import com.servercore.notify.Messages;
import com.servercore.notify.NotificationService;
import com.servercore.plot.PlotRepository;
import com.servercore.plot.PlotService;
import com.servercore.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Recovery of stock from a shop whose plot expired.
 *
 * <p>The spec forbids silently deleting shop inventory. This is where it comes
 * back: each entry is one shop's goods, serialised with Paper's own item
 * serialisation so enchantments and data components survive intact.
 *
 * <p>Collection is conditional on the entry still being uncollected, and the
 * items are only handed over once that claim succeeds. Two fast clicks therefore
 * cannot duplicate a shop's entire stock -- which is precisely the exploit a
 * naive "give items then mark collected" would create.
 */
public final class MailboxMenu extends PaginatedMenu<PlotRepository.MailboxEntry> {

    private final PlotService plots;
    private final NotificationService notifications;
    private final Scheduling scheduling;

    private List<PlotRepository.MailboxEntry> loaded = List.of();

    public MailboxMenu(MenuManager menus,
                       Player viewer,
                       PlotService plots,
                       NotificationService notifications,
                       Scheduling scheduling) {
        super(menus, null, viewer, Text.mm("<dark_gray>Recovered Stock</dark_gray>"), 6);
        this.plots = plots;
        this.notifications = notifications;
        this.scheduling = scheduling;
    }

    public static void openFor(MenuManager menus,
                               Player player,
                               PlotService plots,
                               NotificationService notifications,
                               Scheduling scheduling) {
        new MailboxMenu(menus, player, plots, notifications, scheduling).open();
    }

    @Override
    public void open() {
        scheduling.thenSync(
                scheduling.supplyAsync(() -> plots.pendingMail(viewer.getUniqueId())),
                entries -> {
                    loaded = entries;
                    super.open();
                },
                error -> viewer.sendMessage(Text.mm("<red>Could not load your stored stock.</red>")));
    }

    @Override
    protected List<PlotRepository.MailboxEntry> source() {
        return loaded;
    }

    @Override
    protected Button renderEntry(PlotRepository.MailboxEntry entry) {
        ItemStack[] items = deserialise(entry);
        int stacks = items == null ? 0 : items.length;
        int total = 0;
        List<String> contents = new ArrayList<>();
        if (items != null) {
            for (ItemStack stack : items) {
                if (stack == null) {
                    continue;
                }
                total += stack.getAmount();
                if (contents.size() < 6) {
                    contents.add("<dark_gray>  " + stack.getAmount() + "x "
                            + friendly(stack.getType()) + "</dark_gray>");
                }
            }
            if (stacks > 6) {
                contents.add("<dark_gray>  ...and " + (stacks - 6) + " more stack(s)</dark_gray>");
            }
        }

        List<String> lore = new ArrayList<>();
        lore.add("<gray>From plot:</gray> <white>" + entry.plotId() + "</white>");
        lore.add("<gray>Stored:</gray> <white>" + age(entry.storedAt()) + " ago</white>");
        lore.add("<gray>Items:</gray> <white>" + total + "</white> <dark_gray>in "
                + stacks + " stack(s)</dark_gray>");
        if (!contents.isEmpty()) {
            lore.add("");
            lore.addAll(contents);
        }
        lore.add("");
        lore.add("<gray>Click to collect.</gray>");
        lore.add("<dark_gray>Make room in your inventory first.</dark_gray>");

        return Button.of(ItemBuilder.of(Material.CHEST)
                .name("<gold>Recovered stock</gold>")
                .lore(lore)
                .clean().build(), click -> collect(click, entry));
    }

    /**
     * Claims the entry, then hands the items over.
     *
     * <p>Order matters: the database claim happens first and is conditional, so
     * only one click can ever succeed. Handing items over first and marking
     * afterwards would let two clicks duplicate the lot.
     */
    private void collect(ClickContext click, PlotRepository.MailboxEntry entry) {
        ItemStack[] items = deserialise(entry);
        if (items == null) {
            notifications.error(click.player(), "plot.error.mail-unreadable", Messages.of());
            return;
        }

        scheduling.thenSync(
                scheduling.supplyAsync(() -> plots.claimMail(entry.id())),
                claimed -> {
                    if (!Boolean.TRUE.equals(claimed)) {
                        // Somebody already collected it -- a second click, most likely.
                        notifications.info(click.player(), "plot.mail-already-collected",
                                Messages.of());
                        open();
                        return;
                    }
                    int dropped = 0;
                    for (ItemStack stack : items) {
                        if (stack == null) {
                            continue;
                        }
                        var leftovers = click.player().getInventory().addItem(stack);
                        for (ItemStack leftover : leftovers.values()) {
                            // Dropping beats deleting: the entry is already claimed,
                            // so refusing here would lose the goods entirely.
                            click.player().getWorld().dropItemNaturally(
                                    click.player().getLocation(), leftover);
                            dropped++;
                        }
                    }
                    notifications.success(click.player(), "plot.mail-collected", Messages.of(
                            "plot", entry.plotId(),
                            "dropped", String.valueOf(dropped)));
                    if (dropped > 0) {
                        notifications.info(click.player(), "plot.mail-dropped",
                                Messages.of("count", String.valueOf(dropped)));
                    }
                    open();
                },
                error -> notifications.error(click.player(), "error.internal", Messages.of()));
    }

    @Override
    protected void decorate() {
        if (loaded.isEmpty()) {
            set(22, Button.display(ItemBuilder.of(Material.BARRIER)
                    .name("<gray>Nothing stored</gray>")
                    .lore("<gray>Stock from an expired plot would appear here.</gray>")
                    .clean().build()));
        }
    }

    /** Null when the stored bytes cannot be read, rather than throwing into the GUI. */
    private static ItemStack[] deserialise(PlotRepository.MailboxEntry entry) {
        try {
            return ItemStack.deserializeItemsFromBytes(entry.itemData());
        } catch (Exception e) {
            return null;
        }
    }

    private static String age(long storedAt) {
        Duration since = Duration.between(Instant.ofEpochMilli(storedAt), Instant.now());
        return com.servercore.util.Durations.remaining(since);
    }

    private static String friendly(Material material) {
        String raw = material.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(raw.charAt(0)) + raw.substring(1);
    }
}
