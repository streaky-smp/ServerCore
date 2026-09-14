package com.streakysmp.gui;

import com.streakysmp.core.Scheduling;
import com.streakysmp.economy.EconomyService;
import com.streakysmp.notify.Messages;
import com.streakysmp.notify.NotificationService;
import com.streakysmp.plot.PlotResult;
import com.streakysmp.plot.PlotService;
import com.streakysmp.plot.SpawnPlot;
import com.streakysmp.util.Durations;
import com.streakysmp.util.Text;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * One plot's details, with purchase and rent actions.
 *
 * <p>Follows the layout the specification sketches: size, purchase price, rent,
 * status, then the actions. The confirmation dialog states the recurring rent
 * alongside the one-off price, because a player agreeing to 25,000 needs to know
 * they are also committing to 5,000 a week.
 */
public final class PlotDetailMenu extends Menu {

    private final PlotService plots;
    private final EconomyService economy;
    private final NotificationService notifications;
    private final Scheduling scheduling;
    private final String plotId;

    private SpawnPlot plot;
    private long balance;
    private boolean busy;

    public PlotDetailMenu(MenuManager menus,
                          Player viewer,
                          PlotService plots,
                          EconomyService economy,
                          NotificationService notifications,
                          Scheduling scheduling,
                          String plotId) {
        super(menus, viewer, Text.mm("<dark_gray>Spawn Plot</dark_gray>"), 4);
        this.plots = plots;
        this.economy = economy;
        this.notifications = notifications;
        this.scheduling = scheduling;
        this.plotId = plotId;
    }

    @Override
    public void open() {
        scheduling.thenSync(
                scheduling.supplyAsync(() -> new Object[]{
                        plots.byId(plotId).orElse(null),
                        economy.getBalance(viewer.getUniqueId())}),
                data -> {
                    plot = (SpawnPlot) data[0];
                    balance = (Long) data[1];
                    if (plot == null) {
                        viewer.sendMessage(Text.mm("<red>That plot no longer exists.</red>"));
                        return;
                    }
                    super.open();
                },
                error -> viewer.sendMessage(Text.mm("<red>Could not load that plot.</red>")));
    }

    @Override
    protected void build() {
        fillEmpty(Material.GRAY_STAINED_GLASS_PANE);

        set(0, 4, Button.display(ItemBuilder.of(Material.EMERALD_BLOCK)
                .name("<white><bold>Plot " + plot.id() + "</bold></white>")
                .lore(details())
                .clean().build()));

        boolean mine = plot.isOwner(viewer.getUniqueId());

        if (plot.status().purchasable()) {
            set(1, 2, purchaseButton());
        } else if (mine) {
            set(1, 2, payRentButton());
        } else {
            set(1, 2, Button.display(ItemBuilder.of(Material.GRAY_CONCRETE)
                    .name("<dark_gray>" + plot.status().displayName() + "</dark_gray>")
                    .lore("<gray>This plot is not for sale.</gray>")
                    .clean().build()));
        }

        set(1, 6, Button.of(ItemBuilder.of(Material.COMPASS)
                .name("<aqua>View location</aqua>")
                .lore("<gray>Shows the plot's coordinates and</gray>",
                        "<gray>points you towards it.</gray>")
                .clean().build(), click -> showLocation(click.player())));

        if (hasParent()) {
            set(3, 0, Button.of(ItemBuilder.of(Material.ARROW)
                    .name("<green>Back</green>").clean().build(), click -> parent().open()));
        }
        set(3, 8, Button.of(ItemBuilder.of(Material.BARRIER)
                .name("<red>Close</red>").clean().build(), ClickContext::close));
    }

