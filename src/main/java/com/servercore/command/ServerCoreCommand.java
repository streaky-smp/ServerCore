package com.servercore.command;

import com.servercore.ServerCorePlugin;
import com.servercore.config.ConfigException;
import com.servercore.data.Database;
import com.servercore.data.SchemaMigrator;
import com.servercore.gui.MenuManager;
import com.servercore.integration.IntegrationManager;
import com.servercore.log.AuditAction;
import com.servercore.log.AuditLog;
import com.servercore.notify.Messages;
import com.servercore.notify.NotificationService;
import com.servercore.permission.PermissionService;
import com.servercore.permission.Permissions;
import com.servercore.util.Text;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * {@code /servercore} -- administration and diagnostics.
 *
 * <p>Implemented against Paper's {@link BasicCommand} rather than the legacy
 * {@code plugin.yml} command system, so it participates in Brigadier's
 * tab-completion and permission gating.
 */
public final class ServerCoreCommand implements BasicCommand {

    private final ServerCorePlugin plugin;
    private final PermissionService permissions;
    private final NotificationService notifications;
    private final AuditLog auditLog;

    public ServerCoreCommand(ServerCorePlugin plugin,
                             PermissionService permissions,
                             NotificationService notifications,
                             AuditLog auditLog) {
        this.plugin = plugin;
        this.permissions = permissions;
        this.notifications = notifications;
        this.auditLog = auditLog;
    }

    @Override
    public void execute(@NotNull CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        if (!permissions.require(sender, Permissions.ADMIN)) {
            return;
        }

        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload" -> reload(sender);
            case "status" -> status(sender);
            case "admin", "gui" -> admin(sender);
            default -> help(sender);
        }
    }

    @Override
    public @NotNull java.util.Collection<String> suggest(@NotNull CommandSourceStack source, String[] args) {
        if (args.length <= 1) {
            String partial = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            return List.of("reload", "status", "admin", "help").stream()
                    .filter(option -> option.startsWith(partial))
                    .toList();
        }
        return List.of();
    }

    @Override
    public boolean canUse(@NotNull CommandSender sender) {
        return permissions.has(sender, Permissions.ADMIN);
    }

    @Override
    public @NotNull String permission() {
        return Permissions.ADMIN;
    }

    /** Opens the administration GUI. */
    private void admin(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            notifications.error(sender, "error.player-only", Messages.of());
            return;
        }
        var services = plugin.services();
        com.servercore.gui.AdminMenu.openFor(
                services.get(com.servercore.gui.MenuManager.class),
                services.get(com.servercore.gui.ChatInput.class),
                player, plugin, notifications, permissions, plugin.scheduling());
    }

    private void reload(CommandSender sender) {
        try {
            plugin.configManager().reload();
            notifications.success(sender, "admin.reload-success", Messages.of());
            auditLog.record(AuditAction.ADMIN_ACTION, uuidOf(sender), sender.getName(),
                    "Reloaded configuration");
        } catch (ConfigException e) {
            // The previous configuration is still live; say so explicitly, since
            // the obvious assumption after a failed reload is that the server is
            // now running on nothing.
            notifications.error(sender, "admin.reload-failed",
                    Messages.of("reason", e.getMessage() == null ? "unknown error" : e.getMessage()));
        } catch (Exception e) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "Config reload failed", e);
            notifications.error(sender, "admin.reload-failed",
                    Messages.of("reason", String.valueOf(e.getMessage())));
        }
    }

    private void status(CommandSender sender) {
        var services = plugin.services();
        Database database = services.get(Database.class);
        IntegrationManager integrations = services.get(IntegrationManager.class);
        MenuManager menus = services.get(MenuManager.class);
        SchemaMigrator migrator = plugin.migrator();

        sender.sendMessage(Text.mm("<gray><strikethrough>                    </strikethrough></gray>"
                + " <aqua><bold>ServerCore</bold></aqua> "
                + "<gray><strikethrough>                    </strikethrough></gray>"));

        line(sender, "Version", plugin.getPluginMeta().getVersion());
        line(sender, "Services", String.valueOf(services.startOrder().size()));
        line(sender, "Database", database.isOpen() ? "<green>open</green>" : "<red>closed</red>");
        line(sender, "Schema version", migrator == null
                ? "<red>unknown</red>"
                : String.valueOf(migrator.targetVersion()));
        line(sender, "Audit queue", String.valueOf(auditLog.pending()));
        line(sender, "Permissions", permissions.provider());

        sender.sendMessage(Text.mm("<gray>Integrations:</gray>"));
        integration(sender, "Geyser", integrations.hasGeyser());
        integration(sender, "Floodgate", integrations.hasFloodgate());
        if (integrations.hasFloodgate() && !integrations.canIdentifyBedrockPlayers()) {
            sender.sendMessage(Text.mm(
                    "  <yellow>Floodgate API unavailable; using UUID-based Bedrock detection.</yellow>"));
        }
        integration(sender, "LuckPerms", integrations.hasLuckPerms());
        integration(sender, "Vault", integrations.hasVault());
        integration(sender, "PlaceholderAPI", integrations.hasPlaceholderApi());

        if (sender instanceof Player player) {
            line(sender, "Your platform",
                    integrations.isBedrockPlayer(player) ? "Bedrock" : "Java");
            line(sender, "Open menu", menus.openMenu(player) == null
                    ? "none"
                    : menus.openMenu(player).getClass().getSimpleName());
        }
    }

    private void help(CommandSender sender) {
        sender.sendMessage(Text.mm("<aqua>ServerCore administration</aqua>"));
        sender.sendMessage(Text.mm("<gray>/servercore reload</gray> <dark_gray>-</dark_gray> "
                + "<white>Re-read configuration files.</white>"));
        sender.sendMessage(Text.mm("<gray>/servercore status</gray> <dark_gray>-</dark_gray> "
                + "<white>Show service and integration state.</white>"));
        sender.sendMessage(Text.mm("<gray>/servercore admin</gray> <dark_gray>-</dark_gray> "
                + "<white>Open the administration menu.</white>"));
    }

    private static void line(CommandSender sender, String label, String value) {
        sender.sendMessage(Text.mm("<gray>" + label + ":</gray> <white>" + value + "</white>"));
    }

    private static void integration(CommandSender sender, String name, boolean present) {
        sender.sendMessage(Text.mm("  <gray>" + name + ":</gray> "
                + (present ? "<green>detected</green>" : "<dark_gray>not present</dark_gray>")));
    }

    private static UUID uuidOf(CommandSender sender) {
        return sender instanceof Player player ? player.getUniqueId() : null;
    }
}
