package com.servercore.playershop;

import com.servercore.claim.Claim;
import com.servercore.claim.ClaimService;
import com.servercore.config.ConfigManager;
import com.servercore.core.Scheduling;
import com.servercore.core.Service;
import com.servercore.data.Database;
import com.servercore.economy.EconomyService;
import com.servercore.economy.TransactionType;
import com.servercore.log.AuditAction;
import com.servercore.log.AuditEntry;
import com.servercore.log.AuditLog;
import com.servercore.util.Inventories;
import com.servercore.util.Text;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Player-owned shops.
 *
 * <h2>The three-way atomicity problem</h2>
 * A purchase moves money from the customer to the owner, decrements the shop's
 * stock, and hands an item to the customer. The first two live in the database
 * and can share a transaction. The third is a player's inventory in memory.
 *
 * <p>Money and stock therefore commit together, and the item is handed over
 * afterwards. If delivery fails, both are reversed. The ordering is chosen so a
 * failure leaves the customer temporarily short rather than temporarily ahead --
 * the same rule the server shop follows, for the same reason: being owed money is
 * recoverable, free items are duplication.
 *
 * <h2>Why a shop must sit on protected land</h2>
 * A shop on unclaimed ground can be broken by anyone, and its stock is then
 * unreachable. Creation requires the block to be inside the owner's own claim or
 * on a plot they hold, so the thing customers interact with cannot be destroyed
 * by a passer-by.
 */
public final class PlayerShopService implements Service, ConfigManager.Reloadable {

    private static final int MAX_NAME_LENGTH = 32;
    private static final int MAX_DESCRIPTION_LENGTH = 96;

    private final Database database;
    private final PlayerShopRepository repository;
    private final EconomyService economy;
    private final ClaimService claims;
    private final AuditLog auditLog;
    private final Scheduling scheduling;
    private final Logger logger;

    private volatile PlayerShopSettings settings;

    /**
     * Block location to shop id, for main-thread lookups.
     *
     * <p>A player clicking a block must be answered within the tick, and a
     * database query cannot run on the main thread. The map holds one short
     * string per shop and is kept in step on create and delete.
     */
    private final java.util.Map<String, String> shopIdByLocation =
            new java.util.concurrent.ConcurrentHashMap<>();

    public PlayerShopService(Database database,
                             PlayerShopRepository repository,
                             EconomyService economy,
                             ClaimService claims,
                             AuditLog auditLog,
                             Scheduling scheduling,
                             Logger logger) {
        this.database = database;
        this.repository = repository;
        this.economy = economy;
        this.claims = claims;
        this.auditLog = auditLog;
        this.scheduling = scheduling;
        this.logger = logger;
    }

    @Override
    public void load(ConfigManager.ConfigBundle configs) {
        int fractionDigits = configs.main()
                .section("economy").section("currency")
                .getInt("fraction-digits", 2, 0, 4);
        this.settings = PlayerShopSettings.from(
                configs.main().section("player-shops"), fractionDigits);
    }

    public PlayerShopSettings settings() {
        PlayerShopSettings snapshot = settings;
        if (snapshot == null) {
            throw new IllegalStateException("Player shop settings accessed before configuration loaded");
        }
        return snapshot;
    }

    public PlayerShopRepository repository() {
        return repository;
    }

    /**
     * Builds the location index.
     *
     * <p>Called once at startup on a worker thread, before players can connect.
     */
    public void loadIndex() {
        shopIdByLocation.clear();
        for (PlayerShopRepository.DirectoryEntry entry : repository.directory()) {
            PlayerShop shop = entry.shop();
            shopIdByLocation.put(shop.locationKey(), shop.id());
        }
        logger.info("Loaded " + shopIdByLocation.size() + " player shop(s).");
    }

    /**
     * The id of the shop anchored to a block, or null.
     *
     * <p>Main-thread safe: reads the in-memory index only.
     */
    public String shopIdAt(String worldName, int x, int y, int z) {
        return shopIdByLocation.get(PlayerShop.locationKey(worldName, x, y, z));
    }

    public boolean hasShopAt(String worldName, int x, int y, int z) {
        return shopIdAt(worldName, x, y, z) != null;
    }

    public int indexedShopCount() {
        return shopIdByLocation.size();
    }

    // ------------------------------------------------------------ lifecycle

