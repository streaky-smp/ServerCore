package com.streakysmp.command;

import com.streakysmp.core.Scheduling;
import com.streakysmp.economy.AccountRepository;
import com.streakysmp.economy.EconomyResult;
import com.streakysmp.economy.EconomyService;
import com.streakysmp.economy.TransactionType;
import com.streakysmp.economy.TransactionRepository;
import com.streakysmp.notify.Messages;
import com.streakysmp.notify.NotificationService;
import com.streakysmp.permission.PermissionService;
import com.streakysmp.permission.Permissions;
import com.streakysmp.util.Numbers;
import com.streakysmp.util.Text;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * {@code /eco give|take|set|info} -- administrative economy control.
 *
 * <p>Every adjustment made here is written to the ledger as an {@code ADMIN}
 * transaction and to the audit log with the administrator's identity. Economy
 * reports exclude admin flow from organic figures, so a server owner topping up a
 * player does not silently look like the economy generating money.
 */
public final class EconomyAdminCommand implements BasicCommand {

    private static final List<String> SUBCOMMANDS = List.of("give", "take", "set", "info");

    private final EconomyService economy;
    private final AccountRepository accounts;
    private final TransactionRepository transactions;
    private final PlayerLookup lookup;
    private final PermissionService permissions;
    private final NotificationService notifications;
    private final Scheduling scheduling;

    public EconomyAdminCommand(EconomyService economy,
                               AccountRepository accounts,
                               TransactionRepository transactions,
                               PlayerLookup lookup,
                               PermissionService permissions,
                               NotificationService notifications,
                               Scheduling scheduling) {
        this.economy = economy;
        this.accounts = accounts;
        this.transactions = transactions;
        this.lookup = lookup;
        this.permissions = permissions;
        this.notifications = notifications;
        this.scheduling = scheduling;
    }

    @Override
    public void execute(@NotNull CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        if (!permissions.require(sender, Permissions.ECONOMY_ADMIN)) {
            return;
        }
        if (args.length == 0) {
            usage(sender);
            return;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("info")) {
            info(sender);
            return;
        }
        if (!SUBCOMMANDS.contains(sub) || args.length != 3) {
            usage(sender);
            return;
        }

        var money = economy.money();
        var parsed = Numbers.parseMoney(args[2], money.fractionDigits());
        if (parsed.isEmpty()) {
            notifications.error(sender, "error.invalid-amount", Messages.of("input", args[2]));
            return;
        }
        long amount = parsed.get();
        if (amount <= 0 && !sub.equals("set")) {
            notifications.error(sender, "error.invalid-amount", Messages.of("input", args[2]));
            return;
        }

        String targetName = args[1];
        UUID actor = sender instanceof Player player ? player.getUniqueId() : null;
        String actorName = sender.getName();

        scheduling.thenSync(lookup.resolve(targetName), resolved -> {
            if (resolved.isEmpty()) {
                notifications.error(sender, "error.unknown-player", Messages.of("name", targetName));
                return;
            }
            PlayerLookup.Target target = resolved.get();
            apply(sender, sub, target, amount, actor, actorName);
        }, error -> notifications.error(sender, "error.internal", Messages.of()));
    }

    private void apply(CommandSender sender, String sub, PlayerLookup.Target target,
                       long amount, UUID actor, String actorName) {
        String reason = "By " + actorName;

        scheduling.thenSync(scheduling.supplyAsync(() -> switch (sub) {
            case "give" -> economy.deposit(target.uuid(), amount, TransactionType.ADMIN, reason);
            case "take" -> economy.withdraw(target.uuid(), amount, TransactionType.ADMIN, reason);
            case "set" -> economy.setBalance(target.uuid(), amount, actor, reason);
            default -> EconomyResult.failure(EconomyResult.Outcome.FAILED, 0L);
        }), result -> {
            var money = economy.money();
            if (!result.isSuccess()) {
                if (result.outcome() == EconomyResult.Outcome.INSUFFICIENT_FUNDS) {
                    notifications.error(sender, "economy.admin.insufficient", Messages.of(
                            "name", Text.escape(target.name()),
                            "balance", money.format(result.newBalance())));
                } else {
                    notifications.error(sender, result.messageKey(), Messages.of());
                }
                return;
            }
            notifications.success(sender, "economy.admin.applied", Messages.of(
                    "action", sub,
                    "amount", money.format(amount),
                    "name", Text.escape(target.name()),
                    "balance", money.format(result.newBalance())));

            // Tell the player their balance changed; discovering it silently is
            // how support tickets start.
            notifications.notifyPlayer(target.uuid(), "economy.admin.notified", Messages.of(
                    "balance", money.format(result.newBalance())));
        }, error -> notifications.error(sender, "error.internal", Messages.of()));
    }

