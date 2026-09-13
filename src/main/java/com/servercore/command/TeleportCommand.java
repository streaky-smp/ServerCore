package com.servercore.command;

import com.servercore.core.Scheduling;
import com.servercore.gui.MenuManager;
import com.servercore.gui.TeleportRequestMenu;
import com.servercore.notify.Messages;
import com.servercore.notify.NotificationService;
import com.servercore.permission.PermissionService;
import com.servercore.permission.Permissions;
import com.servercore.teleport.TeleportRequest;
import com.servercore.teleport.TeleportService;
import com.servercore.util.Durations;
import com.servercore.util.Text;
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

/**
 * One command class serving {@code /tpa}, {@code /tpahere}, {@code /tpaccept},
 * {@code /tpdeny} and {@code /tpacancel}.
 *
 * <p>Registered five times with different modes rather than written five times.
 * The handling is nearly identical and duplicating it is how {@code /tpahere}
 * ends up teleporting the wrong player.
 */
public final class TeleportCommand implements BasicCommand {

    /** Which command this instance is serving. */
    public enum Mode {
        REQUEST_TO_TARGET,
        REQUEST_TO_SELF,
        ACCEPT,
        DENY,
        CANCEL,
        MENU
    }

    private final Mode mode;
    private final TeleportService teleports;
    private final PermissionService permissions;
    private final NotificationService notifications;
    private final MenuManager menus;
    private final Scheduling scheduling;

    public TeleportCommand(Mode mode,
                           TeleportService teleports,
                           PermissionService permissions,
                           NotificationService notifications,
                           MenuManager menus,
                           Scheduling scheduling) {
        this.mode = mode;
        this.teleports = teleports;
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
        if (!permissions.require(player, Permissions.TPA_USE)) {
            return;
        }
        if (!teleports.settings().enabled()) {
            notifications.error(player, "tpa.error.disabled", Messages.of());
            return;
        }

        switch (mode) {
            case REQUEST_TO_TARGET -> request(player, args, TeleportRequest.Direction.TO_TARGET);
            case REQUEST_TO_SELF -> request(player, args, TeleportRequest.Direction.TO_REQUESTER);
            case ACCEPT -> respond(player, args, true);
            case DENY -> respond(player, args, false);
            case CANCEL -> cancel(player, args);
            case MENU -> TeleportRequestMenu.openFor(menus, player, teleports,
                    notifications, scheduling);
        }
    }

