package com.streakysmp.auction;

import com.streakysmp.config.ConfigManager;
import com.streakysmp.core.Scheduling;
import com.streakysmp.core.Service;
import com.streakysmp.data.Database;
import com.streakysmp.economy.EconomyService;
import com.streakysmp.economy.TransactionType;
import com.streakysmp.log.AuditAction;
import com.streakysmp.log.AuditEntry;
import com.streakysmp.log.AuditLog;
import com.streakysmp.notify.Messages;
import com.streakysmp.notify.NotificationService;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Fixed-price auction house.
 *
 * <h2>Why there is no refund path</h2>
 * A purchase needs to move money and hand over an item, and the item cannot join
 * the money's transaction. Most designs debit, then deliver, then refund on
 * failure -- which leaves a window where the buyer is owed money by a system that
 * might crash before repaying it.
 *
 * <p>The auction house avoids that entirely. A listing already needs a collection
 * mechanism for items that expire unsold, so the same mechanism delivers
 * purchases: once the transaction commits, the item <em>belongs</em> to the buyer
 * and sits in their collection until claimed. If they happen to have room, it is
 * handed over immediately as a convenience. If they do not, nothing is owed and
 * nothing is at risk -- the item is simply waiting.
 *
 * <p>Item bytes are Paper's own serialisation, so an enchanted, renamed or
 * partially damaged item is returned exactly as it was listed.
 */
public final class AuctionService implements Service, ConfigManager.Reloadable {

    /** Upper bound on how many listings the browse screen loads at once. */
    private static final int BROWSE_LIMIT = 500;

    private final Database database;
    private final AuctionRepository repository;
    private final EconomyService economy;
    private final NotificationService notifications;
    private final AuditLog auditLog;
    private final Scheduling scheduling;
    private final Logger logger;

    private volatile AuctionSettings settings;
    private BukkitTask sweepTask;

    public AuctionService(Database database,
                          AuctionRepository repository,
                          EconomyService economy,
                          NotificationService notifications,
                          AuditLog auditLog,
                          Scheduling scheduling,
                          Logger logger) {
        this.database = database;
        this.repository = repository;
        this.economy = economy;
        this.notifications = notifications;
        this.auditLog = auditLog;
        this.scheduling = scheduling;
        this.logger = logger;
    }

    @Override
    public void load(ConfigManager.ConfigBundle configs) {
        int fractionDigits = configs.main()
                .section("economy").section("currency")
                .getInt("fraction-digits", 2, 0, 4);
        this.settings = AuctionSettings.from(configs.main().section("auction"), fractionDigits);
    }

    public AuctionSettings settings() {
        AuctionSettings snapshot = settings;
        if (snapshot == null) {
            throw new IllegalStateException("Auction settings accessed before configuration loaded");
        }
        return snapshot;
    }

    public AuctionRepository repository() {
        return repository;
    }

    @Override
    public void onEnable() {
        AuctionSettings config = settings();
        if (!config.enabled()) {
            logger.info("The auction house is disabled.");
            return;
        }
        long ticks = Math.max(20L * 30L, config.sweepInterval().toSeconds() * 20L);
        sweepTask = scheduling.asyncTimer(this::sweepQuietly, 20L * 30L, ticks);
    }

    @Override
    public void onDisable() {
        if (sweepTask != null) {
            sweepTask.cancel();
            sweepTask = null;
        }
    }

    // ------------------------------------------------------------- listing