    private List<String> details() {
        long now = System.currentTimeMillis();
        List<String> lore = new ArrayList<>();
        lore.add("<gray>Size:</gray> <white>" + plot.width() + " x " + plot.depth() + "</white>");
        lore.add("<gray>Area:</gray> <white>" + plot.area() + " blocks</white>");
        lore.add("<gray>Purchase:</gray> <white>"
                + economy.money().format(plot.purchasePrice()) + "</white>");
        lore.add("<gray>Rent:</gray> <white>" + economy.money().format(plot.rentPrice())
                + plot.rentPeriod().suffix() + "</white>");
        lore.add("<gray>Status:</gray> <white>" + plot.status().displayName() + "</white>");
        lore.add("<gray>World:</gray> <white>" + plot.worldName() + "</white>");

        if (plot.isOwner(viewer.getUniqueId())) {
            lore.add("");
            if (plot.inGracePeriod()) {
                lore.add("<red>Rent overdue.</red>");
                lore.add("<red>Shop closes in "
                        + Durations.remaining(plot.graceRemaining(now)) + ".</red>");
            } else if (plot.status() == com.streakysmp.plot.PlotStatus.EXPIRED) {
                lore.add("<red>Expired. Pay the rent to recover it.</red>");
            } else if (plot.rentDueAt() != null) {
                lore.add("<gray>Next rent in</gray> <white>"
                        + Durations.remaining(plot.untilRentDue(now)) + "</white>");
            }
            lore.add("<gray>Rent paid to date:</gray> <white>"
                    + economy.money().format(plot.rentPaid()) + "</white>");
        }
        return lore;
    }

    private Button purchaseButton() {
        boolean affordable = balance >= plot.purchasePrice();

        List<String> lore = new ArrayList<>();
        lore.add("<gray>One-off:</gray> <white>"
                + economy.money().format(plot.purchasePrice()) + "</white>");
        lore.add("<gray>Then:</gray> <white>" + economy.money().format(plot.rentPrice())
                + plot.rentPeriod().suffix() + "</white>");
        lore.add("<gray>Your balance:</gray> " + (affordable ? "<green>" : "<red>")
                + economy.money().format(balance) + (affordable ? "</green>" : "</red>"));
        lore.add("");
        lore.add(affordable
                ? "<gray>Click to purchase.</gray>"
                : "<red>You cannot afford this yet.</red>");

        return Button.of(ItemBuilder.of(affordable ? Material.LIME_CONCRETE : Material.GRAY_CONCRETE)
                .name("<green><bold>Purchase Plot</bold></green>")
                .lore(lore)
                .clean().build(), click -> {
            if (!affordable) {
                notifications.error(click.player(), "plot.error.insufficient-funds", Messages.of(
                        "needed", economy.money().format(plot.purchasePrice() - balance),
                        "cost", economy.money().format(plot.purchasePrice())));
                return;
            }
            confirmPurchase(click.player());
        });
    }

    /** States the recurring commitment as prominently as the one-off price. */
    private void confirmPurchase(Player player) {
        ConfirmMenu.open(menus, player,
                Text.mm("<dark_gray>Purchase plot</dark_gray>"),
                List.of("<gray>Plot:</gray> <white>" + plot.id() + "</white>",
                        "<gray>Size:</gray> <white>" + plot.width() + " x " + plot.depth() + "</white>",
                        "<gray>Purchase:</gray> <yellow>"
                                + economy.money().format(plot.purchasePrice()) + "</yellow>",
                        "",
                        "<red>You will also owe</red> <white>"
                                + economy.money().format(plot.rentPrice()) + "</white>",
                        "<red>every " + plot.rentPeriod().displayName().toLowerCase(
                                java.util.Locale.ROOT) + ".</red>",
                        "<gray>Miss it and your shop closes after a grace period.</gray>"),
                "Confirm Purchase",
                () -> submitPurchase(player),
                () -> notifications.info(player, "plot.cancelled", Messages.of()));
    }

    private void submitPurchase(Player player) {
        if (busy) {
            return;
        }
        busy = true;
        scheduling.thenSync(
                scheduling.supplyAsync(() -> plots.purchase(player.getUniqueId(), plotId)),
                result -> {
                    busy = false;
                    if (result.isSuccess()) {
                        notifications.success(player, "plot.purchased", Messages.of(
                                "plot", plotId,
                                "cost", economy.money().format(result.amount()),
                                "rent", economy.money().format(result.plot().rentPrice())
                                        + result.plot().rentPeriod().suffix()));
                    } else {
                        notifications.error(player, result.messageKey(), Messages.of(
                                "needed", economy.money().format(result.shortfall()),
                                "cost", economy.money().format(result.amount()),
                                "limit", String.valueOf(plots.settings().maxPlotsPerPlayer())));
                    }
                    open();
                },
                error -> {
                    busy = false;
                    notifications.error(player, "error.internal", Messages.of());
                });
    }

