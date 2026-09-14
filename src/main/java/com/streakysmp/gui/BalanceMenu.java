package com.streakysmp.gui;

import com.streakysmp.core.Scheduling;
import com.streakysmp.economy.AccountRepository;
import com.streakysmp.economy.BalanceSnapshot;
import com.streakysmp.economy.EconomyService;
import com.streakysmp.economy.TransactionRepository;
import com.streakysmp.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * The GUI equivalent of {@code /balance}.
 *
 * <p>Renders entirely from a {@link BalanceSnapshot} captured before the menu
 * opened, so drawing touches no database.
 */
public final class BalanceMenu extends Menu {

    private final EconomyService economy;
    private final BalanceSnapshot snapshot;
    private final TransactionRepository transactions;
    private final AccountRepository accounts;
    private final ChatInput chatInput;
    private final Scheduling scheduling;

    public BalanceMenu(MenuManager menus,
                       ChatInput chatInput,
                       Player viewer,
                       EconomyService economy,
                       BalanceSnapshot snapshot,
                       TransactionRepository transactions,
                       AccountRepository accounts,
                       Scheduling scheduling) {
        super(menus, viewer, Text.mm("<dark_gray>Balance</dark_gray>"), 3);
        this.chatInput = chatInput;
        this.economy = economy;
        this.snapshot = snapshot;
        this.transactions = transactions;
        this.accounts = accounts;
        this.scheduling = scheduling;
    }

    @Override
    protected void build() {
        fillEmpty(Material.GRAY_STAINED_GLASS_PANE);

        set(1, 4, Button.display(ItemBuilder.head(snapshot.uuid())
                .name("<white>" + Text.escape(snapshot.name()) + "</white>")
                .lore(summaryLore())
                .clean().build()));

        set(1, 2, Button.of(ItemBuilder.of(Material.WRITABLE_BOOK)
                .name("<aqua>Transaction history</aqua>")
                .lore("<gray>Every payment in and out.</gray>",
                        "<gray>Click to open.</gray>")
                .clean().build(), click -> {
            TransactionHistoryMenu history = new TransactionHistoryMenu(menus, chatInput, click.player(),
                    economy, transactions, snapshot.uuid(), snapshot.name(), scheduling);
            history.withParent(this);
            history.openAsync();
        }));

        set(1, 6, Button.of(ItemBuilder.of(Material.GOLD_INGOT)
                .name("<gold>Richest players</gold>")
                .lore("<gray>See the balance leaderboard.</gray>",
                        "<gray>Click to open.</gray>")
                .clean().build(), click -> {
            TopBalancesMenu top = new TopBalancesMenu(menus, click.player(), economy, accounts, scheduling);
            top.withParent(this);
            top.openAsync();
        }));

        set(2, 8, Button.of(ItemBuilder.of(Material.BARRIER)
                .name("<red>Close</red>")
                .clean().build(), ClickContext::close));
    }

    private List<String> summaryLore() {
        var money = economy.money();
        List<String> lore = new ArrayList<>();
        lore.add("<gray>Balance:</gray> <green>" + money.format(snapshot.balance()) + "</green>");
        if (snapshot.rank() > 0) {
            lore.add("<gray>Rank:</gray> <white>#" + snapshot.rank()
                    + "</white> <dark_gray>of " + snapshot.accountCount() + "</dark_gray>");
        }
        lore.add("");
        lore.add("<dark_gray>Recent activity:</dark_gray>");
        if (snapshot.recent().isEmpty()) {
            lore.add("<dark_gray>  nothing yet</dark_gray>");
        } else {
            snapshot.recent().stream().limit(3).forEach(entry -> {
                long signed = entry.signedAmountFor(snapshot.uuid());
                String colour = signed < 0 ? "red" : "green";
                lore.add("<dark_gray>  " + entry.type().displayName() + "</dark_gray> <" + colour + ">"
                        + (signed < 0 ? "-" : "+") + money.format(Math.abs(signed))
                        + "</" + colour + ">");
            });
        }
        return lore;
    }
}