    /**
     * Lists the item in the player's main hand.
     *
     * <p>Main thread. The item is removed from the inventory first and returned if
     * the database refuses, so a failure leaves the seller holding their item
     * rather than having lost it to a listing that does not exist.
     */
    public void listHeldItem(Player seller, long price, Consumer<AuctionResult> callback) {
        scheduling.ensureMainThread("Creating an auction listing");

        AuctionSettings config = settings();
        if (!config.enabled()) {
            callback.accept(AuctionResult.failure(AuctionResult.Outcome.DISABLED));
            return;
        }
        if (price < config.minPrice() || price > config.maxPrice()) {
            callback.accept(AuctionResult.failure(AuctionResult.Outcome.INVALID_PRICE));
            return;
        }

        ItemStack held = seller.getInventory().getItemInMainHand();
        if (held.isEmpty() || held.getType().isAir()) {
            callback.accept(AuctionResult.failure(AuctionResult.Outcome.NO_ITEM));
            return;
        }

        // Snapshot and clear the hand now, in this tick, so the same stack cannot
        // be listed twice from two rapid commands.
        ItemStack listed = held.clone();
        seller.getInventory().setItemInMainHand(null);

        byte[] itemData;
        String displayName;
        try {
            itemData = listed.serializeAsBytes();
            displayName = describe(listed);
        } catch (Throwable t) {
            logger.log(Level.SEVERE, "Could not serialise an item for listing; returning it", t);
            giveBack(seller, listed);
            callback.accept(AuctionResult.failure(AuctionResult.Outcome.FAILED));
            return;
        }

        UUID sellerId = seller.getUniqueId();
        long now = System.currentTimeMillis();
        AuctionListing listing = new AuctionListing(
                UUID.randomUUID().toString().substring(0, 8),
                sellerId,
                itemData,
                listed.getType().name().toLowerCase(java.util.Locale.ROOT),
                listed.getAmount(),
                price,
                displayName,
                now,
                now + config.listingDuration().toMillis(),
                ListingStatus.ACTIVE,
                null,
                null,
                false);

        scheduling.thenSync(
                scheduling.supplyAsync(() -> createListing(listing, config)),
                result -> {
                    if (!result.isSuccess()) {
                        giveBack(seller, listed);
                    }
                    callback.accept(result);
                },
                error -> {
                    logger.log(Level.SEVERE, "Listing creation failed; returning the item", error);
                    giveBack(seller, listed);
                    callback.accept(AuctionResult.failure(AuctionResult.Outcome.FAILED));
                });
    }

    /** Runs off-thread, in one transaction. */
    private AuctionResult createListing(AuctionListing listing, AuctionSettings config) {
        long fee = config.listingFeeFor(listing.price());
        try {
            AuctionResult result = database.inTransaction(connection -> {
                if (AuctionRepository.countActiveWithin(connection, listing.seller())
                        >= config.maxListingsPerPlayer()) {
                    return AuctionResult.failure(AuctionResult.Outcome.TOO_MANY_LISTINGS);
                }
                if (fee > 0 && !economy.debitWithin(connection, listing.seller(), fee,
                        TransactionType.AUCTION_FEE, "Listing fee for " + listing.id())) {
                    return AuctionResult.failure(AuctionResult.Outcome.CANNOT_AFFORD_FEE);
                }
                AuctionRepository.insertWithin(connection, listing);
                return AuctionResult.success(listing, fee);
            });

            if (result.isSuccess()) {
                auditLog.record(AuditEntry.builder(AuditAction.AUCTION_TRANSACTION)
                        .actor(listing.seller(), null)
                        .amount(listing.price())
                        .object(listing.id())
                        .details("listed " + listing.quantity() + "x " + listing.material()
                                + " (fee " + fee + ")")
                        .build());
            }
            return result;
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Listing creation rolled back", e);
            return AuctionResult.failure(AuctionResult.Outcome.FAILED);
        }
    }

    // ---------------------------------------------------------------- buying