    /**
     * Creates a shop at a block, charging the creation fee.
     *
     * <p>Blocking; call off the main thread. The location checks use the claim
     * index, which is safe from any thread.
     */
    public PlayerShopResult create(UUID owner, String name, String description,
                                   String worldName, int x, int y, int z) {
        PlayerShopSettings config = settings();
        if (!config.enabled()) {
            return PlayerShopResult.failure(PlayerShopResult.Outcome.NOT_TRADING);
        }

        String cleanName = sanitise(name, MAX_NAME_LENGTH);
        if (cleanName == null) {
            return PlayerShopResult.failure(PlayerShopResult.Outcome.INVALID_NAME);
        }
        String cleanDescription = description == null
                ? null : sanitise(description, MAX_DESCRIPTION_LENGTH);

        if (repository.byLocation(worldName, x, y, z).isPresent()) {
            return PlayerShopResult.failure(PlayerShopResult.Outcome.LOCATION_TAKEN);
        }

        // The shop must be somewhere its owner controls, or its stock is one
        // pickaxe away from being someone else's.
        String claimId = null;
        if (config.requireProtectedLocation()) {
            Claim claim = claims.claimAt(worldName, x, z).orElse(null);
            if (claim == null || !claim.isOwner(owner)) {
                return PlayerShopResult.failure(PlayerShopResult.Outcome.UNPROTECTED_LOCATION);
            }
            claimId = claim.id();
        } else {
            Claim claim = claims.claimAt(worldName, x, z).orElse(null);
            claimId = claim == null ? null : claim.id();
        }

        String id = UUID.randomUUID().toString().substring(0, 8);
        PlayerShop shop = new PlayerShop(id, owner, cleanName, cleanDescription,
                worldName, x, y, z, claimId, null, PlayerShopStatus.OPEN,
                System.currentTimeMillis(), 0L, List.of());

        long fee = config.creationFee();
        try {
            PlayerShopResult result = database.inTransaction(connection -> {
                if (config.hasShopLimit()
                        && PlayerShopRepository.countOwnedWithin(connection, owner)
                            >= config.maxShopsPerOwner()) {
                    return PlayerShopResult.failure(PlayerShopResult.Outcome.TOO_MANY_SHOPS);
                }
                if (fee > 0 && !economy.debitWithin(connection, owner, fee,
                        TransactionType.SHOP_RENT, "Player shop creation fee")) {
                    return PlayerShopResult.failure(PlayerShopResult.Outcome.INSUFFICIENT_FUNDS);
                }
                repository.saveWithin(connection, shop);
                return PlayerShopResult.success(shop, 0, fee);
            });

            if (result.isSuccess()) {
                shopIdByLocation.put(shop.locationKey(), shop.id());
                auditLog.record(AuditEntry.builder(AuditAction.SHOP_CREATED)
                        .actor(owner, null)
                        .amount(fee)
                        .object(id)
                        .details(worldName + " " + x + "," + y + "," + z)
                        .build());
            }
            return result;
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Player shop creation failed and was rolled back", e);
            return PlayerShopResult.failure(PlayerShopResult.Outcome.FAILED);
        }
    }

    /**
     * Deletes a shop.
     *
     * <p>Refuses while stock remains. The spec is explicit that shop inventory
     * must never be silently destroyed, and the honest way to honour that is to
     * make the owner collect it first rather than quietly dropping it somewhere
     * they may never look.
     */
    public PlayerShopResult delete(UUID actor, String shopId, boolean adminOverride) {
        PlayerShop shop = repository.byId(shopId).orElse(null);
        if (shop == null) {
            return PlayerShopResult.failure(PlayerShopResult.Outcome.NOT_FOUND);
        }
        if (!adminOverride && !shop.isOwner(actor)) {
            return PlayerShopResult.failure(PlayerShopResult.Outcome.NOT_PERMITTED);
        }
        if (shop.totalStock() > 0) {
            return PlayerShopResult.failure(PlayerShopResult.Outcome.STOCK_REMAINING);
        }

        repository.delete(shopId);
        shopIdByLocation.remove(shop.locationKey());
        auditLog.record(AuditEntry.builder(AuditAction.SHOP_DELETED)
                .actor(actor, null)
                .object(shopId)
                .details(adminOverride ? "admin removal" : "owner removal")
                .build());
        return PlayerShopResult.success(shop, 0, 0L);
    }

