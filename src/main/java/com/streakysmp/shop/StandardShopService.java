package com.streakysmp.shop;

import com.streakysmp.core.Scheduling;
import com.streakysmp.economy.EconomyResult;
import com.streakysmp.economy.EconomyService;
import com.streakysmp.economy.TransactionType;
import com.streakysmp.log.AuditAction;
import com.streakysmp.log.AuditEntry;
import com.streakysmp.log.AuditLog;
import com.streakysmp.util.Inventories;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Default {@link ShopService}.
 *
 * <p>See the interface for why buying debits before delivering and selling
 * removes before paying.
 *
 * <h2>The residual window</h2>
 * Between the debit committing and the items being handed over there is a gap of
 * under one tick. A server crash inside that gap loses the purchase: the money is
 * gone and the items were never given. The window is tiny and the failure is
 * loud, but it is real, and closing it entirely would need a durable
 * pending-delivery table. That is deliberately not built here -- it is the right
 * answer for the auction house, where deliveries are asynchronous by nature, and
 * over-engineering for a sub-tick window in a shop is not.
 */
public final class StandardShopService implements ShopService {

    private final ShopCatalogue catalogue;
    private final EconomyService economy;
    private final AuditLog auditLog;
    private final Scheduling scheduling;
    private final Logger logger;

    public StandardShopService(ShopCatalogue catalogue,
                               EconomyService economy,
                               AuditLog auditLog,
                               Scheduling scheduling,
                               Logger logger) {
        this.catalogue = catalogue;
        this.economy = economy;
        this.auditLog = auditLog;
        this.scheduling = scheduling;
        this.logger = logger;
    }

    @Override
    public ShopCatalogue catalogue() {
        return catalogue;
    }

    // ------------------------------------------------------------------ buy

    @Override
    public void buy(Player player, Material material, int quantity, Consumer<ShopResult> callback) {
        scheduling.ensureMainThread("Shop purchase");

        ShopItem item = catalogue.item(material).orElse(null);
        if (item == null) {
            // Unreachable through the GUI, so this is a stale menu or a crafted
            // interaction. Worth recording either way.
            auditLog.securityViolation(player.getUniqueId(), player.getName(),
                    "Attempted to buy item not in catalogue: " + material);
            callback.accept(ShopResult.failure(ShopResult.Outcome.UNKNOWN_ITEM));
            return;
        }
        if (!item.buyable()) {
            callback.accept(ShopResult.failure(ShopResult.Outcome.NOT_BUYABLE));
            return;
        }

        int requested = ShopPricing.clampQuantity(quantity, item.maxBuyQuantity());
        if (requested < 1) {
            callback.accept(ShopResult.failure(ShopResult.Outcome.INVALID_QUANTITY));
            return;
        }

        if (spaceFor(player, material) < requested) {
            callback.accept(ShopResult.failure(ShopResult.Outcome.INVENTORY_FULL));
            return;
        }

        long total;
        try {
            total = ShopPricing.total(item.buyPrice(), requested);
        } catch (ArithmeticException e) {
            callback.accept(ShopResult.failure(ShopResult.Outcome.INVALID_QUANTITY));
            return;
        }

        UUID id = player.getUniqueId();
        String description = "Bought " + requested + "x " + item.key();

        scheduling.thenSync(
                scheduling.supplyAsync(() ->
                        economy.withdraw(id, total, TransactionType.SHOP_BUY, description)),
                payment -> {
                    if (!payment.isSuccess()) {
                        callback.accept(mapPaymentFailure(payment));
                        return;
                    }
                    deliver(player, item, requested, total, payment.newBalance(), callback);
                },
                error -> {
                    logger.log(Level.SEVERE, "Shop purchase failed for " + player.getName(), error);
                    callback.accept(ShopResult.failure(ShopResult.Outcome.FAILED));
                });
    }

    /**
     * Hands over a paid-for purchase, refunding if it no longer fits.
     *
     * <p>Capacity is re-checked here rather than trusted from before the debit:
     * a tick has passed, and the player may have picked something up in it.
     */
    private void deliver(Player player, ShopItem item, int quantity,
                         long paid, long balanceAfterDebit, Consumer<ShopResult> callback) {
        if (!player.isOnline() || spaceFor(player, item.material()) < quantity) {
            refund(player, item, quantity, paid, callback);
            return;
        }

        List<ItemStack> leftovers = Inventories.give(player, item.material(), quantity);
        if (!leftovers.isEmpty()) {
            // Should be impossible given the check immediately above. Dropping is
            // the least-bad outcome: the player paid, and deleting the items would
            // be the one thing worse than dropping them.
            logger.warning("Shop delivery overflowed for " + player.getName()
                    + " despite a capacity check; dropping " + leftovers.size() + " stack(s) at their feet.");
            for (ItemStack leftover : leftovers) {
                player.getWorld().dropItemNaturally(player.getLocation(), leftover);
            }
        }

        auditLog.record(AuditEntry.builder(AuditAction.SHOP_TRANSACTION)
                .actor(player.getUniqueId(), player.getName())
                .amount(paid)
                .object(item.key())
                .details("buy x" + quantity)
                .build());

        callback.accept(ShopResult.success(quantity, paid, balanceAfterDebit));
    }

