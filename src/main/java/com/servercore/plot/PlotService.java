package com.servercore.plot;

import com.servercore.config.ConfigManager;
import com.servercore.core.Scheduling;
import com.servercore.core.Service;
import com.servercore.data.Database;
import com.servercore.economy.AccountRepository;
import com.servercore.economy.EconomyService;
import com.servercore.economy.TransactionType;
import com.servercore.log.AuditAction;
import com.servercore.log.AuditEntry;
import com.servercore.log.AuditLog;
import com.servercore.notify.Messages;
import com.servercore.notify.NotificationService;
import com.servercore.playershop.PlayerShop;
import com.servercore.playershop.PlayerShopRepository;
import com.servercore.playershop.PlayerShopStatus;
import com.servercore.playershop.ShopOffer;
import com.servercore.util.Durations;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The premium spawn commercial district: plots, purchases and recurring rent.
 *
 * <h2>The rent lifecycle</h2>
 * A sweep runs on a timer and processes every plot whose due date has passed.
 * Absolute due dates mean downtime cannot grant free time, and the schedule stays
 * aligned to the original purchase rather than drifting to whenever a late
 * payment happened to land.
 *
 * <p>A failed payment does <em>not</em> close the shop. The plot enters a grace
 * period during which it trades normally and the owner is warned; only when that
 * expires do shops close, and even then the stock is preserved and the plot is
 * held for a cleanup period before being released. The specification is explicit
 * on both points, and the alternative -- a shop vanishing the moment a player is
 * briefly short -- would make renting a trap rather than a commitment.
 *
 * <h2>Stock is never destroyed</h2>
 * When a plot expires, the stock of every shop on it is serialised into a
 * mailbox for the owner to collect. Nothing is deleted.
 */
public final class PlotService implements Service, ConfigManager.Reloadable {

    private final Database database;
    private final PlotRepository repository;
    private final PlayerShopRepository shops;
    private final EconomyService economy;
    private final NotificationService notifications;
    private final AuditLog auditLog;
    private final Scheduling scheduling;
    private final Logger logger;

    private volatile PlotSettings settings;
    private BukkitTask rentTask;

    public PlotService(Database database,
                       PlotRepository repository,
                       PlayerShopRepository shops,
                       EconomyService economy,
                       NotificationService notifications,
                       AuditLog auditLog,
                       Scheduling scheduling,
                       Logger logger) {
        this.database = database;
        this.repository = repository;
        this.shops = shops;
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
        this.settings = PlotSettings.from(configs.main().section("spawn-plots"), fractionDigits);
    }

    public PlotSettings settings() {
        PlotSettings snapshot = settings;
        if (snapshot == null) {
            throw new IllegalStateException("Plot settings accessed before configuration was loaded");
        }
        return snapshot;
    }

    public PlotRepository repository() {
        return repository;
    }

    @Override
    public void onEnable() {
        PlotSettings config = settings();
        if (!config.enabled()) {
            logger.info("Spawn plots are disabled; no rent will be charged.");
            return;
        }
        long ticks = Math.max(20L * 60L, config.rentCheckInterval().toSeconds() * 20L);
        // First sweep a minute after startup so the server has settled.
        rentTask = scheduling.asyncTimer(this::sweepQuietly, 20L * 60L, ticks);
        logger.info("Spawn plot rent sweep every " + config.rentCheckInterval().toMinutes()
                + " minute(s); grace period " + config.gracePeriod().toHours() + "h.");
    }

    @Override
    public void onDisable() {
        if (rentTask != null) {
            rentTask.cancel();
            rentTask = null;
        }
    }

    // ------------------------------------------------------- administration

