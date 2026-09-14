package com.streakysmp.gui;

import com.streakysmp.core.Scheduling;
import com.streakysmp.economy.EconomyService;
import com.streakysmp.notify.NotificationService;
import com.streakysmp.plot.PlotService;
import com.streakysmp.plot.PlotStatus;
import com.streakysmp.plot.SpawnPlot;
import com.streakysmp.util.Durations;
import com.streakysmp.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The spawn commercial district: every plot and its status.
 */
public final class PlotDistrictMenu extends PaginatedMenu<SpawnPlot> {

    private final PlotService plots;
    private final EconomyService economy;
    private final NotificationService notifications;
    private final Scheduling scheduling;
    private final ChatInput chatInput;

    private List<SpawnPlot> loaded = List.of();
    private long balance;

    public PlotDistrictMenu(MenuManager menus,
                            ChatInput chatInput,
                            Player viewer,
                            PlotService plots,
                            EconomyService economy,
                            NotificationService notifications,
                            Scheduling scheduling) {
        super(menus, chatInput, viewer, Text.mm("<dark_gray>Spawn Shop District</dark_gray>"), 6);
        this.chatInput = chatInput;
        this.plots = plots;
        this.economy = economy;
        this.notifications = notifications;
        this.scheduling = scheduling;
    }

    public static void openFor(MenuManager menus,
                               ChatInput chatInput,
                               Player player,
                               PlotService plots,
                               EconomyService economy,
                               NotificationService notifications,
                               Scheduling scheduling) {
        new PlotDistrictMenu(menus, chatInput, player, plots, economy,
                notifications, scheduling).open();
    }

    @Override
    public void open() {
        scheduling.thenSync(
                scheduling.supplyAsync(() -> new Object[]{
                        plots.all(), economy.getBalance(viewer.getUniqueId())}),
                data -> {
                    @SuppressWarnings("unchecked")
                    List<SpawnPlot> all = (List<SpawnPlot>) data[0];
                    loaded = all;
                    balance = (Long) data[1];
                    super.open();
                },
                error -> viewer.sendMessage(Text.mm("<red>Could not load the district.</red>")));
    }

    @Override
    protected List<SpawnPlot> source() {
        return loaded;
    }

    @Override
    protected boolean supportsSearch() {
        return true;
    }

    @Override
    protected boolean matches(SpawnPlot entry, String lowercaseQuery) {
        return entry.id().toLowerCase(Locale.ROOT).contains(lowercaseQuery)
                || entry.status().displayName().toLowerCase(Locale.ROOT).contains(lowercaseQuery);
    }

    @Override
    protected List<SortOption<SpawnPlot>> sortOptions() {
        return List.of(
                // Available first: a player opening the district is usually shopping.
                new SortOption<>("Available first", Comparator
                        .comparing((SpawnPlot p) -> p.status().purchasable() ? 0 : 1)
                        .thenComparing(SpawnPlot::id)),
                new SortOption<>("Cheapest first",
                        Comparator.comparingLong(SpawnPlot::purchasePrice)),
                new SortOption<>("Largest first",
                        Comparator.comparingLong(SpawnPlot::area).reversed()),
                new SortOption<>("Plot id", Comparator.comparing(SpawnPlot::id)));
    }

    @Override
    protected Button renderEntry(SpawnPlot plot) {
        boolean mine = plot.isOwner(viewer.getUniqueId());
        boolean affordable = balance >= plot.purchasePrice();
        long now = System.currentTimeMillis();

        List<String> lore = new ArrayList<>();
        lore.add("<gray>Size:</gray> <white>" + plot.width() + " x " + plot.depth()
                + "</white> <dark_gray>(" + plot.area() + " blocks)</dark_gray>");
        lore.add("<gray>Purchase:</gray> " + (affordable ? "<white>" : "<red>")
                + economy.money().format(plot.purchasePrice()) + (affordable ? "</white>" : "</red>"));
        lore.add("<gray>Rent:</gray> <white>" + economy.money().format(plot.rentPrice())
                + plot.rentPeriod().suffix() + "</white>");
        lore.add("<gray>Status:</gray> " + statusColour(plot.status())
                + plot.status().displayName() + "</" + statusColourName(plot.status()) + ">");

        if (mine) {
            lore.add("");
            lore.add("<aqua>This is your plot.</aqua>");
            if (plot.inGracePeriod()) {
                lore.add("<red>Rent overdue - " + Durations.remaining(plot.graceRemaining(now))
                        + " left</red>");
            } else if (plot.rentDueAt() != null) {
                lore.add("<gray>Next rent in</gray> <white>"
                        + Durations.remaining(plot.untilRentDue(now)) + "</white>");
            }
        } else if (plot.status().hasOwner()) {
            lore.add("<gray>Owner:</gray> <white>"
                    + Text.escape(String.valueOf(
                            org.bukkit.Bukkit.getOfflinePlayer(plot.owner()).getName())) + "</white>");
        }
        lore.add("");
        lore.add("<gray>Click for details.</gray>");

        Material icon = switch (plot.status()) {
            case AVAILABLE -> affordable ? Material.LIME_CONCRETE : Material.YELLOW_CONCRETE;
            case OWNED -> mine ? Material.GOLD_BLOCK : Material.LIGHT_GRAY_CONCRETE;
            case RENT_OVERDUE -> Material.ORANGE_CONCRETE;
            case EXPIRED -> Material.RED_CONCRETE;
            case DISABLED -> Material.BARRIER;
        };

        return Button.of(ItemBuilder.of(icon)
                .name("<white><bold>Plot " + plot.id() + "</bold></white>")
                .lore(lore)
                .glow(mine)
                .clean().build(), click -> {
            PlotDetailMenu menu = new PlotDetailMenu(menus, click.player(), plots, economy,
                    notifications, scheduling, plot.id());
            menu.withParent(this);
            menu.open();
        });
    }

    @Override
    protected void decorate() {
        int lastRow = rows() - 1;

        if (loaded.isEmpty()) {
            set(22, Button.display(ItemBuilder.of(Material.BARRIER)
                    .name("<red>No plots have been created</red>")
                    .lore("<gray>An administrator defines plots with</gray>",
                            "<white>/plot create [id]</white>")
                    .clean().build()));
        }

        set(lastRow, 1, Button.display(ItemBuilder.of(Material.GOLD_NUGGET)
                .name("<gold>Your balance</gold>")
                .lore("<green>" + economy.money().format(balance) + "</green>")
                .clean().build()));

        set(lastRow, 7, Button.display(ItemBuilder.of(Material.BOOK)
                .name("<aqua>About the district</aqua>")
                .lore("<gray>Plots near spawn carry a one-off purchase</gray>",
                        "<gray>price and recurring rent.</gray>",
                        "",
                        "<gray>What a plot buys you:</gray>",
                        "<dark_gray>- central, high-traffic location</dark_gray>",
                        "<dark_gray>- prominence in the shop directory</dark_gray>",
                        "<dark_gray>- extra offer slots for your shop</dark_gray>",
                        "",
                        "<yellow>It does not guarantee customers.</yellow>")
                .clean().build()));
    }

    private static String statusColour(PlotStatus status) {
        return "<" + statusColourName(status) + ">";
    }

    private static String statusColourName(PlotStatus status) {
        return switch (status) {
            case AVAILABLE -> "green";
            case OWNED -> "white";
            case RENT_OVERDUE -> "gold";
            case EXPIRED -> "red";
            case DISABLED -> "dark_gray";
        };
    }
}
