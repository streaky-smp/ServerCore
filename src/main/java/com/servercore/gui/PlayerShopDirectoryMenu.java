package com.servercore.gui;

import com.servercore.core.Scheduling;
import com.servercore.economy.EconomyService;
import com.servercore.notify.NotificationService;
import com.servercore.playershop.PlayerShop;
import com.servercore.playershop.PlayerShopRepository;
import com.servercore.playershop.PlayerShopService;
import com.servercore.playershop.ShopOffer;
import com.servercore.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The global directory of player shops.
 *
 * <p>Searchable by shop name, owner name, and item -- typing {@code diamond}
 * finds every stall selling diamonds, which is the question the directory
 * actually exists to answer.
 */
public final class PlayerShopDirectoryMenu
        extends PaginatedMenu<PlayerShopRepository.DirectoryEntry> {

    private final PlayerShopService shops;
    private final EconomyService economy;
    private final NotificationService notifications;
    private final Scheduling scheduling;
    private final ChatInput chatInput;

    private List<PlayerShopRepository.DirectoryEntry> loaded = List.of();

    public PlayerShopDirectoryMenu(MenuManager menus,
                                   ChatInput chatInput,
                                   Player viewer,
                                   PlayerShopService shops,
                                   EconomyService economy,
                                   NotificationService notifications,
                                   Scheduling scheduling) {
        super(menus, chatInput, viewer, Text.mm("<dark_gray>Shop Directory</dark_gray>"), 6);
        this.chatInput = chatInput;
        this.shops = shops;
        this.economy = economy;
        this.notifications = notifications;
        this.scheduling = scheduling;
    }

    public static void openFor(MenuManager menus,
                               ChatInput chatInput,
                               Player player,
                               PlayerShopService shops,
                               EconomyService economy,
                               NotificationService notifications,
                               Scheduling scheduling) {
        new PlayerShopDirectoryMenu(menus, chatInput, player, shops, economy,
                notifications, scheduling).open();
    }

    @Override
    public void open() {
        scheduling.thenSync(
                scheduling.supplyAsync(shops::directory),
                entries -> {
                    loaded = entries;
                    super.open();
                },
                error -> viewer.sendMessage(Text.mm("<red>Could not load the shop directory.</red>")));
    }

    @Override
    protected List<PlayerShopRepository.DirectoryEntry> source() {
        return loaded;
    }

    @Override
    protected boolean supportsSearch() {
        return true;
    }

    /**
     * Matches shop name, owner name, or any item the shop trades.
     *
     * <p>Item matching is what makes the directory useful: players look for goods,
     * not for shop names they have never heard.
     */
    @Override
    protected boolean matches(PlayerShopRepository.DirectoryEntry entry, String lowercaseQuery) {
        PlayerShop shop = entry.shop();
        if (shop.name().toLowerCase(Locale.ROOT).contains(lowercaseQuery)) {
            return true;
        }
        if (entry.ownerName() != null
                && entry.ownerName().toLowerCase(Locale.ROOT).contains(lowercaseQuery)) {
            return true;
        }
        if (shop.description() != null
                && shop.description().toLowerCase(Locale.ROOT).contains(lowercaseQuery)) {
            return true;
        }
        for (ShopOffer offer : shop.offers()) {
            if (offer.material().name().toLowerCase(Locale.ROOT).replace('_', ' ')
                    .contains(lowercaseQuery)) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected List<SortOption<PlayerShopRepository.DirectoryEntry>> sortOptions() {
        return List.of(
                // Spawn-plot shops first: prominence in the directory is one of the
                // concrete advantages a premium plot buys.
                new SortOption<>("Featured first", Comparator
                        .comparing((PlayerShopRepository.DirectoryEntry e) ->
                                e.shop().onSpawnPlot() ? 0 : 1)
                        .thenComparing(e -> e.shop().name())),
                new SortOption<>("Name (A-Z)",
                        Comparator.comparing(e -> e.shop().name())),
                new SortOption<>("Most stocked", Comparator
                        .comparingInt((PlayerShopRepository.DirectoryEntry e) ->
                                e.shop().totalStock()).reversed()),
                new SortOption<>("Newest first", Comparator
                        .comparingLong((PlayerShopRepository.DirectoryEntry e) ->
                                e.shop().createdAt()).reversed()));
    }

    @Override
    protected Button renderEntry(PlayerShopRepository.DirectoryEntry entry) {
        PlayerShop shop = entry.shop();
        List<ShopOffer> buyable = shop.buyableOffers();

        List<String> lore = new ArrayList<>();
        lore.add("<gray>Owner:</gray> <white>"
                + Text.escape(entry.ownerName() == null ? "unknown" : entry.ownerName()) + "</white>");
        if (shop.description() != null && !shop.description().isBlank()) {
            lore.add("<dark_gray>" + shop.description() + "</dark_gray>");
        }
        lore.add("<gray>Location:</gray> <white>" + shop.worldName() + " "
                + shop.x() + ", " + shop.y() + ", " + shop.z() + "</white>");
        if (shop.onSpawnPlot()) {
            lore.add("<gold>Spawn district</gold>");
        }
        lore.add("");

        if (!shop.trading()) {
            lore.add("<red>" + shop.status().displayName() + "</red>");
        } else if (buyable.isEmpty()) {
            lore.add("<dark_gray>Nothing in stock</dark_gray>");
        } else {
            lore.add("<gray>Selling:</gray>");
            buyable.stream().limit(4).forEach(offer -> lore.add("<dark_gray>  "
                    + friendly(offer) + " <white>x" + offer.stock() + "</white> at <yellow>"
                    + economy.money().format(offer.buyPrice()) + "</yellow></dark_gray>"));
            if (buyable.size() > 4) {
                lore.add("<dark_gray>  ...and " + (buyable.size() - 4) + " more</dark_gray>");
            }
        }
        lore.add("");
        lore.add("<gray>Click to view.</gray>");

        Material icon = shop.onSpawnPlot() ? Material.GOLD_BLOCK
                : shop.trading() ? Material.CHEST : Material.BARRIER;

        return Button.of(ItemBuilder.of(icon)
                .name("<white>" + shop.name() + "</white>")
                .lore(lore)
                .glow(shop.onSpawnPlot())
                .clean().build(), click -> {
            PlayerShopViewMenu menu = new PlayerShopViewMenu(menus, chatInput, click.player(),
                    shops, economy, notifications, scheduling, shop.id());
            menu.withParent(this);
            menu.open();
        });
    }

    @Override
    protected void decorate() {
        if (loaded.isEmpty()) {
            set(22, Button.display(ItemBuilder.of(Material.BARRIER)
                    .name("<red>No player shops yet</red>")
                    .lore("<gray>Use <white>/pshop create</white> inside your</gray>",
                            "<gray>own claim to open one.</gray>")
                    .clean().build()));
        }
    }

    private static String friendly(ShopOffer offer) {
        String raw = offer.material().name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(raw.charAt(0)) + raw.substring(1);
    }
}