    /** Creates a plot. Administrative; blocking. */
    public PlotResult createPlot(String id, String worldName, int x1, int z1, int x2, int z2,
                                 Long purchasePrice, Long rentPrice, RentPeriod period) {
        PlotSettings config = settings();
        String normalised = id.toLowerCase(Locale.ROOT);
        if (!normalised.matches("[a-z0-9_-]{1,32}")) {
            return PlotResult.failure(PlotResult.Outcome.INVALID_ID);
        }
        if (repository.byId(normalised).isPresent()) {
            return PlotResult.failure(PlotResult.Outcome.INVALID_ID);
        }

        SpawnPlot candidate = SpawnPlot.available(normalised, worldName, x1, z1, x2, z2,
                purchasePrice == null ? config.defaultPurchasePrice() : purchasePrice,
                rentPrice == null ? config.defaultRentPrice() : rentPrice,
                period == null ? config.defaultRentPeriod() : period);

        try {
            return database.inTransaction(connection -> {
                if (PlotRepository.overlapsWithin(connection, candidate, null)) {
                    return PlotResult.failure(PlotResult.Outcome.OVERLAPS);
                }
                repository.saveWithin(connection, candidate);
                return PlotResult.success(candidate, 0L);
            });
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Could not create plot " + normalised, e);
            return PlotResult.failure(PlotResult.Outcome.FAILED);
        }
    }

    public PlotResult deletePlot(String plotId) {
        SpawnPlot plot = repository.byId(plotId).orElse(null);
        if (plot == null) {
            return PlotResult.failure(PlotResult.Outcome.NOT_FOUND);
        }
        repository.delete(plotId);
        return PlotResult.success(plot, 0L);
    }

    public PlotResult setPricing(String plotId, Long purchasePrice, Long rentPrice, RentPeriod period) {
        SpawnPlot plot = repository.byId(plotId).orElse(null);
        if (plot == null) {
            return PlotResult.failure(PlotResult.Outcome.NOT_FOUND);
        }
        SpawnPlot updated = plot.withPricing(
                purchasePrice == null ? plot.purchasePrice() : purchasePrice,
                rentPrice == null ? plot.rentPrice() : rentPrice,
                period == null ? plot.rentPeriod() : period);
        repository.save(updated);
        return PlotResult.success(updated, 0L);
    }

    public PlotResult setStatus(String plotId, PlotStatus status) {
        SpawnPlot plot = repository.byId(plotId).orElse(null);
        if (plot == null) {
            return PlotResult.failure(PlotResult.Outcome.NOT_FOUND);
        }
        SpawnPlot updated = status == PlotStatus.AVAILABLE ? plot.release() : plot.withStatus(status);
        repository.save(updated);
        return PlotResult.success(updated, 0L);
    }

    // ------------------------------------------------------------ purchase

    /**
     * Buys a plot.
     *
     * <p>Availability is re-checked inside the transaction, so two players
     * clicking the same plot at the same moment cannot both own it.
     */
    public PlotResult purchase(UUID buyer, String plotId) {
        PlotSettings config = settings();
        if (!config.enabled()) {
            return PlotResult.failure(PlotResult.Outcome.DISABLED);
        }

        try {
            PlotResult result = database.inTransaction(connection -> {
                SpawnPlot plot = PlotRepository.byIdWithin(connection, plotId).orElse(null);
                if (plot == null) {
                    return PlotResult.failure(PlotResult.Outcome.NOT_FOUND);
                }
                if (!plot.status().purchasable()) {
                    return PlotResult.failure(PlotResult.Outcome.NOT_AVAILABLE);
                }
                if (config.hasPlotLimit()
                        && PlotRepository.countOwnedWithin(connection, buyer)
                            >= config.maxPlotsPerPlayer()) {
                    return PlotResult.failure(PlotResult.Outcome.TOO_MANY_PLOTS);
                }

                long price = plot.purchasePrice();
                if (price > 0 && !economy.debitWithin(connection, buyer, price,
                        TransactionType.PLOT_PURCHASE, "Spawn plot " + plotId)) {
                    long balance = AccountRepository.balance(connection, buyer);
                    return PlotResult.insufficientFunds(price, Math.max(0L, price - balance));
                }

                SpawnPlot owned = plot.purchasedBy(buyer, System.currentTimeMillis());
                repository.saveWithin(connection, owned);
                return PlotResult.success(owned, price);
            });

            if (result.isSuccess()) {
                auditLog.record(AuditEntry.builder(AuditAction.PLOT_PURCHASED)
                        .actor(buyer, null)
                        .amount(result.amount())
                        .object(plotId)
                        .details("first rent due "
                                + Durations.remaining(result.plot()
                                        .untilRentDue(System.currentTimeMillis())))
                        .build());
            }
            return result;
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Plot purchase failed and was rolled back", e);
            return PlotResult.failure(PlotResult.Outcome.FAILED);
        }
    }

