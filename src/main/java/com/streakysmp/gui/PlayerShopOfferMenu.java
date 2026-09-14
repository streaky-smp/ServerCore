package com.streakysmp.gui;

import com.streakysmp.core.Scheduling;
import com.streakysmp.economy.EconomyService;
import com.streakysmp.notify.Messages;
import com.streakysmp.notify.NotificationService;
import com.streakysmp.playershop.PlayerShop;
import com.streakysmp.playershop.PlayerShopResult;
import com.streakysmp.playershop.PlayerShopService;
import com.streakysmp.playershop.ShopOffer;
import com.streakysmp.util.Inventories;
import com.streakysmp.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Buying and selling one item at a player shop.
 *
 * <p>Uses the shared {@link QuantityControls}, so the picker behaves exactly as
 * it does in the server shop.
 */
public final class PlayerShopOfferMenu extends Menu {

    private static final int SLOT_ITEM = 4;
    private static final int QUANTITY_ROW_BASE = 19;
    private static final int SLOT_BUY = 29;
    private static final int SLOT_MAX = 31;
    private static final int SLOT_SELL = 33;
    private static final int SLOT_BACK = 45;
    private static final int SLOT_BALANCE = 49;
    private static final int SLOT_CLOSE = 53;

    private final PlayerShopService shops;
    private final EconomyService economy;
    private final NotificationService notifications;
    private final Scheduling scheduling;
    private final String shopId;
    private final Material material;

    private int quantity = 1;
    private long balance;
    private PlayerShop shop;
    private boolean busy;

    public PlayerShopOfferMenu(MenuManager menus,
                               Player viewer,
                               PlayerShopService shops,
                               EconomyService economy,
                               NotificationService notifications,
                               Scheduling scheduling,
                               String shopId,
                               Material material) {
        super(menus, viewer, Text.mm("<dark_gray>Player Shop</dark_gray>"), 6);
        this.shops = shops;
        this.economy = economy;
        this.notifications = notifications;
        this.scheduling = scheduling;
        this.shopId = shopId;
        this.material = material;
    }

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
    protected void build() {
        fillEmpty(Material.GRAY_STAINED_GLASS_PANE);

        ShopOffer offer = shop == null ? null : shop.offer(material).orElse(null);
        if (offer == null) {
            set(SLOT_ITEM, Button.display(ItemBuilder.of(Material.BARRIER)
                    .name("<red>This shop no longer trades that.</red>").clean().build()));
            navigation();
            return;
        }

        int held = Inventories.countPlain(viewer, material);
        int space = Inventories.spaceFor(viewer, material);

        set(SLOT_ITEM, Button.display(ItemBuilder.of(material)
                .name("<white>" + friendly() + "</white>")
                .lore(itemLore(offer, held, space))
                .clean().build()));

        int cap = Math.max(1, Math.max(maxBuyable(offer, space), Math.min(held, 2_304)));
        QuantityControls.render(this, QUANTITY_ROW_BASE, quantity, cap, next -> quantity = next);

        set(SLOT_MAX, Button.of(ItemBuilder.of(Material.HOPPER)
                .name("<aqua>Set to maximum</aqua>")
                .lore("<gray>Buy: as many as you can afford and fit.</gray>",
                        "<gray>Sell: everything you are carrying.</gray>")
                .clean().build(), click -> {
            quantity = Math.max(1, Math.max(maxBuyable(offer, space), held));
            click.refresh();
        }));

        buildBuy(offer, space);
        buildSell(offer, held);

        set(SLOT_BALANCE, Button.display(ItemBuilder.of(Material.GOLD_NUGGET)
                .name("<gold>Your balance</gold>")
                .lore("<green>" + economy.money().format(balance) + "</green>")
                .clean().build()));
        navigation();
    }

    private void buildBuy(ShopOffer offer, int space) {
        if (!offer.hasBuyPrice()) {
            set(SLOT_BUY, Button.display(ItemBuilder.of(Material.GRAY_CONCRETE)
                    .name("<dark_gray>Not for sale</dark_gray>")
                    .lore("<gray>This shop does not sell that.</gray>")
                    .clean().build()));
            return;
        }

        int available = Math.min(quantity, offer.stock());
        long total = safeTotal(offer.buyPrice(), available);
        boolean affordable = total <= balance;
        boolean fits = available <= space;
        boolean possible = available > 0 && affordable && fits;

        List<String> lore = new ArrayList<>();
        lore.add("<gray>Unit price:</gray> <white>"
                + economy.money().format(offer.buyPrice()) + "</white>");
        lore.add("<gray>In stock:</gray> <white>" + offer.stock() + "</white>");
        lore.add("<gray>You would buy:</gray> <white>" + available + "</white>");
        lore.add("<gray>Total:</gray> " + (affordable ? "<green>" : "<red>")
                + economy.money().format(total) + (affordable ? "</green>" : "</red>"));
        if (available <= 0) {
            lore.add("<red>Out of stock.</red>");
        } else if (!affordable) {
            lore.add("<red>You cannot afford this.</red>");
        } else if (!fits) {
            lore.add("<red>Not enough inventory space.</red>");
        }

        set(SLOT_BUY, Button.of(ItemBuilder.of(possible ? Material.LIME_CONCRETE : Material.GRAY_CONCRETE)
                .name("<green><bold>BUY " + available + "</bold></green>")
                .lore(lore)
                .clean().build(), click -> {
            if (!possible) {
                notifications.error(click.player(),
                        available <= 0 ? "playershop.error.out-of-stock"
                                : affordable ? "playershop.error.inventory-full"
                                : "playershop.error.insufficient-funds",
                        Messages.of("balance", economy.money().format(balance)));
                return;
            }
            act(click, () -> shops.buy(click.player(), shopId, material, available,
                    result -> report(click, result, "playershop.bought")));
        }));
    }