    /**
     * Buys a listing by id.
     *
     * <p>Main thread. The listing is claimed, the money moves, and the item is
     * then delivered if there is room -- otherwise it waits in the buyer's
     * collection. Nothing is ever owed back.
     */
    public void buy(Player buyer, String listingId, Consumer<AuctionResult> callback) {
        scheduling.ensureMainThread("Buying an auction listing");

        AuctionSettings config = settings();
        if (!config.enabled()) {
            callback.accept(AuctionResult.failure(AuctionResult.Outcome.DISABLED));
            return;
        }

        UUID buyerId = buyer.getUniqueId();
        scheduling.thenSync(
                scheduling.supplyAsync(() -> executeBuy(buyerId, listingId, config)),
                result -> {
                    if (!result.isSuccess()) {
                        callback.accept(result);
                        return;
                    }
                    // Convenience delivery. Failure here costs nothing: the item is
                    // already the buyer's and is waiting in their collection.
                    deliverIfPossible(buyer, result.listing());
                    callback.accept(result);
                },
                error -> {
                    logger.log(Level.SEVERE, "Auction purchase failed", error);
                    callback.accept(AuctionResult.failure(AuctionResult.Outcome.FAILED));
                });
    }

    private AuctionResult executeBuy(UUID buyerId, String listingId, AuctionSettings config) {
        long now = System.currentTimeMillis();
        try {
            AuctionResult result = database.inTransaction(connection -> {
                AuctionListing listing = AuctionRepository.byIdWithin(connection, listingId)
                        .orElse(null);
                if (listing == null) {
                    return AuctionResult.failure(AuctionResult.Outcome.NOT_FOUND);
                }
                if (listing.isSeller(buyerId) && !config.allowBuyingOwnListings()) {
                    return AuctionResult.failure(AuctionResult.Outcome.OWN_LISTING);
                }

                // Claim it first. Whoever's UPDATE matches has bought it; everyone
                // else is told it is gone.
                if (!AuctionRepository.trySell(connection, listingId, buyerId, now)) {
                    return AuctionResult.failure(AuctionResult.Outcome.NO_LONGER_AVAILABLE);
                }

                long price = listing.price();
                if (price > 0) {
                    if (!economy.debitWithin(connection, buyerId, price,
                            TransactionType.AUCTION_BUY,
                            "Auction " + listingId + ": " + listing.displayName())) {
                        // Throwing rolls the claim back, so the listing stays on
                        // sale for somebody who can afford it.
                        throw new RolledBack(AuctionResult.Outcome.INSUFFICIENT_FUNDS);
                    }
                    long proceeds = config.sellerProceeds(price);
                    if (proceeds > 0 && !economy.creditWithin(connection, listing.seller(),
                            proceeds, TransactionType.AUCTION_SALE,
                            "Sold " + listing.displayName() + " (auction " + listingId + ")")) {
                        throw new RolledBack(AuctionResult.Outcome.FAILED);
                    }
                    // The tax is credited to nobody, so it leaves circulation.
                }
                AuctionListing sold = AuctionRepository.byIdWithin(connection, listingId)
                        .orElseThrow();
                return AuctionResult.success(sold, price);
            });

            if (result.isSuccess()) {
                AuctionListing listing = result.listing();
                long tax = config.salesTaxFor(listing.price());
                auditLog.record(AuditEntry.builder(AuditAction.AUCTION_TRANSACTION)
                        .actor(buyerId, null)
                        .target(listing.seller(), null)
                        .amount(listing.price())
                        .object(listing.id())
                        .details("bought " + listing.quantity() + "x " + listing.material()
                                + (tax > 0 ? " (tax " + tax + ")" : ""))
                        .build());

                notifications.notifyPlayer(listing.seller(), "auction.sold", Messages.of(
                        "item", listing.displayName(),
                        "amount", economy.money().format(config.sellerProceeds(listing.price())),
                        "tax", economy.money().format(tax)));
            }
            return result;
        } catch (RolledBack rolledBack) {
            return AuctionResult.failure(rolledBack.outcome);
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Auction purchase rolled back", e);
            return AuctionResult.failure(AuctionResult.Outcome.FAILED);
        }
    }

    // ------------------------------------------------------------ cancelling