    public PlayerShopResult setStatus(UUID actor, String shopId,
                                      PlayerShopStatus status, boolean adminOverride) {
        PlayerShop shop = repository.byId(shopId).orElse(null);
        if (shop == null) {
            return PlayerShopResult.failure(PlayerShopResult.Outcome.NOT_FOUND);
        }
        if (!adminOverride && !shop.isOwner(actor)) {
            return PlayerShopResult.failure(PlayerShopResult.Outcome.NOT_PERMITTED);
        }
        // An owner may not reopen a shop an administrator suspended, nor one
        // closed because its rent lapsed.
        if (!adminOverride && !shop.status().ownerCanReopen()
                && shop.status() != PlayerShopStatus.OPEN) {
            return PlayerShopResult.failure(PlayerShopResult.Outcome.NOT_PERMITTED);
        }

        PlayerShop updated = shop.withStatus(status);
        repository.save(updated);
        return PlayerShopResult.success(updated, 0, 0L);
    }

    public PlayerShopResult rename(UUID actor, String shopId, String name, String description) {
        PlayerShop shop = repository.byId(shopId).orElse(null);
        if (shop == null) {
            return PlayerShopResult.failure(PlayerShopResult.Outcome.NOT_FOUND);
        }
        if (!shop.isOwner(actor)) {
            return PlayerShopResult.failure(PlayerShopResult.Outcome.NOT_PERMITTED);
        }
        String cleanName = name == null ? shop.name() : sanitise(name, MAX_NAME_LENGTH);
        if (cleanName == null) {
            return PlayerShopResult.failure(PlayerShopResult.Outcome.INVALID_NAME);
        }
        String cleanDescription = description == null
                ? shop.description() : sanitise(description, MAX_DESCRIPTION_LENGTH);

        PlayerShop updated = shop.withDetails(cleanName, cleanDescription);
        repository.save(updated);
        return PlayerShopResult.success(updated, 0, 0L);
    }

    // --------------------------------------------------------------- offers

    /** Adds or updates an offer's prices. Pass {@link ShopOffer#UNAVAILABLE} to disable a direction. */
    public PlayerShopResult setOffer(UUID actor, String shopId, Material material,
                                     long buyPrice, long sellPrice) {
        PlayerShop shop = repository.byId(shopId).orElse(null);
        if (shop == null) {
            return PlayerShopResult.failure(PlayerShopResult.Outcome.NOT_FOUND);
        }
        if (!shop.isOwner(actor)) {
            return PlayerShopResult.failure(PlayerShopResult.Outcome.NOT_PERMITTED);
        }
        if (buyPrice == ShopOffer.UNAVAILABLE && sellPrice == ShopOffer.UNAVAILABLE) {
            return PlayerShopResult.failure(PlayerShopResult.Outcome.INVALID_PRICE);
        }
        PlayerShopSettings config = settings();
        if (buyPrice > config.maxItemPrice() || sellPrice > config.maxItemPrice()) {
            return PlayerShopResult.failure(PlayerShopResult.Outcome.INVALID_PRICE);
        }
        // A shop that pays more than it charges is an arbitrage machine: buy from
        // it, sell straight back, repeat. Refuse rather than let the owner
        // discover it by going bankrupt overnight.
        if (buyPrice != ShopOffer.UNAVAILABLE && sellPrice != ShopOffer.UNAVAILABLE
                && sellPrice > buyPrice) {
            return PlayerShopResult.failure(PlayerShopResult.Outcome.INVALID_PRICE);
        }
        if (shop.offer(material).isEmpty()
                && shop.offers().size() >= config.offerLimitFor(shop)) {
            return PlayerShopResult.failure(PlayerShopResult.Outcome.TOO_MANY_SHOPS);
        }

        repository.saveOffer(shopId, material, buyPrice, sellPrice);
        return PlayerShopResult.success(repository.byId(shopId).orElse(shop), 0, 0L);
    }

