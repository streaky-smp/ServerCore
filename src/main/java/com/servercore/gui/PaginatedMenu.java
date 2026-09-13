package com.servercore.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A menu that pages through a list, with optional search and sorting.
 *
 * <p>Subclasses supply the data and how to draw one entry; paging, bounds and the
 * navigation row are handled here. Every screen in the plugin that shows a
 * variable-length list -- shop categories, auction listings, the shop directory,
 * transaction history -- is built on this, so paging behaves identically
 * everywhere.
 *
 * <h2>Bedrock compatibility</h2>
 * Search is collected through chat rather than an anvil rename, because anvil
 * text input does not round-trip reliably through Geyser. Sorting cycles on left
 * click instead of offering a hover menu.
 *
 * @param <T> the element type being listed
 */
public abstract class PaginatedMenu<T> extends Menu {

    private final ChatInput chatInput;

    private List<T> visible = List.of();
    private int page;
    private String searchQuery = "";
    private int sortIndex;

    protected PaginatedMenu(MenuManager menus, ChatInput chatInput, Player viewer, Component title, int rows) {
        super(menus, viewer, title, rows);
        this.chatInput = chatInput;
    }

    // ------------------------------------------------------ subclass contract

    /**
     * The full, unfiltered list.
     *
     * <p>Called on every render, so it must be cheap and must not hit the
     * database. Load the data before opening the menu and return the cached list
     * from here.
     */
    protected abstract List<T> source();

    /** Draws one entry. */
    protected abstract Button renderEntry(T entry);

    /**
     * Whether {@code entry} matches a search term.
     *
     * <p>Default matches nothing, which effectively disables search. Override
     * together with {@link #supportsSearch()}.
     */
    protected boolean matches(T entry, String lowercaseQuery) {
        return false;
    }

    /** Sort options offered by the sort button, in cycle order. */
    protected List<SortOption<T>> sortOptions() {
        return List.of();
    }

    protected boolean supportsSearch() {
        return false;
    }

    /** A named ordering. */
    public record SortOption<T>(String label, Comparator<T> comparator) {
    }

    // ------------------------------------------------------------- rendering

    /**
     * Slots that hold entries.
     *
     * <p>Defaults to everything above the final row, leaving that row for
     * navigation.
     */
    protected int[] contentSlots() {
        int count = size() - COLUMNS;
        int[] slots = new int[count];
        for (int i = 0; i < count; i++) {
            slots[i] = i;
        }
        return slots;
    }

    protected int pageSize() {
        return contentSlots().length;
    }

    @Override
    protected void build() {
        visible = computeVisible();

        int pageSize = pageSize();
        int maxPage = Math.max(0, (visible.size() - 1) / Math.max(1, pageSize));
        // Clamp rather than error: the underlying list can shrink between renders
        // when another player buys the listing you were looking at.
        page = Math.min(Math.max(0, page), maxPage);

        int[] slots = contentSlots();
        int start = page * pageSize;
        for (int i = 0; i < slots.length; i++) {
            int index = start + i;
            if (index >= visible.size()) {
                break;
            }
            set(slots[i], renderEntry(visible.get(index)));
        }

        buildNavigationRow(maxPage);
        decorate();
    }

    /** Hook for subclasses to add their own buttons after paging is laid out. */
    protected void decorate() {
    }

    private void buildNavigationRow(int maxPage) {
        int lastRow = rows() - 1;
        int base = lastRow * COLUMNS;

        if (page > 0) {
            set(base, Button.of(ItemBuilder.of(Material.ARROW)
                    .name("<green>Previous page</green>")
                    .lore("<gray>Page " + page + " of " + (maxPage + 1) + "</gray>")
                    .clean().build(), click -> {
                page--;
                click.refresh();
            }));
        }

        if (page < maxPage) {
            set(base + 8, Button.of(ItemBuilder.of(Material.ARROW)
                    .name("<green>Next page</green>")
                    .lore("<gray>Page " + (page + 2) + " of " + (maxPage + 1) + "</gray>")
                    .clean().build(), click -> {
                page++;
                click.refresh();
            }));
        }

        // Always present so the player can see where they are even on one page.
        set(base + 4, Button.display(ItemBuilder.of(Material.PAPER)
                .name("<white>Page <yellow>" + (page + 1) + "</yellow> of <yellow>"
                        + (maxPage + 1) + "</yellow></white>")
                .lore(buildCountLore())
                .clean().build()));

        if (supportsSearch()) {
            set(base + 2, buildSearchButton());
            if (!searchQuery.isEmpty()) {
                // A dedicated button rather than a right-click: a touch player
                // through Geyser cannot reliably produce a right-click, and
                // being unable to clear a filter strands them on an empty page.
                set(base + 1, Button.of(ItemBuilder.of(Material.CAULDRON)
                        .name("<red>Clear search</red>")
                        .lore("<gray>Currently filtering by:</gray>",
                                "<yellow>" + sanitise(searchQuery) + "</yellow>")
                        .clean().build(), click -> {
                    searchQuery = "";
                    page = 0;
                    click.refresh();
                }));
            }
        }

        List<SortOption<T>> options = sortOptions();
        if (!options.isEmpty()) {
            set(base + 6, buildSortButton(options));
        }

        if (hasParent()) {
            set(base + 3, Button.of(ItemBuilder.of(Material.BARRIER)
                    .name("<red>Back</red>")
                    .clean().build(), click -> parent().open()));
        }
    }