    private void buildSell(ShopOffer offer, int held) {
        if (!offer.hasSellPrice()) {
            set(SLOT_SELL, Button.display(ItemBuilder.of(Material.GRAY_CONCRETE)
                    .name("<dark_gray>Not bought</dark_gray>")
                    .lore("<gray>This shop does not buy that.</gray>")
                    .clean().build()));
            return;
        }

        int sellable = Math.min(quantity, held);
        long total = safeTotal(offer.sellPrice(), sellable);

        List<String> lore = new ArrayList<>();
        lore.add("<gray>Shop pays:</gray> <white>"
                + economy.money().format(offer.sellPrice()) + "</white> <dark_gray>each</dark_gray>");
        lore.add("<gray>You are carrying:</gray> <white>" + held + "</white>");
        lore.add("<gray>You would sell:</gray> <white>" + sellable + "</white>");
        lore.add("<gray>You would receive:</gray> <green>"
                + economy.money().format(total) + "</green>");
        lore.add("");
        lore.add("<dark_gray>The owner must have the money to pay.</dark_gray>");
        lore.add("<dark_gray>Only undamaged, unenchanted items count.</dark_gray>");

        set(SLOT_SELL, Button.of(ItemBuilder.of(sellable > 0
                        ? Material.ORANGE_CONCRETE : Material.GRAY_CONCRETE)
                .name("<gold><bold>SELL " + sellable + "</bold></gold>")
                .lore(lore)
                .clean().build(), click -> {
            if (sellable < 1) {
                notifications.error(click.player(), "playershop.error.not-enough-items",
                        Messages.of());
                return;
            }
            act(click, () -> shops.sell(click.player(), shopId, material, sellable,
                    result -> report(click, result, "playershop.sold")));
        }));
    }

    /** Guards against a second click while a trade is already in flight. */
    private void act(ClickContext click, Runnable action) {
        if (busy) {
            return;
        }
        busy = true;
        action.run();
    }

    private void report(ClickContext click, PlayerShopResult result, String successKey) {
        busy = false;
        if (result.isSuccess()) {
            notifications.success(click.player(), successKey, Messages.of(
                    "quantity", String.valueOf(result.quantity()),
                    "item", friendly(),
                    "amount", economy.money().format(result.amount())));
        } else {
            notifications.error(click.player(), result.messageKey(), Messages.of(
                    "balance", economy.money().format(balance)));
        }
        // Reopen to pick up new balances and stock.
        open();
    }

    private int maxBuyable(ShopOffer offer, int space) {
        if (!offer.hasBuyPrice() || offer.buyPrice() <= 0) {
            return Math.min(offer.stock(), space);
        }
        long affordable = balance / offer.buyPrice();
        return (int) Math.min(Math.min(affordable, offer.stock()), space);
    }

    private long safeTotal(long unitPrice, int qty) {
        try {
            return Math.multiplyExact(unitPrice, Math.max(0, qty));
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE;
        }
    }

    private List<String> itemLore(ShopOffer offer, int held, int space) {
        List<String> lore = new ArrayList<>();
        lore.add("<gray>Shop:</gray> <white>" + shop.name() + "</white>");
        if (offer.hasBuyPrice()) {
            lore.add("<gray>Sells at:</gray> <white>"
                    + economy.money().format(offer.buyPrice()) + "</white>");
        }
        if (offer.hasSellPrice()) {
            lore.add("<gray>Buys at:</gray> <white>"
                    + economy.money().format(offer.sellPrice()) + "</white>");
        }
        lore.add("");
        lore.add("<gray>You are carrying:</gray> <white>" + held + "</white>");
        lore.add("<gray>Space available:</gray> <white>" + space + "</white>");
        return lore;
    }

    private void navigation() {
        if (hasParent()) {
            set(SLOT_BACK, Button.of(ItemBuilder.of(Material.ARROW)
                    .name("<green>Back</green>").clean().build(), click -> parent().open()));
        }
        set(SLOT_CLOSE, Button.of(ItemBuilder.of(Material.BARRIER)
                .name("<red>Close</red>").clean().build(), ClickContext::close));
    }

    private String friendly() {
        String raw = material.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(raw.charAt(0)) + raw.substring(1);
    }
}