    /**
     * Pays a plot's rent immediately, ahead of the due date.
     *
     * <p>The spec requires this during a grace period. It is allowed at any time,
     * because a tenant who wants to pay early should never be prevented.
     */
    public PlotResult payRent(UUID payer, String plotId) {
        try {
            PlotResult result = database.inTransaction(connection -> {
                SpawnPlot plot = PlotRepository.byIdWithin(connection, plotId).orElse(null);
                if (plot == null) {
                    return PlotResult.failure(PlotResult.Outcome.NOT_FOUND);
                }
                if (!plot.isOwner(payer)) {
                    return PlotResult.failure(PlotResult.Outcome.NOT_PERMITTED);
                }
                if (!plot.status().rentApplies()) {
                    return PlotResult.failure(PlotResult.Outcome.NOTHING_DUE);
                }

                long rent = plot.rentPrice();
                if (rent > 0 && !economy.debitWithin(connection, payer, rent,
                        TransactionType.SHOP_RENT, "Rent for spawn plot " + plotId)) {
                    long balance = AccountRepository.balance(connection, payer);
                    return PlotResult.insufficientFunds(rent, Math.max(0L, rent - balance));
                }

                SpawnPlot paid = plot.rentPaid(rent, System.currentTimeMillis());
                repository.saveWithin(connection, paid);
                return PlotResult.success(paid, rent);
            });

            if (result.isSuccess()) {
                // Reopen anything the lapse had closed.
                reopenShops(plotId);
                auditLog.record(AuditEntry.builder(AuditAction.RENT_PAYMENT)
                        .actor(payer, null)
                        .amount(result.amount())
                        .object(plotId)
                        .details("paid manually")
                        .build());
            }
            return result;
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Manual rent payment failed", e);
            return PlotResult.failure(PlotResult.Outcome.FAILED);
        }
    }

    // ----------------------------------------------------------- rent sweep

    private void sweepQuietly() {
        try {
            sweep();
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Rent sweep failed", e);
        }
    }

    /** Charges due rent, advances grace periods, expires and releases plots. */
    public void sweep() {
        PlotSettings config = settings();
        long now = System.currentTimeMillis();

        for (SpawnPlot plot : repository.withRentDue(now)) {
            try {
                processDuePlot(plot, config, now);
            } catch (Exception e) {
                logger.log(Level.SEVERE, "Could not process rent for plot " + plot.id(), e);
            }
        }

        // Plots whose cleanup period has elapsed go back on the market.
        long releaseCutoff = now - config.cleanupPeriod().toMillis();
        for (SpawnPlot plot : repository.readyForRelease(releaseCutoff)) {
            try {
                release(plot);
            } catch (Exception e) {
                logger.log(Level.SEVERE, "Could not release plot " + plot.id(), e);
            }
        }
    }

