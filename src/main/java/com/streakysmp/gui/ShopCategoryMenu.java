package com.streakysmp.gui;

import com.streakysmp.core.Scheduling;
import com.streakysmp.economy.EconomyService;
import com.streakysmp.notify.NotificationService;
import com.streakysmp.shop.ShopCategory;
import com.streakysmp.shop.ShopItem;
import com.streakysmp.shop.ShopService;
import com.streakysmp.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The items in one shop category, paged, searchable and sortable.
 */
public final class ShopCategoryMenu extends PaginatedMenu<ShopItem> {

    private final ShopService shop;
    private final EconomyService economy;
    private final NotificationService notifications;
    private final Scheduling scheduling;
    private final ShopCategory category;

    private long balance;

    public ShopCategoryMenu(MenuManager menus,
                            ChatInput chatInput,
                            Player viewer,
                            ShopService shop,
                            EconomyService economy,
                            NotificationService notifications,
                            Scheduling scheduling,
                            ShopCategory category,
                            long balance) {
        super(menus, chatInput, viewer, Text.mm("<dark_gray>Shop</dark_gray>"), 6);
        this.shop = shop;
        this.economy = economy;
        this.notifications = notifications;
        this.scheduling = scheduling;
        this.category = category;
        this.balance = balance;
    }

    @Override
    protected List<ShopItem> source() {
        return category.items();
    }

    @Override
    protected boolean supportsSearch() {
        return true;
    }

    @Override
    protected boolean matches(ShopItem entry, String lowercaseQuery) {
        return displayName(entry).toLowerCase(Locale.ROOT).contains(lowercaseQuery)
                || entry.key().toLowerCase(Locale.ROOT).contains(lowercaseQuery);
    }

    @Override
    protected List<SortOption<ShopItem>> sortOptions() {
        return List.of(
                new SortOption<>("Name (A-Z)",
                        Comparator.comparing(ShopCategoryMenu::displayName)),
                new SortOption<>("Cheapest first",
                        Comparator.comparingLong(item -> item.buyable() ? item.buyPrice() : Long.MAX_VALUE)),
                new SortOption<>("Most valuable first",
                        Comparator.comparingLong((ShopItem item) ->
                                item.sellable() ? item.sellPrice() : Long.MIN_VALUE).reversed()));
    }

    @Override
    protected Button renderEntry(ShopItem item) {
        int held = shop.countSellable(viewer, item.material());

        List<String> lore = new ArrayList<>();
        if (item.buyable()) {
            boolean affordable = balance >= item.buyPrice();
            lore.add("<gray>Buy:</gray> " + (affordable ? "<white>" : "<red>")
                    + economy.money().format(item.buyPrice())
                    + (affordable ? "</white>" : "</red>") + " <dark_gray>each</dark_gray>");
        } else {
            lore.add("<dark_gray>Not sold here</dark_gray>");
        }
        if (item.sellable()) {
            lore.add("<gray>Sell:</gray> <white>" + economy.money().format(item.sellPrice())
                    + "</white> <dark_gray>each</dark_gray>");
        } else {
            lore.add("<dark_gray>Not bought here</dark_gray>");
        }
        lore.add("");
        if (held > 0) {
            lore.add("<gray>You are carrying:</gray> <white>" + held + "</white>");
        }
        lore.add("<gray>Click to choose a quantity.</gray>");

        return Button.of(ItemBuilder.of(item.material())
                .name("<white>" + displayName(item) + "</white>")
                .lore(lore)
                .clean().build(), click -> {
            ShopItemMenu menu = new ShopItemMenu(menus, click.player(), shop, economy,
                    notifications, scheduling, item, balance);
            menu.withParent(this);
            menu.open();
        });
    }

    @Override
    protected void decorate() {
        set(rows() - 1, 1, Button.display(ItemBuilder.of(Material.GOLD_NUGGET)
                .name("<gold>Your balance</gold>")
                .lore("<green>" + economy.money().format(balance) + "</green>")
                .clean().build()));
    }

    /** Refreshes the balance on every open, including a return from an item screen. */
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

    /** Title-cases a material name, e.g. {@code IRON_INGOT} to {@code Iron ingot}. */
    static String displayName(ShopItem item) {
        String raw = item.material().name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(raw.charAt(0)) + raw.substring(1);
    }
}
