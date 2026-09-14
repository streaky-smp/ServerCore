package com.streakysmp.gui;

import com.streakysmp.core.Scheduling;
import com.streakysmp.economy.EconomyService;
import com.streakysmp.notify.Messages;
import com.streakysmp.notify.NotificationService;
import com.streakysmp.shop.ShopItem;
import com.streakysmp.shop.ShopPricing;
import com.streakysmp.shop.ShopResult;
import com.streakysmp.shop.ShopService;
import com.streakysmp.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * Buy and sell one item, with a quantity picker.
 *
 * <h2>Bedrock layout</h2>
 * Quantity is adjusted by six discrete buttons rather than by scrolling or
 * dragging, and both actions are large, labelled and far apart. Every control is
 * a plain left click; nothing here needs a modifier key, a hover tooltip or a
 * drag, none of which survive the trip through Geyser to a touchscreen.
 */
public final class ShopItemMenu extends Menu {

    private static final int SLOT_ITEM = 4;
    private static final int SLOT_MINUS_64 = 19;
    private static final int SLOT_MINUS_8 = 20;
    private static final int SLOT_MINUS_1 = 21;
    private static final int SLOT_QUANTITY = 22;
    private static final int SLOT_PLUS_1 = 23;
    private static final int SLOT_PLUS_8 = 24;
    private static final int SLOT_PLUS_64 = 25;
    private static final int SLOT_BUY = 29;
    private static final int SLOT_MAX = 31;
    private static final int SLOT_SELL = 33;
    private static final int SLOT_BACK = 45;
    private static final int SLOT_BALANCE = 49;
    private static final int SLOT_CLOSE = 53;

    private final ShopService shop;
    private final EconomyService economy;
    private final NotificationService notifications;
    private final Scheduling scheduling;
    private final ShopItem item;

    private int quantity = 1;
    private long balance;
    /** Guards against a second click while a transaction is already in flight. */
    private boolean busy;

    public ShopItemMenu(MenuManager menus,
                        Player viewer,
                        ShopService shop,
                        EconomyService economy,
                        NotificationService notifications,
                        Scheduling scheduling,
                        ShopItem item,
                        long balance) {
        super(menus, viewer, Text.mm("<dark_gray>Shop</dark_gray>"), 6);
        this.shop = shop;
        this.economy = economy;
        this.notifications = notifications;
        this.scheduling = scheduling;
        this.item = item;
        this.balance = balance;
    }

    @Override
    protected void build() {
        fillEmpty(Material.GRAY_STAINED_GLASS_PANE);

        int held = shop.countSellable(viewer, item.material());
        int space = shop.spaceFor(viewer, item.material());

        set(SLOT_ITEM, Button.display(ItemBuilder.of(item.material())
                .name("<white>" + itemName() + "</white>")
                .lore(itemLore(held, space))
                .clean().build()));

        buildQuantityControls(held, space);
        buildActions(held, space);

        set(SLOT_BALANCE, Button.display(ItemBuilder.of(Material.GOLD_NUGGET)
                .name("<gold>Your balance</gold>")
                .lore("<green>" + economy.money().format(balance) + "</green>")
                .clean().build()));

        if (hasParent()) {
            set(SLOT_BACK, Button.of(ItemBuilder.of(Material.ARROW)
                    .name("<green>Back</green>")
                    .clean().build(), click -> parent().open()));
        }
        set(SLOT_CLOSE, Button.of(ItemBuilder.of(Material.BARRIER)
                .name("<red>Close</red>")
                .clean().build(), ClickContext::close));
    }

    private void buildQuantityControls(int held, int space) {
        adjustButton(SLOT_MINUS_64, -64);
        adjustButton(SLOT_MINUS_8, -8);
        adjustButton(SLOT_MINUS_1, -1);
        adjustButton(SLOT_PLUS_1, 1);
        adjustButton(SLOT_PLUS_8, 8);
        adjustButton(SLOT_PLUS_64, 64);

        set(SLOT_QUANTITY, Button.display(ItemBuilder.of(Material.PAPER, Math.min(64, quantity))
                .name("<yellow>Quantity: <white>" + quantity + "</white></yellow>")
                .lore("<gray>Use the arrows to change.</gray>")
                .clean().build()));

        // One button that picks the largest sensible amount, because stepping to
        // 64 with +1 clicks on a phone is nobody's idea of a good time.
        set(SLOT_MAX, Button.of(ItemBuilder.of(Material.HOPPER)
                .name("<aqua>Set to maximum</aqua>")
                .lore("<gray>Buy: as many as you can afford and fit.</gray>",
                        "<gray>Sell: everything you are carrying.</gray>")
                .clean().build(), click -> {
            quantity = Math.max(1, Math.max(maxBuyable(space), held));
            click.refresh();
        }));
    }