    /** Removes an offer. Refuses while it still holds stock. */
    public PlayerShopResult removeOffer(UUID actor, String shopId, Material material) {
        PlayerShop shop = repository.byId(shopId).orElse(null);
        if (shop == null) {
            return PlayerShopResult.failure(PlayerShopResult.Outcome.NOT_FOUND);
        }
        if (!shop.isOwner(actor)) {
            return PlayerShopResult.failure(PlayerShopResult.Outcome.NOT_PERMITTED);
        }
        ShopOffer offer = shop.offer(material).orElse(null);
        if (offer == null) {
            return PlayerShopResult.failure(PlayerShopResult.Outcome.NO_SUCH_OFFER);
        }
        if (offer.stock() > 0) {
            return PlayerShopResult.failure(PlayerShopResult.Outcome.STOCK_REMAINING);
        }
        repository.removeOffer(shopId, material);
        return PlayerShopResult.success(shop, 0, 0L);
    }

    // ---------------------------------------------------------------- stock

    /**
     * Moves items from the owner's inventory into the shop's stock.
     *
     * <p>Main thread. Items are removed first, then the stock row is incremented;
     * if the database write fails the items go straight back.
     */
    public void stock(Player owner, String shopId, Material material, int quantity,
                      Consumer<PlayerShopResult> callback) {
        scheduling.ensureMainThread("Stocking a player shop");

        int held = Inventories.countPlain(owner, material);
        int toMove = Math.min(Math.max(0, quantity), held);
        if (toMove < 1) {
            callback.accept(PlayerShopResult.failure(PlayerShopResult.Outcome.NOT_ENOUGH_ITEMS));
            return;
        }

        UUID actor = owner.getUniqueId();
        scheduling.thenSync(
                scheduling.supplyAsync(() -> validateStockable(actor, shopId, material, toMove)),
                problem -> {
                    if (problem != null) {
                        callback.accept(PlayerShopResult.failure(problem));
                        return;
                    }
                    int removed = Inventories.removePlain(owner, material, toMove);
                    if (removed < 1) {
                        callback.accept(PlayerShopResult.failure(
                                PlayerShopResult.Outcome.NOT_ENOUGH_ITEMS));
                        return;
                    }
                    scheduling.thenSync(
                            scheduling.supplyAsync(() -> database.inTransaction(connection ->
                                    PlayerShopRepository.addStock(connection, shopId, material, removed))),
                            ok -> {
                                if (Boolean.TRUE.equals(ok)) {
                                    callback.accept(PlayerShopResult.success(null, removed, 0L));
                                } else {
                                    Inventories.giveOrDrop(owner, material, removed);
                                    callback.accept(PlayerShopResult.failure(
                                            PlayerShopResult.Outcome.NO_SUCH_OFFER));
                                }
                            },
                            error -> {
                                logger.log(Level.SEVERE, "Stocking failed; returning items", error);
                                Inventories.giveOrDrop(owner, material, removed);
                                callback.accept(PlayerShopResult.failure(
                                        PlayerShopResult.Outcome.FAILED));
                            });
                },
                error -> callback.accept(PlayerShopResult.failure(PlayerShopResult.Outcome.FAILED)));
    }

    /** Runs off-thread. Returns the problem, or null if stocking may proceed. */
    private PlayerShopResult.Outcome validateStockable(UUID actor, String shopId,
                                                       Material material, int quantity) {
        PlayerShop shop = repository.byId(shopId).orElse(null);
        if (shop == null) {
            return PlayerShopResult.Outcome.NOT_FOUND;
        }
        if (!shop.isOwner(actor)) {
            return PlayerShopResult.Outcome.NOT_PERMITTED;
        }
        ShopOffer offer = shop.offer(material).orElse(null);
        if (offer == null) {
            return PlayerShopResult.Outcome.NO_SUCH_OFFER;
        }
        if (offer.stock() + quantity > settings().maxStockPerOffer()) {
            return PlayerShopResult.Outcome.INVALID_QUANTITY;
        }
        return null;
    }