    /** Economy health: supply, accounts, and sources against sinks. */
    private void info(CommandSender sender) {
        long since = System.currentTimeMillis() - Duration.ofDays(7).toMillis();
        scheduling.thenSync(scheduling.supplyAsync(() -> new EconomyOverview(
                accounts.totalSupply(),
                accounts.accountCount(),
                transactions.volumeByTypeSince(since))
        ), overview -> {
            var money = economy.money();
            sender.sendMessage(Text.mm("<gray><strikethrough>          </strikethrough></gray> "
                    + "<aqua>Economy</aqua> <gray><strikethrough>          </strikethrough></gray>"));
            sender.sendMessage(Text.mm("<gray>Total supply:</gray> <green>"
                    + money.format(overview.totalSupply()) + "</green>"));
            sender.sendMessage(Text.mm("<gray>Accounts:</gray> <white>"
                    + overview.accountCount() + "</white>"));
            if (overview.accountCount() > 0) {
                sender.sendMessage(Text.mm("<gray>Average:</gray> <white>"
                        + money.format(overview.totalSupply() / overview.accountCount()) + "</white>"));
            }

            long created = sumFlow(overview.volume(), TransactionType.Flow.SOURCE);
            long destroyed = sumFlow(overview.volume(), TransactionType.Flow.SINK);
            sender.sendMessage(Text.mm("<dark_gray>Last 7 days:</dark_gray>"));
            sender.sendMessage(Text.mm("  <gray>Created:</gray> <green>+"
                    + money.format(created) + "</green>"));
            sender.sendMessage(Text.mm("  <gray>Destroyed:</gray> <red>-"
                    + money.format(destroyed) + "</red>"));

            long net = created - destroyed;
            String verdict = net > 0
                    ? "<yellow>inflating</yellow>"
                    : net < 0 ? "<aqua>deflating</aqua>" : "<green>balanced</green>";
            sender.sendMessage(Text.mm("  <gray>Net:</gray> " + verdict
                    + " <dark_gray>(" + (net >= 0 ? "+" : "-")
                    + money.format(Math.abs(net)) + ")</dark_gray>"));
        }, error -> notifications.error(sender, "error.internal", Messages.of()));
    }

    private record EconomyOverview(long totalSupply, int accountCount,
                                   Map<TransactionType, Long> volume) {
    }

    private static long sumFlow(Map<TransactionType, Long> volume, TransactionType.Flow flow) {
        long total = 0L;
        for (Map.Entry<TransactionType, Long> entry : volume.entrySet()) {
            if (entry.getKey().flow() == flow) {
                total += entry.getValue();
            }
        }
        return total;
    }

    private void usage(CommandSender sender) {
        // Square brackets, not angle brackets: MiniMessage would treat <player>
        // as a tag and swallow it.
        sender.sendMessage(Text.mm("<aqua>Economy administration</aqua>"));
        sender.sendMessage(Text.mm("<gray>/eco give [player] [amount]</gray>"));
        sender.sendMessage(Text.mm("<gray>/eco take [player] [amount]</gray>"));
        sender.sendMessage(Text.mm("<gray>/eco set [player] [amount]</gray>"));
        sender.sendMessage(Text.mm("<gray>/eco info</gray> <dark_gray>-</dark_gray> "
                + "<white>supply, accounts, sources vs sinks</white>"));
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
            return out;
        }
        if (args.length == 2 && !args[0].equalsIgnoreCase("info")) {
            Player requester = source.getSender() instanceof Player p ? p : null;
            return lookup.suggestOnline(args[1], requester);
        }
        return List.of();
    }

    @Override
    public boolean canUse(@NotNull CommandSender sender) {
        return permissions.has(sender, Permissions.ECONOMY_ADMIN);
    }

    @Override
    public @NotNull String permission() {
        return Permissions.ECONOMY_ADMIN;
    }
}