    /** Returns money taken for a purchase that could not be delivered. */
    private void refund(Player player, ShopItem item, int quantity, long paid,
                        Consumer<ShopResult> callback) {
        UUID id = player.getUniqueId();
        scheduling.thenSync(
                scheduling.supplyAsync(() -> economy.deposit(id, paid, TransactionType.SHOP_SELL,
                        "Refund: could not deliver " + quantity + "x " + item.key())),
                refund -> {
                    if (refund.isSuccess()) {
                        auditLog.record(AuditEntry.builder(AuditAction.SHOP_TRANSACTION)
                                .actor(id, player.getName())
                                .amount(paid)
                                .object(item.key())
                                .details("refund x" + quantity + " (delivery failed)")
                                .build());
                        callback.accept(ShopResult.failure(
                                ShopResult.Outcome.REFUNDED, refund.newBalance()));
                    } else {
                        // Money taken and the refund also failed. This is the one
                        // case a human must look at, so make it impossible to miss.
                        logger.severe("REFUND FAILED for " + player.getName() + ": took "
                                + paid + " for " + quantity + "x " + item.key()
                                + " but could not return it. Manual correction required.");
                        auditLog.securityViolation(id, player.getName(),
                                "Refund failed: " + paid + " owed for undelivered " + item.key());
                        callback.accept(ShopResult.failure(ShopResult.Outcome.FAILED));
                    }
                },
                error -> {
                    logger.log(Level.SEVERE, "Refund threw for " + player.getName()
                            + "; " + paid + " owed for undelivered " + item.key(), error);
                    callback.accept(ShopResult.failure(ShopResult.Outcome.FAILED));
                });
    }

    // ----------------------------------------------------------------- sell

    @Override
    public void sell(Player player, Material material, int quantity, Consumer<ShopResult> callback) {
        scheduling.ensureMainThread("Shop sale");

        ShopItem item = catalogue.item(material).orElse(null);
        if (item == null) {
            auditLog.securityViolation(player.getUniqueId(), player.getName(),
                    "Attempted to sell item not in catalogue: " + material);
            callback.accept(ShopResult.failure(ShopResult.Outcome.UNKNOWN_ITEM));
            return;
        }
        if (!item.sellable()) {
            callback.accept(ShopResult.failure(ShopResult.Outcome.NOT_SELLABLE));
            return;
        }

        int held = countSellable(player, material);
        int toSell = Math.min(ShopPricing.clampQuantity(quantity, item.maxSellQuantity()), held);
        if (toSell < 1) {
            callback.accept(ShopResult.failure(
                    held < 1 ? ShopResult.Outcome.NOT_ENOUGH_ITEMS : ShopResult.Outcome.INVALID_QUANTITY));
            return;
        }

        long total;
        try {
            total = ShopPricing.total(item.sellPrice(), toSell);
        } catch (ArithmeticException e) {
            callback.accept(ShopResult.failure(ShopResult.Outcome.INVALID_QUANTITY));
            return;
        }

        // Items go first. Taking them synchronously, in this tick, is what makes
        // it impossible to sell the same stack twice from two menus.
        int removed = Inventories.removePlain(player, material, toSell);
        if (removed != toSell) {
            // Inventory changed under us. Put back whatever we took and abandon.
            if (removed > 0) {
                Inventories.giveOrDrop(player, material, removed);
            }
            callback.accept(ShopResult.failure(ShopResult.Outcome.NOT_ENOUGH_ITEMS));
            return;
        }

        UUID id = player.getUniqueId();
        String description = "Sold " + toSell + "x " + item.key();

        scheduling.thenSync(
                scheduling.supplyAsync(() ->
                        economy.deposit(id, total, TransactionType.SHOP_SELL, description)),
                payment -> {
                    if (!payment.isSuccess()) {
                        restore(player, item, toSell);
                        callback.accept(new ShopResult(
                                payment.outcome() == EconomyResult.Outcome.WOULD_EXCEED_MAX_BALANCE
                                        ? ShopResult.Outcome.REFUNDED
                                        : ShopResult.Outcome.FAILED,
                                0, 0L, payment.newBalance()));
                        return;
                    }
                    auditLog.record(AuditEntry.builder(AuditAction.SHOP_TRANSACTION)
                            .actor(id, player.getName())
                            .amount(total)
                            .object(item.key())
                            .details("sell x" + toSell)
                            .build());
                    callback.accept(ShopResult.success(toSell, total, payment.newBalance()));
                },
                error -> {
                    logger.log(Level.SEVERE, "Shop sale failed for " + player.getName(), error);
                    restore(player, item, toSell);
                    callback.accept(ShopResult.failure(ShopResult.Outcome.FAILED));
                });
    }

    /** Gives back items taken for a sale that was not paid for. */
    private void restore(Player player, ShopItem item, int quantity) {
        Inventories.giveOrDrop(player, item.material(), quantity);
        logger.warning("Returned " + quantity + "x " + item.key() + " to " + player.getName()
                + " because the sale could not be paid.");
        auditLog.record(AuditEntry.builder(AuditAction.SHOP_TRANSACTION)
                .actor(player.getUniqueId(), player.getName())
                .object(item.key())
                .details("sale reverted x" + quantity)
                .build());
    }

    // ------------------------------------------------------- inventory reads

    @Override
    public int countSellable(Player player, Material material) {
        scheduling.ensureMainThread("Counting sellable items");
        return Inventories.countPlain(player, material);
    }

    @Override
    public int spaceFor(Player player, Material material) {
        scheduling.ensureMainThread("Checking inventory space");
        return Inventories.spaceFor(player, material);
    }

    private static ShopResult mapPaymentFailure(EconomyResult payment) {
        ShopResult.Outcome outcome = switch (payment.outcome()) {
            case INSUFFICIENT_FUNDS -> ShopResult.Outcome.INSUFFICIENT_FUNDS;
            case INVALID_AMOUNT -> ShopResult.Outcome.INVALID_QUANTITY;
            default -> ShopResult.Outcome.FAILED;
        };
        return ShopResult.failure(outcome, payment.newBalance());
    }
}
