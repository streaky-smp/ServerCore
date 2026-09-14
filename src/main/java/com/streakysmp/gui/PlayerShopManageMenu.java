package com.streakysmp.gui;

import com.streakysmp.core.Scheduling;
import com.streakysmp.economy.EconomyService;
import com.streakysmp.notify.Messages;
import com.streakysmp.notify.NotificationService;
import com.streakysmp.playershop.PlayerShop;
import com.streakysmp.playershop.PlayerShopResult;
import com.streakysmp.playershop.PlayerShopService;
import com.streakysmp.playershop.PlayerShopStatus;
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
 * The owner's view of their own shop: offers, stock and prices.
 *
 * <p>Stocking is done by holding the item and clicking its offer, rather than by
 * dragging items into the menu. The GUI framework refuses item movement outright,
 * and for good reason -- a drag that half-lands is how stock gets duplicated. A
 * click reads what the player is holding and moves a known quantity.
 */
public final class PlayerShopManageMenu extends PaginatedMenu<ShopOffer> {

    private final PlayerShopService shops;
    private final EconomyService economy;
    private final NotificationService notifications;
    private final Scheduling scheduling;
    private final ChatInput chatInput;
    private final String shopId;

    private PlayerShop shop;

    public PlayerShopManageMenu(MenuManager menus,
                                ChatInput chatInput,
                                Player viewer,
                                PlayerShopService shops,
                                EconomyService economy,
                                NotificationService notifications,
                                Scheduling scheduling,
                                String shopId) {
        super(menus, chatInput, viewer, Text.mm("<dark_gray>Manage Shop</dark_gray>"), 6);
        this.chatInput = chatInput;
        this.shops = shops;
        this.economy = economy;
        this.notifications = notifications;
        this.scheduling = scheduling;
        this.shopId = shopId;
    }

    @Override
    public void open() {
        scheduling.thenSync(
                scheduling.supplyAsync(() -> shops.byId(shopId).orElse(null)),
                loaded -> {
                    shop = loaded;
                    if (shop == null) {
                        viewer.sendMessage(Text.mm("<red>That shop no longer exists.</red>"));
                        return;
                    }
                    super.open();
                },
                error -> viewer.sendMessage(Text.mm("<red>Could not load that shop.</red>")));
    }

    @Override
    protected List<ShopOffer> source() {
        return shop == null ? List.of() : shop.offers();
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
        int held = Inventories.countPlain(viewer, offer.material());

        List<String> lore = new ArrayList<>();
        lore.add("<gray>Stock:</gray> <white>" + offer.stock() + "</white>");
        lore.add(offer.hasBuyPrice()
                ? "<gray>Selling at:</gray> <white>"
                        + economy.money().format(offer.buyPrice()) + "</white>"
                : "<dark_gray>Not selling</dark_gray>");
        lore.add(offer.hasSellPrice()
                ? "<gray>Buying at:</gray> <white>"
                        + economy.money().format(offer.sellPrice()) + "</white>"
                : "<dark_gray>Not buying</dark_gray>");
        lore.add("");
        lore.add("<gray>You are carrying:</gray> <white>" + held + "</white>");
        lore.add("");
        lore.add("<gray>Click to add stock, take stock back,</gray>");
        lore.add("<gray>or change the prices.</gray>");

        // A plain click into a dedicated screen, rather than hiding withdraw
        // behind a right-click and repricing behind a shift-click. Neither
        // modifier is reliably producible on a Bedrock touchscreen.
        return Button.of(ItemBuilder.of(offer.material())
                .name("<white>" + friendly(offer.material()) + "</white>")
                .lore(lore)
                .clean().build(), click -> {
            ShopOfferManageMenu menu = new ShopOfferManageMenu(menus, chatInput, click.player(),
                    shops, economy, notifications, scheduling, shopId, offer.material());
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
                .lore("<gray>Status:</gray> " + (shop.trading()
                                ? "<green>" : "<red>") + shop.status().displayName()
                                + (shop.trading() ? "</green>" : "</red>"),
                        "<gray>Offers:</gray> <white>" + shop.offers().size() + "</white>",
                        "<gray>Lifetime revenue:</gray> <green>"
                                + economy.money().format(shop.revenue()) + "</green>")
                .clean().build()));

        set(lastRow, 3, Button.of(ItemBuilder.of(Material.EMERALD)
                .name("<green>Add an item</green>")
                .lore("<gray>Hold the item and click.</gray>",
                        "<gray>You will be asked for prices.</gray>")
                .clean().build(), click -> addHeldItem(click)));

        if (shop.status().ownerCanReopen() || shop.status() == PlayerShopStatus.OPEN) {
            boolean open = shop.status() == PlayerShopStatus.OPEN;
            set(lastRow, 7, Button.of(ItemBuilder.of(open
                            ? Material.LIME_DYE : Material.GRAY_DYE)
                    .name(open ? "<green>Open for business</green>" : "<red>Closed</red>")
                    .lore("<gray>Click to " + (open ? "close" : "reopen") + ".</gray>")
                    .clean().build(), click -> {
                PlayerShopStatus next = open ? PlayerShopStatus.CLOSED : PlayerShopStatus.OPEN;
                scheduling.thenSync(
                        scheduling.supplyAsync(() -> shops.setStatus(
                                click.player().getUniqueId(), shopId, next, false)),
                        result -> {
                            report(click, result, "playershop.status-changed");
                            open();
                        },
                        error -> notifications.error(click.player(), "error.internal", Messages.of()));
            }));
        } else {
            set(lastRow, 7, Button.display(ItemBuilder.of(Material.BARRIER)
                    .name("<red>" + shop.status().displayName() + "</red>")
                    .lore("<gray>An administrator or unpaid rent closed this shop.</gray>",
                            "<gray>You cannot reopen it yourself.</gray>")
                    .clean().build()));
        }
    }

