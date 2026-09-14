package com.streakysmp.command;

import com.streakysmp.core.Scheduling;
import com.streakysmp.economy.EconomyService;
import com.streakysmp.gui.ChatInput;
import com.streakysmp.gui.MenuManager;
import com.streakysmp.gui.ShopCategoryMenu;
import com.streakysmp.gui.ShopMenu;
import com.streakysmp.notify.Messages;
import com.streakysmp.notify.NotificationService;
import com.streakysmp.permission.PermissionService;
import com.streakysmp.permission.Permissions;
import com.streakysmp.shop.ShopCategory;
import com.streakysmp.shop.ShopService;
import com.streakysmp.util.Text;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * {@code /shop [category]} -- opens the server shop.
 */
public final class ShopCommand implements BasicCommand {

    private final ShopService shop;
    private final EconomyService economy;
    private final PermissionService permissions;
    private final NotificationService notifications;
    private final MenuManager menus;
    private final ChatInput chatInput;
    private final Scheduling scheduling;

    public ShopCommand(ShopService shop,
                       EconomyService economy,
                       PermissionService permissions,
                       NotificationService notifications,
                       MenuManager menus,
                       ChatInput chatInput,
                       Scheduling scheduling) {
        this.shop = shop;
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
        if (!permissions.require(player, Permissions.SHOP_USE)) {
            return;
        }
        if (shop.catalogue().isEmpty()) {
            notifications.error(player, "shop.error.empty", Messages.of());
            return;
        }

        if (args.length == 0) {
            ShopMenu.openFor(menus, player, shop, economy, notifications, chatInput, scheduling);
            return;
        }

        String id = args[0].toLowerCase(Locale.ROOT);
        Optional<ShopCategory> category = shop.catalogue().category(id);
        if (category.isEmpty()) {
            notifications.error(player, "shop.error.unknown-category", Messages.of("name", args[0]));
            return;
        }
        // The permission is re-checked when the menu renders, but failing here
        // gives a clear message instead of an empty screen.
        if (!permissions.require(player, category.get().permission())) {
            return;
        }

        scheduling.thenSync(
                scheduling.supplyAsync(() -> economy.getBalance(player.getUniqueId())),
                balance -> new ShopCategoryMenu(menus, chatInput, player, shop, economy,
                        notifications, scheduling, category.get(), balance).open(),
                error -> player.sendMessage(Text.mm("<red>Could not open the shop.</red>")));
    }

    @Override
    public @NotNull Collection<String> suggest(@NotNull CommandSourceStack source, String[] args) {
        if (args.length > 1) {
            return List.of();
        }
        String partial = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (ShopCategory category : shop.catalogue().categories()) {
            if (category.id().startsWith(partial)
                    && permissions.has(source.getSender(), category.permission())) {
                out.add(category.id());
            }
        }
        return out;
    }

    @Override
    public boolean canUse(@NotNull CommandSender sender) {
        return permissions.has(sender, Permissions.SHOP_USE);
    }

    @Override
    public @NotNull String permission() {
        return Permissions.SHOP_USE;
    }
}
