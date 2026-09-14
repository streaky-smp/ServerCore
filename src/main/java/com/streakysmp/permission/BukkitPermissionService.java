package com.streakysmp.permission;

import com.streakysmp.notify.Messages;
import com.streakysmp.notify.NotificationService;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * Permission checks routed through Bukkit's permissible API.
 *
 * <p>Works unchanged with LuckPerms, any other permission plugin, or Paper's
 * built-in op handling, because all of them implement the same underlying check.
 * The plugin never needs to know which is installed.
 */
public final class BukkitPermissionService implements PermissionService {

    /** Permission plugins we can name in diagnostics, most specific first. */
    private static final List<String> KNOWN_PROVIDERS =
            List.of("LuckPerms", "UltraPermissions", "PermissionsEx", "GroupManager");

    private final NotificationService notifications;

    private String provider = "Paper (built-in)";

    public BukkitPermissionService(NotificationService notifications) {
        this.notifications = notifications;
    }

    @Override
    public void onEnable() {
        for (String candidate : KNOWN_PROVIDERS) {
            if (Bukkit.getPluginManager().isPluginEnabled(candidate)) {
                provider = candidate;
                break;
            }
        }
    }

    @Override
    public boolean has(CommandSender sender, String permission) {
        // Console is unconditionally allowed. Gating console behind permission
        // nodes only ever locks operators out of their own server.
        if (sender instanceof ConsoleCommandSender) {
            return true;
        }
        return sender.hasPermission(permission);
    }

    @Override
    public boolean require(CommandSender sender, String permission) {
        if (has(sender, permission)) {
            return true;
        }
        notifications.error(sender, "error.no-permission",
                Messages.of("permission", permission));
        return false;
    }

    @Override
    public boolean isAdmin(CommandSender sender) {
        if (!(sender instanceof Player)) {
            return true;
        }
        return sender.hasPermission(Permissions.ADMIN);
    }

    @Override
    public String provider() {
        return provider;
    }
}
