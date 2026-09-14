package com.streakysmp.command;

import com.streakysmp.core.Scheduling;
import com.streakysmp.economy.AccountRepository;
import com.streakysmp.economy.BalanceSnapshot;
import com.streakysmp.economy.EconomyService;
import com.streakysmp.economy.Transaction;
import com.streakysmp.economy.TransactionRepository;
import com.streakysmp.gui.BalanceMenu;
import com.streakysmp.gui.ChatInput;
import com.streakysmp.gui.MenuManager;
import com.streakysmp.notify.Messages;
import com.streakysmp.notify.NotificationService;
import com.streakysmp.permission.PermissionService;
import com.streakysmp.permission.Permissions;
import com.streakysmp.util.Text;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code /balance [player]} and {@code /balance gui}.
 *
 * <p>Chat output is the default because it is the fastest path to the one number
 * the player wanted. The GUI adds ranking and history.
 */
public final class BalanceCommand implements BasicCommand {

    private final EconomyService economy;
    private final AccountRepository accounts;
    private final TransactionRepository transactions;
    private final PlayerLookup lookup;
    private final PermissionService permissions;
    private final NotificationService notifications;
    private final MenuManager menus;
    private final ChatInput chatInput;
    private final Scheduling scheduling;

    public BalanceCommand(EconomyService economy,
                          AccountRepository accounts,
                          TransactionRepository transactions,
                          PlayerLookup lookup,
                          PermissionService permissions,
                          NotificationService notifications,
                          MenuManager menus,
                          ChatInput chatInput,
                          Scheduling scheduling) {
        this.economy = economy;
        this.accounts = accounts;
        this.transactions = transactions;
        this.lookup = lookup;
        this.permissions = permissions;
        this.notifications = notifications;
        this.menus = menus;
        this.chatInput = chatInput;
        this.scheduling = scheduling;
    }

    @Override
    public void execute(@NotNull CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        if (!permissions.require(sender, Permissions.ECONOMY_BALANCE)) {
            return;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("gui")) {
            if (!(sender instanceof Player player)) {
                notifications.error(sender, "error.player-only", Messages.of());
                return;
            }
            openGui(player, player.getUniqueId(), player.getName());
            return;
        }

        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                notifications.error(sender, "error.player-only", Messages.of());
                return;
            }
            showChat(sender, player.getUniqueId(), player.getName(), true);
            return;
        }

        // Looking at someone else's balance is a separate permission.
        if (!permissions.require(sender, Permissions.ECONOMY_BALANCE_OTHERS)) {
            return;
        }
        String targetName = args[0];
        scheduling.thenSync(lookup.resolve(targetName), resolved -> {
            if (resolved.isEmpty()) {
                notifications.error(sender, "error.unknown-player", Messages.of("name", targetName));
                return;
            }
            showChat(sender, resolved.get().uuid(), resolved.get().name(), false);
        }, error -> notifications.error(sender, "error.internal", Messages.of()));
    }

    private void showChat(CommandSender viewer, UUID subject, String subjectName, boolean self) {
        scheduling.thenSync(
                scheduling.supplyAsync(() -> snapshot(subject, subjectName, 3)),
                snapshot -> {
                    var money = economy.money();
                    if (self) {
                        notifications.info(viewer, "economy.balance.self", Messages.of(
                                "balance", money.format(snapshot.balance()),
                                "rank", String.valueOf(snapshot.rank()),
                                "total", String.valueOf(snapshot.accountCount())));
                    } else {
                        notifications.info(viewer, "economy.balance.other", Messages.of(
                                "name", Text.escape(snapshot.name()),
                                "balance", money.format(snapshot.balance()),
                                "rank", String.valueOf(snapshot.rank())));
                    }
                    for (Transaction entry : snapshot.recent()) {
                        viewer.sendMessage(Text.mm(describe(entry, subject)));
                    }
                },
                error -> notifications.error(viewer, "error.internal", Messages.of()));
    }

    private void openGui(Player player, UUID subject, String subjectName) {
        scheduling.thenSync(
                scheduling.supplyAsync(() -> snapshot(subject, subjectName,
                        economy.settings().historyPageSize())),
                snapshot -> new BalanceMenu(menus, chatInput, player, economy, snapshot,
                        transactions, accounts, scheduling).open(),
                error -> notifications.error(player, "error.internal", Messages.of()));
    }

    /** Runs on a worker thread. */
    private BalanceSnapshot snapshot(UUID subject, String name, int historyLimit) {
        return new BalanceSnapshot(
                subject,
                name,
                accounts.getBalance(subject),
                accounts.rankOf(subject),
                accounts.accountCount(),
                transactions.historyFor(subject, historyLimit, 0));
    }

    /** One-line summary of a ledger entry from the viewer's perspective. */
    private String describe(Transaction entry, UUID viewer) {
        long signed = entry.signedAmountFor(viewer);
        String colour = signed < 0 ? "red" : "green";
        String sign = signed < 0 ? "-" : "+";
        return "<dark_gray>  " + entry.type().displayName() + "</dark_gray> "
                + "<" + colour + ">" + sign + economy.money().format(Math.abs(signed))
                + "</" + colour + ">";
    }

    @Override
    public @NotNull Collection<String> suggest(@NotNull CommandSourceStack source, String[] args) {
        if (args.length <= 1) {
            String partial = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            Player requester = source.getSender() instanceof Player p ? p : null;
            List<String> options = new java.util.ArrayList<>();
            if ("gui".startsWith(partial)) {
                options.add("gui");
            }
            if (permissions.has(source.getSender(), Permissions.ECONOMY_BALANCE_OTHERS)) {
                options.addAll(lookup.suggestOnline(partial, requester));
            }
            return options;
        }
        return List.of();
    }

    @Override
    public boolean canUse(@NotNull CommandSender sender) {
        return permissions.has(sender, Permissions.ECONOMY_BALANCE);
    }

    @Override
    public @NotNull String permission() {
        return Permissions.ECONOMY_BALANCE;
    }

    /** Exposed so the profile GUI can reuse the same gathering logic. */
    public Optional<BalanceSnapshot> snapshotBlocking(UUID subject, String name, int historyLimit) {
        try {
            return Optional.of(snapshot(subject, name, historyLimit));
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
