package com.streakysmp.command;

import com.streakysmp.core.Scheduling;
import com.streakysmp.leaderboard.Leaderboard;
import com.streakysmp.leaderboard.LeaderboardService;
import com.streakysmp.leaderboard.LeaderboardStat;
import com.streakysmp.log.AuditAction;
import com.streakysmp.log.AuditLog;
import com.streakysmp.notify.Messages;
import com.streakysmp.notify.NotificationService;
import com.streakysmp.permission.PermissionService;
import com.streakysmp.permission.Permissions;
import com.streakysmp.util.Text;
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
 * {@code /leaderboard create|remove|list|refresh}.
 *
 * <p>Creation places the board at the sender's feet, raised slightly so the text
 * floats at head height rather than clipping into the floor.
 */
public final class LeaderboardCommand implements BasicCommand {

    /** Roughly eye level above the block the operator is standing on. */
    private static final double PLACEMENT_HEIGHT_OFFSET = 2.0d;

    private static final List<String> SUBCOMMANDS = List.of("create", "remove", "list", "refresh");

    private final LeaderboardService leaderboards;
    private final PermissionService permissions;
    private final NotificationService notifications;
    private final AuditLog auditLog;
    private final Scheduling scheduling;

    public LeaderboardCommand(LeaderboardService leaderboards,
                              PermissionService permissions,
                              NotificationService notifications,
                              AuditLog auditLog,
                              Scheduling scheduling) {
        this.leaderboards = leaderboards;
        this.permissions = permissions;
        this.notifications = notifications;
        this.auditLog = auditLog;
        this.scheduling = scheduling;
    }

    @Override
    public void execute(@NotNull CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        if (!permissions.require(sender, Permissions.LEADERBOARD_ADMIN)) {
            return;
        }
        if (args.length == 0) {
            usage(sender);
            return;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "create" -> create(sender, args);
            case "remove", "delete" -> remove(sender, args);
            case "list" -> list(sender);
            case "refresh" -> {
                leaderboards.refreshAll();
                notifications.success(sender, "leaderboard.refreshed", Messages.of());
            }
            default -> usage(sender);
        }
    }

    private void create(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            notifications.error(sender, "error.player-only", Messages.of());
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(Text.mm(
                    "<gray>Usage: /leaderboard create [statistic] [id] [size] [title...]</gray>"));
            sender.sendMessage(Text.mm("<gray>Statistics: <white>" + statisticNames() + "</white></gray>"));
            return;
        }

        Optional<LeaderboardStat> stat = LeaderboardStat.byKey(args[1]);
        if (stat.isEmpty()) {
            notifications.error(sender, "leaderboard.error.unknown-stat",
                    Messages.of("name", args[1], "options", statisticNames()));
            return;
        }

        String id = args.length >= 3 ? args[2].toLowerCase(Locale.ROOT) : stat.get().key();
        if (!id.matches("[a-z0-9_-]{1,32}")) {
            notifications.error(sender, "leaderboard.error.bad-id", Messages.of("name", id));
            return;
        }

        int size = Leaderboard.MAX_SIZE / 3;
        if (args.length >= 4) {
            Optional<Integer> parsed = com.streakysmp.util.Numbers.parsePositiveInt(
                    args[3], Leaderboard.MIN_SIZE, Leaderboard.MAX_SIZE);
            if (parsed.isEmpty()) {
                notifications.error(sender, "leaderboard.error.bad-size",
                        Messages.of("min", String.valueOf(Leaderboard.MIN_SIZE),
                                "max", String.valueOf(Leaderboard.MAX_SIZE)));
                return;
            }
            size = parsed.get();
        }

        String title = null;
        if (args.length >= 5) {
            // Escape it: the title is operator input but still rendered through
            // MiniMessage alongside our own markup.
            title = Text.escape(String.join(" ", java.util.Arrays.copyOfRange(args, 4, args.length)));
        }

        Location location = player.getLocation().clone();
        location.add(0, PLACEMENT_HEIGHT_OFFSET, 0);