    /** Takes stock back out of the shop and into the owner's inventory. */
    public void withdrawStock(Player owner, String shopId, Material material, int quantity,
                              Consumer<PlayerShopResult> callback) {
        scheduling.ensureMainThread("Withdrawing shop stock");

        int space = Inventories.spaceFor(owner, material);
        int wanted = Math.min(Math.max(0, quantity), space);
        if (wanted < 1) {
            callback.accept(PlayerShopResult.failure(PlayerShopResult.Outcome.INVENTORY_FULL));
            return;
        }

        UUID actor = owner.getUniqueId();
        scheduling.thenSync(
                scheduling.supplyAsync(() -> database.inTransaction(connection -> {
                    PlayerShop shop = repository.byId(shopId).orElse(null);
                    if (shop == null || !shop.isOwner(actor)) {
                        return 0;
                    }
                    int available = PlayerShopRepository.stockOf(connection, shopId, material);
                    int take = Math.min(wanted, available);
                    if (take < 1) {
                        return 0;
                    }
                    return PlayerShopRepository.tryTakeStock(connection, shopId, material, take)
                            ? take : 0;
                })),
                taken -> {
                    if (taken == null || taken < 1) {
                        callback.accept(PlayerShopResult.failure(
                                PlayerShopResult.Outcome.OUT_OF_STOCK));
                        return;
                    }
                    Inventories.giveOrDrop(owner, material, taken);
                    callback.accept(PlayerShopResult.success(null, taken, 0L));
                },
                error -> callback.accept(PlayerShopResult.failure(PlayerShopResult.Outcome.FAILED)));
    }

    // ------------------------------------------------------------- trading

    /**
     * A customer buys from the shop.
     *
     * <p>Money and stock commit together; the item follows. See the class notes.
     */
    public void buy(Player customer, String shopId, Material material, int quantity,
                    Consumer<PlayerShopResult> callback) {
        scheduling.ensureMainThread("Buying from a player shop");

        if (quantity < 1) {
            callback.accept(PlayerShopResult.failure(PlayerShopResult.Outcome.INVALID_QUANTITY));
            return;
        }
        if (Inventories.spaceFor(customer, material) < quantity) {
            callback.accept(PlayerShopResult.failure(PlayerShopResult.Outcome.INVENTORY_FULL));
            return;
        }

        UUID buyer = customer.getUniqueId();
        scheduling.thenSync(
                scheduling.supplyAsync(() -> executeBuy(buyer, shopId, material, quantity)),
                result -> {
                    if (!result.isSuccess()) {
                        callback.accept(result);
                        return;
                    }
                    // Money and stock have moved. Deliver, and reverse both if the
                    // inventory changed underneath us in the intervening tick.
                    if (!customer.isOnline()
                            || Inventories.spaceFor(customer, material) < result.quantity()) {
                        reverseBuy(buyer, shopId, material, result, callback);
                        return;
                    }
                    Inventories.giveOrDrop(customer, material, result.quantity());
                    callback.accept(result);
                },
                error -> {
                    logger.log(Level.SEVERE, "Player shop purchase failed", error);
                    callback.accept(PlayerShopResult.failure(PlayerShopResult.Outcome.FAILED));
                });
    }

    /** Runs off-thread, inside one transaction. */
    private PlayerShopResult executeBuy(UUID buyer, String shopId, Material material, int quantity) {
        try {
            return database.inTransaction(connection -> {
                PlayerShopStatus status = PlayerShopRepository.statusOf(connection, shopId)
                        .orElse(null);
                if (status == null) {
                    return PlayerShopResult.failure(PlayerShopResult.Outcome.NOT_FOUND);
                }
                if (!status.trading()) {
                    return PlayerShopResult.failure(PlayerShopResult.Outcome.NOT_TRADING);
                }

                // Price is read inside the transaction, never taken from the click.
                long[] prices = PlayerShopRepository.pricesOf(connection, shopId, material)
                        .orElse(null);
                if (prices == null || prices[0] == ShopOffer.UNAVAILABLE) {
                    return PlayerShopResult.failure(PlayerShopResult.Outcome.NO_SUCH_OFFER);
                }
                long unitPrice = prices[0];
                long total;
                try {
                    total = Math.multiplyExact(unitPrice, quantity);
                } catch (ArithmeticException e) {
                    return PlayerShopResult.failure(PlayerShopResult.Outcome.INVALID_QUANTITY);
                }

                PlayerShop shop = repository.byId(shopId).orElse(null);
                if (shop == null) {
                    return PlayerShopResult.failure(PlayerShopResult.Outcome.NOT_FOUND);
                }
                if (shop.isOwner(buyer)) {
                    // Buying from yourself would just cycle money and stock.
                    return PlayerShopResult.failure(PlayerShopResult.Outcome.NOT_PERMITTED);
                }

                if (!PlayerShopRepository.tryTakeStock(connection, shopId, material, quantity)) {
                    return PlayerShopResult.failure(PlayerShopResult.Outcome.OUT_OF_STOCK);
                }
                if (total > 0) {
                    if (!economy.debitWithin(connection, buyer, total,
                            TransactionType.PLAYERSHOP_PURCHASE,
                            "Bought " + quantity + "x " + material.name() + " from " + shop.name())) {
                        // Throwing rolls the stock decrement back with it.
                        throw new RolledBack(PlayerShopResult.Outcome.INSUFFICIENT_FUNDS);
                    }
                    if (!economy.creditWithin(connection, shop.owner(), total,
                            TransactionType.PLAYERSHOP_PURCHASE,
                            "Sold " + quantity + "x " + material.name() + " at " + shop.name())) {
                        throw new RolledBack(PlayerShopResult.Outcome.OWNER_CANNOT_PAY);
                    }
                    PlayerShopRepository.addRevenue(connection, shopId, total);
                }
                return PlayerShopResult.success(shop, quantity, total);
            });
        } catch (RolledBack rolledBack) {
            return PlayerShopResult.failure(rolledBack.outcome);
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Player shop purchase rolled back", e);
            return PlayerShopResult.failure(PlayerShopResult.Outcome.FAILED);
        }
    }

