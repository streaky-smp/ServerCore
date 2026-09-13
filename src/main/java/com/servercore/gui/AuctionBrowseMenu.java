package com.servercore.gui;

import com.servercore.auction.AuctionListing;
import com.servercore.auction.AuctionResult;
import com.servercore.auction.AuctionService;
import com.servercore.core.Scheduling;
import com.servercore.economy.EconomyService;
import com.servercore.notify.Messages;
import com.servercore.notify.NotificationService;
import com.servercore.util.Durations;
import com.servercore.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The auction house browse screen.
 *
 * <p>Each entry renders the actual listed item, so an enchanted sword looks like
 * an enchanted sword rather than a generic icon. Clicking opens a confirmation
 * that restates the price, because a misclick here spends real money.
 *
 * <p>Listings are acted on by their id, never by the slot that was clicked. A
 * stale menu therefore fails safely -- it reports the listing as gone instead of
 * buying whatever now occupies that position.
 */
public final class AuctionBrowseMenu extends PaginatedMenu<AuctionListing> {

    private final AuctionService auctions;
    private final EconomyService economy;
    private final NotificationService notifications;
    private final Scheduling scheduling;
    private final ChatInput chatInput;

    private List<AuctionListing> loaded = List.of();
    private long balance;
    private boolean busy;

    public AuctionBrowseMenu(MenuManager menus,
                             ChatInput chatInput,
                             Player viewer,
                             AuctionService auctions,
                             EconomyService economy,
                             NotificationService notifications,
                             Scheduling scheduling) {
        super(menus, chatInput, viewer, Text.mm("<dark_gray>Auction House</dark_gray>"), 6);
        this.chatInput = chatInput;
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
        new AuctionBrowseMenu(menus, chatInput, player, auctions, economy,
                notifications, scheduling).open();
    }

    @Override
    public void open() {
        scheduling.thenSync(
                scheduling.supplyAsync(() -> new Object[]{
                        auctions.browse(), economy.getBalance(viewer.getUniqueId())}),
                data -> {
                    @SuppressWarnings("unchecked")
                    List<AuctionListing> listings = (List<AuctionListing>) data[0];
                    loaded = listings;
                    balance = (Long) data[1];
                    super.open();
                },
                error -> viewer.sendMessage(Text.mm("<red>Could not load the auction house.</red>")));
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
        if (entry.material().replace('_', ' ').contains(lowercaseQuery)) {
            return true;
        }
        return entry.displayName() != null
                && entry.displayName().toLowerCase(Locale.ROOT).contains(lowercaseQuery);
    }

    @Override
    protected List<SortOption<AuctionListing>> sortOptions() {
        return List.of(
                new SortOption<>("Newest first",
                        Comparator.comparingLong(AuctionListing::createdAt).reversed()),
                new SortOption<>("Cheapest first",
                        Comparator.comparingLong(AuctionListing::price)),
                new SortOption<>("Cheapest per item",
                        Comparator.comparingLong(AuctionListing::unitPrice)),
                new SortOption<>("Ending soonest",
                        Comparator.comparingLong(AuctionListing::expiresAt)));
    }