    private void adjustButton(int slot, int delta) {
        boolean increase = delta > 0;
        Material icon = increase ? Material.LIME_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE;
        set(slot, Button.of(ItemBuilder.of(icon, Math.min(64, Math.abs(delta)))
                .name((increase ? "<green>+" : "<red>-") + Math.abs(delta)
                        + (increase ? "</green>" : "</red>"))
                .clean().build(), click -> {
            quantity = Math.max(1, Math.min(quantity + delta, hardQuantityCap()));
            click.refresh();
        }));
    }

    private void buildActions(int held, int space) {
        if (item.buyable()) {
            long total = safeTotal(item.buyPrice());
            boolean affordable = total <= balance;
            boolean fits = quantity <= space;

            List<String> lore = new ArrayList<>();
            lore.add("<gray>Unit price:</gray> <white>"
                    + economy.money().format(item.buyPrice()) + "</white>");
            lore.add("<gray>Total:</gray> " + (affordable ? "<green>" : "<red>")
                    + economy.money().format(total) + (affordable ? "</green>" : "</red>"));
            if (!affordable) {
                lore.add("<red>You cannot afford this.</red>");
            }
            if (!fits) {
                lore.add("<red>Not enough inventory space.</red>");
            }
            lore.add("");
            lore.add(affordable && fits ? "<gray>Click to buy.</gray>" : "<dark_gray>Unavailable.</dark_gray>");

            set(SLOT_BUY, Button.of(ItemBuilder.of(
                            affordable && fits ? Material.LIME_CONCRETE : Material.GRAY_CONCRETE)
                    .name("<green><bold>BUY " + quantity + "</bold></green>")
                    .lore(lore)
                    .clean().build(), click -> {
                if (!affordable || !fits) {
                    notifications.error(click.player(),
                            affordable ? "shop.error.inventory-full" : "shop.error.insufficient-funds",
                            Messages.of("needed", economy.money().format(Math.max(0, total - balance)),
                                    "balance", economy.money().format(balance)));
                    return;
                }
                confirmIfLarge(click, total, "buy", () -> performBuy(click.player()));
            }));
        } else {
            set(SLOT_BUY, Button.display(ItemBuilder.of(Material.GRAY_CONCRETE)
                    .name("<dark_gray>Not for sale</dark_gray>")
                    .lore("<gray>The shop does not sell this item.</gray>")
                    .clean().build()));
        }

        if (item.sellable()) {
            int sellable = Math.min(quantity, held);
            long total = safeTotal(item.sellPrice(), sellable);
            boolean canSell = sellable > 0;

            List<String> lore = new ArrayList<>();
            lore.add("<gray>Unit price:</gray> <white>"
                    + economy.money().format(item.sellPrice()) + "</white>");
            lore.add("<gray>You are carrying:</gray> <white>" + held + "</white>");
            lore.add("<gray>You would sell:</gray> <white>" + sellable + "</white>");
            lore.add("<gray>You would receive:</gray> <green>"
                    + economy.money().format(total) + "</green>");
            lore.add("");
            lore.add(canSell
                    ? "<gray>Click to sell.</gray>"
                    : "<dark_gray>You have none to sell.</dark_gray>");
            lore.add("<dark_gray>Only undamaged, unenchanted items count.</dark_gray>");

            set(SLOT_SELL, Button.of(ItemBuilder.of(
                            canSell ? Material.ORANGE_CONCRETE : Material.GRAY_CONCRETE)
                    .name("<gold><bold>SELL " + sellable + "</bold></gold>")
                    .lore(lore)
                    .clean().build(), click -> {
                if (!canSell) {
                    notifications.error(click.player(), "shop.error.not-enough-items", Messages.of());
                    return;
                }
                confirmIfLarge(click, total, "sell", () -> performSell(click.player()));
            }));
        } else {
            set(SLOT_SELL, Button.display(ItemBuilder.of(Material.GRAY_CONCRETE)
                    .name("<dark_gray>Not bought</dark_gray>")
                    .lore("<gray>The shop does not buy this item.</gray>")
                    .clean().build()));
        }
    }

