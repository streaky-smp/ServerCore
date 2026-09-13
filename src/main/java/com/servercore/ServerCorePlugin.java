package com.servercore;

import com.servercore.command.BalanceCommand;
import com.servercore.command.EconomyAdminCommand;
import com.servercore.command.PayCommand;
import com.servercore.command.PlayerLookup;
import com.servercore.command.ServerCoreCommand;
import com.servercore.command.AuctionCommand;
import com.servercore.command.ClaimCommand;
import com.servercore.command.PlayerShopCommand;
import com.servercore.command.PlotCommand;
import com.servercore.command.TeleportCommand;
import com.servercore.command.LeaderboardCommand;
import com.servercore.command.ShopCommand;
import com.servercore.command.StatsCommand;
import com.servercore.auction.AuctionRepository;
import com.servercore.auction.AuctionService;
import com.servercore.claim.ClaimIndex;
import com.servercore.claim.ClaimProtectionListener;
import com.servercore.claim.ClaimRepository;
import com.servercore.claim.ClaimService;
import com.servercore.claim.ClaimVisualiser;
import com.servercore.claim.StandardClaimService;
import com.servercore.config.ConfigManager;
import com.servercore.config.ConfigView;
import com.servercore.core.PlayerSessionListener;
import com.servercore.core.Scheduling;
import com.servercore.core.Service;
import com.servercore.core.ServiceRegistry;
import com.servercore.data.Database;
import com.servercore.data.PlayerRepository;
import com.servercore.data.Schema;
import com.servercore.data.SchemaMigrator;
import com.servercore.economy.AccountRepository;
import com.servercore.economy.EconomyService;
import com.servercore.economy.StandardEconomyService;
import com.servercore.economy.TransactionRepository;
import com.servercore.shop.ShopCatalogue;
import com.servercore.shop.ShopService;
import com.servercore.shop.StandardShopService;
import com.servercore.statistics.CombatTracker;
import com.servercore.statistics.StandardStatisticsService;
import com.servercore.statistics.StatisticsRepository;
import com.servercore.statistics.StatisticsService;
import com.servercore.gui.ChatInput;
import com.servercore.gui.MenuManager;
import com.servercore.integration.IntegrationManager;
import com.servercore.leaderboard.LeaderboardRepository;
import com.servercore.leaderboard.LeaderboardService;
import com.servercore.log.AuditLog;
import com.servercore.notify.Messages;
import com.servercore.notify.NotificationRepository;
import com.servercore.notify.NotificationService;
import com.servercore.notify.StandardNotificationService;
import com.servercore.permission.BukkitPermissionService;
import com.servercore.playershop.PlayerShopInteractListener;
import com.servercore.playershop.PlayerShopRepository;
import com.servercore.playershop.PlayerShopService;
import com.servercore.plot.PlotRepository;
import com.servercore.plot.PlotService;
import com.servercore.teleport.TeleportService;
import com.servercore.permission.PermissionService;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.logging.Level;

/**
 * Plugin entry point.
 *
 * <p>Intentionally thin. Its only jobs are to construct services in dependency
 * order, hand them to a {@link ServiceRegistry}, and start and stop them. All
 * behaviour lives in the modules.
 *
 * <p>Startup is fail-fast: if any service throws while enabling, the ones already
 * started are stopped in reverse order and the plugin disables itself. A server
 * running with, say, a failed database but a working shop GUI would take real
 * money from players and lose it.
 */
public final class ServerCorePlugin extends JavaPlugin {

    private final ServiceRegistry services = new ServiceRegistry();

    private ConfigManager configManager;
    private Scheduling scheduling;
    private SchemaMigrator migrator;

    @Override
    public void onEnable() {
        try {
            bootstrap();
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "ServerCore failed to start and has been disabled. "
                    + "No gameplay systems are active.", e);
            shutdownServices();
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        shutdownServices();
    }

