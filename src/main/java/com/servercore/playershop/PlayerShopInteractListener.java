package com.servercore.playershop;

import com.servercore.core.Scheduling;
import com.servercore.core.Service;
import com.servercore.economy.EconomyService;
import com.servercore.gui.ChatInput;
import com.servercore.gui.MenuManager;
import com.servercore.gui.PlayerShopManageMenu;
import com.servercore.gui.PlayerShopViewMenu;
import com.servercore.notify.Messages;
import com.servercore.notify.NotificationService;
import com.servercore.permission.PermissionService;
import com.servercore.permission.Permissions;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.plugin.Plugin;

/**
 * Opens a player shop when its anchor block is clicked, and stops that block from
 * being destroyed while the shop exists.
 *
 * <p>Both handlers consult the in-memory location index, so the common case -- a
 * click on an ordinary block -- costs a single map lookup and nothing else.
 */
public final class PlayerShopInteractListener implements Service, Listener {

    private final Plugin plugin;
    private final PlayerShopService shops;
    private final EconomyService economy;
    private final NotificationService notifications;
    private final PermissionService permissions;
    private final MenuManager menus;
    private final ChatInput chatInput;
    private final Scheduling scheduling;

    public PlayerShopInteractListener(Plugin plugin,
                                      PlayerShopService shops,
                                      EconomyService economy,
                                      NotificationService notifications,
                                      PermissionService permissions,
                                      MenuManager menus,
                                      ChatInput chatInput,
                                      Scheduling scheduling) {
        this.plugin = plugin;
        this.shops = shops;
        this.economy = economy;
        this.notifications = notifications;
        this.permissions = permissions;
        this.menus = menus;
        this.chatInput = chatInput;
        this.scheduling = scheduling;
    }

    @Override
    public void onEnable() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @Override
    public void onDisable() {
        HandlerList.unregisterAll(this);
    }

    /**
     * Right-clicking a shop block opens it.
     *
     * <p>The owner gets the management screen, everyone else the customer view.
     * The interaction is cancelled so a shop built on a chest does not also open
     * the chest behind the menu.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }
        String shopId = shops.shopIdAt(block.getWorld().getName(),
                block.getX(), block.getY(), block.getZ());
        if (shopId == null) {
            return;
        }

        event.setCancelled(true);
        Player player = event.getPlayer();

        scheduling.thenSync(
                scheduling.supplyAsync(() -> shops.byId(shopId).orElse(null)),
                shop -> {
                    if (shop == null) {
                        return;
                    }
                    boolean owner = shop.isOwner(player.getUniqueId());
                    boolean admin = permissions.has(player, Permissions.PLAYERSHOP_ADMIN);
                    if (owner || admin) {
                        new PlayerShopManageMenu(menus, chatInput, player, shops, economy,
                                notifications, scheduling, shopId).open();
                    } else {
                        new PlayerShopViewMenu(menus, chatInput, player, shops, economy,
                                notifications, scheduling, shopId).open();
                    }
                },
                error -> notifications.error(player, "error.internal", Messages.of()));
    }

    /**
     * Refuses to let the anchor block be broken while the shop exists.
     *
     * <p>Otherwise the shop survives in the database with nothing to click, and
     * its stock becomes unreachable. Telling the owner to close the shop properly
     * is the only path that guarantees their goods come back to them.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (!shops.hasShopAt(block.getWorld().getName(),
                block.getX(), block.getY(), block.getZ())) {
            return;
        }
        event.setCancelled(true);
        notifications.error(event.getPlayer(), "playershop.error.block-protected", Messages.of());
    }
}
