package com.servercore.command;

import com.servercore.auction.AuctionListing;
import com.servercore.auction.AuctionService;
import com.servercore.core.Scheduling;
import com.servercore.economy.EconomyService;
import com.servercore.gui.AuctionBrowseMenu;
import com.servercore.gui.AuctionSelfMenu;
import com.servercore.gui.ChatInput;
import com.servercore.gui.ConfirmMenu;
import com.servercore.gui.MenuManager;
import com.servercore.log.AuditAction;
import com.servercore.log.AuditLog;
import com.servercore.notify.Messages;
import com.servercore.notify.NotificationService;
import com.servercore.permission.PermissionService;
import com.servercore.permission.Permissions;
import com.servercore.util.Numbers;
import com.servercore.util.Text;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * {@code /ah} -- the auction house.
 */
public final class AuctionCommand implements BasicCommand {

    private static final List<String> SUBCOMMANDS =
            List.of("browse", "sell", "mine", "collect", "help");
    private static final List<String> ADMIN_SUBCOMMANDS = List.of("remove", "sweep");

    private final AuctionService auctions;
    private final EconomyService economy;
    private final PermissionService permissions;
    private final NotificationService notifications;
    private final AuditLog auditLog;
    private final MenuManager menus;
    private final ChatInput chatInput;
    private final Scheduling scheduling;

    public AuctionCommand(AuctionService auctions,
                          EconomyService economy,
                          PermissionService permissions,
                          NotificationService notifications,
                          AuditLog auditLog,
                          MenuManager menus,
                          ChatInput chatInput,
                          Scheduling scheduling) {
        this.auctions = auctions;
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
        String sub = args.length == 0 ? "browse" : args[0].toLowerCase(Locale.ROOT);

        if (ADMIN_SUBCOMMANDS.contains(sub)) {
            if (!permissions.require(sender, Permissions.AUCTION_ADMIN)) {
                return;
            }
            switch (sub) {
                case "remove" -> remove(sender, args);
                case "sweep" -> {
                    // Reported rather than fired and forgotten: an admin running a
                    // sweep by hand is usually diagnosing something, and "it started"
                    // is not an answer if the sweep then throws.
                    notifications.success(sender, "auction.sweep-started", Messages.of());
                    scheduling.thenSync(scheduling.runAsync(auctions::sweep),
                            ignored -> notifications.success(sender, "auction.sweep-finished",
                                    Messages.of()),
                            error -> notifications.error(sender, "error.internal", Messages.of()));
                }
                default -> help(sender);
            }
            return;
        }

        if (!(sender instanceof Player player)) {
            notifications.error(sender, "error.player-only", Messages.of());
            return;
        }

        switch (sub) {
            case "browse", "list" -> browse(player);
            case "sell" -> sell(player, args);
            case "mine", "listings" -> mine(player);
            case "collect" -> mine(player);
            default -> help(player);
        }
    }

    private void browse(Player player) {
        if (!permissions.require(player, Permissions.AUCTION_USE)) {
            return;
        }
        if (!auctions.settings().enabled()) {
            notifications.error(player, "auction.error.disabled", Messages.of());
            return;
        }
        AuctionBrowseMenu.openFor(menus, chatInput, player, auctions, economy,
                notifications, scheduling);
    }

    private void mine(Player player) {
        if (!permissions.require(player, Permissions.AUCTION_USE)) {
            return;
        }
        AuctionSelfMenu.openFor(menus, chatInput, player, auctions, economy,
                notifications, scheduling);
    }

