package com.servercore.util;

import com.servercore.shop.InventorySpace;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Inventory reads and writes shared by every system that trades items.
 *
 * <p>Extracted so the server shop, player shops and the auction house all count,
 * remove and deliver items the same way. Three subtly different implementations
 * of "does this stack count as the item we mean" is exactly how a duplication
 * exploit gets in through the least-reviewed one.
 *
 * <p>Every method must be called on the main thread.
 */
public final class Inventories {

    private Inventories() {
    }

    /**
     * Whether a stack is the plain, unmodified form of a material.
     *
     * <p>Compared against a freshly constructed stack, so enchantments, custom
     * names, damage, stored components and anything a plugin attached all
     * disqualify it. An enchanted diamond pickaxe is not the item a plain price
     * list describes, and paying the plain price for one is the exploit this
     * prevents.
     */
    public static boolean isPlain(ItemStack stack, Material material) {
        if (stack == null || stack.getType() != material) {
            return false;
        }
        return stack.isSimilar(new ItemStack(material));
    }

    /** How many plain copies of {@code material} a player holds in their main inventory. */
    public static int countPlain(Player player, Material material) {
        int total = 0;
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (isPlain(stack, material)) {
                total += stack.getAmount();
            }
        }
        return total;
    }

    /** How many more of {@code material} will fit, counting partial stacks and empty slots. */
    public static int spaceFor(Player player, Material material) {
        ItemStack[] contents = player.getInventory().getStorageContents();
        List<Integer> partials = new ArrayList<>();
        int emptySlots = 0;

        for (ItemStack stack : contents) {
            if (stack == null || stack.getType().isAir()) {
                emptySlots++;
            } else if (isPlain(stack, material)) {
                partials.add(stack.getAmount());
            }
        }

        int[] amounts = new int[partials.size()];
        for (int i = 0; i < amounts.length; i++) {
            amounts[i] = partials.get(i);
        }
        return InventorySpace.capacityFor(material.getMaxStackSize(), amounts, emptySlots);
    }

    /**
     * Removes exactly {@code quantity} plain copies.
     *
     * <p>Operates on a snapshot of the contents array and writes it back in one
     * call, so the removal is atomic within the tick. That is what makes it
     * impossible to sell the same stack twice from two open menus.
     *
     * @return how many were actually removed
     */
    public static int removePlain(Player player, Material material, int quantity) {
        PlayerInventory inventory = player.getInventory();
        ItemStack[] contents = inventory.getStorageContents();
        int remaining = quantity;

        for (int slot = 0; slot < contents.length && remaining > 0; slot++) {
            ItemStack stack = contents[slot];
            if (!isPlain(stack, material)) {
                continue;
            }
            int take = Math.min(remaining, stack.getAmount());
            remaining -= take;
            if (stack.getAmount() == take) {
                contents[slot] = null;
            } else {
                stack.setAmount(stack.getAmount() - take);
            }
        }

        inventory.setStorageContents(contents);
        return quantity - remaining;
    }

    /**
     * Adds items, returning anything that would not fit.
     *
     * <p>Split into stack-sized pieces because a single oversized stack is
     * rejected outright by {@code addItem}.
     */
    public static List<ItemStack> give(Player player, Material material, int quantity) {
        int stackSize = material.getMaxStackSize();
        List<ItemStack> stacks = new ArrayList<>();
        int remaining = quantity;
        while (remaining > 0) {
            int amount = Math.min(stackSize, remaining);
            stacks.add(new ItemStack(material, amount));
            remaining -= amount;
        }

        Map<Integer, ItemStack> leftovers =
                player.getInventory().addItem(stacks.toArray(new ItemStack[0]));
        return List.copyOf(leftovers.values());
    }

    /**
     * Adds items, dropping at the player's feet anything that will not fit.
     *
     * <p>Used on the recovery paths -- returning a failed sale, restoring stock.
     * Dropping is the least-bad outcome there: the alternative is deleting items
     * the player is owed.
     *
     * @return how many stacks had to be dropped
     */
    public static int giveOrDrop(Player player, Material material, int quantity) {
        List<ItemStack> leftovers = give(player, material, quantity);
        for (ItemStack leftover : leftovers) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover);
        }
        return leftovers.size();
    }
}
