package com.servercore.gui;

import com.servercore.core.Scheduling;
import com.servercore.core.Service;
import com.servercore.log.AuditLog;
import com.servercore.util.Text;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Opens menus and routes inventory events to them.
 *
 * <p>This class is the trust boundary for every GUI in the plugin. The rules it
 * enforces are deliberately blunt, because the cost of being wrong is item
 * duplication:
 *
 * <ul>
 *   <li>Every click inside one of our inventories is cancelled before any
 *       handler runs. Handlers cause effects by calling services, never by
 *       letting the click through.</li>
 *   <li>Any action capable of moving an item into or out of the menu is
 *       cancelled even when the click landed in the player's own inventory --
 *       shift-click, hotbar swap, double-click collect and drags all qualify.</li>
 *   <li>A click on a slot holding no registered button is logged as a security
 *       violation. A vanilla client cannot produce one, so a burst of them
 *       indicates a modified client probing for a handler.</li>
 * </ul>
 */
public final class MenuManager implements Service, Listener {

    private final Plugin plugin;
    private final Scheduling scheduling;
    private final AuditLog auditLog;
    private final Logger logger;

    private final Map<UUID, Menu> openMenus = new ConcurrentHashMap<>();

    public MenuManager(Plugin plugin, Scheduling scheduling, AuditLog auditLog, Logger logger) {
        this.plugin = plugin;
        this.scheduling = scheduling;
        this.auditLog = auditLog;
        this.logger = logger;
    }

    @Override
    public void onEnable() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @Override
    public void onDisable() {
        HandlerList.unregisterAll(this);
        // Close everything we own so no player is left staring at a dead screen
        // whose handlers no longer exist.
        for (Menu menu : Map.copyOf(openMenus).values()) {
            try {
                menu.viewer().closeInventory();
            } catch (Exception e) {
                logger.log(Level.WARNING, "Failed to close menu on shutdown", e);
            }
        }
        openMenus.clear();
    }

    /** Renders {@code menu} and shows it to its viewer. */
    public void open(Menu menu) {
        scheduling.ensureMainThread("Opening a menu");
        Player viewer = menu.viewer();
        if (!viewer.isOnline()) {
            return;
        }
        menu.render();
        viewer.openInventory(menu.getInventory());
        // Registered after openInventory on purpose: opening fires a close event
        // for whatever was open before, and that handler removes the entry keyed
        // by this same player.
        menu.setOpen(true);
        openMenus.put(viewer.getUniqueId(), menu);
    }

    /** Closes {@code menu} on the next tick. */
    public void closeLater(Menu menu) {
        scheduling.syncLater(() -> {
            if (openMenus.get(menu.viewer().getUniqueId()) == menu) {
                menu.viewer().closeInventory();
            }
        }, 1L);
    }

    /** The menu a player currently has open, or null. */
    public Menu openMenu(Player player) {
        return openMenus.get(player.getUniqueId());
    }

    // --------------------------------------------------------------- events

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Menu menu)) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            event.setCancelled(true);
            return;
        }

        Inventory clicked = event.getClickedInventory();
        boolean clickedMenu = clicked != null && clicked.equals(menu.getInventory());

        // Actions that can move items across the boundary are refused wherever
        // they originate. Checking the action rather than only the clicked
        // inventory is what closes the shift-click and hotbar-swap paths.
        if (movesItemsAcrossBoundary(event.getAction())) {
            event.setCancelled(true);
            if (!clickedMenu) {
                return;
            }
        }

        if (!clickedMenu) {
            // A click in the player's own inventory. Allowed only for menus that
            // opt in, and even then it never transfers items by itself.
            if (!menu.allowsPlayerInventoryInteraction()) {
                event.setCancelled(true);
            }
            return;
        }

        event.setCancelled(true);

        int slot = event.getSlot();
        if (slot < 0 || slot >= menu.size()) {
            return;
        }

        Button button = menu.buttonAt(slot);
        if (button == null || !button.isInteractive()) {
            // An empty decorated slot is a normal, expected click. Only a click
            // on a slot with nothing rendered at all is suspicious.
            if (event.getCurrentItem() == null && button == null) {
                auditLog.securityViolation(player.getUniqueId(), player.getName(),
                        "Clicked unregistered slot " + slot + " in menu " + menu.getClass().getSimpleName());
            }
            return;
        }

        ClickContext context = new ClickContext(player, menu, slot, event.getClick());
        try {
            button.onClick().accept(context);
        } catch (Exception e) {
            // A handler blowing up must not abort event processing for other
            // plugins or leave the player with no feedback.
            logger.log(Level.SEVERE, "Menu click handler failed in "
                    + menu.getClass().getName() + " slot " + slot, e);
            player.sendMessage(Text.mm("<red>Something went wrong handling that click. "
                    + "It has been logged and nothing was changed.</red>"));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Menu menu)) {
            return;
        }
        int topSize = menu.size();
        // A drag that touches even one menu slot is refused outright. Dragging is
        // the easiest way to smear an item across a GUI and have part of it land
        // somewhere the click handlers never see.
        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot < topSize) {
                event.setCancelled(true);
                return;
            }
        }
        if (!menu.allowsPlayerInventoryInteraction()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof Menu menu)) {
            return;
        }
        UUID id = event.getPlayer().getUniqueId();
        // Only clear the tracking entry if this close is for the menu we think is
        // open. Opening a replacement menu fires a close for the old one, and
        // that must not evict the new entry.
        if (openMenus.get(id) == menu) {
            openMenus.remove(id);
        }
        menu.setOpen(false);
        try {
            menu.onClose();
        } catch (Exception e) {
            logger.log(Level.WARNING, "Menu close handler failed in " + menu.getClass().getName(), e);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        openMenus.remove(event.getPlayer().getUniqueId());
    }

    /**
     * Whether an action can move items between the menu and the player.
     *
     * <p>Listed explicitly rather than inferred, so a new {@link InventoryAction}
     * added by a future Minecraft version defaults to being treated as safe only
     * if it genuinely is -- and the blanket cancel on menu clicks covers the rest.
     */
    private static boolean movesItemsAcrossBoundary(InventoryAction action) {
        return switch (action) {
            case MOVE_TO_OTHER_INVENTORY,
                 COLLECT_TO_CURSOR,
                 HOTBAR_SWAP,
                 HOTBAR_MOVE_AND_READD -> true;
            default -> false;
        };
    }
}
