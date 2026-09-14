package com.streakysmp.command;

import com.streakysmp.core.Scheduling;
import com.streakysmp.economy.AccountRepository;
import com.streakysmp.economy.EconomyService;
import com.streakysmp.gui.MenuManager;
import com.streakysmp.gui.ProfileMenu;
import com.streakysmp.notify.Messages;
import com.streakysmp.notify.NotificationService;
import com.streakysmp.permission.PermissionService;
import com.streakysmp.permission.Permissions;
import com.streakysmp.statistics.ProfileSnapshot;
import com.streakysmp.statistics.StatisticType;
import com.streakysmp.statistics.StatisticsService;
import com.streakysmp.util.Durations;
import com.streakysmp.util.Text;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@code /stats [player]} -- opens a profile, or prints one to console.
 */
public final class StatsCommand implements BasicCommand {

    private final StatisticsService statistics;
    private final EconomyService economy;
    private final AccountRepository accounts;
    private final PlayerLookup lookup;
    private final PermissionService permissions;
    private final NotificationService notifications;
    private final MenuManager menus;
    private final Scheduling scheduling;

    public StatsCommand(StatisticsService statistics,
                        EconomyService economy,
                        AccountRepository accounts,
                        PlayerLookup lookup,
                        PermissionService permissions,
                        NotificationService notifications,
                        MenuManager menus,
                        Scheduling scheduling) {
        this.statistics = statistics;
        this.economy = economy;
        this.accounts = accounts;
        this.lookup = lookup;
        this.permissions = permissions;
        this.notifications = notifications;
        this.menus = menus;
        this.scheduling = scheduling;
    }

    @Override
    public void execute(@NotNull CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        if (!permissions.require(sender, Permissions.STATS_VIEW)) {
            return;
        }

        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                notifications.error(sender, "error.player-only", Messages.of());
                return;
            }
            show(sender, player.getUniqueId(), player.getName());
            return;
        }

        String name = args[0];
        scheduling.thenSync(lookup.resolve(name), resolved -> {
            if (resolved.isEmpty()) {
                notifications.error(sender, "error.unknown-player", Messages.of("name", name));
                return;
            }
            show(sender, resolved.get().uuid(), resolved.get().name());
        }, error -> notifications.error(sender, "error.internal", Messages.of()));
    }

    private void show(CommandSender viewer, UUID subject, String subjectName) {
        scheduling.thenSync(
                scheduling.supplyAsync(() -> snapshot(subject, subjectName)),
                snapshot -> {
                    if (viewer instanceof Player player) {
                        new ProfileMenu(menus, player, economy, snapshot).open();
                    } else {
                        printToConsole(viewer, snapshot);
                    }
                },
                error -> notifications.error(viewer, "error.internal", Messages.of()));
    }

    /** Runs on a worker thread. */
    private ProfileSnapshot snapshot(UUID subject, String name) {
        Map<StatisticType, Long> values = statistics.allFor(subject);
        Map<StatisticType, Integer> ranks = new EnumMap<>(StatisticType.class);
        for (StatisticType type : List.of(StatisticType.KILLS, StatisticType.DEATHS,
                StatisticType.PLAYTIME, StatisticType.MOBS_KILLED)) {
            ranks.put(type, statistics.rankOf(subject, type));
        }
        return new ProfileSnapshot(
                subject,
                name,
                accounts.getBalance(subject),
                accounts.rankOf(subject),
                statistics.livePlaytimeSeconds(subject),
                values,
                ranks);
    }

    private void printToConsole(CommandSender viewer, ProfileSnapshot snapshot) {
        viewer.sendMessage(Text.mm("<aqua>" + Text.escape(snapshot.name()) + "</aqua>"));
        viewer.sendMessage(Text.mm("<gray>Balance:</gray> <white>"
                + economy.money().format(snapshot.balance()) + "</white>"));
        viewer.sendMessage(Text.mm("<gray>Playtime:</gray> <white>"
                + Durations.playtime(Duration.ofSeconds(snapshot.playtimeSeconds()), false) + "</white>"));
        viewer.sendMessage(Text.mm("<gray>Kills:</gray> <white>"
                + snapshot.statistic(StatisticType.KILLS) + "</white> <gray>Deaths:</gray> <white>"
                + snapshot.statistic(StatisticType.DEATHS) + "</white> <gray>K/D:</gray> <white>"
                + String.format(java.util.Locale.ROOT, "%.2f", snapshot.killDeathRatio()) + "</white>"));
        viewer.sendMessage(Text.mm("<gray>Mobs killed:</gray> <white>"
                + snapshot.statistic(StatisticType.MOBS_KILLED) + "</white>"));
    }

    @Override
    public @NotNull Collection<String> suggest(@NotNull CommandSourceStack source, String[] args) {
        if (args.length <= 1) {
            Player requester = source.getSender() instanceof Player p ? p : null;
            return lookup.suggestOnline(args.length == 0 ? "" : args[0], requester);
        }
        return List.of();
    }

    @Override
    public boolean canUse(@NotNull CommandSender sender) {
        return permissions.has(sender, Permissions.STATS_VIEW);
    }

    @Override
    public @NotNull String permission() {
        return Permissions.STATS_VIEW;
    }
}
