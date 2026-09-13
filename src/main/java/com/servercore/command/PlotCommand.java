package com.servercore.command;

import com.servercore.core.Scheduling;
import com.servercore.economy.EconomyService;
import com.servercore.gui.ChatInput;
import com.servercore.gui.MailboxMenu;
import com.servercore.gui.MenuManager;
import com.servercore.gui.PlotDistrictMenu;
import com.servercore.log.AuditAction;
import com.servercore.log.AuditLog;
import com.servercore.notify.Messages;
import com.servercore.notify.NotificationService;
import com.servercore.permission.PermissionService;
import com.servercore.permission.Permissions;
import com.servercore.plot.PlotService;
import com.servercore.plot.PlotStatus;
import com.servercore.plot.RentPeriod;
import com.servercore.plot.SpawnPlot;
import com.servercore.util.Durations;
import com.servercore.util.Numbers;
import com.servercore.util.Text;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * {@code /plot} -- browse the district, and administer plots.
 *
 * <p>Plot creation uses the player's current position and a radius, matching the
 * approach {@code /claim} takes and for the same crossplay reason.
 */
public final class PlotCommand implements BasicCommand {

    private static final List<String> PLAYER_SUBCOMMANDS =
            List.of("district", "list", "mail", "pay", "help");
    private static final List<String> ADMIN_SUBCOMMANDS =
            List.of("create", "remove", "setprice", "setrent", "setperiod", "status", "sweep");

    private final PlotService plots;
    private final EconomyService economy;
    private final PermissionService permissions;
    private final NotificationService notifications;
    private final AuditLog auditLog;
    private final MenuManager menus;
    private final ChatInput chatInput;
    private final Scheduling scheduling;

    public PlotCommand(PlotService plots,
                       EconomyService economy,
                       PermissionService permissions,
                       NotificationService notifications,
                       AuditLog auditLog,
                       MenuManager menus,
                       ChatInput chatInput,
                       Scheduling scheduling) {
        this.plots = plots;
        this.economy = economy;
        this.permissions = permissions;
        this.notifications = notifications;
        this.auditLog = auditLog;
        this.menus = menus;
        this.chatInput = chatInput;
        this.scheduling = scheduling;
    }

    @Override
    public void execute(@NotNull CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        String sub = args.length == 0 ? "district" : args[0].toLowerCase(Locale.ROOT);

        if (ADMIN_SUBCOMMANDS.contains(sub)) {
            if (!permissions.require(sender, Permissions.PLOT_ADMIN)) {
                return;
            }
            switch (sub) {
                case "create" -> create(sender, args);
                case "remove" -> remove(sender, args);
                case "setprice" -> setPrice(sender, args, true);
                case "setrent" -> setPrice(sender, args, false);
                case "setperiod" -> setPeriod(sender, args);
                case "status" -> setStatus(sender, args);
                case "sweep" -> sweep(sender);
                default -> help(sender);
            }
            return;
        }

        switch (sub) {
            case "district", "browse" -> district(sender);
            case "list" -> list(sender);
            case "mail" -> mail(sender);
            case "pay" -> pay(sender, args);
            default -> help(sender);
        }
    }

    // ------------------------------------------------------------- players