        final String finalId = id;
        final int finalSize = size;
        final String finalTitle = title;
        scheduling.thenSync(
                scheduling.supplyAsync(() -> leaderboards.create(
                        finalId, stat.get(), location, finalSize, finalTitle, player.getUniqueId())),
                created -> {
                    if (created.isEmpty()) {
                        notifications.error(sender, "leaderboard.error.id-taken",
                                Messages.of("name", finalId));
                        return;
                    }
                    auditLog.record(AuditAction.ADMIN_ACTION, player.getUniqueId(), player.getName(),
                            "Created leaderboard '" + finalId + "' (" + stat.get().key() + ")");
                    notifications.success(sender, "leaderboard.created",
                            Messages.of("name", finalId, "stat", stat.get().key()));
                    leaderboards.refreshAll();
                },
                error -> notifications.error(sender, "error.internal", Messages.of()));
    }

    private void remove(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Text.mm("<gray>Usage: /leaderboard remove [id]</gray>"));
            return;
        }
        String id = args[1].toLowerCase(Locale.ROOT);
        scheduling.thenSync(
                scheduling.supplyAsync(() -> leaderboards.remove(id)),
                removed -> {
                    if (removed) {
                        auditLog.record(AuditAction.ADMIN_ACTION,
                                sender instanceof Player p ? p.getUniqueId() : null,
                                sender.getName(), "Removed leaderboard '" + id + "'");
                        notifications.success(sender, "leaderboard.removed", Messages.of("name", id));
                    } else {
                        notifications.error(sender, "leaderboard.error.not-found",
                                Messages.of("name", id));
                    }
                },
                error -> notifications.error(sender, "error.internal", Messages.of()));
    }

    private void list(CommandSender sender) {
        scheduling.thenSync(
                scheduling.supplyAsync(leaderboards::all),
                boards -> {
                    if (boards.isEmpty()) {
                        sender.sendMessage(Text.mm("<gray>No leaderboards have been placed.</gray>"));
                        return;
                    }
                    sender.sendMessage(Text.mm("<aqua>Leaderboards (" + boards.size() + ")</aqua>"));
                    for (Leaderboard board : boards) {
                        boolean loaded = board.location().isPresent();
                        sender.sendMessage(Text.mm("<gray>- <white>" + board.id() + "</white> "
                                + "<dark_gray>(" + board.stat().key() + ", top " + board.size() + ")</dark_gray> "
                                + (loaded ? "<green>" : "<red>")
                                + board.worldName() + " "
                                + Math.round(board.x()) + " " + Math.round(board.y()) + " "
                                + Math.round(board.z())
                                + (loaded ? "</green>" : " (world not loaded)</red>")));
                    }
                },
                error -> notifications.error(sender, "error.internal", Messages.of()));
    }

    private void usage(CommandSender sender) {
        sender.sendMessage(Text.mm("<aqua>Leaderboards</aqua>"));
        sender.sendMessage(Text.mm("<gray>/leaderboard create [statistic] [id] [size] [title...]</gray>"));
        sender.sendMessage(Text.mm("<gray>/leaderboard remove [id]</gray>"));
        sender.sendMessage(Text.mm("<gray>/leaderboard list</gray>"));
        sender.sendMessage(Text.mm("<gray>/leaderboard refresh</gray>"));
        sender.sendMessage(Text.mm("<gray>Statistics: <white>" + statisticNames() + "</white></gray>"));
    }

    private static String statisticNames() {
        StringBuilder sb = new StringBuilder();
        for (LeaderboardStat stat : LeaderboardStat.values()) {
            if (!sb.isEmpty()) {
                sb.append(", ");
            }
            sb.append(stat.key());
        }
        return sb.toString();
    }

    @Override
    public @NotNull Collection<String> suggest(@NotNull CommandSourceStack source, String[] args) {
        if (args.length <= 1) {
            String partial = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            return SUBCOMMANDS.stream().filter(option -> option.startsWith(partial)).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("create")) {
            String partial = args[1].toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<>();
            for (LeaderboardStat stat : LeaderboardStat.values()) {
                if (stat.key().startsWith(partial)) {
                    out.add(stat.key());
                }
            }
            return out;
        }
        return List.of();
    }

    @Override
    public boolean canUse(@NotNull CommandSender sender) {
        return permissions.has(sender, Permissions.LEADERBOARD_ADMIN);
    }

    @Override
    public @NotNull String permission() {
        return Permissions.LEADERBOARD_ADMIN;
    }
}
