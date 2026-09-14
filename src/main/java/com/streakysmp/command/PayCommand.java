package com.streakysmp.command;

import com.streakysmp.core.Scheduling;
import com.streakysmp.economy.EconomyResult;
import com.streakysmp.economy.EconomyService;
import com.streakysmp.economy.EconomySettings;
import com.streakysmp.gui.ConfirmMenu;
import com.streakysmp.gui.MenuManager;
import com.streakysmp.notify.Messages;
import com.streakysmp.notify.NotificationService;
import com.streakysmp.permission.PermissionService;
import com.streakysmp.permission.Permissions;
import com.streakysmp.util.Durations;
import com.streakysmp.util.Numbers;
import com.streakysmp.util.Text;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code /pay <player> <amount>}.
 *
 * <p>The validation order matters and is deliberate: everything cheap and local
 * is checked before the database is touched, and everything that depends on the
 * recipient existing is checked before the player is asked to confirm. A player
 * should never confirm a payment that was always going to be refused.
 */
public final class PayCommand implements BasicCommand {

    private final EconomyService economy;
    private final PlayerLookup lookup;
    private final PermissionService permissions;
    private final NotificationService notifications;
    private final MenuManager menus;
    private final Scheduling scheduling;

    public PayCommand(EconomyService economy,
                      PlayerLookup lookup,
                      PermissionService permissions,
                      NotificationService notifications,
                      MenuManager menus,
                      Scheduling scheduling) {
        this.economy = economy;
        this.lookup = lookup;
        this.permissions = permissions;
        this.notifications = notifications;
        this.menus = menus;
        this.scheduling = scheduling;
    }

    @Override
    public void execute(@NotNull CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        if (!(sender instanceof Player player)) {
            notifications.error(sender, "error.player-only", Messages.of());
            return;
        }
        if (!permissions.require(player, Permissions.ECONOMY_PAY)) {
            return;
        }
        if (args.length != 2) {
            notifications.info(player, "economy.pay.usage", Messages.of());
            return;
        }

        EconomySettings settings = economy.settings();

        Optional<Long> parsed = Numbers.parseMoney(args[1], settings.money().fractionDigits());
        if (parsed.isEmpty() || parsed.get() <= 0) {
            notifications.error(player, "error.invalid-amount", Messages.of("input", args[1]));
            return;
        }
        long amount = parsed.get();

        // Local rules first: no reason to hit the database to reject an amount
        // that is out of bounds regardless of who the recipient is.
        if (amount < settings.minimumPayment()) {
            notifications.error(player, "economy.error.below-minimum",
                    Messages.of("minimum", settings.money().format(settings.minimumPayment())));
            return;
        }
        if (amount > settings.maximumPayment()) {
            notifications.error(player, "economy.error.above-maximum",
                    Messages.of("maximum", settings.money().format(settings.maximumPayment())));
            return;
        }
        long cooldown = economy.remainingCooldownMillis(player.getUniqueId());
        if (cooldown > 0) {
            notifications.error(player, "economy.error.cooldown",
                    Messages.of("remaining", Durations.remaining(Duration.ofMillis(cooldown))));
            return;
        }

        String targetName = args[0];
        scheduling.thenSync(lookup.resolve(targetName), resolved -> {
            if (resolved.isEmpty()) {
                notifications.error(player, "error.unknown-player", Messages.of("name", targetName));
                return;
            }
            PlayerLookup.Target target = resolved.get();

            if (target.uuid().equals(player.getUniqueId()) && !settings.allowSelfPayment()) {
                notifications.error(player, "economy.error.self-payment", Messages.of());
                return;
            }
            if (!target.online() && !settings.allowOfflinePayment()) {
                notifications.error(player, "economy.error.offline-payment",
                        Messages.of("name", target.name()));
                return;
            }

            if (settings.needsConfirmation(amount)) {
                confirm(player, target, amount, settings);
            } else {
                send(player, target, amount);
            }
        }, error -> notifications.error(player, "error.internal", Messages.of()));
    }