    /**
     * Interposes a confirmation for a large amount.
     *
     * <p>Uses the same threshold as {@code /pay}, so "large" means one thing
     * across the whole plugin rather than being re-decided per feature.
     */
    private void confirmIfLarge(ClickContext click, long total, String verb, Runnable action) {
        if (!economy.settings().needsConfirmation(total)) {
            action.run();
            return;
        }
        ConfirmMenu.open(menus, click.player(),
                Text.mm("<dark_gray>Confirm</dark_gray>"),
                List.of("<gray>Item:</gray> <white>" + itemName() + "</white>",
                        "<gray>Quantity:</gray> <white>" + quantity + "</white>",
                        "<gray>Total:</gray> <yellow>" + economy.money().format(total) + "</yellow>"),
                verb.equals("buy") ? "Buy" : "Sell",
                action,
                this::open);
    }

    private void performBuy(Player player) {
        if (busy) {
            return;
        }
        busy = true;
        shop.buy(player, item.material(), quantity, result -> {
            busy = false;
            report(player, result, "buy");
        });
    }

    private void performSell(Player player) {
        if (busy) {
            return;
        }
        busy = true;
        shop.sell(player, item.material(), quantity, result -> {
            busy = false;
            report(player, result, "sell");
        });
    }

    private void report(Player player, ShopResult result, String verb) {
        if (result.isSuccess()) {
            balance = result.newBalance();
            notifications.success(player, "shop." + verb + "-success", Messages.of(
                    "quantity", String.valueOf(result.quantity()),
                    "item", itemName(),
                    "amount", economy.money().format(result.amount()),
                    "balance", economy.money().format(result.newBalance())));
        } else {
            notifications.error(player, result.messageKey(), Messages.of(
                    "balance", economy.money().format(result.newBalance())));
        }
        // Re-render either way: balances and carried quantities have moved.
        if (isOpen()) {
            refresh();
        }
    }

    // ----------------------------------------------------------------- maths

    /** Never let the picker exceed what the catalogue permits. */
    private int hardQuantityCap() {
        return Math.max(item.maxBuyQuantity(), item.maxSellQuantity());
    }

    private int maxBuyable(int space) {
        if (!item.buyable()) {
            return 0;
        }
        int cap = Math.min(item.maxBuyQuantity(), space);
        return ShopPricing.maxAffordable(balance, item.buyPrice(), cap);
    }

    /**
     * Total for display only.
     *
     * <p>Saturates instead of throwing so an absurd picker value renders as an
     * unaffordable figure rather than breaking the menu. The figure the player is
     * actually charged is recomputed by the service from the catalogue.
     */
    private long safeTotal(long unitPrice, int qty) {
        try {
            return ShopPricing.total(unitPrice, qty);
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE;
        }
    }

    private long safeTotal(long unitPrice) {
        return safeTotal(unitPrice, quantity);
    }

    private String itemName() {
        String raw = item.material().name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(raw.charAt(0)) + raw.substring(1);
    }

    private List<String> itemLore(int held, int space) {
        List<String> lore = new ArrayList<>();
        if (item.buyable()) {
            lore.add("<gray>Buy price:</gray> <white>"
                    + economy.money().format(item.buyPrice()) + "</white> <dark_gray>each</dark_gray>");
        }
        if (item.sellable()) {
            lore.add("<gray>Sell price:</gray> <white>"
                    + economy.money().format(item.sellPrice()) + "</white> <dark_gray>each</dark_gray>");
        }
        lore.add("");
        lore.add("<gray>You are carrying:</gray> <white>" + held + "</white>");
        lore.add("<gray>Space available:</gray> <white>" + space + "</white>");
        return lore;
    }
}