    /** Undoes a committed purchase whose item could not be delivered. */
    private void reverseBuy(UUID buyer, String shopId, Material material,
                            PlayerShopResult original, Consumer<PlayerShopResult> callback) {
        scheduling.thenSync(
                scheduling.supplyAsync(() -> {
                    try {
                        return database.inTransaction(connection -> {
                            PlayerShopRepository.addStock(connection, shopId, material,
                                    original.quantity());
                            if (original.amount() > 0) {
                                economy.debitWithin(connection, original.shop().owner(),
                                        original.amount(), TransactionType.PLAYERSHOP_PURCHASE,
                                        "Reversed undelivered sale");
                                economy.creditWithin(connection, buyer, original.amount(),
                                        TransactionType.PLAYERSHOP_PURCHASE,
                                        "Refund: shop purchase could not be delivered");
                                PlayerShopRepository.addRevenue(connection, shopId,
                                        -original.amount());
                            }
                            return true;
                        });
                    } catch (Exception e) {
                        logger.log(Level.SEVERE, "Could not reverse an undelivered shop purchase; "
                                + buyer + " is owed " + original.amount(), e);
                        return false;
                    }
                }),
                reversed -> callback.accept(PlayerShopResult.failure(
                        Boolean.TRUE.equals(reversed)
                                ? PlayerShopResult.Outcome.REFUNDED
                                : PlayerShopResult.Outcome.FAILED)),
                error -> callback.accept(PlayerShopResult.failure(PlayerShopResult.Outcome.FAILED)));
    }

    /**
     * A customer sells to the shop.
     *
     * <p>Items are taken first, then money and stock commit together. If the
     * database refuses, the items go straight back.
     */
    public void sell(Player customer, String shopId, Material material, int quantity,
                     Consumer<PlayerShopResult> callback) {
        scheduling.ensureMainThread("Selling to a player shop");

        int held = Inventories.countPlain(customer, material);
        int toSell = Math.min(Math.max(0, quantity), held);
        if (toSell < 1) {
            callback.accept(PlayerShopResult.failure(PlayerShopResult.Outcome.NOT_ENOUGH_ITEMS));
            return;
        }

        int removed = Inventories.removePlain(customer, material, toSell);
        if (removed < 1) {
            callback.accept(PlayerShopResult.failure(PlayerShopResult.Outcome.NOT_ENOUGH_ITEMS));
            return;
        }

        UUID seller = customer.getUniqueId();
        scheduling.thenSync(
                scheduling.supplyAsync(() -> executeSell(seller, shopId, material, removed)),
                result -> {
                    if (!result.isSuccess()) {
                        // The shop would not or could not pay; give the items back.
                        Inventories.giveOrDrop(customer, material, removed);
                    }
                    callback.accept(result);
                },
                error -> {
                    logger.log(Level.SEVERE, "Player shop sale failed; returning items", error);
                    Inventories.giveOrDrop(customer, material, removed);
                    callback.accept(PlayerShopResult.failure(PlayerShopResult.Outcome.FAILED));
                });
    }

