package com.servercore.command;

import com.servercore.core.Scheduling;
import com.servercore.economy.EconomyService;
import com.servercore.gui.ChatInput;
import com.servercore.gui.MenuManager;
import com.servercore.gui.PlayerShopDirectoryMenu;
import com.servercore.gui.PlayerShopManageMenu;
import com.servercore.notify.Messages;
import com.servercore.notify.NotificationService;
import com.servercore.permission.PermissionService;
import com.servercore.permission.Permissions;
import com.servercore.playershop.PlayerShop;
import com.servercore.playershop.PlayerShopService;
import com.servercore.util.Text;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * {@code /pshop} -- create and manage player shops, and browse the directory.
 */
public final class PlayerShopCommand implements BasicCommand {

    private static final List<String> SUBCOMMANDS =
            List.of("create", "list", "directory", "manage", "delete", "rename", "help");

    /** How far a player can be from the block they are designating as their shop. */
    private static final int TARGET_RANGE = 6;

    private final PlayerShopService shops;
    private final EconomyService economy;
    private final PermissionService permissions;
    private final NotificationService notifications;
    private final MenuManager menus;
    private final ChatInput chatInput;
    private final Scheduling scheduling;

    public PlayerShopCommand(PlayerShopService shops,
                             EconomyService economy,
                             PermissionService permissions,
                             NotificationService notifications,
                             MenuManager menus,
                             ChatInput chatInput,
                             Scheduling scheduling) {
        this.shops = shops;
        this.economy = economy;
        this.permissions = permissions;
        this.notifications = notifications;
        this.menus = menus;
        this.chatInput = chatInput;
        this.scheduling = scheduling;
    }