    private void bootstrap() throws Exception {
        this.scheduling = new Scheduling(this);

        // The config manager is the one component outside the registry: everything
        // else needs its values in order to be constructed at all.
        this.configManager = new ConfigManager(this);
        configManager.onEnable();
        ConfigView main = configManager.currentBundle().main();

        // --- Persistence ----------------------------------------------------
        ConfigView databaseConfig = main.section("database");
        Database database = Database.forPlugin(this,
                databaseConfig.getString("file", "servercore.db"),
                databaseConfig.getInt("pool-size", 8, 2, 32),
                databaseConfig.getLong("busy-timeout-millis", 5_000L, 500L, 60_000L));
        services.register(Database.class, database);

        // --- Audit log ------------------------------------------------------
        ConfigView loggingConfig = main.section("logging");
        AuditLog auditLog = new AuditLog(database, scheduling, getLogger(),
                loggingConfig.getLong("audit-flush-interval-ticks", 100L, 20L, 1_200L),
                loggingConfig.getBoolean("mirror-audit-to-server-log", false));
        services.register(AuditLog.class, auditLog);

        // --- Messaging and notifications ------------------------------------
        Messages messages = new Messages();
        services.register(Messages.class, messages);
        configManager.addListener(messages);

        NotificationRepository notificationRepository = new NotificationRepository(database);
        StandardNotificationService notifications = new StandardNotificationService(
                messages, notificationRepository, scheduling, getLogger());
        services.register(NotificationService.class, notifications);
        configManager.addListener(notifications);

        // --- Permissions ----------------------------------------------------
        PermissionService permissions = new BukkitPermissionService(notifications);
        services.register(PermissionService.class, permissions);

        // --- Integrations ---------------------------------------------------
        IntegrationManager integrations = new IntegrationManager(getLogger());
        services.register(IntegrationManager.class, integrations);

        // --- GUI ------------------------------------------------------------
        ChatInput chatInput = new ChatInput(this, scheduling,
                main.section("gui").getLong("chat-input-timeout-ticks", 1_200L, 100L, 12_000L));
        services.register(ChatInput.class, chatInput);

        MenuManager menus = new MenuManager(this, scheduling, auditLog, getLogger());
        services.register(MenuManager.class, menus);

        // --- Economy --------------------------------------------------------
        AccountRepository accounts = new AccountRepository(database);
        TransactionRepository ledger = new TransactionRepository(database);
        StandardEconomyService economy = new StandardEconomyService(
                database, accounts, ledger, auditLog, getLogger());
        services.register(EconomyService.class, economy);
        configManager.addListener(economy);

        // --- Server shop ----------------------------------------------------
        ShopCatalogue catalogue = new ShopCatalogue(getLogger());
        services.register(ShopCatalogue.class, catalogue);
        configManager.addListener(catalogue);

        StandardShopService shop = new StandardShopService(
                catalogue, economy, auditLog, scheduling, getLogger());
        services.register(ShopService.class, shop);

        // --- Statistics -----------------------------------------------------
        StatisticsRepository statisticsRepository = new StatisticsRepository(database);
        StandardStatisticsService statistics = new StandardStatisticsService(
                statisticsRepository, scheduling, getLogger());
        services.register(StatisticsService.class, statistics);
        configManager.addListener(statistics);

        CombatTracker combat = new CombatTracker(
                this, statistics, statisticsRepository, scheduling, getLogger());
        services.register(CombatTracker.class, combat);
        configManager.addListener(combat);

        // --- Claims ---------------------------------------------------------
        ClaimRepository claimRepository = new ClaimRepository(database);
        ClaimIndex claimIndex = new ClaimIndex();
        StandardClaimService claimService = new StandardClaimService(
                database, claimRepository, claimIndex, economy, auditLog, getLogger());
        services.register(ClaimService.class, claimService);
        configManager.addListener(claimService);

        ClaimVisualiser visualiser = new ClaimVisualiser(scheduling, claimService, integrations);
        services.register(ClaimVisualiser.class, visualiser);

        services.register(ClaimProtectionListener.class, new ClaimProtectionListener(
                this, claimService, notifications, permissions));

        // --- Player shops ---------------------------------------------------
        PlayerShopRepository playerShopRepository = new PlayerShopRepository(database);
        PlayerShopService playerShops = new PlayerShopService(database, playerShopRepository,
                economy, claimService, auditLog, scheduling, getLogger());
        services.register(PlayerShopService.class, playerShops);
        configManager.addListener(playerShops);

        services.register(PlayerShopInteractListener.class, new PlayerShopInteractListener(
                this, playerShops, economy, notifications, permissions, menus, chatInput, scheduling));

        // --- Spawn plots ----------------------------------------------------
        PlotRepository plotRepository = new PlotRepository(database);
        PlotService plotService = new PlotService(database, plotRepository, playerShopRepository,
                economy, notifications, auditLog, scheduling, getLogger());
        services.register(PlotService.class, plotService);
        configManager.addListener(plotService);

        // --- Auction house --------------------------------------------------
        AuctionRepository auctionRepository = new AuctionRepository(database);
        AuctionService auctions = new AuctionService(database, auctionRepository, economy,
                notifications, auditLog, scheduling, getLogger());
        services.register(AuctionService.class, auctions);
        configManager.addListener(auctions);

        // --- Teleport requests ----------------------------------------------
        TeleportService teleports = new TeleportService(this, notifications, scheduling);
        services.register(TeleportService.class, teleports);
        configManager.addListener(teleports);

        // --- Leaderboards ---------------------------------------------------
        LeaderboardRepository leaderboardRepository = new LeaderboardRepository(database);
        LeaderboardService leaderboards = new LeaderboardService(this, leaderboardRepository,
                statisticsRepository, accounts, economy, scheduling, getLogger());
        services.register(LeaderboardService.class, leaderboards);
        configManager.addListener(leaderboards);

        startServices();

        // Schema migration runs after the database service is up but before any
        // repository is used. Off the main thread, but blocking: nothing may
        // touch the database until the schema is known to be correct.
        migrateSchema(database);
        loadClaimIndex(claimService);
        loadPlayerShopIndex(playerShops);

        PlayerRepository players = new PlayerRepository(database);
        getServer().getPluginManager().registerEvents(
                new PlayerSessionListener(players, notifications, integrations, economy,
                        statistics, plotService, scheduling, getLogger()),
                this);

        PlayerLookup lookup = new PlayerLookup(players, scheduling);
        registerCommands(permissions, notifications, auditLog,
                economy, accounts, ledger, lookup, menus, chatInput, shop, statistics,
                leaderboards, claimService, visualiser, playerShops, plotService, auctions,
                teleports);

        services.seal();
        getLogger().info("ServerCore enabled (" + services.startOrder().size() + " services)");
    }

