package com.streakysmp.gui;

import com.streakysmp.core.Scheduling;
import com.streakysmp.economy.EconomyService;
import com.streakysmp.notify.Messages;
import com.streakysmp.notify.NotificationService;
import com.streakysmp.playershop.PlayerShopResult;
import com.streakysmp.playershop.PlayerShopService;
import com.streakysmp.playershop.ShopOffer;
import com.streakysmp.util.Inventories;
import com.streakysmp.util.Numbers;
import com.streakysmp.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Everything a shop owner can do to one of their offers.
 *
 * <p>Exists because the previous design hid "withdraw stock" behind a right-click
 * and "set prices" behind a shift-click. Neither is reliably producible on a
 * Bedrock touchscreen through Geyser, so a Bedrock owner could stock a shop but
 * never reprice it or take anything back -- a feature that silently did not exist
 * for half the player base.
 *
 * <p>Every action here is a plain left click on a labelled button.
 */
public final class ShopOfferManageMenu extends Menu {

    private final PlayerShopService shops;
    private final EconomyService economy;
    private final NotificationService notifications;
    private final Scheduling scheduling;
    private final ChatInput chatInput;
    private final String shopId;
    private final Material material;

    private ShopOffer offer;
    private int quantity = 64;

    public ShopOfferManageMenu(MenuManager menus,
                               ChatInput chatInput,
                               Player viewer,
                               PlayerShopService shops,
                               EconomyService economy,
                               NotificationService notifications,
                               Scheduling scheduling,
                               String shopId,
                               Material material) {
        super(menus, viewer, Text.mm("<dark_gray>Manage Item</dark_gray>"), 5);
        this.chatInput = chatInput;
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
                scheduling.supplyAsync(() -> shops.byId(shopId)
                        .flatMap(shop -> shop.offer(material))
                        .orElse(null)),
                loaded -> {
                    offer = loaded;
                    if (offer == null) {
                        viewer.sendMessage(Text.mm("<red>That item is no longer listed.</red>"));
                        if (hasParent()) {
                            parent().open();
                        }
                        return;
                    }
                    super.open();
                },
                error -> viewer.sendMessage(Text.mm("<red>Could not load that item.</red>")));
    }

    @Override
    protected void build() {
        fillEmpty(Material.GRAY_STAINED_GLASS_PANE);

        int held = Inventories.countPlain(viewer, material);
        int space = Inventories.spaceFor(viewer, material);

        set(0, 4, Button.display(ItemBuilder.of(material)
                .name("<white><bold>" + friendly() + "</bold></white>")
                .lore(summary(held))
                .clean().build()));

        // Quantity picker, shared with every other buy/sell screen.
        int cap = Math.max(1, Math.max(held, offer.stock()));
        QuantityControls.render(this, 9 + 1, quantity, cap, next -> quantity = next);

        set(2, 1, Button.of(ItemBuilder.of(held > 0 ? Material.LIME_CONCRETE : Material.GRAY_CONCRETE)
                .name("<green><bold>Add to stock</bold></green>")
                .lore("<gray>Moves</gray> <white>" + Math.min(quantity, held) + "</white> "
                                + "<gray>from your inventory into the shop.</gray>",
                        "<gray>You are carrying:</gray> <white>" + held + "</white>",
                        held > 0 ? "<gray>Click to add.</gray>"
                                : "<red>You are not carrying any.</red>")
                .clean().build(), click -> {
            if (held < 1) {
                notifications.error(click.player(), "playershop.error.not-enough-items",
                        Messages.of());
                return;
            }
            shops.stock(click.player(), shopId, material, Math.min(quantity, held),
                    result -> report(click.player(), result, "playershop.stock-added"));
        }));

        set(2, 3, Button.of(ItemBuilder.of(offer.stock() > 0 && space > 0
                        ? Material.ORANGE_CONCRETE : Material.GRAY_CONCRETE)
                .name("<gold><bold>Take back</bold></gold>")
                .lore("<gray>Moves</gray> <white>"
                                + Math.min(Math.min(quantity, offer.stock()), space) + "</white> "
                                + "<gray>out of the shop into your inventory.</gray>",
                        "<gray>In stock:</gray> <white>" + offer.stock() + "</white>",
                        "<gray>Your free space:</gray> <white>" + space + "</white>",
                        offer.stock() > 0 && space > 0
                                ? "<gray>Click to take.</gray>"
                                : offer.stock() <= 0 ? "<red>Nothing in stock.</red>"
                                        : "<red>No room in your inventory.</red>")
                .clean().build(), click -> {
            if (offer.stock() < 1) {
                notifications.error(click.player(), "playershop.error.out-of-stock", Messages.of());
                return;
            }
            if (space < 1) {
                notifications.error(click.player(), "playershop.error.inventory-full",
                        Messages.of());
                return;
            }
            shops.withdrawStock(click.player(), shopId, material,
                    Math.min(Math.min(quantity, offer.stock()), space),
                    result -> report(click.player(), result, "playershop.stock-withdrawn"));
        }));

        set(2, 5, Button.of(ItemBuilder.of(Material.NAME_TAG)
                .name("<aqua><bold>Change prices</bold></aqua>")
                .lore(priceLore())
                .clean().build(), click -> promptForPrices(click.player())));

        set(2, 7, Button.of(ItemBuilder.of(offer.stock() > 0
                        ? Material.GRAY_CONCRETE : Material.RED_CONCRETE)
                .name("<red><bold>Stop trading this</bold></red>")
                .lore(offer.stock() > 0
                                ? "<red>Take your stock back first.</red>"
                                : "<gray>Removes this item from the shop.</gray>",
                        "<dark_gray>The shop itself stays open.</dark_gray>")
                .clean().build(), click -> {
            if (offer.stock() > 0) {
                notifications.error(click.player(), "playershop.error.stock-remaining",
                        Messages.of());
                return;
            }
            removeOffer(click.player());
        }));

        if (hasParent()) {
            set(4, 0, Button.of(ItemBuilder.of(Material.ARROW)
                    .name("<green>Back</green>").clean().build(), click -> parent().open()));
        }
        set(4, 8, Button.of(ItemBuilder.of(Material.BARRIER)
                .name("<red>Close</red>").clean().build(), ClickContext::close));
    }

    private List<String> summary(int held) {
        List<String> lore = new ArrayList<>();
        lore.add("<gray>In stock:</gray> <white>" + offer.stock() + "</white>");
        lore.add(offer.hasBuyPrice()
                ? "<gray>Customers pay:</gray> <white>"
                        + economy.money().format(offer.buyPrice()) + "</white>"
                : "<dark_gray>Not selling this</dark_gray>");
        lore.add(offer.hasSellPrice()
                ? "<gray>You pay:</gray> <white>"
                        + economy.money().format(offer.sellPrice()) + "</white>"
                : "<dark_gray>Not buying this</dark_gray>");
        lore.add("");
        lore.add("<gray>You are carrying:</gray> <white>" + held + "</white>");
        return lore;
    }

    private List<String> priceLore() {
        List<String> lore = new ArrayList<>();
        lore.add(offer.hasBuyPrice()
                ? "<gray>Selling at:</gray> <white>"
                        + economy.money().format(offer.buyPrice()) + "</white>"
                : "<dark_gray>Not selling</dark_gray>");
        lore.add(offer.hasSellPrice()
                ? "<gray>Buying at:</gray> <white>"
                        + economy.money().format(offer.sellPrice()) + "</white>"
                : "<dark_gray>Not buying</dark_gray>");
        lore.add("");
        lore.add("<gray>Click and type the new prices in chat.</gray>");
        lore.add("<dark_gray>Chat is used because it round-trips</dark_gray>");
        lore.add("<dark_gray>reliably on Bedrock.</dark_gray>");
        return lore;
    }

    /** Asks for both prices in chat, the only text entry that works everywhere. */
    private void promptForPrices(Player player) {
        closeLater();
        int fractionDigits = economy.money().fractionDigits();

        chatInput.prompt(player,
                "<aqua>What should customers pay for one <white>" + friendly()
                        + "</white>? Type <white>none</white> if you are not selling it, "
                        + "or <white>cancel</white>.</aqua>",
                buyInput -> {
                    Optional<Long> buyPrice = parsePrice(buyInput, fractionDigits);
                    if (buyPrice.isEmpty()) {
                        notifications.error(player, "error.invalid-amount",
                                Messages.of("input", buyInput));
                        open();
                        return;
                    }
                    chatInput.prompt(player,
                            "<aqua>What will you pay customers for one <white>" + friendly()
                                    + "</white>? Type <white>none</white> if you are not "
                                    + "buying it.</aqua>",
                            sellInput -> {
                                Optional<Long> sellPrice = parsePrice(sellInput, fractionDigits);
                                if (sellPrice.isEmpty()) {
                                    notifications.error(player, "error.invalid-amount",
                                            Messages.of("input", sellInput));
                                    open();
                                    return;
                                }
                                applyPrices(player, buyPrice.get(), sellPrice.get());
                            },
                            this::open);
                },
                this::open);
    }

    /** Accepts a money amount, or {@code none} meaning "not traded that way". */
    static Optional<Long> parsePrice(String input, int fractionDigits) {
        if (input == null) {
            return Optional.empty();
        }
        String trimmed = input.trim().toLowerCase(Locale.ROOT);
        if (trimmed.equals("none") || trimmed.equals("no") || trimmed.equals("-")) {
            return Optional.of(ShopOffer.UNAVAILABLE);
        }
        return Numbers.parseMoney(trimmed, fractionDigits);
    }

    private void applyPrices(Player player, long buyPrice, long sellPrice) {
        scheduling.thenSync(
                scheduling.supplyAsync(() -> shops.setOffer(
                        player.getUniqueId(), shopId, material, buyPrice, sellPrice)),
                result -> {
                    if (result.isSuccess()) {
                        notifications.success(player, "playershop.offer-updated",
                                Messages.of("item", friendly()));
                    } else {
                        notifications.error(player, result.messageKey(), Messages.of());
                    }
                    open();
                },
                error -> {
                    notifications.error(player, "error.internal", Messages.of());
                    open();
                });
    }

    private void removeOffer(Player player) {
        scheduling.thenSync(
                scheduling.supplyAsync(() ->
                        shops.removeOffer(player.getUniqueId(), shopId, material)),
                result -> {
                    if (result.isSuccess()) {
                        notifications.success(player, "playershop.offer-removed",
                                Messages.of("item", friendly()));
                        if (hasParent()) {
                            parent().open();
                        }
                    } else {
                        notifications.error(player, result.messageKey(), Messages.of());
                        open();
                    }
                },
                error -> notifications.error(player, "error.internal", Messages.of()));
    }

    private void report(Player player, PlayerShopResult result, String successKey) {
        if (result.isSuccess()) {
            notifications.success(player, successKey,
                    Messages.of("quantity", String.valueOf(result.quantity())));
        } else {
            notifications.error(player, result.messageKey(), Messages.of());
        }
        open();
    }

    private String friendly() {
        String raw = material.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(raw.charAt(0)) + raw.substring(1);
    }

}
