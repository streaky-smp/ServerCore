package com.servercore.permission;

import com.servercore.core.Service;
import org.bukkit.command.CommandSender;

/**
 * The single place permissions are evaluated.
 *
 * <p>Deliberately an interface over Bukkit's own permission check rather than a
 * direct LuckPerms dependency. LuckPerms, PermissionsEx, GroupManager and Paper's
 * built-in handling all implement {@link CommandSender#hasPermission(String)}, so
 * routing through it supports every permission plugin without the core depending
 * on any of them. The LuckPerms integration exists only for things Bukkit cannot
 * express, such as reading a player's group or prefix.
 */
public interface PermissionService extends Service {

    boolean has(CommandSender sender, String permission);

    /**
     * Checks a permission and tells the sender if they lack it.
     *
     * @return true if the sender may proceed
     */
    boolean require(CommandSender sender, String permission);

    /** True for console, and for players holding {@link Permissions#ADMIN}. */
    boolean isAdmin(CommandSender sender);

    /**
     * Describes which permission plugin is providing evaluation, for the
     * diagnostics screen.
     */
    String provider();
}
