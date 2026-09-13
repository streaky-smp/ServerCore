package com.servercore.gui;

import com.servercore.core.Scheduling;
import com.servercore.notify.Messages;
import com.servercore.notify.NotificationService;
import com.servercore.teleport.TeleportRequest;
import com.servercore.teleport.TeleportService;
import com.servercore.util.Durations;
import com.servercore.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Pending teleport requests, with accept and deny buttons.
 *
 * <p>The GUI equivalent of {@code /tpaccept} and {@code /tpdeny}. It matters more
 * on Bedrock than on Java: typing a command with a player's exact name on a touch
 * keyboard is genuinely awkward, and two buttons are not.
 *
 * <p>Accept and deny sit at opposite ends of each row so a mistimed tap lands on
 * neither.
 */
public final class TeleportRequestMenu extends Menu {

    /** Rows of the menu that hold requests; one request per row. */
    private static final int MAX_SHOWN = 4;

    private final TeleportService teleports;
    private final NotificationService notifications;
    private final Scheduling scheduling;

    private List<TeleportRequest> pending = List.of();

    public TeleportRequestMenu(MenuManager menus,
                               Player viewer,
                               TeleportService teleports,
                               NotificationService notifications,
                               Scheduling scheduling) {
        super(menus, viewer, Text.mm("<dark_gray>Teleport Requests</dark_gray>"), 6);
        this.teleports = teleports;
        this.notifications = notifications;
        this.scheduling = scheduling;
    }

    public static void openFor(MenuManager menus,
                               Player player,
                               TeleportService teleports,
                               NotificationService notifications,
                               Scheduling scheduling) {
        new TeleportRequestMenu(menus, player, teleports, notifications, scheduling).open();
    }

    @Override
    public void open() {
        // Requests live in memory, so this is a cheap main-thread read.
        pending = teleports.pendingFor(viewer.getUniqueId());
        super.open();
    }

    @Override
    protected void build() {
        fillEmpty(Material.GRAY_STAINED_GLASS_PANE);

        if (pending.isEmpty()) {
            set(2, 4, Button.display(ItemBuilder.of(Material.BARRIER)
                    .name("<gray>No pending requests</gray>")
                    .lore("<gray>Requests appear here when somebody</gray>",
                            "<gray>asks to teleport.</gray>")
                    .clean().build()));
            navigation();
            return;
        }

        long now = System.currentTimeMillis();
        int shown = Math.min(pending.size(), MAX_SHOWN);
        for (int i = 0; i < shown; i++) {
            TeleportRequest request = pending.get(i);
            int row = i + 1;

            set(row, 1, Button.display(ItemBuilder.head(request.requester())
                    .name("<white>" + nameOf(request.requester()) + "</white>")
                    .lore(describe(request, now))
                    .clean().build()));

            set(row, 3, Button.of(ItemBuilder.of(Material.LIME_CONCRETE)
                    .name("<green><bold>Accept</bold></green>")
                    .lore("<gray>Start the teleport.</gray>")
                    .clean().build(), click -> accept(click, request)));

            set(row, 7, Button.of(ItemBuilder.of(Material.RED_CONCRETE)
                    .name("<red><bold>Deny</bold></red>")
                    .lore("<gray>Dismiss this request.</gray>")
                    .clean().build(), click -> deny(click, request)));
        }

        if (pending.size() > MAX_SHOWN) {
            set(5, 4, Button.display(ItemBuilder.of(Material.PAPER)
                    .name("<gray>" + (pending.size() - MAX_SHOWN) + " more waiting</gray>")
                    .lore("<gray>Answer these first, or use</gray>",
                            "<white>/tpaccept [player]</white>")
                    .clean().build()));
        }
        navigation();
    }

    private List<String> describe(TeleportRequest request, long now) {
        List<String> lore = new ArrayList<>();
        lore.add(request.direction() == TeleportRequest.Direction.TO_TARGET
                ? "<gray>Wants to teleport <white>to you</white>.</gray>"
                : "<gray>Wants <white>you</white> to teleport to them.</gray>");
        lore.add("<gray>Expires in:</gray> <white>"
                + Durations.remaining(request.remaining(now)) + "</white>");

        var settings = teleports.settings();
        if (settings.hasWarmUp()) {
            lore.add("");
            lore.add("<dark_gray>Warm-up: " + settings.warmUp().toSeconds() + "s"
                    + (settings.cancelOnMove() ? ", cancelled by moving" : "") + "</dark_gray>");
        }
        return lore;
    }

    private void accept(ClickContext click, TeleportRequest request) {
        TeleportService.Outcome outcome = teleports.accept(click.player(), request);
        if (outcome == TeleportService.Outcome.SUCCESS) {
            notifications.success(click.player(), "tpa.accepted",
                    Messages.of("name", nameOf(request.requester())));
            notifications.notifyPlayer(request.requester(), "tpa.accepted-sender",
                    Messages.of("name", Text.escape(click.player().getName())));
            click.close();
            return;
        }
        notifications.error(click.player(), switch (outcome) {
            case NO_REQUEST -> "tpa.error.no-request";
            case TARGET_OFFLINE -> "tpa.error.offline";
            case UNSAFE_DESTINATION -> "tpa.unsafe";
            case ALREADY_TELEPORTING -> "tpa.error.already-teleporting";
            default -> "error.internal";
        }, Messages.of());
        open();
    }

    private void deny(ClickContext click, TeleportRequest request) {
        teleports.deny(request);
        notifications.info(click.player(), "tpa.denied",
                Messages.of("name", nameOf(request.requester())));
        notifications.notifyPlayer(request.requester(), "tpa.denied-sender",
                Messages.of("name", Text.escape(click.player().getName())));
        open();
    }

    private void navigation() {
        set(5, 8, Button.of(ItemBuilder.of(Material.BARRIER)
                .name("<red>Close</red>").clean().build(), ClickContext::close));
    }

    private static String nameOf(UUID id) {
        Player online = Bukkit.getPlayer(id);
        return Text.escape(online != null ? online.getName()
                : String.valueOf(Bukkit.getOfflinePlayer(id).getName()));
    }
}