    private void request(Player player, String[] args, TeleportRequest.Direction direction) {
        if (args.length < 1) {
            player.sendMessage(Text.mm("<gray>Usage: /"
                    + (direction == TeleportRequest.Direction.TO_TARGET ? "tpa" : "tpahere")
                    + " [player]</gray>"));
            return;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        // Respect vanish: a hidden player should not be discoverable by /tpa.
        if (target == null || !player.canSee(target)) {
            notifications.error(player, "error.unknown-player", Messages.of("name", args[0]));
            return;
        }

        TeleportService.Outcome outcome = teleports.request(player, target, direction);
        switch (outcome) {
            case SUCCESS -> {
                notifications.success(player, "tpa.sent",
                        Messages.of("name", Text.escape(target.getName())));
                notifications.info(target,
                        direction == TeleportRequest.Direction.TO_TARGET
                                ? "tpa.received" : "tpa.received-here",
                        Messages.of(
                                "name", Text.escape(player.getName()),
                                "expiry", Durations.remaining(
                                        teleports.settings().requestExpiry())));
            }
            case SELF -> notifications.error(player, "tpa.error.self", Messages.of());
            case ALREADY_PENDING ->
                    notifications.error(player, "tpa.error.already-pending", Messages.of());
            case TOO_MANY_PENDING ->
                    notifications.error(player, "tpa.error.too-many", Messages.of());
            case COOLDOWN -> notifications.error(player, "tpa.error.cooldown",
                    Messages.of("remaining",
                            teleports.formattedCooldown(player.getUniqueId())));
            case DISABLED -> notifications.error(player, "tpa.error.disabled", Messages.of());
            default -> notifications.error(player, "error.internal", Messages.of());
        }
    }

    /**
     * Accepts or denies.
     *
     * <p>With no argument the oldest pending request is used, which is what a
     * player expects when only one is waiting. Naming a player is how you pick
     * between several.
     */
    private void respond(Player player, String[] args, boolean accept) {
        Optional<TeleportRequest> request = args.length >= 1
                ? findFrom(player, args[0])
                : teleports.oldestFor(player.getUniqueId());

        if (request.isEmpty()) {
            notifications.error(player, "tpa.error.no-request", Messages.of());
            return;
        }
        TeleportRequest pending = request.get();
        String requesterName = nameOf(pending.requester());

        if (!accept) {
            teleports.deny(pending);
            notifications.info(player, "tpa.denied", Messages.of("name", requesterName));
            notifications.notifyPlayer(pending.requester(), "tpa.denied-sender",
                    Messages.of("name", Text.escape(player.getName())));
            return;
        }

        TeleportService.Outcome outcome = teleports.accept(player, pending);
        switch (outcome) {
            case SUCCESS -> {
                notifications.success(player, "tpa.accepted",
                        Messages.of("name", requesterName));
                notifications.notifyPlayer(pending.requester(), "tpa.accepted-sender",
                        Messages.of("name", Text.escape(player.getName())));
            }
            case NO_REQUEST -> notifications.error(player, "tpa.error.no-request", Messages.of());
            case TARGET_OFFLINE ->
                    notifications.error(player, "tpa.error.offline", Messages.of());
            case UNSAFE_DESTINATION ->
                    notifications.error(player, "tpa.unsafe", Messages.of());
            case ALREADY_TELEPORTING ->
                    notifications.error(player, "tpa.error.already-teleporting", Messages.of());
            default -> notifications.error(player, "error.internal", Messages.of());
        }
    }

    private void cancel(Player player, String[] args) {
        if (args.length < 1) {
            player.sendMessage(Text.mm("<gray>Usage: /tpacancel [player]</gray>"));
            return;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            notifications.error(player, "error.unknown-player", Messages.of("name", args[0]));
            return;
        }
        if (teleports.cancelFrom(player.getUniqueId(), target.getUniqueId())) {
            notifications.success(player, "tpa.cancelled",
                    Messages.of("name", Text.escape(target.getName())));
        } else {
            notifications.error(player, "tpa.error.no-request", Messages.of());
        }
    }

    private Optional<TeleportRequest> findFrom(Player target, String requesterName) {
        Player requester = Bukkit.getPlayerExact(requesterName);
        if (requester == null) {
            return Optional.empty();
        }
        return teleports.pendingFor(target.getUniqueId()).stream()
                .filter(request -> request.requester().equals(requester.getUniqueId()))
                .findFirst();
    }

    private static String nameOf(java.util.UUID id) {
        Player online = Bukkit.getPlayer(id);
        return Text.escape(online != null ? online.getName()
                : String.valueOf(Bukkit.getOfflinePlayer(id).getName()));
    }

    @Override
    public @NotNull Collection<String> suggest(@NotNull CommandSourceStack source, String[] args) {
        if (args.length > 1 || !(source.getSender() instanceof Player player)) {
            return List.of();
        }
        String partial = args.length == 0 ? "" : args[0].toLowerCase(java.util.Locale.ROOT);

        // Accept and deny suggest only the players who actually have a request
        // pending, which is far more useful than the whole online list.
        if (mode == Mode.ACCEPT || mode == Mode.DENY) {
            return teleports.pendingFor(player.getUniqueId()).stream()
                    .map(request -> nameOf(request.requester()))
                    .filter(name -> name.toLowerCase(java.util.Locale.ROOT).startsWith(partial))
                    .toList();
        }

        List<String> names = new java.util.ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.equals(player) || !player.canSee(online)) {
                continue;
            }
            if (online.getName().toLowerCase(java.util.Locale.ROOT).startsWith(partial)) {
                names.add(online.getName());
            }
        }
        return names;
    }

    @Override
    public boolean canUse(@NotNull CommandSender sender) {
        return permissions.has(sender, Permissions.TPA_USE);
    }

    @Override
    public @NotNull String permission() {
        return Permissions.TPA_USE;
    }

    /** Exposed so the menu can format the same expiry text. */
    public static String expiryText(Duration expiry) {
        return Durations.remaining(expiry);
    }
}
