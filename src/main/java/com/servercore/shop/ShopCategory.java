package com.servercore.shop;

import org.bukkit.Material;

import java.util.List;

/**
 * A named group of shop items, rendered as one page of the shop.
 *
 * @param id          stable identifier used in config and permissions
 * @param displayName MiniMessage markup shown on the category icon
 * @param icon        the item used as the category's button
 */
public record ShopCategory(
        String id,
        String displayName,
        Material icon,
        List<ShopItem> items) {

    public ShopCategory {
        items = List.copyOf(items);
    }

    /**
     * Permission required to open this category.
     *
     * <p>Derived rather than configured so an operator adding a category gets a
     * working node without also having to remember to declare one.
     */
    public String permission() {
        return "server.shop.category." + id;
    }
}
