package com.servercore.gui;

import com.servercore.core.Scheduling;
import com.servercore.economy.AccountRepository;
import com.servercore.economy.EconomyService;
import com.servercore.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * The richest accounts on the server.
 *
 * <p>A read-only view; the general leaderboard framework arrives in Phase 5. This
 * exists now because {@code /balance} shows a player their rank, and a rank with
 * no way to see the table above you is a dead end.
 */
public final class TopBalancesMenu extends PaginatedMenu<TopBalancesMenu.Ranked> {

    /** Deep enough to be interesting, shallow enough to stay one quick query. */
    private static final int LIMIT = 90;

    /**
     * An entry with its position baked in.
     *
     * <p>Position is captured at load time rather than derived from list index at
     * render time, so it stays correct no matter how the list is later filtered.
     */
    public record Ranked(int position, AccountRepository.BalanceEntry entry) {
    }

    private final EconomyService economy;
    private final AccountRepository accounts;
    private final Scheduling scheduling;

    private List<Ranked> loaded = List.of();

    public TopBalancesMenu(MenuManager menus,
                           Player viewer,
                           EconomyService economy,
                           AccountRepository accounts,
                           Scheduling scheduling) {
        super(menus, null, viewer, Text.mm("<dark_gray>Richest players</dark_gray>"), 6);
        this.economy = economy;
        this.accounts = accounts;
        this.scheduling = scheduling;
    }

    public void openAsync() {
        scheduling.thenSync(
                scheduling.supplyAsync(() -> rank(accounts.topBalances(LIMIT))),
                entries -> {
                    loaded = entries;
                    open();
                },
                error -> viewer.sendMessage(Text.mm("<red>Could not load the leaderboard.</red>")));
    }

    private static List<Ranked> rank(List<AccountRepository.BalanceEntry> entries) {
        List<Ranked> ranked = new ArrayList<>(entries.size());
        for (int i = 0; i < entries.size(); i++) {
            ranked.add(new Ranked(i + 1, entries.get(i)));
        }
        return List.copyOf(ranked);
    }

    @Override
    protected List<Ranked> source() {
        return loaded;
    }

    @Override
    protected Button renderEntry(Ranked ranked) {
        AccountRepository.BalanceEntry entry = ranked.entry();
        boolean isViewer = entry.uuid().equals(viewer.getUniqueId());

        List<String> lore = new ArrayList<>();
        lore.add("<gray>Balance:</gray> <green>" + economy.money().format(entry.balance()) + "</green>");
        if (isViewer) {
            lore.add("<aqua>This is you.</aqua>");
        }

        return Button.display(ItemBuilder.head(entry.uuid())
                .name(rankLabel(ranked.position()) + " <white>" + Text.escape(entry.name()) + "</white>")
                .lore(lore)
                .glow(isViewer)
                .clean().build());
    }

    /** Gold, silver and bronze for the podium; plain for everyone below. */
    private static String rankLabel(int position) {
        String colour = switch (position) {
            case 1 -> "gold";
            case 2 -> "gray";
            case 3 -> "dark_red";
            default -> "dark_gray";
        };
        return "<" + colour + ">#" + position + "</" + colour + ">";
    }

    @Override
    protected void decorate() {
        set(rows() - 1, 5, Button.display(ItemBuilder.of(Material.GOLD_BLOCK)
                .name("<gold>Top " + LIMIT + "</gold>")
                .lore("<gray>Ranked by current balance.</gray>")
                .clean().build()));
    }
}
