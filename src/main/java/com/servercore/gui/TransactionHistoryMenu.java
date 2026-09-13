package com.servercore.gui;

import com.servercore.core.Scheduling;
import com.servercore.economy.EconomyService;
import com.servercore.economy.Transaction;
import com.servercore.economy.TransactionRepository;
import com.servercore.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Paged transaction history for one player.
 *
 * <p>Loads a bounded window rather than the whole ledger. A long-lived server
 * accumulates hundreds of thousands of entries, and nobody pages past the first
 * few screens; an unbounded load would be a memory spike for no benefit.
 */
public final class TransactionHistoryMenu extends PaginatedMenu<Transaction> {

    /** How far back the history screen can reach in one load. */
    private static final int WINDOW = 500;

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm").withLocale(Locale.ROOT);

    private final EconomyService economy;
    private final TransactionRepository transactions;
    private final Scheduling scheduling;
    private final UUID subject;
    private final String subjectName;

    private List<Transaction> loaded = List.of();

    public TransactionHistoryMenu(MenuManager menus,
                                  ChatInput chatInput,
                                  Player viewer,
                                  EconomyService economy,
                                  TransactionRepository transactions,
                                  UUID subject,
                                  String subjectName,
                                  Scheduling scheduling) {
        super(menus, chatInput, viewer,
                Text.mm("<dark_gray>History</dark_gray>"), 6);
        this.economy = economy;
        this.transactions = transactions;
        this.scheduling = scheduling;
        this.subject = subject;
        this.subjectName = subjectName;
    }

    /** Loads off-thread, then opens on the main thread. */
    public void openAsync() {
        scheduling.thenSync(
                scheduling.supplyAsync(() -> transactions.historyFor(subject, WINDOW, 0)),
                entries -> {
                    loaded = entries;
                    open();
                },
                error -> viewer.sendMessage(
                        Text.mm("<red>Could not load transaction history.</red>")));
    }

    @Override
    protected List<Transaction> source() {
        return loaded;
    }

    @Override
    protected boolean supportsSearch() {
        return true;
    }

    @Override
    protected boolean matches(Transaction entry, String lowercaseQuery) {
        if (entry.type().displayName().toLowerCase(Locale.ROOT).contains(lowercaseQuery)) {
            return true;
        }
        String description = entry.description();
        return description != null && description.toLowerCase(Locale.ROOT).contains(lowercaseQuery);
    }

    @Override
    protected List<SortOption<Transaction>> sortOptions() {
        return List.of(
                new SortOption<>("Newest first",
                        Comparator.comparingLong(Transaction::createdAt).reversed()),
                new SortOption<>("Oldest first",
                        Comparator.comparingLong(Transaction::createdAt)),
                new SortOption<>("Largest first",
                        Comparator.comparingLong((Transaction t) ->
                                Math.abs(t.signedAmountFor(subject))).reversed()));
    }

    @Override
    protected Button renderEntry(Transaction entry) {
        var money = economy.money();
        long signed = entry.signedAmountFor(subject);
        boolean outgoing = signed < 0;

        // Colour and icon both encode direction, because colour alone is not
        // enough for a colour-blind player and lore text is the only reliable
        // channel on a small Bedrock screen.
        Material icon = outgoing ? Material.RED_STAINED_GLASS_PANE : Material.LIME_STAINED_GLASS_PANE;
        String colour = outgoing ? "red" : "green";
        String direction = outgoing ? "Sent" : "Received";

        List<String> lore = new ArrayList<>();
        lore.add("<gray>" + direction + ":</gray> <" + colour + ">"
                + money.format(Math.abs(signed)) + "</" + colour + ">");
        if (entry.fee() > 0 && outgoing) {
            lore.add("<dark_gray>  includes " + money.format(entry.fee()) + " fee</dark_gray>");
        }
        lore.add("<gray>Type:</gray> <white>" + entry.type().displayName() + "</white>");
        if (entry.description() != null && !entry.description().isBlank()) {
            lore.add("<gray>Note:</gray> <white>" + Text.escape(entry.description()) + "</white>");
        }
        lore.add("<gray>When:</gray> <white>" + formatWhen(entry.createdAt()) + "</white>");
        lore.add("<dark_gray>Reference #" + entry.id() + "</dark_gray>");

        return Button.display(ItemBuilder.of(icon)
                .name("<" + colour + ">" + (outgoing ? "-" : "+")
                        + money.format(Math.abs(signed)) + "</" + colour + ">")
                .lore(lore)
                .clean().build());
    }

    @Override
    protected void decorate() {
        set(rows() - 1, 5, Button.display(ItemBuilder.of(Material.PLAYER_HEAD)
                .name("<white>" + Text.escape(subjectName) + "</white>")
                .lore("<gray>Showing the most recent " + WINDOW + " entries.</gray>")
                .clean().build()));
    }

    /**
     * Renders a timestamp as a relative age for recent entries and an absolute
     * date for older ones, which is how a player actually thinks about it.
     */
    private static String formatWhen(long epochMillis) {
        Duration age = Duration.between(Instant.ofEpochMilli(epochMillis), Instant.now());
        if (age.toDays() < 1) {
            long hours = age.toHours();
            if (hours < 1) {
                long minutes = Math.max(1, age.toMinutes());
                return minutes + " minute" + (minutes == 1 ? "" : "s") + " ago";
            }
            return hours + " hour" + (hours == 1 ? "" : "s") + " ago";
        }
        if (age.toDays() < 7) {
            long days = age.toDays();
            return days + " day" + (days == 1 ? "" : "s") + " ago";
        }
        return TIMESTAMP.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()));
    }
}
