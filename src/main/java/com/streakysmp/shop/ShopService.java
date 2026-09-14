package com.streakysmp.shop;

import com.streakysmp.core.Service;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.function.Consumer;

/**
 * Buying from and selling to the server shop.
 *
 * <h2>The ordering problem</h2>
 * Money lives in a transactional database; items live in a player's inventory in
 * memory on the main thread. The two cannot share a transaction, so one of them
 * must move first and the other must be able to undo.
 *
 * <p>Both directions are ordered so that a failure leaves the player temporarily
 * <em>short</em>, never temporarily <em>ahead</em>:
 *
 * <ul>
 *   <li><strong>Buying</strong> debits first, then delivers. If delivery fails the
 *       money is refunded. The alternative -- deliver, then charge -- means a
 *       failed charge leaves the player holding free items, which is duplication.</li>
 *   <li><strong>Selling</strong> removes the items first, then pays. If payment
 *       fails the items are returned. The alternative -- pay, then take -- means a
 *       failed removal pays for items the player still has.</li>
 * </ul>
 *
 * <p>Every entry point must be called on the main thread; the inventory reads are
 * only meaningful there. The database work is dispatched internally.
 */
public interface ShopService extends Service {

    /**
     * Buys {@code quantity} of {@code material}.
     *
     * <p>The price is looked up server-side from the catalogue. The quantity is
     * clamped to the configured maximum, the stack limit, what the player can
     * afford and what will fit. Nothing about the price comes from the caller.
     *
     * @param callback invoked on the main thread with the outcome
     */
    void buy(Player player, Material material, int quantity, Consumer<ShopResult> callback);

    /**
     * Sells {@code quantity} of {@code material} from the player's inventory.
     *
     * <p>Only unmodified items count. An enchanted, renamed or damaged copy is not
     * the item the price list describes, and paying the plain price for one is how
     * a shop becomes an exploit.
     */
    void sell(Player player, Material material, int quantity, Consumer<ShopResult> callback);

    /** How many unmodified copies of {@code material} the player is holding. */
    int countSellable(Player player, Material material);

    /** How many more of {@code material} will fit in the player's inventory. */
    int spaceFor(Player player, Material material);

    ShopCatalogue catalogue();
}
