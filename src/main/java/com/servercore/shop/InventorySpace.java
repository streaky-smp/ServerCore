package com.servercore.shop;

/**
 * How many more of an item will fit in an inventory.
 *
 * <p>Deliberately pure arithmetic over plain integers, with no Bukkit types. An
 * {@code ItemStack} cannot be constructed without a running server, so keeping
 * the calculation separate from the code that reads the inventory is what makes
 * the part most likely to be wrong -- the counting -- unit-testable.
 *
 * <p>The Bukkit-facing half lives in {@link com.servercore.shop.StandardShopService},
 * which extracts these numbers and calls in here.
 */
public final class InventorySpace {

    private InventorySpace() {
    }

    /**
     * Room remaining for an item, counting both partial stacks and empty slots.
     *
     * @param maxStackSize    the item's maximum stack size, e.g. 64
     * @param partialStacks   amounts of existing stacks of this exact item
     * @param emptySlots      fully empty slots available
     * @return how many more can be taken in, saturating at {@link Integer#MAX_VALUE}
     */
    public static int capacityFor(int maxStackSize, int[] partialStacks, int emptySlots) {
        if (maxStackSize <= 0) {
            throw new IllegalArgumentException("maxStackSize must be positive, got " + maxStackSize);
        }
        if (emptySlots < 0) {
            throw new IllegalArgumentException("emptySlots must not be negative, got " + emptySlots);
        }

        long capacity = 0L;
        for (int amount : partialStacks) {
            if (amount < 0) {
                throw new IllegalArgumentException("Stack amount must not be negative, got " + amount);
            }
            // A stack already at or over the limit contributes nothing. Over is
            // possible for items another plugin has overstacked; treat it as full
            // rather than as negative space.
            if (amount < maxStackSize) {
                capacity += maxStackSize - amount;
            }
        }
        capacity += (long) emptySlots * maxStackSize;

        return capacity > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) capacity;
    }

    /** Whether {@code wanted} of the item will fit. */
    public static boolean fits(int maxStackSize, int[] partialStacks, int emptySlots, int wanted) {
        return wanted <= capacityFor(maxStackSize, partialStacks, emptySlots);
    }

    /** Total of the given stack amounts, saturating rather than overflowing. */
    public static int totalHeld(int[] stackAmounts) {
        long total = 0L;
        for (int amount : stackAmounts) {
            total += Math.max(0, amount);
        }
        return total > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) total;
    }

    /**
     * The largest whole number of stacks that fits, for the "buy a full
     * inventory" shortcut in the quantity picker.
     */
    public static int wholeStacksThatFit(int maxStackSize, int[] partialStacks, int emptySlots) {
        return capacityFor(maxStackSize, partialStacks, emptySlots) / maxStackSize;
    }
}