    @Override
    public void execute(@NotNull CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        if (!(sender instanceof Player player)) {
            notifications.error(sender, "error.player-only", Messages.of());
            return;
        }

        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "create" -> create(player, args);
            case "list" -> list(player);
            case "directory", "browse", "find" -> PlayerShopDirectoryMenu.openFor(
                    menus, chatInput, player, shops, economy, notifications, scheduling);
            case "manage" -> manage(player);
            case "delete", "remove" -> delete(player);
            case "rename" -> rename(player, args);
            default -> help(player);
        }
    }

    /**
     * Creates a shop on the block the player is looking at.
     *
     * <p>Targeting a block rather than using the player's own position means the
     * shop sits on the thing customers will actually click -- a chest or a sign --
     * instead of floating at their feet.
     */
    private void create(Player player, String[] args) {
        if (!permissions.require(player, Permissions.PLAYERSHOP_CREATE)) {
            return;
        }
        if (args.length < 2) {
            player.sendMessage(Text.mm("<gray>Usage: /pshop create [name]</gray>"));
            player.sendMessage(Text.mm(
                    "<gray>Look at the block customers should click, then run this.</gray>"));
            return;
        }

        Block target = player.getTargetBlockExact(TARGET_RANGE);
        if (target == null || target.getType().isAir()) {
            notifications.error(player, "playershop.error.no-target-block", Messages.of());
            return;
        }

        String name = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
        long fee = shops.settings().creationFee();
        String world = target.getWorld().getName();
        int x = target.getX();
        int y = target.getY();
        int z = target.getZ();

        Runnable submit = () -> scheduling.thenSync(
                scheduling.supplyAsync(() ->
                        shops.create(player.getUniqueId(), name, null, world, x, y, z)),
                result -> {
                    if (!result.isSuccess()) {
                        notifications.error(player, result.messageKey(), Messages.of(
                                "limit", String.valueOf(shops.settings().maxShopsPerOwner()),
                                "cost", economy.money().format(fee)));
                        return;
                    }
                    notifications.success(player, "playershop.created", Messages.of(
                            "name", result.shop().name(),
                            "cost", economy.money().format(result.amount())));
                    new PlayerShopManageMenu(menus, chatInput, player, shops, economy,
                            notifications, scheduling, result.shop().id()).open();
                },
                error -> notifications.error(player, "error.internal", Messages.of()));

        if (fee > 0 && economy.settings().needsConfirmation(fee)) {
            com.servercore.gui.ConfirmMenu.open(menus, player,
                    Text.mm("<dark_gray>Open a shop</dark_gray>"),
                    List.of("<gray>Name:</gray> <white>" + Text.escape(name) + "</white>",
                            "<gray>At:</gray> <white>" + x + ", " + y + ", " + z + "</white>",
                            "<gray>Fee:</gray> <yellow>" + economy.money().format(fee) + "</yellow>"),
                    "Open shop",
                    submit,
                    () -> notifications.info(player, "playershop.cancelled", Messages.of()));
            return;
        }
        submit.run();
    }

    private void manage(Player player) {
        withTargetedShop(player, here -> {
            if (!here.isOwner(player.getUniqueId())
                    && !permissions.has(player, Permissions.PLAYERSHOP_ADMIN)) {
                notifications.error(player, "playershop.error.not-permitted", Messages.of());
                return;
            }
            new PlayerShopManageMenu(menus, chatInput, player, shops, economy,
                    notifications, scheduling, here.id()).open();
        });
    }

    private void delete(Player player) {
        withTargetedShop(player, here -> deleteShop(player, here));
    }

    private void deleteShop(Player player, PlayerShop here) {
        boolean admin = !here.isOwner(player.getUniqueId())
                && permissions.has(player, Permissions.PLAYERSHOP_ADMIN);

        com.servercore.gui.ConfirmMenu.open(menus, player,
                Text.mm("<dark_gray>Close this shop</dark_gray>"),
                List.of("<gray>Shop:</gray> <white>" + here.name() + "</white>",
                        "<gray>Stock remaining:</gray> <white>" + here.totalStock() + "</white>",
                        "",
                        here.totalStock() > 0
                                ? "<red>Collect your stock first.</red>"
                                : "<gray>This cannot be undone.</gray>"),
                "Close shop",
                () -> scheduling.thenSync(
                        scheduling.supplyAsync(() ->
                                shops.delete(player.getUniqueId(), here.id(), admin)),
                        result -> {
                            if (result.isSuccess()) {
                                notifications.success(player, "playershop.deleted",
                                        Messages.of("name", here.name()));
                            } else {
                                notifications.error(player, result.messageKey(), Messages.of());
                            }
                        },
                        error -> notifications.error(player, "error.internal", Messages.of())),
                () -> notifications.info(player, "playershop.cancelled", Messages.of()));
    }

    private void rename(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(Text.mm("<gray>Usage: /pshop rename [new name]</gray>"));
            return;
        }
        withTargetedShop(player, here -> renameShop(player, here, args));
    }

    private void renameShop(Player player, PlayerShop here, String[] args) {
        String name = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
        scheduling.thenSync(
                scheduling.supplyAsync(() ->
                        shops.rename(player.getUniqueId(), here.id(), name, null)),
                result -> {
                    if (result.isSuccess()) {
                        notifications.success(player, "playershop.renamed",
                                Messages.of("name", result.shop().name()));
                    } else {
                        notifications.error(player, result.messageKey(), Messages.of());
                    }
                },
                error -> notifications.error(player, "error.internal", Messages.of()));
    }

    private void list(Player player) {
        scheduling.thenSync(
                scheduling.supplyAsync(() -> shops.ownedBy(player.getUniqueId())),
                owned -> {
                    if (owned.isEmpty()) {
                        notifications.info(player, "playershop.list-empty", Messages.of());
                        return;
                    }
                    player.sendMessage(Text.mm("<aqua>Your shops (" + owned.size() + ")</aqua>"));
                    for (PlayerShop shop : owned) {
                        player.sendMessage(Text.mm("<gray>- <white>" + shop.name() + "</white> "
                                + "<dark_gray>" + shop.worldName() + " " + shop.x() + ","
                                + shop.y() + "," + shop.z() + " | " + shop.offers().size()
                                + " offers | " + shop.status().displayName() + "</dark_gray>"));
                    }
                },
                error -> notifications.error(player, "error.internal", Messages.of()));
    }

    /**
     * The id of the shop on the block the player is looking at, or null.
     *
     * <p>Resolved from the in-memory location index, so it is safe on the main
     * thread. The full shop is then loaded off-thread by whoever needs it.
     */
    private String shopIdAtTarget(Player player) {
        Block target = player.getTargetBlockExact(TARGET_RANGE);
        if (target == null) {
            return null;
        }
        return shops.shopIdAt(target.getWorld().getName(),
                target.getX(), target.getY(), target.getZ());
    }

    /** Loads the targeted shop off-thread and hands it to {@code action}. */
    private void withTargetedShop(Player player, java.util.function.Consumer<PlayerShop> action) {
        String id = shopIdAtTarget(player);
        if (id == null) {
            notifications.error(player, "playershop.error.no-shop-here", Messages.of());
            return;
        }
        scheduling.thenSync(
                scheduling.supplyAsync(() -> shops.byId(id).orElse(null)),
                shop -> {
                    if (shop == null) {
                        notifications.error(player, "playershop.error.no-shop-here", Messages.of());
                        return;
                    }
                    action.accept(shop);
                },
                error -> notifications.error(player, "error.internal", Messages.of()));
    }

    private void help(Player player) {
        player.sendMessage(Text.mm("<aqua>Player shops</aqua>"));
        player.sendMessage(Text.mm("<gray>/pshop create [name]</gray> <dark_gray>-</dark_gray> "
                + "<white>open a shop on the block you are looking at</white>"));
        player.sendMessage(Text.mm("<gray>/pshop directory</gray> <dark_gray>-</dark_gray> "
                + "<white>browse and search every shop</white>"));
        player.sendMessage(Text.mm("<gray>/pshop manage | delete | rename | list</gray>"));
        player.sendMessage(Text.mm("<dark_gray>Creation fee: "
                + economy.money().format(shops.settings().creationFee()) + "</dark_gray>"));
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
        return List.of();
    }

    @Override
    public boolean canUse(@NotNull CommandSender sender) {
        return permissions.has(sender, Permissions.PLAYERSHOP_CREATE);
    }

    @Override
    public @NotNull String permission() {
        return Permissions.PLAYERSHOP_CREATE;
    }
}
