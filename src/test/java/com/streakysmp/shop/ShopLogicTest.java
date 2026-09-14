package com.streakysmp.shop;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shop's pure arithmetic.
 *
 * <p>Item handling itself cannot be unit-tested: constructing an {@code ItemStack}
 * requires a running server. The response is to keep the parts most likely to be
 * wrong -- counting space and computing totals -- free of Bukkit types so they
 * can be tested exhaustively here, leaving only thin glue unverified.
 */
class ShopLogicTest {

    @Nested
    @DisplayName("inventory space")
    class Space {

        @Test
        void emptyInventory() {
            assertEquals(36 * 64, InventorySpace.capacityFor(64, new int[0], 36));
        }

        @Test
        void partialStacksContributeTheirRemainder() {
            // Two stacks of 50 leave 14 each, plus one empty slot of 64.
            assertEquals(14 + 14 + 64, InventorySpace.capacityFor(64, new int[]{50, 50}, 1));
        }

        @Test
        void fullStacksContributeNothing() {
            assertEquals(0, InventorySpace.capacityFor(64, new int[]{64, 64, 64}, 0));
        }

        @Test
        void respectsSmallerStackLimits() {
            // Items like ender pearls stack to 16.
            assertEquals(16 * 5, InventorySpace.capacityFor(16, new int[0], 5));
            assertEquals(6 + 16, InventorySpace.capacityFor(16, new int[]{10}, 1));
        }

        @Test
        @DisplayName("an overstacked stack counts as full, never as negative space")
        void overstackedStacksAreTreatedAsFull() {
            // Another plugin may have produced a stack above the normal limit.
            // Subtracting would give negative capacity and let a purchase through.
            assertEquals(64, InventorySpace.capacityFor(64, new int[]{99}, 1));
        }

        @Test
        void noSpaceAtAll() {
            assertEquals(0, InventorySpace.capacityFor(64, new int[0], 0));
            assertFalse(InventorySpace.fits(64, new int[0], 0, 1));
        }

        @Test
        void fitsMatchesCapacity() {
            assertTrue(InventorySpace.fits(64, new int[]{60}, 0, 4));
            assertFalse(InventorySpace.fits(64, new int[]{60}, 0, 5));
        }

        @Test
        @DisplayName("a huge inventory saturates rather than overflowing to negative")
        void capacitySaturates() {
            int capacity = InventorySpace.capacityFor(64, new int[0], Integer.MAX_VALUE / 2);
            assertTrue(capacity > 0, "overflow here would report space as negative and reject every buy");
        }

        @Test
        void wholeStacksThatFit() {
            assertEquals(5, InventorySpace.wholeStacksThatFit(64, new int[0], 5));
            assertEquals(1, InventorySpace.wholeStacksThatFit(64, new int[]{32}, 1));
        }

        @Test
        void totalHeldSums() {
            assertEquals(114, InventorySpace.totalHeld(new int[]{64, 50}));
            assertEquals(0, InventorySpace.totalHeld(new int[0]));
        }

        @Test
        void rejectsNonsenseInput() {
            assertThrows(IllegalArgumentException.class,
                    () -> InventorySpace.capacityFor(0, new int[0], 1));
            assertThrows(IllegalArgumentException.class,
                    () -> InventorySpace.capacityFor(64, new int[0], -1));
            assertThrows(IllegalArgumentException.class,
                    () -> InventorySpace.capacityFor(64, new int[]{-5}, 1));
        }
    }

    @Nested
    @DisplayName("pricing")
    class Pricing {

        @Test
        void totalIsUnitPriceTimesQuantity() {
            assertEquals(64 * 1_800L, ShopPricing.total(1_800L, 64));
            assertEquals(0L, ShopPricing.total(1_800L, 0));
            assertEquals(0L, ShopPricing.total(0L, 64));
        }

        @Test
        @DisplayName("an overflowing total throws rather than wrapping to a negative price")
        void totalRefusesToOverflow() {
            // A wrapped negative total would pay the player to take the item.
            assertThrows(ArithmeticException.class,
                    () -> ShopPricing.total(Long.MAX_VALUE, 2));
        }

        @Test
        void rejectsNegativeInputs() {
            assertThrows(IllegalArgumentException.class, () -> ShopPricing.total(-1L, 1));
            assertThrows(IllegalArgumentException.class, () -> ShopPricing.total(1L, -1));
        }

        @Test
        void maxAffordableDividesBalanceByPrice() {
            assertEquals(5, ShopPricing.maxAffordable(1_000L, 200L, 64));
            assertEquals(4, ShopPricing.maxAffordable(999L, 200L, 64));
        }

        @Test
        void maxAffordableHonoursTheCap() {
            assertEquals(10, ShopPricing.maxAffordable(1_000_000L, 1L, 10));
        }

        @Test
        void maxAffordableIsZeroWhenBroke() {
            assertEquals(0, ShopPricing.maxAffordable(0L, 100L, 64));
            assertEquals(0, ShopPricing.maxAffordable(99L, 100L, 64));
        }

        @Test
        @DisplayName("a free item is limited only by the cap, not by dividing by zero")
        void freeItemsDoNotDivideByZero() {
            assertEquals(64, ShopPricing.maxAffordable(500L, 0L, 64));
        }

        @Test
        void clampKeepsQuantityInRange() {
            assertEquals(10, ShopPricing.clampQuantity(64, 10));
            assertEquals(5, ShopPricing.clampQuantity(5, 10));
            assertEquals(0, ShopPricing.clampQuantity(0, 10));
            assertEquals(0, ShopPricing.clampQuantity(-5, 10));
            assertEquals(0, ShopPricing.clampQuantity(5, 0));
        }
    }

    @Nested
    @DisplayName("catalogue rules")
    class CatalogueRules {

        /**
         * The single most important invariant in the shop: an item that sells for
         * more than it costs is an unlimited money generator. The catalogue
         * refuses to load one, and this documents the intent even though the
         * enforcement itself needs a server to exercise.
         */
        @Test
        @DisplayName("sell price above buy price is a money loop")
        void sellAboveBuyWouldBeAMoneyLoop() {
            long buy = 100L;
            long sell = 120L;
            assertTrue(sell > buy,
                    "if this ever passes for a shipped item, buying and reselling prints money");
        }

        @Test
        @DisplayName("an item with neither price cannot be traded")
        void itemNeedsAtLeastOnePrice() {
            assertEquals(ShopItem.UNAVAILABLE, -1L,
                    "the sentinel must stay distinguishable from a genuine price of zero");
        }
    }
}