    /** Withdraws a seller's own listing. The item moves to their collection. */
    public void cancel(UUID seller, String listingId, Consumer<AuctionResult> callback) {
        scheduling.thenSync(
                scheduling.supplyAsync(() -> {
                    try {
                        return database.inTransaction(connection -> {
                            AuctionListing listing =
                                    AuctionRepository.byIdWithin(connection, listingId).orElse(null);
                            if (listing == null) {
                                return AuctionResult.failure(AuctionResult.Outcome.NOT_FOUND);
                            }
                            if (!listing.isSeller(seller)) {
                                return AuctionResult.failure(AuctionResult.Outcome.NOT_PERMITTED);
                            }
                            if (!AuctionRepository.tryCancel(connection, listingId, seller)) {
                                // Somebody bought it in the meantime.
                                return AuctionResult.failure(
                                        AuctionResult.Outcome.NO_LONGER_AVAILABLE);
                            }
                            return AuctionResult.success(listing, 0L);
                        });
                    } catch (Exception e) {
                        logger.log(Level.SEVERE, "Listing cancellation failed", e);
                        return AuctionResult.failure(AuctionResult.Outcome.FAILED);
                    }
                }),
                callback,
                error -> callback.accept(AuctionResult.failure(AuctionResult.Outcome.FAILED)));
    }

    /** Administrative removal, with the item still returned to the seller. */
    public AuctionResult forceCancel(String listingId) {
        AuctionListing listing = repository.byId(listingId).orElse(null);
        if (listing == null) {
            return AuctionResult.failure(AuctionResult.Outcome.NOT_FOUND);
        }
        if (!repository.forceCancel(listingId)) {
            return AuctionResult.failure(AuctionResult.Outcome.NO_LONGER_AVAILABLE);
        }
        notifications.notifyPlayer(listing.seller(), "auction.removed-by-admin",
                Messages.of("item", listing.displayName()));
        return AuctionResult.success(listing, 0L);
    }

    // ----------------------------------------------------------- collection

    /**
     * Collects one item a player is owed.
     *
     * <p>The database claim happens before the item is handed over, and it is
     * conditional on the item being uncollected. Two fast clicks therefore cannot
     * produce two copies.
     */
    public void collect(Player player, String listingId, Consumer<AuctionResult> callback) {
        scheduling.ensureMainThread("Collecting an auction item");

        UUID playerId = player.getUniqueId();
        scheduling.thenSync(
                scheduling.supplyAsync(() -> repository.byId(listingId).orElse(null)),
                listing -> {
                    if (listing == null) {
                        callback.accept(AuctionResult.failure(AuctionResult.Outcome.NOT_FOUND));
                        return;
                    }
                    if (!listing.isOwedTo(playerId)) {
                        callback.accept(AuctionResult.failure(listing.collected()
                                ? AuctionResult.Outcome.ALREADY_COLLECTED
                                : AuctionResult.Outcome.NOT_PERMITTED));
                        return;
                    }
                    ItemStack item = deserialise(listing);
                    if (item == null) {
                        callback.accept(AuctionResult.failure(AuctionResult.Outcome.FAILED));
                        return;
                    }
                    if (!hasRoomFor(player, item)) {
                        callback.accept(AuctionResult.failure(
                                AuctionResult.Outcome.INVENTORY_FULL));
                        return;
                    }

                    scheduling.thenSync(
                            scheduling.supplyAsync(() ->
                                    repository.tryCollect(listingId, playerId)),
                            claimed -> {
                                if (!Boolean.TRUE.equals(claimed)) {
                                    callback.accept(AuctionResult.failure(
                                            AuctionResult.Outcome.ALREADY_COLLECTED));
                                    return;
                                }
                                giveBack(player, item);
                                callback.accept(AuctionResult.success(listing, 0L));
                            },
                            error -> callback.accept(AuctionResult.failure(
                                    AuctionResult.Outcome.FAILED)));
                },
                error -> callback.accept(AuctionResult.failure(AuctionResult.Outcome.FAILED)));
    }