    private Button payRentButton() {
        boolean affordable = balance >= plot.rentPrice();
        List<String> lore = new ArrayList<>();
        lore.add("<gray>Amount:</gray> <white>"
                + economy.money().format(plot.rentPrice()) + "</white>");
        if (plot.inGracePeriod()) {
            lore.add("<red>Overdue. Pay now to keep your shop open.</red>");
        } else {
            lore.add("<gray>Pays the next period early.</gray>");
        }
        lore.add(affordable ? "<gray>Click to pay.</gray>" : "<red>You cannot afford this.</red>");

        return Button.of(ItemBuilder.of(affordable
                        ? Material.EMERALD : Material.GRAY_CONCRETE)
                .name("<green><bold>Pay rent now</bold></green>")
                .lore(lore)
                .clean().build(), click -> {
            if (!affordable || busy) {
                if (!affordable) {
                    notifications.error(click.player(), "plot.error.insufficient-funds",
                            Messages.of(
                                    "needed", economy.money().format(plot.rentPrice() - balance),
                                    "cost", economy.money().format(plot.rentPrice())));
                }
                return;
            }
            busy = true;
            scheduling.thenSync(
                    scheduling.supplyAsync(() -> plots.payRent(click.player().getUniqueId(), plotId)),
                    result -> {
                        busy = false;
                        if (result.isSuccess()) {
                            notifications.success(click.player(), "plot.rent-paid", Messages.of(
                                    "plot", plotId,
                                    "amount", economy.money().format(result.amount())));
                        } else {
                            notifications.error(click.player(), result.messageKey(), Messages.of(
                                    "needed", economy.money().format(result.shortfall()),
                                    "cost", economy.money().format(result.amount())));
                        }
                        open();
                    },
                    error -> {
                        busy = false;
                        notifications.error(click.player(), "error.internal", Messages.of());
                    });
        });
    }

    /**
     * Prints the plot's coordinates and a bearing.
     *
     * <p>Text rather than a teleport: teleporting players into a commercial
     * district on a click is a griefing and escape vector, and the coordinates are
     * what a player actually needs to walk there.
     */
    private void showLocation(Player player) {
        Location from = player.getLocation();
        int dx = plot.centreX() - from.getBlockX();
        int dz = plot.centreZ() - from.getBlockZ();
        int distance = (int) Math.sqrt((double) dx * dx + (double) dz * dz);

        player.sendMessage(Text.mm("<aqua>Plot " + plot.id() + "</aqua>"));
        player.sendMessage(Text.mm("<gray>Centre:</gray> <white>" + plot.centreX() + ", "
                + plot.centreZ() + "</white> <dark_gray>in " + plot.worldName() + "</dark_gray>"));
        player.sendMessage(Text.mm("<gray>Corners:</gray> <white>" + plot.minX() + ", "
                + plot.minZ() + "</white> <dark_gray>to</dark_gray> <white>"
                + plot.maxX() + ", " + plot.maxZ() + "</white>"));
        if (plot.worldName().equals(from.getWorld().getName())) {
            player.sendMessage(Text.mm("<gray>Distance:</gray> <white>" + distance
                    + " blocks</white> <dark_gray>" + bearing(dx, dz) + "</dark_gray>"));
        } else {
            player.sendMessage(Text.mm("<gray>You are in a different world.</gray>"));
        }
    }

    /** Compass direction from a delta, for the "which way do I walk" answer. */
    private static String bearing(int dx, int dz) {
        if (dx == 0 && dz == 0) {
            return "you are here";
        }
        StringBuilder direction = new StringBuilder();
        if (dz < -Math.abs(dx) / 2) {
            direction.append("north");
        } else if (dz > Math.abs(dx) / 2) {
            direction.append("south");
        }
        if (dx > Math.abs(dz) / 2) {
            direction.append("east");
        } else if (dx < -Math.abs(dz) / 2) {
            direction.append("west");
        }
        return direction.isEmpty() ? "nearby" : "to the " + direction;
    }
}