    private PlayerShopResult executeSell(UUID seller, String shopId, Material material, int quantity) {
        try {
            return database.inTransaction(connection -> {
                PlayerShopStatus status = PlayerShopRepository.statusOf(connection, shopId)
                        .orElse(null);
                if (status == null) {
                    return PlayerShopResult.failure(PlayerShopResult.Outcome.NOT_FOUND);
                }
                if (!status.trading()) {
                    return PlayerShopResult.failure(PlayerShopResult.Outcome.NOT_TRADING);
                }

                long[] prices = PlayerShopRepository.pricesOf(connection, shopId, material)
                        .orElse(null);
                if (prices == null || prices[1] == ShopOffer.UNAVAILABLE) {
                    return PlayerShopResult.failure(PlayerShopResult.Outcome.NO_SUCH_OFFER);
                }
                long total;
                try {
                    total = Math.multiplyExact(prices[1], quantity);
                } catch (ArithmeticException e) {
                    return PlayerShopResult.failure(PlayerShopResult.Outcome.INVALID_QUANTITY);
                }

                PlayerShop shop = repository.byId(shopId).orElse(null);
                if (shop == null) {
                    return PlayerShopResult.failure(PlayerShopResult.Outcome.NOT_FOUND);
                }
                if (shop.isOwner(seller)) {
                    return PlayerShopResult.failure(PlayerShopResult.Outcome.NOT_PERMITTED);
                }
                int currentStock = PlayerShopRepository.stockOf(connection, shopId, material);
                if (currentStock + quantity > settings().maxStockPerOffer()) {
                    return PlayerShopResult.failure(PlayerShopResult.Outcome.INVALID_QUANTITY);
                }

                if (total > 0) {
                    // The owner pays out of their own balance. A shop that cannot
                    // cover its buy price simply stops buying, which is the
                    // correct behaviour and not an error.
                    if (!economy.debitWithin(connection, shop.owner(), total,
                            TransactionType.PLAYERSHOP_PURCHASE,
                            "Bought " + quantity + "x " + material.name() + " at " + shop.name())) {
                        return PlayerShopResult.failure(PlayerShopResult.Outcome.OWNER_CANNOT_PAY);
                    }
                    if (!economy.creditWithin(connection, seller, total,
                            TransactionType.PLAYERSHOP_PURCHASE,
                            "Sold " + quantity + "x " + material.name() + " to " + shop.name())) {
                        throw new RolledBack(PlayerShopResult.Outcome.FAILED);
                    }
                }
                if (!PlayerShopRepository.addStock(connection, shopId, material, quantity)) {
                    throw new RolledBack(PlayerShopResult.Outcome.NO_SUCH_OFFER);
                }
                return PlayerShopResult.success(shop, quantity, total);
            });
        } catch (RolledBack rolledBack) {
            return PlayerShopResult.failure(rolledBack.outcome);
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Player shop sale rolled back", e);
            return PlayerShopResult.failure(PlayerShopResult.Outcome.FAILED);
        }
    }

    // ------------------------------------------------------------- queries

    public Optional<PlayerShop> byId(String shopId) {
        return repository.byId(shopId);
    }

    public Optional<PlayerShop> at(Location location) {
        return repository.byLocation(location.getWorld().getName(),
                location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    public List<PlayerShop> ownedBy(UUID owner) {
        return repository.ownedBy(owner);
    }

    public List<PlayerShopRepository.DirectoryEntry> directory() {
        return repository.directory();
    }

    /** Directory entries that stock a given material, for material search. */
    public List<PlayerShopRepository.DirectoryEntry> stocking(Material material) {
        List<String> ids = repository.shopIdsStocking(material);
        List<PlayerShopRepository.DirectoryEntry> matches = new ArrayList<>();
        for (PlayerShopRepository.DirectoryEntry entry : repository.directory()) {
            if (ids.contains(entry.shop().id())) {
                matches.add(entry);
            }
        }
        return List.copyOf(matches);
    }

    // ------------------------------------------------------------ internals

    /** Signals a rollback with a reason. */
    private static final class RolledBack extends RuntimeException {
        private final PlayerShopResult.Outcome outcome;

        RolledBack(PlayerShopResult.Outcome outcome) {
            super(outcome.name(), null, false, false);
            this.outcome = outcome;
        }
    }

    /** Trims, length-checks and escapes player-supplied text. */
    private static String sanitise(String raw, int maxLength) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || trimmed.length() > maxLength) {
            return null;
        }
        return Text.escape(trimmed);
    }
}