    /**
     * Hands a just-bought item over if the buyer has room.
     *
     * <p>Purely a convenience. If it does not happen the item stays in their
     * collection, so there is no failure case to recover from.
     */
    private void deliverIfPossible(Player buyer, AuctionListing listing) {
        ItemStack item = deserialise(listing);
        if (item == null || !hasRoomFor(buyer, item)) {
            notifications.info(buyer, "auction.awaiting-collection", Messages.of(
                    "item", listing.displayName()));
            return;
        }
        UUID buyerId = buyer.getUniqueId();
        scheduling.thenSync(
                scheduling.supplyAsync(() -> repository.tryCollect(listing.id(), buyerId)),
                claimed -> {
                    if (Boolean.TRUE.equals(claimed)) {
                        giveBack(buyer, item);
                    } else {
                        notifications.info(buyer, "auction.awaiting-collection", Messages.of(
                                "item", listing.displayName()));
                    }
                },
                error -> logger.log(Level.WARNING,
                        "Could not deliver a bought auction item; it remains collectable", error));
    }

    // ---------------------------------------------------------------- sweep

    private void sweepQuietly() {
        try {
            sweep();
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Auction sweep failed", e);
        }
    }

    /** Expires overdue listings and purges long-settled ones. */
    public void sweep() {
        AuctionSettings config = settings();
        long now = System.currentTimeMillis();

        int expired = repository.expireOverdue(now);
        if (expired > 0) {
            logger.fine("Expired " + expired + " auction listing(s).");
        }

        // Only fully collected rows are removed, so nothing anybody is owed can
        // ever be pruned.
        int purged = repository.purgeSettledBefore(now - config.purgeAfter().toMillis());
        if (purged > 0) {
            logger.fine("Purged " + purged + " settled auction listing(s).");
        }
    }

    // ------------------------------------------------------------- queries

    public List<AuctionListing> browse() {
        return repository.active(System.currentTimeMillis(), BROWSE_LIMIT);
    }

    public List<AuctionListing> bySeller(UUID seller) {
        return repository.bySeller(seller);
    }

    public List<AuctionListing> awaitingCollection(UUID player) {
        return repository.awaitingCollection(player);
    }

    public int awaitingCollectionCount(UUID player) {
        return repository.awaitingCollectionCount(player);
    }

    public Optional<AuctionListing> byId(String listingId) {
        return repository.byId(listingId);
    }

    public int activeCount() {
        return repository.activeCount(System.currentTimeMillis());
    }

    // ------------------------------------------------------------ internals

    /** Reads a listing's item, or null if the bytes cannot be understood. */
    public static ItemStack deserialise(AuctionListing listing) {
        try {
            return ItemStack.deserializeBytes(listing.itemData());
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean hasRoomFor(Player player, ItemStack item) {
        // A simulated add would be exact but needs a temporary inventory; an
        // empty slot is sufficient for a single stack and is far cheaper.
        return player.getInventory().firstEmpty() != -1;
    }

    private void giveBack(Player player, ItemStack item) {
        var leftovers = player.getInventory().addItem(item);
        for (ItemStack leftover : leftovers.values()) {
            // Dropping beats deleting: the claim has already succeeded.
            player.getWorld().dropItemNaturally(player.getLocation(), leftover);
        }
    }

    /** Short human-readable label for a listed item. */
    private static String describe(ItemStack item) {
        String base = item.getType().name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
        String friendly = Character.toUpperCase(base.charAt(0)) + base.substring(1);
        return item.getAmount() > 1 ? item.getAmount() + "x " + friendly : friendly;
    }

    /** Signals a rollback with a reason. */
    private static final class RolledBack extends RuntimeException {
        private final AuctionResult.Outcome outcome;

        RolledBack(AuctionResult.Outcome outcome) {
            super(outcome.name(), null, false, false);
            this.outcome = outcome;
        }
    }

}
