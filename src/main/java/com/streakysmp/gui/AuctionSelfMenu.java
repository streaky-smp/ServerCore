package com.streakysmp.gui;

import com.streakysmp.auction.AuctionListing;
import com.streakysmp.auction.AuctionService;
import com.streakysmp.auction.ListingStatus;
import com.streakysmp.core.Scheduling;
import com.streakysmp.economy.EconomyService;
import com.streakysmp.notify.Messages;
import com.streakysmp.notify.NotificationService;
import com.streakysmp.util.Durations;
import com.streakysmp.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * A player's own auction activity: active listings, and everything waiting to be
 * collected.
 *
 * <p>Both live in one screen because they are the same question from the player's
 * side -- "what of mine is in the auction house" -- and splitting them would mean
 * a player with an item waiting never finds it.
 */
public final class AuctionSelfMenu extends PaginatedMenu<AuctionListing> {

    private final AuctionService auctions;
    private final EconomyService economy;
    private final NotificationService notifications;
    private final Scheduling scheduling;

    private List<AuctionListing> loaded = List.of();
    private boolean busy;

    public AuctionSelfMenu(MenuManager menus,
                           ChatInput chatInput,
                           Player viewer,
                           AuctionService auctions,
                           EconomyService economy,
                           NotificationService notifications,
                           Scheduling scheduling) {
        super(menus, chatInput, viewer, Text.mm("<dark_gray>My Auctions</dark_gray>"), 6);
        this.auctions = auctions;
        this.economy = economy;
        this.notifications = notifications;
        this.scheduling = scheduling;
    }

    public static void openFor(MenuManager menus,
                               ChatInput chatInput,
                               Player player,
                               AuctionService auctions,
                               EconomyService economy,
                               NotificationService notifications,
                               Scheduling scheduling) {
        new AuctionSelfMenu(menus, chatInput, player, auctions, economy,
                notifications, scheduling).open();
    }

    @Override
    public void open() {
        scheduling.thenSync(
                scheduling.supplyAsync(() -> {
                    // Own listings plus anything bought and not yet collected.
                    List<AuctionListing> mine =
                            new ArrayList<>(auctions.bySeller(viewer.getUniqueId()));
                    for (AuctionListing owed : auctions.awaitingCollection(viewer.getUniqueId())) {
                        if (mine.stream().noneMatch(l -> l.id().equals(owed.id()))) {
                            mine.add(owed);
                        }
                    }
                    return List.copyOf(mine);
                }),
                listings -> {
                    loaded = listings;
                    super.open();
                },
                error -> viewer.sendMessage(Text.mm("<red>Could not load your auctions.</red>")));
    }

    @Override
    protected List<AuctionListing> source() {
        return loaded;
    }

    @Override
    protected boolean supportsSearch() {
        return true;
    }

    @Override
    protected boolean matches(AuctionListing entry, String lowercaseQuery) {
        return entry.material().replace('_', ' ').contains(lowercaseQuery)
                || (entry.displayName() != null
                        && entry.displayName().toLowerCase(Locale.ROOT).contains(lowercaseQuery));
    }

    @Override
    protected List<SortOption<AuctionListing>> sortOptions() {
        return List.of(
                // Things needing action first: collectable, then active, then done.
                new SortOption<>("Needs attention", Comparator
                        .comparingInt((AuctionListing l) -> priority(l))
                        .thenComparing(Comparator.comparingLong(
                                AuctionListing::createdAt).reversed())),
                new SortOption<>("Newest first",
                        Comparator.comparingLong(AuctionListing::createdAt).reversed()),
                new SortOption<>("Highest price first",
                        Comparator.comparingLong(AuctionListing::price).reversed()));
    }

    private int priority(AuctionListing listing) {
        if (listing.isOwedTo(viewer.getUniqueId())) {
            return 0;
        }
        return listing.status().isActive() ? 1 : 2;
    }