    /**
     * Lists the held item.
     *
     * <p>Confirms first when the asking price is large, using the same threshold
     * as every other expensive action, so "expensive" means one thing across the
     * plugin.
     */
    private void sell(Player player, String[] args) {
        if (!permissions.require(player, Permissions.AUCTION_CREATE)) {
            return;
        }
        if (!auctions.settings().enabled()) {
            notifications.error(player, "auction.error.disabled", Messages.of());
            return;
        }
        if (args.length < 2) {
            player.sendMessage(Text.mm("<gray>Usage: /ah sell [price]</gray>"));
            player.sendMessage(Text.mm("<gray>Hold the item you want to sell.</gray>"));
            return;
        }

        Optional<Long> price = Numbers.parseMoney(args[1], economy.money().fractionDigits());
        if (price.isEmpty() || price.get() <= 0) {
            notifications.error(player, "error.invalid-amount", Messages.of("input", args[1]));
            return;
        }

        var settings = auctions.settings();
        long fee = settings.listingFeeFor(price.get());
        var held = player.getInventory().getItemInMainHand();
        if (held.isEmpty() || held.getType().isAir()) {
            notifications.error(player, "auction.error.no-item", Messages.of());
            return;
        }

        Runnable submit = () -> auctions.listHeldItem(player, price.get(), result -> {
            if (result.isSuccess()) {
                notifications.success(player, "auction.listed", Messages.of(
                        "item", result.listing().displayName(),
                        "price", economy.money().format(result.listing().price()),
                        "fee", economy.money().format(result.amount())));
                auditLog.record(AuditAction.AUCTION_TRANSACTION, player.getUniqueId(),
                        player.getName(), "listed " + result.listing().id());
            } else {
                notifications.error(player, result.messageKey(), Messages.of(
                        "limit", String.valueOf(settings.maxListingsPerPlayer()),
                        "min", economy.money().format(settings.minPrice()),
                        "max", economy.money().format(settings.maxPrice())));
            }
        });

        List<String> lines = new ArrayList<>();
        lines.add("<gray>Item:</gray> <white>" + held.getAmount() + "x "
                + friendly(held.getType().name()) + "</white>");
        lines.add("<gray>Asking:</gray> <yellow>"
                + economy.money().format(price.get()) + "</yellow>");
        if (fee > 0) {
            lines.add("<gray>Listing fee:</gray> <red>"
                    + economy.money().format(fee) + "</red>");
            lines.add("<dark_gray>Charged now, whether or not it sells.</dark_gray>");
        }
        long tax = settings.salesTaxFor(price.get());
        if (tax > 0) {
            lines.add("<gray>You would receive:</gray> <green>"
                    + economy.money().format(settings.sellerProceeds(price.get())) + "</green>");
        }

        if (fee > 0 || economy.settings().needsConfirmation(price.get())) {
            ConfirmMenu.open(menus, player,
                    Text.mm("<dark_gray>Create listing</dark_gray>"),
                    lines, "List item", submit,
                    () -> notifications.info(player, "auction.cancelled", Messages.of()));
            return;
        }
        submit.run();
    }

    private void remove(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Text.mm("<gray>Usage: /ah remove [listing id]</gray>"));
            return;
        }
        String id = args[1];
        scheduling.thenSync(
                scheduling.supplyAsync(() -> auctions.forceCancel(id)),
                result -> {
                    if (result.isSuccess()) {
                        auditLog.record(AuditAction.AUCTION_TRANSACTION,
                                sender instanceof Player p ? p.getUniqueId() : null,
                                sender.getName(), "admin removed listing " + id);
                        notifications.success(sender, "auction.removed", Messages.of("id", id));
                    } else {
                        notifications.error(sender, result.messageKey(), Messages.of());
                    }
                },
                error -> notifications.error(sender, "error.internal", Messages.of()));
    }

    private void help(CommandSender sender) {
        sender.sendMessage(Text.mm("<aqua>Auction house</aqua>"));
        sender.sendMessage(Text.mm("<gray>/ah</gray> <dark_gray>-</dark_gray> "
                + "<white>browse everything for sale</white>"));
        sender.sendMessage(Text.mm("<gray>/ah sell [price]</gray> <dark_gray>-</dark_gray> "
                + "<white>list the item you are holding</white>"));
        sender.sendMessage(Text.mm("<gray>/ah mine</gray> <dark_gray>-</dark_gray> "
                + "<white>your listings and anything waiting to collect</white>"));

        var settings = auctions.settings();
        sender.sendMessage(Text.mm("<dark_gray>Listings last "
                + settings.listingDuration().toHours() + "h; up to "
                + settings.maxListingsPerPlayer() + " at a time.</dark_gray>"));
        if (permissions.has(sender, Permissions.AUCTION_ADMIN)) {
            sender.sendMessage(Text.mm("<dark_gray>Admin: /ah remove [id] | /ah sweep</dark_gray>"));
        }
    }

    private static String friendly(String materialName) {
        String raw = materialName.toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(raw.charAt(0)) + raw.substring(1);
    }

    @Override
    public @NotNull Collection<String> suggest(@NotNull CommandSourceStack source, String[] args) {
        if (args.length <= 1) {
            String partial = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<>();
            for (String option : SUBCOMMANDS) {
                if (option.startsWith(partial)) {
                    out.add(option);
                }
            }
            if (permissions.has(source.getSender(), Permissions.AUCTION_ADMIN)) {
                for (String option : ADMIN_SUBCOMMANDS) {
                    if (option.startsWith(partial)) {
                        out.add(option);
                    }
                }
            }
            return out;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("sell")) {
            return List.of("100", "500", "1000", "5000");
        }
        return List.of();
    }

    @Override
    public boolean canUse(@NotNull CommandSender sender) {
        return permissions.has(sender, Permissions.AUCTION_USE);
    }

    @Override
    public @NotNull String permission() {
        return Permissions.AUCTION_USE;
    }
}