    @Override
    protected Button renderEntry(AuctionListing listing) {
        ItemStack item = AuctionService.deserialise(listing);
        long now = System.currentTimeMillis();
        boolean mine = listing.isSeller(viewer.getUniqueId());
        boolean affordable = balance >= listing.price();

        List<String> lore = new ArrayList<>();
        lore.add("<gray>Price:</gray> " + (affordable ? "<green>" : "<red>")
                + economy.money().format(listing.price()) + (affordable ? "</green>" : "</red>"));
        if (listing.quantity() > 1) {
            lore.add("<gray>Each:</gray> <white>"
                    + economy.money().format(listing.unitPrice()) + "</white>");
        }
        lore.add("<gray>Seller:</gray> <white>"
                + Text.escape(String.valueOf(
                        org.bukkit.Bukkit.getOfflinePlayer(listing.seller()).getName()))
                + "</white>");
        lore.add("<gray>Ends in:</gray> <white>"
                + Durations.remaining(listing.timeRemaining(now)) + "</white>");
        lore.add("");
        if (mine) {
            lore.add("<aqua>This is your listing.</aqua>");
            lore.add("<gray>Manage it under <white>My listings</white>.</gray>");
        } else if (!affordable) {
            lore.add("<red>You cannot afford this.</red>");
        } else {
            lore.add("<gray>Click to buy.</gray>");
        }
        lore.add("<dark_gray>Listing #" + listing.id() + "</dark_gray>");

        // Render the real item where possible; fall back to a marker if the bytes
        // cannot be read, rather than hiding the listing entirely.
        ItemBuilder icon = item == null
                ? ItemBuilder.of(Material.BARRIER).name("<red>Unreadable listing</red>")
                : ItemBuilder.copyOf(item).name("<white>"
                        + (listing.displayName() == null ? listing.material()
                                : listing.displayName()) + "</white>");

        return Button.of(icon.lore(lore).clean().build(), click -> {
            if (mine) {
                notifications.info(click.player(), "auction.error.own-listing", Messages.of());
                return;
            }
            if (!affordable) {
                notifications.error(click.player(), "auction.error.insufficient-funds",
                        Messages.of("balance", economy.money().format(balance)));
                return;
            }
            confirmPurchase(click.player(), listing, item);
        });
    }

    private void confirmPurchase(Player player, AuctionListing listing, ItemStack item) {
        List<String> lines = new ArrayList<>();
        lines.add("<gray>Item:</gray> <white>"
                + (listing.displayName() == null ? listing.material() : listing.displayName())
                + "</white>");
        lines.add("<gray>Price:</gray> <yellow>"
                + economy.money().format(listing.price()) + "</yellow>");
        lines.add("<gray>Your balance after:</gray> <white>"
                + economy.money().format(Math.max(0, balance - listing.price())) + "</white>");
        if (item == null || player.getInventory().firstEmpty() == -1) {
            lines.add("");
            lines.add("<yellow>No inventory space - the item will wait</yellow>");
            lines.add("<yellow>in <white>/ah collect</white>.</yellow>");
        }

        ConfirmMenu.open(menus, player,
                Text.mm("<dark_gray>Confirm purchase</dark_gray>"),
                lines,
                "Buy",
                () -> submit(player, listing),
                this::open);
    }

    private void submit(Player player, AuctionListing listing) {
        if (busy) {
            return;
        }
        busy = true;
        auctions.buy(player, listing.id(), result -> {
            busy = false;
            report(player, result, listing);
            open();
        });
    }

    private void report(Player player, AuctionResult result, AuctionListing listing) {
        if (result.isSuccess()) {
            notifications.success(player, "auction.bought", Messages.of(
                    "item", listing.displayName() == null ? listing.material()
                            : listing.displayName(),
                    "amount", economy.money().format(result.amount())));
        } else {
            notifications.error(player, result.messageKey(), Messages.of(
                    "balance", economy.money().format(balance)));
        }
    }

    @Override
    protected void decorate() {
        int lastRow = rows() - 1;

        if (loaded.isEmpty()) {
            set(22, Button.display(ItemBuilder.of(Material.BARRIER)
                    .name("<red>Nothing listed right now</red>")
                    .lore("<gray>Hold an item and use</gray>",
                            "<white>/ah sell [price]</white>")
                    .clean().build()));
        }

        set(lastRow, 1, Button.display(ItemBuilder.of(Material.GOLD_NUGGET)
                .name("<gold>Your balance</gold>")
                .lore("<green>" + economy.money().format(balance) + "</green>")
                .clean().build()));

        set(lastRow, 7, Button.of(ItemBuilder.of(Material.ENDER_CHEST)
                .name("<aqua>My listings and collection</aqua>")
                .lore("<gray>Your active listings, plus items</gray>",
                        "<gray>bought or returned to you.</gray>")
                .clean().build(), click -> {
            AuctionSelfMenu menu = new AuctionSelfMenu(menus, chatInput, click.player(),
                    auctions, economy, notifications, scheduling);
            menu.withParent(this);
            menu.open();
        }));
    }

    /** Time formatting shared with the self-service screen. */
    static String remaining(AuctionListing listing) {
        return Durations.remaining(Duration.ofMillis(
                Math.max(0L, listing.expiresAt() - System.currentTimeMillis())));
    }
}