    /**
     * Registers commands through Paper's Brigadier lifecycle.
     *
     * <p>Commands are registered here rather than declared in {@code plugin.yml}
     * so they get real argument parsing and tab-completion, and so a command is
     * only exposed once the services it depends on actually exist.
     */
    private void registerCommands(PermissionService permissions,
                                  NotificationService notifications,
                                  AuditLog auditLog,
                                  EconomyService economy,
                                  AccountRepository accounts,
                                  TransactionRepository ledger,
                                  PlayerLookup lookup,
                                  MenuManager menus,
                                  ChatInput chatInput,
                                  ShopService shop,
                                  StatisticsService statistics,
                                  LeaderboardService leaderboards,
                                  ClaimService claims,
                                  ClaimVisualiser visualiser,
                                  PlayerShopService playerShops,
                                  PlotService plotService,
                                  AuctionService auctions,
                                  TeleportService teleports) {
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            var registrar = event.registrar();

            registrar.register("servercore",
                    "ServerCore administration and diagnostics",
                    List.of("sc"),
                    new ServerCoreCommand(this, permissions, notifications, auditLog));

            registrar.register("balance",
                    "Check your balance, ranking and recent transactions",
                    List.of("bal", "money"),
                    new BalanceCommand(economy, accounts, ledger, lookup, permissions,
                            notifications, menus, chatInput, scheduling));

            registrar.register("pay",
                    "Send money to another player",
                    List.of(),
                    new PayCommand(economy, lookup, permissions, notifications, menus, scheduling));

            registrar.register("shop",
                    "Open the server shop",
                    List.of(),
                    new ShopCommand(shop, economy, permissions, notifications,
                            menus, chatInput, scheduling));

            registrar.register("stats",
                    "View a player's profile and statistics",
                    List.of("profile"),
                    new StatsCommand(statistics, economy, accounts, lookup, permissions,
                            notifications, menus, scheduling));

            // One command class, five registrations. The handling is nearly
            // identical, and duplicating it is how /tpahere ends up teleporting
            // the wrong player.
            registrar.register("tpa",
                    "Ask to teleport to another player",
                    List.of(),
                    new TeleportCommand(TeleportCommand.Mode.REQUEST_TO_TARGET,
                            teleports, permissions, notifications, menus, scheduling));

            registrar.register("tpahere",
                    "Ask another player to teleport to you",
                    List.of(),
                    new TeleportCommand(TeleportCommand.Mode.REQUEST_TO_SELF,
                            teleports, permissions, notifications, menus, scheduling));

            registrar.register("tpaccept",
                    "Accept a teleport request",
                    List.of("tpyes"),
                    new TeleportCommand(TeleportCommand.Mode.ACCEPT,
                            teleports, permissions, notifications, menus, scheduling));

            registrar.register("tpdeny",
                    "Deny a teleport request",
                    List.of("tpno"),
                    new TeleportCommand(TeleportCommand.Mode.DENY,
                            teleports, permissions, notifications, menus, scheduling));

            registrar.register("tpacancel",
                    "Withdraw a teleport request you sent",
                    List.of(),
                    new TeleportCommand(TeleportCommand.Mode.CANCEL,
                            teleports, permissions, notifications, menus, scheduling));

            registrar.register("tpalist",
                    "Show pending teleport requests",
                    List.of("tpamenu"),
                    new TeleportCommand(TeleportCommand.Mode.MENU,
                            teleports, permissions, notifications, menus, scheduling));

            registrar.register("ah",
                    "Browse, buy and sell on the auction house",
                    List.of("auction", "auctionhouse"),
                    new AuctionCommand(auctions, economy, permissions, notifications,
                            auditLog, menus, chatInput, scheduling));

            registrar.register("plot",
                    "Browse the spawn commercial district and manage plots",
                    List.of("plots"),
                    new PlotCommand(plotService, economy, permissions, notifications,
                            auditLog, menus, chatInput, scheduling));

            registrar.register("pshop",
                    "Create and manage player shops, and browse the directory",
                    List.of("playershop", "shops"),
                    new PlayerShopCommand(playerShops, economy, permissions, notifications,
                            menus, chatInput, scheduling));

            registrar.register("claim",
                    "Buy and manage land claims",
                    List.of("claims", "land"),
                    new ClaimCommand(claims, visualiser, economy, lookup, permissions,
                            notifications, menus, chatInput, scheduling));

            registrar.register("leaderboard",
                    "Create and manage physical leaderboards",
                    List.of("lb"),
                    new LeaderboardCommand(leaderboards, permissions, notifications,
                            auditLog, scheduling));

            registrar.register("eco",
                    "Administrative economy control",
                    List.of("economy"),
                    new EconomyAdminCommand(economy, accounts, ledger, lookup, permissions,
                            notifications, scheduling));
        });
    }

    /**
     * Applies pending migrations, blocking startup until finished.
     *
     * <p>Runs on a worker thread because {@link Database} refuses main-thread
     * access, but the main thread waits for it. Letting the server finish
     * starting while the schema is still changing would let a player join and hit
     * a table that does not exist yet.
     */
    private void migrateSchema(Database database) throws Exception {
        this.migrator = new SchemaMigrator(database, getLogger(), Schema.migrations());
        Thread worker = new Thread(() -> migrator.migrate(), "ServerCore-Migrate");
        final Throwable[] failure = new Throwable[1];
        worker.setUncaughtExceptionHandler((t, e) -> failure[0] = e);
        worker.start();
        worker.join();
        if (failure[0] != null) {
            throw new IllegalStateException("Database migration failed", failure[0]);
        }
    }

    /**
     * Builds the claim index before the server finishes starting.
     *
     * <p>Until the index is populated the protection listener sees no claims and
     * would let anyone build anywhere, so this must complete before a player can
     * possibly join. Runs on a worker thread because the database refuses
     * main-thread access, but the main thread waits for it.
     */
    private void loadClaimIndex(StandardClaimService claims) throws Exception {
        final Throwable[] failure = new Throwable[1];
        Thread worker = new Thread(claims::loadIndex, "ServerCore-ClaimIndex");
        worker.setUncaughtExceptionHandler((thread, error) -> failure[0] = error);
        worker.start();
        worker.join();
        if (failure[0] != null) {
            throw new IllegalStateException("Could not load claims", failure[0]);
        }
    }

    /**
     * Builds the player-shop location index before players can connect.
     *
     * <p>Without it, right-clicking a shop block would do nothing until the first
     * lookup populated the map.
     */
    private void loadPlayerShopIndex(PlayerShopService shops) throws Exception {
        final Throwable[] failure = new Throwable[1];
        Thread worker = new Thread(shops::loadIndex, "ServerCore-ShopIndex");
        worker.setUncaughtExceptionHandler((thread, error) -> failure[0] = error);
        worker.start();
        worker.join();
        if (failure[0] != null) {
            throw new IllegalStateException("Could not load player shops", failure[0]);
        }
    }

    private void startServices() throws Exception {
        for (Service service : services.startOrder()) {
            try {
                service.onEnable();
            } catch (Exception e) {
                throw new IllegalStateException("Service " + service.serviceName() + " failed to start", e);
            }
        }
    }

    private void shutdownServices() {
        for (Service service : services.shutdownOrder()) {
            try {
                service.onDisable();
            } catch (Exception e) {
                // Keep going: one badly behaved service must not prevent the rest
                // from flushing their state.
                getLogger().log(Level.WARNING,
                        "Service " + service.serviceName() + " threw while shutting down", e);
            }
        }
    }

    public ServiceRegistry services() {
        return services;
    }

    public ConfigManager configManager() {
        return configManager;
    }

    public Scheduling scheduling() {
        return scheduling;
    }

    public SchemaMigrator migrator() {
        return migrator;
    }
}