    private void processDuePlot(SpawnPlot plot, PlotSettings config, long now) {
        UUID owner = plot.owner();
        if (owner == null) {
            return;
        }

        // Try to collect. A conditional debit inside the transaction means a
        // tenant who is a penny short is not charged a partial amount.
        boolean paid = database.inTransaction(connection -> {
            SpawnPlot current = PlotRepository.byIdWithin(connection, plot.id()).orElse(null);
            if (current == null || current.owner() == null || !current.rentDue(now)) {
                // Someone paid manually between the query and here.
                return true;
            }
            long rent = current.rentPrice();
            if (rent > 0 && !economy.debitWithin(connection, current.owner(), rent,
                    TransactionType.SHOP_RENT, "Rent for spawn plot " + current.id())) {
                return false;
            }
            repository.saveWithin(connection, current.rentPaid(rent, now));
            return true;
        });

        if (paid) {
            reopenShops(plot.id());
            auditLog.record(AuditEntry.builder(AuditAction.RENT_PAYMENT)
                    .actor(owner, null)
                    .amount(plot.rentPrice())
                    .object(plot.id())
                    .details("collected automatically")
                    .build());
            notifications.notifyPlayer(owner, "plot.rent-paid", Messages.of(
                    "plot", plot.id(),
                    "amount", economy.money().format(plot.rentPrice())));
            return;
        }

        // Payment failed. Escalate one step, no further.
        if (plot.graceEndsAt() == null) {
            SpawnPlot overdue = plot.enterGrace(now + config.gracePeriod().toMillis());
            repository.save(overdue);
            notifications.notifyPlayer(owner, "plot.rent-overdue", Messages.of(
                    "plot", plot.id(),
                    "amount", economy.money().format(plot.rentPrice()),
                    "grace", Durations.remaining(config.gracePeriod())));
            logger.info("Plot " + plot.id() + " entered its grace period (rent unpaid).");
            return;
        }

        if (plot.graceExpired(now) && plot.status() != PlotStatus.EXPIRED) {
            expire(plot);
            return;
        }

        // Still inside the grace period: remind, do not escalate.
        notifications.notifyPlayer(owner, "plot.rent-reminder", Messages.of(
                "plot", plot.id(),
                "amount", economy.money().format(plot.rentPrice()),
                "grace", Durations.remaining(plot.graceRemaining(now))));
    }

    /**
     * Closes the plot's shops and moves their stock into the owner's mailbox.
     *
     * <p>Nothing is deleted. The plot itself is held for the cleanup period so the
     * owner can still pay and get everything back.
     */
    private void expire(SpawnPlot plot) {
        SpawnPlot expired = plot.expire();
        repository.save(expired);
        shops.setStatusForPlot(plot.id(), PlayerShopStatus.RENT_OVERDUE);

        int stacksStored = 0;
        for (PlayerShop shop : shops.byPlot(plot.id())) {
            try {
                stacksStored += preserveStock(shop, plot.id());
            } catch (Throwable t) {
                // The plot must still expire. Leaving it OWNED because one shop's
                // stock could not be serialised would mean rent kept accruing on a
                // plot nobody can use, which is worse than a manual recovery.
                logger.log(Level.SEVERE, "Could not preserve stock for shop " + shop.id()
                        + " on plot " + plot.id() + "; the stock is left in the shop and the "
                        + "plot has still expired. Manual recovery may be needed.", t);
            }
        }

        auditLog.record(AuditEntry.builder(AuditAction.ADMIN_ACTION)
                .actor(plot.owner(), null)
                .object(plot.id())
                .details("plot expired; " + stacksStored + " stack(s) moved to mailbox")
                .build());

        if (plot.owner() != null) {
            notifications.notifyPlayer(plot.owner(), "plot.expired", Messages.of(
                    "plot", plot.id(),
                    "stacks", String.valueOf(stacksStored),
                    "cleanup", Durations.remaining(settings().cleanupPeriod())));
        }
        logger.info("Plot " + plot.id() + " expired; " + stacksStored
                + " stack(s) preserved for collection.");
    }

    /**
     * Moves one shop's stock into its owner's mailbox and empties the offers.
     *
     * <p>Emptying the offers afterwards matters: if the plot is later paid up and
     * reopened, stock that is already in the mailbox must not also be sellable, or
     * it exists twice.
     *
     * @return how many stacks were stored
     */
    private int preserveStock(PlayerShop shop, String plotId) {
        List<ItemStack> stock = new ArrayList<>();
        for (ShopOffer offer : shop.offers()) {
            int remaining = offer.stock();
            while (remaining > 0) {
                int amount = Math.min(offer.material().getMaxStackSize(), remaining);
                stock.add(new ItemStack(offer.material(), amount));
                remaining -= amount;
            }
        }
        if (stock.isEmpty()) {
            return 0;
        }

        // Paper's own serialisation, so enchantments and data components survive.
        byte[] data = ItemStack.serializeItemsAsBytes(stock);
        repository.storeInMailbox(shop.owner(), plotId, data);

        for (ShopOffer offer : shop.offers()) {
            if (offer.stock() > 0) {
                database.inTransaction(connection -> PlayerShopRepository.tryTakeStock(
                        connection, shop.id(), offer.material(), offer.stock()));
            }
        }
        return stock.size();
    }

