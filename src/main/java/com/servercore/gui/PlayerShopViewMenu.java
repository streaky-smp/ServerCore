package com.servercore.gui;

import com.servercore.core.Scheduling;
import com.servercore.economy.EconomyService;
import com.servercore.notify.NotificationService;
import com.servercore.playershop.PlayerShop;
import com.servercore.playershop.PlayerShopService;
import com.servercore.playershop.ShopOffer;
import com.servercore.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A customer's view of one player shop.
 *
 * <p>Shows the shop's name, owner, stock and prices, and the viewer's balance --
 * the layout the specification sketches. Offers the shop is buying are listed
 * alongside the ones it sells, since a stall that purchases cobblestone is as
 * useful to a visitor as one that sells diamonds.
 */
public final class PlayerShopViewMenu extends PaginatedMenu<ShopOffer> {

    private final PlayerShopService shops;
    private final EconomyService economy;
    private final NotificationService notifications;
    private final Scheduling scheduling;
    private final ChatInput chatInput;
    private final String shopId;

    private PlayerShop shop;
    private long balance;

    public PlayerShopViewMenu(MenuManager menus,
                              ChatInput chatInput,
                              Player viewer,
                              PlayerShopService shops,
                              EconomyService economy,
                              NotificationService notifications,
                              Scheduling scheduling,
                              String shopId) {
        super(menus, chatInput, viewer, Text.mm("<dark_gray>Player Shop</dark_gray>"), 6);
        this.chatInput = chatInput;
        this.shops = shops;
        this.economy = economy;
        this.notifications = notifications;
        this.scheduling = scheduling;
        this.shopId = shopId;
    }

    /** Loads the shop and the viewer's balance, then renders. */
    @Override
    public void open() {
        scheduling.thenSync(
                scheduling.supplyAsync(() -> new Object[]{
                        shops.byId(shopId).orElse(null),
                        economy.getBalance(viewer.getUniqueId())}),
                loaded -> {
                    shop = (PlayerShop) loaded[0];
                    balance = (Long) loaded[1];
                    if (shop == null) {
                        viewer.sendMessage(Text.mm("<red>That shop no longer exists.</red>"));
                        return;
                    }
                    super.open();
                },
                error -> viewer.sendMessage(Text.mm("<red>Could not open that shop.</red>")));
    }

    @Override
    protected List<ShopOffer> source() {
        if (shop == null) {
            return List.of();
        }
        // Everything the shop trades in either direction, so a visitor sees the
        // whole catalogue rather than only what happens to be in stock.
        List<ShopOffer> all = new ArrayList<>(shop.offers());
        all.removeIf(offer -> !offer.hasBuyPrice() && !offer.hasSellPrice());
        return List.copyOf(all);
    }

    @Override
    protected boolean supportsSearch() {
        return true;
    }

    @Override
    protected boolean matches(ShopOffer entry, String lowercaseQuery) {
        return entry.material().name().toLowerCase(Locale.ROOT).replace('_', ' ')
                .contains(lowercaseQuery);
    }

    @Override
    protected Button renderEntry(ShopOffer offer) {
        List<String> lore = new ArrayList<>();

        if (offer.hasBuyPrice()) {
            boolean affordable = balance >= offer.buyPrice();
            lore.add("<gray>You pay:</gray> " + (affordable ? "<white>" : "<red>")
                    + economy.money().format(offer.buyPrice())
                    + (affordable ? "</white>" : "</red>") + " <dark_gray>each</dark_gray>");
            lore.add("<gray>In stock:</gray> " + (offer.inStock()
                    ? "<white>" + offer.stock() + "</white>"
                    : "<red>none</red>"));
        }
        if (offer.hasSellPrice()) {
            lore.add("<gray>Shop pays:</gray> <green>"
                    + economy.money().format(offer.sellPrice()) + "</green> <dark_gray>each</dark_gray>");
        }
        lore.add("");
        if (!shop.trading()) {
            lore.add("<red>" + shop.status().displayName() + "</red>");
        } else {
            lore.add("<gray>Click to trade.</gray>");
        }

        return Button.of(ItemBuilder.of(offer.material())
                .name("<white>" + friendly(offer) + "</white>")
                .lore(lore)
                .clean().build(), click -> {
            if (!shop.trading()) {
                notifications.error(click.player(), "playershop.error.not-trading",
                        com.servercore.notify.Messages.of());
                return;
            }
            PlayerShopOfferMenu menu = new PlayerShopOfferMenu(menus, click.player(), shops,
                    economy, notifications, scheduling, shopId, offer.material());
            menu.withParent(this);
            menu.open();
        });
    }

    @Override
    protected void decorate() {
        if (shop == null) {
            return;
        }
        int lastRow = rows() - 1;

        set(lastRow, 1, Button.display(ItemBuilder.of(Material.OAK_SIGN)
                .name("<white><bold>" + shop.name() + "</bold></white>")
                .lore(headerLore())
                .clean().build()));

        set(lastRow, 7, Button.display(ItemBuilder.of(Material.GOLD_NUGGET)
                .name("<gold>Your balance</gold>")
                .lore("<green>" + economy.money().format(balance) + "</green>")
                .clean().build()));
    }

    private List<String> headerLore() {
        List<String> lore = new ArrayList<>();
        lore.add("<gray>Owner:</gray> <white>"
                + Text.escape(String.valueOf(
                        org.bukkit.Bukkit.getOfflinePlayer(shop.owner()).getName())) + "</white>");
        if (shop.description() != null && !shop.description().isBlank()) {
            lore.add("<dark_gray>" + shop.description() + "</dark_gray>");
        }
        lore.add("<gray>Location:</gray> <white>" + shop.worldName() + " "
                + shop.x() + ", " + shop.y() + ", " + shop.z() + "</white>");
        if (shop.onSpawnPlot()) {
            lore.add("<gold>Spawn district</gold>");
        }
        if (!shop.trading()) {
            lore.add("<red>" + shop.status().displayName() + "</red>");
        }
        return lore;
    }

    private static String friendly(ShopOffer offer) {
        String raw = offer.material().name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(raw.charAt(0)) + raw.substring(1);
    }
}