    @Override
    protected Button renderEntry(AuctionListing listing) {
        ItemStack item = AuctionService.deserialise(listing);
        boolean owed = listing.isOwedTo(viewer.getUniqueId());
        boolean active = listing.status().isActive();
        boolean seller = listing.isSeller(viewer.getUniqueId());

        List<String> lore = new ArrayList<>();
        lore.add("<gray>Status:</gray> " + statusColour(listing.status())
                + listing.status().displayName() + "</" + statusColourName(listing.status()) + ">");
        lore.add("<gray>Price:</gray> <white>"
                + economy.money().format(listing.price()) + "</white>");

        if (active) {
            lore.add("<gray>Ends in:</gray> <white>" + AuctionBrowseMenu.remaining(listing)
                    + "</white>");
            lore.add("");
            lore.add("<gray>Click to cancel and reclaim the item.</gray>");
        } else if (owed) {
            lore.add("");
            lore.add(listing.status() == ListingStatus.SOLD
                    ? "<green>You bought this. Click to collect.</green>"
                    : "<gold>Returned to you. Click to collect.</gold>");
            lore.add("<dark_gray>Make room in your inventory first.</dark_gray>");
        } else if (seller && listing.status() == ListingStatus.SOLD) {
            lore.add("<gray>Sold for:</gray> <green>"
                    + economy.money().format(
                            auctions.settings().sellerProceeds(listing.price())) + "</green>");
            long tax = auctions.settings().salesTaxFor(listing.price());
            if (tax > 0) {
                lore.add("<dark_gray>after " + economy.money().format(tax) + " tax</dark_gray>");
            }
        } else {
            lore.add("<dark_gray>Nothing to do.</dark_gray>");
        }
        lore.add("<dark_gray>Listing #" + listing.id() + "</dark_gray>");

        ItemBuilder icon = item == null
                ? ItemBuilder.of(Material.BARRIER).name("<red>Unreadable listing</red>")
                : ItemBuilder.copyOf(item).name("<white>"
                        + (listing.displayName() == null ? listing.material()
                                : listing.displayName()) + "</white>");

        return Button.of(icon.lore(lore).glow(owed).clean().build(), click -> {
            if (owed) {
                collect(click, listing);
            } else if (active && seller) {
                confirmCancel(click.player(), listing);
            }
        });
    }

    private void collect(ClickContext click, AuctionListing listing) {
        if (busy) {
            return;
        }
        busy = true;
        auctions.collect(click.player(), listing.id(), result -> {
            busy = false;
            if (result.isSuccess()) {
                notifications.success(click.player(), "auction.collected", Messages.of(
                        "item", listing.displayName() == null
                                ? listing.material() : listing.displayName()));
            } else {
                notifications.error(click.player(), result.messageKey(), Messages.of());
            }
            open();
        });
    }

    private void confirmCancel(Player player, AuctionListing listing) {
        ConfirmMenu.open(menus, player,
                Text.mm("<dark_gray>Cancel listing</dark_gray>"),
                List.of("<gray>Item:</gray> <white>"
                                + (listing.displayName() == null ? listing.material()
                                        : listing.displayName()) + "</white>",
                        "<gray>Asking price:</gray> <white>"
                                + economy.money().format(listing.price()) + "</white>",
                        "",
                        "<gray>The item returns to your collection.</gray>",
                        "<dark_gray>The listing fee is not refunded.</dark_gray>"),
                "Cancel listing",
                // cancel() dispatches its own database work and calls back on the
                // main thread, so it is invoked directly rather than wrapped again.
                () -> auctions.cancel(player.getUniqueId(), listing.id(), result -> {
                    if (result.isSuccess()) {
                        notifications.success(player, "auction.cancelled", Messages.of(
                                "item", listing.displayName() == null
                                        ? listing.material() : listing.displayName()));
                    } else {
                        notifications.error(player, result.messageKey(), Messages.of());
                    }
                    open();
                }),
                this::open);
    }

    @Override
    protected void decorate() {
        int lastRow = rows() - 1;
        long owed = loaded.stream().filter(l -> l.isOwedTo(viewer.getUniqueId())).count();

        if (loaded.isEmpty()) {
            set(22, Button.display(ItemBuilder.of(Material.BARRIER)
                    .name("<gray>Nothing here</gray>")
                    .lore("<gray>Hold an item and use</gray>",
                            "<white>/ah sell [price]</white>")
                    .clean().build()));
        }

        set(lastRow, 1, Button.display(ItemBuilder.of(
                        owed > 0 ? Material.ENDER_CHEST : Material.CHEST)
                .name("<aqua>Waiting for you</aqua>")
                .lore(owed > 0
                                ? "<green>" + owed + " item(s) to collect</green>"
                                : "<gray>Nothing to collect</gray>",
                        "<gray>Active listings:</gray> <white>"
                                + loaded.stream().filter(l -> l.status().isActive()).count()
                                + "</white>")
                .glow(owed > 0)
                .clean().build()));
    }

    private static String statusColour(ListingStatus status) {
        return "<" + statusColourName(status) + ">";
    }

    private static String statusColourName(ListingStatus status) {
        return switch (status) {
            case ACTIVE -> "green";
            case SOLD -> "gold";
            case EXPIRED -> "red";
            case CANCELLED -> "dark_gray";
        };
    }
}
