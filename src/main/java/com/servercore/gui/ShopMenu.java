package com.servercore.gui;

import com.servercore.core.Scheduling;
import com.servercore.economy.EconomyService;
import com.servercore.notify.NotificationService;
import com.servercore.shop.ShopCategory;
import com.servercore.shop.ShopService;
import com.servercore.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * The shop's front page: one button per category.
 *
 * <p>Categories a player lacks permission for are omitted entirely rather than
 * shown greyed out. A visible button that cannot be pressed is worse than no
 * button, and the framework re-checks the permission at click time anyway.
 */
public final class ShopMenu extends Menu {

    /** Interior slots of a 6-row menu, avoiding the border and the nav row. */
    private static final int[] CATEGORY_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34
    };

    private final ShopService shop;
    private final EconomyService economy;
    private final NotificationService notifications;
    private final ChatInput chatInput;
    private final Scheduling scheduling;

    private long balance;

    public ShopMenu(MenuManager menus,
                    Player viewer,
                    ShopService shop,
                    EconomyService economy,
                    NotificationService notifications,
                    ChatInput chatInput,
                    Scheduling scheduling,
                    long balance) {
        super(menus, viewer, Text.mm("<dark_gray>Server Shop</dark_gray>"), 6);
        this.shop = shop;
        this.economy = economy;
        this.notifications = notifications;
        this.chatInput = chatInput;
        this.scheduling = scheduling;
        this.balance = balance;
    }

    /**
     * Entry point for {@code /shop}.
     *
     * <p>The balance is fetched by {@link #open()}, which runs on every open
     * including a return from a category, so there is no need to fetch it here as
     * well.
     */
    public static void openFor(MenuManager menus,
                               Player player,
                               ShopService shop,
                               EconomyService economy,
                               NotificationService notifications,
                               ChatInput chatInput,
                               Scheduling scheduling) {
        new ShopMenu(menus, player, shop, economy, notifications, chatInput, scheduling, 0L).open();
    }

    @Override
    protected void build() {
        fillBorder(Material.GRAY_STAINED_GLASS_PANE);

        List<ShopCategory> visible = new ArrayList<>();
        for (ShopCategory category : shop.catalogue().categories()) {
            if (viewer.hasPermission(category.permission())) {
                visible.add(category);
            }
        }

        if (visible.isEmpty()) {
            set(22, Button.display(ItemBuilder.of(Material.BARRIER)
                    .name("<red>The shop is empty</red>")
                    .lore("<gray>No categories are configured, or none are</gray>",
                            "<gray>available to you.</gray>",
                            "<dark_gray>Operators: see shop.yml</dark_gray>")
                    .clean().build()));
        }

        for (int i = 0; i < visible.size() && i < CATEGORY_SLOTS.length; i++) {
            ShopCategory category = visible.get(i);
            set(CATEGORY_SLOTS[i], Button.of(ItemBuilder.of(category.icon())
                    .name(category.displayName())
                    .lore("<gray>" + category.items().size() + " item"
                                    + (category.items().size() == 1 ? "" : "s") + "</gray>",
                            "<gray>Click to browse.</gray>")
                    .clean().build(), click -> {
                ShopCategoryMenu menu = new ShopCategoryMenu(menus, chatInput, click.player(),
                        shop, economy, notifications, scheduling, category, balance);
                menu.withParent(this);
                menu.open();
            }));
        }

        set(49, Button.display(ItemBuilder.of(Material.GOLD_NUGGET)
                .name("<gold>Your balance</gold>")
                .lore("<green>" + economy.money().format(balance) + "</green>")
                .clean().build()));

        set(53, Button.of(ItemBuilder.of(Material.BARRIER)
                .name("<red>Close</red>")
                .clean().build(), ClickContext::close));
    }

    /**
     * Refreshes the cached balance when the menu is reopened after a purchase.
     *
     * <p>Reads off-thread then re-renders, so returning from a category always
     * shows a current figure rather than the one captured when the shop opened.
     */
    @Override
    public void open() {
        scheduling.thenSync(
                scheduling.supplyAsync(() -> economy.getBalance(viewer.getUniqueId())),
                current -> {
                    balance = current;
                    super.open();
                },
                error -> super.open());
    }
}
