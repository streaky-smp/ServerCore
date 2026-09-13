package com.servercore.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;

/**
 * Base class for every inventory screen in the plugin.
 *
 * <p>A menu instance belongs to exactly one viewer and one open session. Keeping
 * per-viewer state out of shared objects removes a whole class of bug where two
 * players paging through the same screen corrupt each other's view.
 *
 * <p>The menu is its own {@link InventoryHolder}. That is how {@link MenuManager}
 * recognises our inventories: identity of the holder object, not the window
 * title, which a resource pack or a translation can change and a client can lie
 * about.
 *
 * <h2>Bedrock compatibility</h2>
 * Geyser renders chest inventories faithfully, so layout carries across. What
 * does not carry across is anything depending on hover text being read before
 * clicking, or on click variants a touch device cannot produce. Subclasses must
 * keep every action reachable by a plain left click.
 */
public abstract class Menu implements InventoryHolder {

    /** Chest inventories are 1-6 rows of 9. */
    public static final int COLUMNS = 9;
    public static final int MAX_ROWS = 6;

    protected final MenuManager menus;
    protected final Player viewer;

    private final Component title;
    private final int size;
    private final Map<Integer, Button> buttons = new HashMap<>();

    /**
     * Created lazily rather than in the constructor.
     *
     * <p>{@code Bukkit.createInventory} takes the holder, and passing {@code this}
     * from a constructor would publish a partially constructed subclass. Deferring
     * to first access means the object is always fully initialised by the time
     * anything else can reach it.
     */
    private Inventory inventory;

    private Menu parent;
    private boolean open;

    protected Menu(MenuManager menus, Player viewer, Component title, int rows) {
        if (rows < 1 || rows > MAX_ROWS) {
            throw new IllegalArgumentException("Menu rows must be 1-" + MAX_ROWS + ", got " + rows);
        }
        this.menus = menus;
        this.viewer = viewer;
        this.title = title;
        this.size = rows * COLUMNS;
    }

    /**
     * Populates the menu.
     *
     * <p>Called on open and on every {@link #refresh()}. Implementations should
     * treat this as a pure render from current state: clear nothing, assume
     * nothing persists, just describe what the screen looks like now. The
     * framework clears slots between builds.
     *
     * <p>Runs on the main thread. Fetch anything that needs the database before
     * opening the menu, not from inside here.
     */
    protected abstract void build();

    // ------------------------------------------------------------------ slots

    /** Registers a button, replacing whatever occupied the slot. */
    protected void set(int slot, Button button) {
        if (slot < 0 || slot >= size) {
            throw new IndexOutOfBoundsException(
                    "Slot " + slot + " is outside this " + size + "-slot menu");
        }
        buttons.put(slot, button);
    }

    /** Registers a button at a row/column position, counting from zero. */
    protected void set(int row, int column, Button button) {
        set(row * COLUMNS + column, button);
    }

    /** Fills every currently empty slot with a non-interactive filler item. */
    protected void fillEmpty(Material material) {
        ItemStack filler = ItemBuilder.of(material).name(" ").clean().build();
        for (int slot = 0; slot < size; slot++) {
            if (!buttons.containsKey(slot)) {
                buttons.put(slot, Button.display(filler));
            }
        }
    }

    /** Fills the outer ring, leaving the interior for content. */
    protected void fillBorder(Material material) {
        ItemStack filler = ItemBuilder.of(material).name(" ").clean().build();
        int rows = size / COLUMNS;
        for (int slot = 0; slot < size; slot++) {
            int row = slot / COLUMNS;
            int column = slot % COLUMNS;
            boolean edge = row == 0 || row == rows - 1 || column == 0 || column == COLUMNS - 1;
            if (edge && !buttons.containsKey(slot)) {
                buttons.put(slot, Button.display(filler));
            }
        }
    }

    // ------------------------------------------------------------- navigation

    /** Sets the menu that a back button should return to. */
    public Menu withParent(Menu parent) {
        this.parent = parent;
        return this;
    }

    public Menu parent() {
        return parent;
    }

    public boolean hasParent() {
        return parent != null;
    }

    // ------------------------------------------------------------- life cycle

    /** Renders and shows the menu. Must be called on the main thread. */
    public void open() {
        menus.open(this);
    }

    /**
     * Re-renders in place.
     *
     * <p>Deliberately does not reopen the window. Reopening flickers on Java and
     * can dismiss the screen entirely on Bedrock, so a refresh only rewrites the
     * contents of the inventory the player already has open.
     */
    public void refresh() {
        render();
    }

    /**
     * Closes on the next tick.
     *
     * <p>Closing an inventory from inside an {@code InventoryClickEvent} handler
     * while the event is still being processed is undefined behaviour in Bukkit,
     * so the close is deferred by one tick.
     */
    public void closeLater() {
        menus.closeLater(this);
    }

    /** Rebuilds the button map and writes it into the inventory. */
    void render() {
        buttons.clear();
        build();
        Inventory target = getInventory();
        target.clear();
        for (Map.Entry<Integer, Button> entry : buttons.entrySet()) {
            Button button = entry.getValue();
            if (button.permission() != null && !viewer.hasPermission(button.permission())) {
                continue;
            }
            target.setItem(entry.getKey(), button.icon());
        }
    }

    /**
     * Resolves the button for a slot, applying the permission check again at
     * click time.
     *
     * <p>Re-checking matters: a permission can be revoked while a menu sits open,
     * and the icon the client still shows must not remain usable.
     */
    Button buttonAt(int slot) {
        Button button = buttons.get(slot);
        if (button == null) {
            return null;
        }
        if (button.permission() != null && !viewer.hasPermission(button.permission())) {
            return null;
        }
        return button;
    }

    /**
     * Whether clicks in the player's own inventory are allowed while this menu is
     * open.
     *
     * <p>False for every menu that only displays things. Screens that genuinely
     * need the player to supply an item -- listing to the auction house, stocking
     * a shop -- override this and validate what arrives.
     */
    public boolean allowsPlayerInventoryInteraction() {
        return false;
    }

    /** Called after the window closes, for cleanup. */
    public void onClose() {
    }

    public Player viewer() {
        return viewer;
    }

    public Component title() {
        return title;
    }

    public int size() {
        return size;
    }

    public int rows() {
        return size / COLUMNS;
    }

    boolean isOpen() {
        return open;
    }

    void setOpen(boolean open) {
        this.open = open;
    }

    @Override
    public Inventory getInventory() {
        if (inventory == null) {
            inventory = Bukkit.createInventory(this, size, title);
        }
        return inventory;
    }
}