    /** Reads the held item and prompts for its prices. */
    private void addHeldItem(ClickContext click) {
        Material held = click.player().getInventory().getItemInMainHand().getType();
        if (held.isAir() || !held.isItem()) {
            notifications.error(click.player(), "playershop.error.hold-an-item", Messages.of());
            return;
        }
        if (shop.offer(held).isPresent()) {
            notifications.error(click.player(), "playershop.error.already-listed",
                    Messages.of("item", friendly(held)));
            return;
        }
        promptForPrices(click.player(), new ShopOffer(-1L, shopId, held,
                ShopOffer.UNAVAILABLE, 0L, 0, System.currentTimeMillis()));
    }

    /**
     * Asks for both prices in chat.
     *
     * <p>Two prompts rather than an anvil or a sign, because chat is the only text
     * entry that round-trips reliably through Geyser.
     */
    private void promptForPrices(Player player, ShopOffer offer) {
        closeLater();
        int fractionDigits = economy.money().fractionDigits();

        chatInput.prompt(player,
                "<aqua>What should customers pay for one <white>" + friendly(offer.material())
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
                            "<aqua>What will you pay customers for one <white>"
                                    + friendly(offer.material()) + "</white>? Type "
                                    + "<white>none</white> if you are not buying it.</aqua>",
                            sellInput -> {
                                Optional<Long> sellPrice = parsePrice(sellInput, fractionDigits);
                                if (sellPrice.isEmpty()) {
                                    notifications.error(player, "error.invalid-amount",
                                            Messages.of("input", sellInput));
                                    open();
                                    return;
                                }
                                applyPrices(player, offer.material(),
                                        buyPrice.get(), sellPrice.get());
                            },
                            this::open);
                },
                this::open);
    }

    /** Accepts a money amount, or {@code none} meaning "not traded that way". */
    private static Optional<Long> parsePrice(String input, int fractionDigits) {
        if (input == null) {
            return Optional.empty();
        }
        String trimmed = input.trim().toLowerCase(Locale.ROOT);
        if (trimmed.equals("none") || trimmed.equals("no") || trimmed.equals("-")) {
            return Optional.of(ShopOffer.UNAVAILABLE);
        }
        return Numbers.parseMoney(trimmed, fractionDigits);
    }

    private void applyPrices(Player player, Material material, long buyPrice, long sellPrice) {
        scheduling.thenSync(
                scheduling.supplyAsync(() -> shops.setOffer(
                        player.getUniqueId(), shopId, material, buyPrice, sellPrice)),
                result -> {
                    if (result.isSuccess()) {
                        notifications.success(player, "playershop.offer-updated",
                                Messages.of("item", friendly(material)));
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

    private void report(ClickContext click, PlayerShopResult result, String successKey) {
        if (result.isSuccess()) {
            notifications.success(click.player(), successKey, Messages.of(
                    "quantity", String.valueOf(result.quantity())));
        } else {
            notifications.error(click.player(), result.messageKey(), Messages.of());
        }
        open();
    }

    private static String friendly(Material material) {
        String raw = material.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(raw.charAt(0)) + raw.substring(1);
    }
}