    private void district(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            notifications.error(sender, "error.player-only", Messages.of());
            return;
        }
        if (!permissions.require(player, Permissions.PLOT_PURCHASE)) {
            return;
        }
        PlotDistrictMenu.openFor(menus, chatInput, player, plots, economy, notifications, scheduling);
    }

    private void mail(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            notifications.error(sender, "error.player-only", Messages.of());
            return;
        }
        MailboxMenu.openFor(menus, player, plots, notifications, scheduling);
    }

    private void list(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            listAll(sender);
            return;
        }
        scheduling.thenSync(
                scheduling.supplyAsync(() -> plots.ownedBy(player.getUniqueId())),
                owned -> {
                    if (owned.isEmpty()) {
                        notifications.info(player, "plot.list-empty", Messages.of());
                        return;
                    }
                    long now = System.currentTimeMillis();
                    player.sendMessage(Text.mm("<aqua>Your plots (" + owned.size() + ")</aqua>"));
                    for (SpawnPlot plot : owned) {
                        String rent = plot.inGracePeriod()
                                ? "<red>overdue, " + Durations.remaining(plot.graceRemaining(now))
                                        + " left</red>"
                                : "<gray>next in " + Durations.remaining(plot.untilRentDue(now))
                                        + "</gray>";
                        player.sendMessage(Text.mm("<gray>- <white>" + plot.id() + "</white> "
                                + "<dark_gray>" + plot.width() + "x" + plot.depth() + ", "
                                + economy.money().format(plot.rentPrice())
                                + plot.rentPeriod().suffix() + "</dark_gray> " + rent));
                    }
                },
                error -> notifications.error(player, "error.internal", Messages.of()));
    }

    private void listAll(CommandSender sender) {
        scheduling.thenSync(
                scheduling.supplyAsync(plots::all),
                all -> {
                    sender.sendMessage(Text.mm("<aqua>Plots (" + all.size() + ")</aqua>"));
                    for (SpawnPlot plot : all) {
                        sender.sendMessage(Text.mm("<gray>- <white>" + plot.id() + "</white> "
                                + "<dark_gray>" + plot.status().displayName() + ", "
                                + plot.worldName() + " " + plot.minX() + "," + plot.minZ()
                                + " to " + plot.maxX() + "," + plot.maxZ() + "</dark_gray>"));
                    }
                },
                error -> notifications.error(sender, "error.internal", Messages.of()));
    }

    private void pay(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            notifications.error(sender, "error.player-only", Messages.of());
            return;
        }
        if (args.length < 2) {
            player.sendMessage(Text.mm("<gray>Usage: /plot pay [plot id]</gray>"));
            return;
        }
        String id = args[1].toLowerCase(Locale.ROOT);
        scheduling.thenSync(
                scheduling.supplyAsync(() -> plots.payRent(player.getUniqueId(), id)),
                result -> {
                    if (result.isSuccess()) {
                        notifications.success(player, "plot.rent-paid", Messages.of(
                                "plot", id,
                                "amount", economy.money().format(result.amount())));
                    } else {
                        notifications.error(player, result.messageKey(), Messages.of(
                                "needed", economy.money().format(result.shortfall()),
                                "cost", economy.money().format(result.amount())));
                    }
                },
                error -> notifications.error(player, "error.internal", Messages.of()));
    }

    // --------------------------------------------------------------- admin

    private void create(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            notifications.error(sender, "error.player-only", Messages.of());
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(Text.mm("<gray>Usage: /plot create [id] [radius] "
                    + "[purchase price] [rent price]</gray>"));
            sender.sendMessage(Text.mm(
                    "<gray>The plot is centred on where you are standing.</gray>"));
            return;
        }
        String id = args[1].toLowerCase(Locale.ROOT);
        int radius = args.length >= 3
                ? Numbers.parsePositiveInt(args[2], 1, 200).orElse(5)
                : 5;
        Long purchasePrice = args.length >= 4
                ? Numbers.parseMoney(args[3], economy.money().fractionDigits()).orElse(null)
                : null;
        Long rentPrice = args.length >= 5
                ? Numbers.parseMoney(args[4], economy.money().fractionDigits()).orElse(null)
                : null;

        Location at = player.getLocation();
        String world = at.getWorld().getName();
        int x1 = at.getBlockX() - radius;
        int z1 = at.getBlockZ() - radius;
        int x2 = at.getBlockX() + radius;
        int z2 = at.getBlockZ() + radius;

        scheduling.thenSync(
                scheduling.supplyAsync(() -> plots.createPlot(
                        id, world, x1, z1, x2, z2, purchasePrice, rentPrice, null)),
                result -> {
                    if (!result.isSuccess()) {
                        notifications.error(sender, result.messageKey(), Messages.of("name", id));
                        return;
                    }
                    SpawnPlot plot = result.plot();
                    auditLog.record(AuditAction.ADMIN_ACTION, player.getUniqueId(),
                            player.getName(), "Created spawn plot " + id);
                    notifications.success(sender, "plot.created", Messages.of(
                            "plot", id,
                            "size", plot.width() + " x " + plot.depth(),
                            "cost", economy.money().format(plot.purchasePrice()),
                            "rent", economy.money().format(plot.rentPrice())
                                    + plot.rentPeriod().suffix()));
                },
                error -> notifications.error(sender, "error.internal", Messages.of()));
    }

    private void remove(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Text.mm("<gray>Usage: /plot remove [id]</gray>"));
            return;
        }
        String id = args[1].toLowerCase(Locale.ROOT);
        scheduling.thenSync(
                scheduling.supplyAsync(() -> plots.deletePlot(id)),
                result -> {
                    if (result.isSuccess()) {
                        auditLog.record(AuditAction.ADMIN_ACTION,
                                sender instanceof Player p ? p.getUniqueId() : null,
                                sender.getName(), "Removed spawn plot " + id);
                        notifications.success(sender, "plot.removed", Messages.of("plot", id));
                    } else {
                        notifications.error(sender, result.messageKey(), Messages.of("name", id));
                    }
                },
                error -> notifications.error(sender, "error.internal", Messages.of()));
    }

    private void setPrice(CommandSender sender, String[] args, boolean purchase) {
        if (args.length < 3) {
            sender.sendMessage(Text.mm("<gray>Usage: /plot "
                    + (purchase ? "setprice" : "setrent") + " [id] [amount]</gray>"));
            return;
        }
        String id = args[1].toLowerCase(Locale.ROOT);
        Optional<Long> amount = Numbers.parseMoney(args[2], economy.money().fractionDigits());
        if (amount.isEmpty()) {
            notifications.error(sender, "error.invalid-amount", Messages.of("input", args[2]));
            return;
        }
        scheduling.thenSync(
                scheduling.supplyAsync(() -> purchase
                        ? plots.setPricing(id, amount.get(), null, null)
                        : plots.setPricing(id, null, amount.get(), null)),
                result -> {
                    if (result.isSuccess()) {
                        notifications.success(sender, "plot.pricing-updated", Messages.of(
                                "plot", id,
                                "cost", economy.money().format(result.plot().purchasePrice()),
                                "rent", economy.money().format(result.plot().rentPrice())
                                        + result.plot().rentPeriod().suffix()));
                    } else {
                        notifications.error(sender, result.messageKey(), Messages.of("name", id));
                    }
                },
                error -> notifications.error(sender, "error.internal", Messages.of()));
    }

    private void setPeriod(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(Text.mm(
                    "<gray>Usage: /plot setperiod [id] [daily|weekly|monthly]</gray>"));
            return;
        }
        String id = args[1].toLowerCase(Locale.ROOT);
        Optional<RentPeriod> period = RentPeriod.byName(args[2]);
        if (period.isEmpty()) {
            notifications.error(sender, "plot.error.bad-period", Messages.of("name", args[2]));
            return;
        }
        scheduling.thenSync(
                scheduling.supplyAsync(() -> plots.setPricing(id, null, null, period.get())),
                result -> {
                    if (result.isSuccess()) {
                        notifications.success(sender, "plot.pricing-updated", Messages.of(
                                "plot", id,
                                "cost", economy.money().format(result.plot().purchasePrice()),
                                "rent", economy.money().format(result.plot().rentPrice())
                                        + result.plot().rentPeriod().suffix()));
                    } else {
                        notifications.error(sender, result.messageKey(), Messages.of("name", id));
                    }
                },
                error -> notifications.error(sender, "error.internal", Messages.of()));
    }

    private void setStatus(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(Text.mm(
                    "<gray>Usage: /plot status [id] [available|owned|disabled]</gray>"));
            return;
        }
        String id = args[1].toLowerCase(Locale.ROOT);
        Optional<PlotStatus> status = PlotStatus.byName(args[2]);
        if (status.isEmpty()) {
            notifications.error(sender, "plot.error.bad-status", Messages.of("name", args[2]));
            return;
        }
        scheduling.thenSync(
                scheduling.supplyAsync(() -> plots.setStatus(id, status.get())),
                result -> {
                    if (result.isSuccess()) {
                        auditLog.record(AuditAction.ADMIN_ACTION,
                                sender instanceof Player p ? p.getUniqueId() : null,
                                sender.getName(),
                                "Set plot " + id + " to " + status.get().name());
                        notifications.success(sender, "plot.status-updated", Messages.of(
                                "plot", id, "status", status.get().displayName()));
                    } else {
                        notifications.error(sender, result.messageKey(), Messages.of("name", id));
                    }
                },
                error -> notifications.error(sender, "error.internal", Messages.of()));
    }

    /** Runs the rent sweep immediately, for testing a configuration. */
    private void sweep(CommandSender sender) {
        // Reported rather than fired and forgotten: a sweep evicts tenants and
        // moves stock, so an admin running one by hand needs to know it finished
        // rather than assuming it did.
        notifications.success(sender, "plot.sweep-started", Messages.of());
        scheduling.thenSync(scheduling.runAsync(plots::sweep),
                ignored -> notifications.success(sender, "plot.sweep-finished", Messages.of()),
                error -> notifications.error(sender, "error.internal", Messages.of()));
    }

    private void help(CommandSender sender) {
        sender.sendMessage(Text.mm("<aqua>Spawn plots</aqua>"));
        sender.sendMessage(Text.mm("<gray>/plot district</gray> <dark_gray>-</dark_gray> "
                + "<white>browse the commercial district</white>"));
        sender.sendMessage(Text.mm("<gray>/plot list | pay [id] | mail</gray>"));
        if (permissions.has(sender, Permissions.PLOT_ADMIN)) {
            sender.sendMessage(Text.mm("<dark_gray>Admin:</dark_gray>"));
            sender.sendMessage(Text.mm("<gray>/plot create [id] [radius] "
                    + "[price] [rent]</gray>"));
            sender.sendMessage(Text.mm("<gray>/plot remove [id]</gray>"));
            sender.sendMessage(Text.mm("<gray>/plot setprice | setrent [id] [amount]</gray>"));
            sender.sendMessage(Text.mm("<gray>/plot setperiod [id] [daily|weekly|monthly]</gray>"));
            sender.sendMessage(Text.mm("<gray>/plot status [id] [status] | sweep</gray>"));
        }
    }

    @Override
    public @NotNull Collection<String> suggest(@NotNull CommandSourceStack source, String[] args) {
        if (args.length <= 1) {
            String partial = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<>();
            for (String option : PLAYER_SUBCOMMANDS) {
                if (option.startsWith(partial)) {
                    out.add(option);
                }
            }
            if (permissions.has(source.getSender(), Permissions.PLOT_ADMIN)) {
                for (String option : ADMIN_SUBCOMMANDS) {
                    if (option.startsWith(partial)) {
                        out.add(option);
                    }
                }
            }
            return out;
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("setperiod")) {
            return List.of("daily", "weekly", "monthly");
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("status")) {
            return List.of("available", "owned", "disabled");
        }
        return List.of();
    }

    @Override
    public boolean canUse(@NotNull CommandSender sender) {
        return permissions.has(sender, Permissions.PLOT_PURCHASE)
                || permissions.has(sender, Permissions.PLOT_ADMIN);
    }

    @Override
    public @NotNull String permission() {
        return Permissions.PLOT_PURCHASE;
    }
}