    private List<String> buildCountLore() {
        List<String> lore = new ArrayList<>();
        lore.add("<gray>Showing <white>" + visible.size() + "</white> entr"
                + (visible.size() == 1 ? "y" : "ies") + "</gray>");
        if (!searchQuery.isEmpty()) {
            lore.add("<gray>Filtered by: <yellow>" + sanitise(searchQuery) + "</yellow></gray>");
        }
        return lore;
    }

    private Button buildSearchButton() {
        ItemBuilder icon = ItemBuilder.of(Material.OAK_SIGN)
                .name("<aqua>Search</aqua>")
                .clean();
        if (searchQuery.isEmpty()) {
            icon.lore("<gray>Click to type a search term.</gray>");
        } else {
            icon.lore(
                    "<gray>Current: <yellow>" + sanitise(searchQuery) + "</yellow></gray>",
                    "<gray>Click to change it.</gray>",
                    "<gray>Use <white>Clear search</white> to remove it.</gray>");
        }
        return Button.of(icon.build(), click -> promptForSearch(click.player()));
    }

    /**
     * Asks for a search term in chat.
     *
     * <p>The menu is closed first: the player cannot type while an inventory is
     * open, and this is the one interaction that behaves identically on Java and
     * Bedrock.
     */
    private void promptForSearch(Player player) {
        closeLater();
        chatInput.prompt(player,
                "<aqua>Type your search term in chat, or <white>cancel</white> to go back.</aqua>",
                input -> {
                    searchQuery = input;
                    page = 0;
                    open();
                },
                this::open);
    }

    private Button buildSortButton(List<SortOption<T>> options) {
        SortOption<T> active = options.get(sortIndex % options.size());
        List<String> lore = new ArrayList<>();
        lore.add("<gray>Currently: <yellow>" + active.label() + "</yellow></gray>");
        lore.add("<gray>Click to change.</gray>");
        for (int i = 0; i < options.size(); i++) {
            String marker = i == sortIndex % options.size() ? "<green>> </green>" : "<dark_gray>  </dark_gray>";
            lore.add(marker + "<gray>" + options.get(i).label() + "</gray>");
        }
        return Button.of(ItemBuilder.of(Material.COMPARATOR)
                .name("<aqua>Sort</aqua>")
                .lore(lore)
                .clean().build(), click -> {
            sortIndex = (sortIndex + 1) % options.size();
            page = 0;
            click.refresh();
        });
    }

    private List<T> computeVisible() {
        List<T> items = new ArrayList<>(source());

        if (!searchQuery.isEmpty()) {
            String needle = searchQuery.toLowerCase(Locale.ROOT);
            items.removeIf(item -> !matches(item, needle));
        }

        List<SortOption<T>> options = sortOptions();
        if (!options.isEmpty()) {
            items.sort(options.get(sortIndex % options.size()).comparator());
        }
        return items;
    }

    // ------------------------------------------------------------- accessors

    protected String searchQuery() {
        return searchQuery;
    }

    protected List<T> visibleEntries() {
        return List.copyOf(visible);
    }

    /** Resets to the first page, e.g. after the underlying data is reloaded. */
    protected void resetPage() {
        page = 0;
    }

    /**
     * Strips MiniMessage tags out of text that originated from a player.
     *
     * <p>Search terms are echoed back into lore; without this a player could type
     * a tag and have it rendered as markup.
     */
    private static String sanitise(String playerText) {
        return com.servercore.util.Text.escape(playerText);
    }

    /** Placeholder map helper for subclasses rendering entry lore. */
    protected static Map<String, String> placeholders(String... keyValuePairs) {
        if (keyValuePairs.length % 2 != 0) {
            throw new IllegalArgumentException("Expected an even number of key/value arguments");
        }
        Map<String, String> map = new java.util.HashMap<>();
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            map.put(keyValuePairs[i], keyValuePairs[i + 1]);
        }
        return map;
    }
}