    /**
     * Shows a confirmation dialog for a large payment.
     *
     * <p>The dialog states the fee and the total separately. A player agreeing to
     * send 5,000 should see that 5,100 will leave their account, not discover it
     * afterwards.
     */
    private void confirm(Player player, PlayerLookup.Target target, long amount, EconomySettings settings) {
        long fee = settings.feeFor(amount);
        java.util.List<String> lines = new java.util.ArrayList<>();
        lines.add("<gray>Recipient:</gray> <white>" + Text.escape(target.name()) + "</white>");
        lines.add("<gray>Amount:</gray> <white>" + settings.money().format(amount) + "</white>");
        if (fee > 0) {
            lines.add("<gray>Fee:</gray> <white>" + settings.money().format(fee) + "</white>");
            lines.add("<gray>Total debited:</gray> <yellow>"
                    + settings.money().format(amount + fee) + "</yellow>");
        }
        if (!target.online()) {
            lines.add("");
            lines.add("<yellow>They are offline and will be told when they return.</yellow>");
        }

        ConfirmMenu.open(menus, player,
                Text.mm("<dark_gray>Confirm payment</dark_gray>"),
                lines,
                "Send " + settings.money().format(amount),
                () -> send(player, target, amount),
                () -> notifications.info(player, "economy.pay.cancelled", Messages.of()));
    }

    /** Performs the payment off-thread and reports the outcome on the main thread. */
    private void send(Player player, PlayerLookup.Target target, long amount) {
        UUID from = player.getUniqueId();
        UUID to = target.uuid();

        scheduling.thenSync(
                scheduling.supplyAsync(() -> economy.pay(from, to, amount)),
                result -> report(player, target, amount, result),
                error -> notifications.error(player, "error.internal", Messages.of()));
    }

    private void report(Player player, PlayerLookup.Target target, long amount, EconomyResult result) {
        var money = economy.money();
        if (!result.isSuccess()) {
            switch (result.outcome()) {
                case INSUFFICIENT_FUNDS -> notifications.error(player, "economy.error.insufficient-funds",
                        Messages.of(
                                "needed", money.format(result.shortfall()),
                                "balance", money.format(result.newBalance())));
                case WOULD_EXCEED_MAX_BALANCE -> notifications.error(player, "economy.error.max-balance",
                        Messages.of("name", Text.escape(target.name())));
                case COOLDOWN_ACTIVE -> notifications.error(player, "economy.error.cooldown",
                        Messages.of("remaining", Durations.remaining(
                                Duration.ofMillis(economy.remainingCooldownMillis(player.getUniqueId())))));
                default -> notifications.error(player, result.messageKey(), Messages.of());
            }
            return;
        }

        notifications.success(player, "economy.pay.sent", Messages.of(
                "amount", money.format(amount),
                "name", Text.escape(target.name()),
                "balance", money.format(result.newBalance())));

        // The recipient is told whether or not they are online; offline messages
        // are queued and shown on their next join.
        notifications.notifyPlayer(to(target), "economy.pay.received", Messages.of(
                "amount", money.format(amount),
                "name", Text.escape(player.getName())));

        Player online = Bukkit.getPlayer(to(target));
        if (online != null && online.isOnline()) {
            notifications.sound(online, "notification");
        }
    }

    private static UUID to(PlayerLookup.Target target) {
        return target.uuid();
    }

    @Override
    public @NotNull Collection<String> suggest(@NotNull CommandSourceStack source, String[] args) {
        if (args.length <= 1) {
            String partial = args.length == 0 ? "" : args[0];
            Player requester = source.getSender() instanceof Player p ? p : null;
            return lookup.suggestOnline(partial, requester);
        }
        if (args.length == 2) {
            // Round numbers a player is likely to want, in major units.
            return List.of("100", "500", "1000", "5000");
        }
        return List.of();
    }

    @Override
    public boolean canUse(@NotNull CommandSender sender) {
        return permissions.has(sender, Permissions.ECONOMY_PAY);
    }

    @Override
    public @NotNull String permission() {
        return Permissions.ECONOMY_PAY;
    }
}