    /** Returns an expired plot to the market. */
    private void release(SpawnPlot plot) {
        UUID previousOwner = plot.owner();
        SpawnPlot released = plot.release();
        repository.save(released);

        // Shops on the plot lose their home; they are closed, not deleted, and
        // their stock is already in the mailbox.
        shops.setStatusForPlot(plot.id(), PlayerShopStatus.SUSPENDED);

        auditLog.record(AuditEntry.builder(AuditAction.PLOT_RELEASED)
                .actor(previousOwner, null)
                .object(plot.id())
                .details("cleanup period elapsed")
                .build());
        if (previousOwner != null) {
            notifications.notifyPlayer(previousOwner, "plot.released",
                    Messages.of("plot", plot.id()));
        }
        logger.info("Plot " + plot.id() + " released and available again.");
    }

    private void reopenShops(String plotId) {
        shops.setStatusForPlot(plotId, PlayerShopStatus.OPEN);
    }

    // ------------------------------------------------------------- queries

    public Optional<SpawnPlot> byId(String plotId) {
        return repository.byId(plotId);
    }

    public List<SpawnPlot> all() {
        return repository.all();
    }

    public List<SpawnPlot> available() {
        return repository.available();
    }

    public List<SpawnPlot> ownedBy(UUID owner) {
        return repository.ownedBy(owner);
    }

    /** The plot covering a block, if any. Blocking. */
    public Optional<SpawnPlot> plotAt(String worldName, int x, int z) {
        for (SpawnPlot plot : repository.all()) {
            if (plot.contains(worldName, x, z)) {
                return Optional.of(plot);
            }
        }
        return Optional.empty();
    }

    public List<PlotRepository.MailboxEntry> pendingMail(UUID player) {
        return repository.pendingMail(player);
    }

    public int pendingMailCount(UUID player) {
        return repository.pendingMailCount(player);
    }

    public boolean claimMail(long entryId) {
        return repository.markCollected(entryId);
    }

    /**
     * Warns a player at login about rent falling due or a grace period running.
     *
     * <p>Required by the spec, and the one notification a tenant genuinely needs:
     * everything else can wait, but losing a shop cannot.
     */
    public void warnAtLogin(UUID player) {
        PlotSettings config = settings();
        if (!config.enabled() || !config.warnOnLogin()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (SpawnPlot plot : repository.ownedBy(player)) {
            if (plot.inGracePeriod()) {
                notifications.notifyPlayer(player, "plot.login-overdue", Messages.of(
                        "plot", plot.id(),
                        "amount", economy.money().format(plot.rentPrice()),
                        "grace", Durations.remaining(plot.graceRemaining(now))));
            } else if (plot.status() == PlotStatus.EXPIRED) {
                notifications.notifyPlayer(player, "plot.login-expired",
                        Messages.of("plot", plot.id()));
            } else if (plot.rentDueAt() != null) {
                Duration until = plot.untilRentDue(now);
                if (!until.isZero() && until.compareTo(config.warnBeforeDue()) <= 0) {
                    notifications.notifyPlayer(player, "plot.login-due-soon", Messages.of(
                            "plot", plot.id(),
                            "amount", economy.money().format(plot.rentPrice()),
                            "remaining", Durations.remaining(until)));
                }
            }
        }
        int mail = repository.pendingMailCount(player);
        if (mail > 0) {
            notifications.notifyPlayer(player, "plot.mail-waiting",
                    Messages.of("count", String.valueOf(mail)));
        }
    }
}
