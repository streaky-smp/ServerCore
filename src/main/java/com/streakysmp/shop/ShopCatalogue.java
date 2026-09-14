package com.streakysmp.shop;

import com.streakysmp.config.ConfigException;
import com.streakysmp.config.ConfigManager;
import com.streakysmp.config.ConfigView;
import com.streakysmp.core.Service;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.PluginManager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * The shop's contents, loaded from {@code shop.yml}.
 *
 * <p>This is the authoritative price list. Nothing a client sends is ever used to
 * price a transaction; every figure is looked up here by material, server-side,
 * at the moment of the trade.
 *
 * <p>Rebuilt wholesale on reload and swapped in atomically, so a trade in flight
 * cannot see half the old catalogue and half the new one.
 */
public final class ShopCatalogue implements Service, ConfigManager.Reloadable {

    private final Logger logger;

    private volatile List<ShopCategory> categories = List.of();
    private volatile Map<Material, ShopItem> byMaterial = Map.of();

    /** Decimal places of the configured currency; set at the start of each load. */
    private volatile int fractionDigits = 2;

    public ShopCatalogue(Logger logger) {
        this.logger = logger;
    }

    @Override
    public void load(ConfigManager.ConfigBundle configs) {
        // Read the currency scale from the economy section rather than taking it
        // from a setter. Shop prices and balances must use the same scale, and
        // deriving it here removes any chance of the two being configured in the
        // wrong order.
        this.fractionDigits = configs.main()
                .section("economy").section("currency")
                .getInt("fraction-digits", 2, 0, 4);

        ConfigView root = configs.file("shop.yml");
        Optional<ConfigView> categorySection = root.optionalSection("categories");

        if (categorySection.isEmpty()) {
            this.categories = List.of();
            this.byMaterial = Map.of();
            logger.info("Server shop has no categories configured; /shop will report it as empty.");
            return;
        }

        List<ShopCategory> parsed = new ArrayList<>();
        Map<Material, ShopItem> index = new LinkedHashMap<>();

        ConfigView section = categorySection.get();
        for (String id : section.keys()) {
            ConfigView categoryView = section.section(id);
            parsed.add(parseCategory(id, categoryView, index));
        }

        this.categories = List.copyOf(parsed);
        this.byMaterial = Map.copyOf(index);
        registerCategoryPermissions(this.categories);

        logger.info("Server shop loaded: " + parsed.size() + " categories, "
                + index.size() + " items.");
    }

    /**
     * Registers a permission node for each category, defaulting to allowed.
     *
     * <p>Categories are operator-defined, so their nodes cannot be declared in
     * {@code plugin.yml} at build time. Without registering them, Bukkit treats an
     * unknown permission as operator-only, and every non-op player would open the
     * shop to find it empty. Registering with {@code TRUE} keeps the shop open by
     * default while still letting an operator revoke a category explicitly.
     */
    private void registerCategoryPermissions(List<ShopCategory> categories) {
        PluginManager pluginManager;
        try {
            pluginManager = Bukkit.getPluginManager();
        } catch (Exception | NoClassDefFoundError e) {
            // No server running (a test, or a very early load). Nothing to do.
            return;
        }
        if (pluginManager == null) {
            return;
        }
        for (ShopCategory category : categories) {
            String node = category.permission();
            // Re-adding an existing node throws, and reloads run this again.
            if (pluginManager.getPermission(node) == null) {
                pluginManager.addPermission(new Permission(node,
                        "Browse the '" + category.id() + "' shop category",
                        PermissionDefault.TRUE));
            }
        }
    }

    private ShopCategory parseCategory(String id, ConfigView view, Map<Material, ShopItem> index) {
        if (!id.matches("[a-z0-9_-]{1,32}")) {
            throw new ConfigException("shop.categories." + id,
                    "category id must be lowercase letters, digits, underscore or hyphen (max 32)");
        }

        String displayName = view.getString("display-name", capitalise(id));
        Material icon = resolveMaterial(view.getString("icon", "minecraft:chest"),
                "shop.categories." + id + ".icon");

        List<ShopItem> items = new ArrayList<>();
        ConfigView itemsView = view.section("items");
        for (String itemKey : itemsView.keys()) {
            ShopItem item = parseItem(id, itemKey, itemsView.section(itemKey));

            // A material priced in two categories would make "what does this cost"
            // ambiguous at sell time, where we look up by material alone.
            ShopItem existing = index.putIfAbsent(item.material(), item);
            if (existing != null) {
                throw new ConfigException("shop.categories." + id + ".items." + itemKey,
                        item.material() + " is already listed in category '"
                                + existing.categoryId() + "'; each item may appear once");
            }
            items.add(item);
        }

        if (items.isEmpty()) {
            throw new ConfigException("shop.categories." + id + ".items",
                    "category has no items, which would render as an empty page");
        }
        return new ShopCategory(id, displayName, icon, items);
    }

    private ShopItem parseItem(String categoryId, String itemKey, ConfigView view) {
        String path = "shop.categories." + categoryId + ".items." + itemKey;
        Material material = resolveMaterial(view.getString("material", itemKey), path + ".material");

        long buyPrice = view.has("buy-price")
                ? view.getMoney("buy-price", 0L, fractionDigits)
                : ShopItem.UNAVAILABLE;
        long sellPrice = view.has("sell-price")
                ? view.getMoney("sell-price", 0L, fractionDigits)
                : ShopItem.UNAVAILABLE;

        // A sell price above the buy price is an infinite money loop: buy, sell,
        // repeat. Refuse to start rather than let it run for a week unnoticed.
        if (buyPrice != ShopItem.UNAVAILABLE && sellPrice != ShopItem.UNAVAILABLE
                && sellPrice > buyPrice) {
            throw new ConfigException(path,
                    material + " sells for more than it costs to buy (" + sellPrice + " > " + buyPrice
                            + "). Players could buy and immediately resell for profit, "
                            + "creating unlimited money.");
        }

        int stackSize = material.getMaxStackSize();
        return new ShopItem(categoryId, material, buyPrice, sellPrice,
                view.getInt("max-buy-quantity", stackSize * 36, 1, 100_000),
                view.getInt("max-sell-quantity", stackSize * 36, 1, 100_000));
    }

    private static Material resolveMaterial(String raw, String path) {
        String value = raw.trim().toLowerCase(Locale.ROOT);
        NamespacedKey key = value.contains(":")
                ? NamespacedKey.fromString(value)
                : NamespacedKey.minecraft(value);
        if (key == null) {
            throw new ConfigException(path, "'" + raw + "' is not a valid item id");
        }
        Material material = Registry.MATERIAL.get(key);
        if (material == null) {
            throw new ConfigException(path, "no such item: '" + raw + "'");
        }
        if (!material.isItem()) {
            throw new ConfigException(path, raw + " exists but cannot be held as an item");
        }
        return material;
    }

    private static String capitalise(String id) {
        String spaced = id.replace('_', ' ').replace('-', ' ');
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    // ---------------------------------------------------------------- lookup

    public List<ShopCategory> categories() {
        return categories;
    }

    public Optional<ShopCategory> category(String id) {
        return categories.stream().filter(c -> c.id().equals(id)).findFirst();
    }

    /**
     * The authoritative entry for a material.
     *
     * <p>The only lookup used when pricing a real transaction.
     */
    public Optional<ShopItem> item(Material material) {
        return Optional.ofNullable(byMaterial.get(material));
    }

    public boolean isEmpty() {
        return categories.isEmpty();
    }

    public int itemCount() {
        return byMaterial.size();
    }
}
