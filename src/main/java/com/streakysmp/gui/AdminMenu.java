package com.streakysmp.gui;

import com.streakysmp.ServerCorePlugin;
import com.streakysmp.auction.AuctionService;
import com.streakysmp.claim.ClaimService;
import com.streakysmp.core.Scheduling;
import com.streakysmp.data.Database;
import com.streakysmp.economy.AccountRepository;
import com.streakysmp.economy.EconomyService;
import com.streakysmp.economy.TransactionRepository;
import com.streakysmp.economy.TransactionType;
import com.streakysmp.integration.IntegrationManager;
import com.streakysmp.log.AuditLog;
import com.streakysmp.notify.NotificationService;
import com.streakysmp.permission.PermissionService;
import com.streakysmp.playershop.PlayerShopService;
import com.streakysmp.plot.PlotService;
import com.streakysmp.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The administrator's front page.
 *
 * <p>Every section here opens a screen that already exists elsewhere in the
 * plugin rather than reimplementing it. An admin looking at a claim should see
 * the same screen its owner sees, plus the ability to act on it -- two separate
 * renderings of the same object is how the two drift apart and one of them starts
 * lying.
 *
 * <p>The one genuinely new thing is the diagnostics tile, which answers "is
 * anything wrong" without reading a log file.
 */
public final class AdminMenu extends Menu {

    private final ServerCorePlugin plugin;
    private final Scheduling scheduling;
    private final ChatInput chatInput;
    private final NotificationService notifications;
    private final PermissionService permissions;

    private Snapshot snapshot;

    /** Everything the front page shows, gathered in one worker-thread pass. */
    private record Snapshot(
            long moneySupply,
            int accounts,
            long created7d,
            long destroyed7d,
            int claims,
            int playerShops,
            int plots,
            int activeListings,
            int auditBacklog,
            int schemaVersion,
            boolean databaseOpen) {
    }

    public AdminMenu(MenuManager menus,
                     ChatInput chatInput,
                     Player viewer,
                     ServerCorePlugin plugin,
                     NotificationService notifications,
                     PermissionService permissions,
                     Scheduling scheduling) {
        super(menus, viewer, Text.mm("<dark_gray>ServerCore Administration</dark_gray>"), 6);
        this.plugin = plugin;
        this.chatInput = chatInput;
        this.notifications = notifications;
        this.permissions = permissions;
        this.scheduling = scheduling;
    }

    public static void openFor(MenuManager menus,
                               ChatInput chatInput,
                               Player player,
                               ServerCorePlugin plugin,
                               NotificationService notifications,
                               PermissionService permissions,
                               Scheduling scheduling) {
        new AdminMenu(menus, chatInput, player, plugin, notifications,
                permissions, scheduling).open();
    }

    @Override
    public void open() {
        scheduling.thenSync(
                scheduling.supplyAsync(this::gather),
                data -> {
                    snapshot = data;
                    super.open();
                },
                error -> viewer.sendMessage(
                        Text.mm("<red>Could not gather administration data.</red>")));
    }

    /** Runs on a worker thread. */
    private Snapshot gather() {
        var services = plugin.services();
        AccountRepository accounts = new AccountRepository(services.get(Database.class));
        TransactionRepository ledger = new TransactionRepository(services.get(Database.class));

        long since = System.currentTimeMillis() - Duration.ofDays(7).toMillis();
        Map<TransactionType, Long> volume = ledger.volumeByTypeSince(since);

        return new Snapshot(
                accounts.totalSupply(),
                accounts.accountCount(),
                sumFlow(volume, TransactionType.Flow.SOURCE),
                sumFlow(volume, TransactionType.Flow.SINK),
                services.get(ClaimService.class).claimCount(),
                services.get(PlayerShopService.class).indexedShopCount(),
                services.get(PlotService.class).all().size(),
                services.get(AuctionService.class).activeCount(),
                services.get(AuditLog.class).pending(),
                plugin.migrator() == null ? 0 : plugin.migrator().targetVersion(),
                services.get(Database.class).isOpen());
    }

    private static long sumFlow(Map<TransactionType, Long> volume, TransactionType.Flow flow) {
        long total = 0L;
        for (Map.Entry<TransactionType, Long> entry : volume.entrySet()) {
            if (entry.getKey().flow() == flow) {
                total += entry.getValue();
            }
        }
        return total;
    }

    @Override
    protected void build() {
        fillBorder(Material.GRAY_STAINED_GLASS_PANE);
        var services = plugin.services();
        EconomyService economy = services.get(EconomyService.class);

        set(1, 1, economyTile(economy));
        set(1, 2, playersTile());
        set(1, 3, claimsTile());
        set(1, 5, shopsTile());
        set(1, 6, auctionTile());
        set(1, 7, plotsTile());

        set(3, 2, leaderboardTile());
        set(3, 4, diagnosticsTile(economy));
        set(3, 6, reloadTile());

        set(5, 8, Button.of(ItemBuilder.of(Material.BARRIER)
                .name("<red>Close</red>").clean().build(), ClickContext::close));
    }

    // ----------------------------------------------------------------- tiles

    private Button economyTile(EconomyService economy) {
        long net = snapshot.created7d() - snapshot.destroyed7d();
        String verdict = net > 0 ? "<yellow>inflating</yellow>"
                : net < 0 ? "<aqua>deflating</aqua>" : "<green>balanced</green>";

        List<String> lore = new ArrayList<>();
        lore.add("<gray>Money supply:</gray> <green>"
                + economy.money().format(snapshot.moneySupply()) + "</green>");
        lore.add("<gray>Accounts:</gray> <white>" + snapshot.accounts() + "</white>");
        if (snapshot.accounts() > 0) {
            lore.add("<gray>Average:</gray> <white>" + economy.money().format(
                    snapshot.moneySupply() / snapshot.accounts()) + "</white>");
        }
        lore.add("");
        lore.add("<dark_gray>Last 7 days:</dark_gray>");
        lore.add("  <gray>Created:</gray> <green>+"
                + economy.money().formatCompact(snapshot.created7d()) + "</green>");
        lore.add("  <gray>Destroyed:</gray> <red>-"
                + economy.money().formatCompact(snapshot.destroyed7d()) + "</red>");
        lore.add("  <gray>Net:</gray> " + verdict);
        lore.add("");
        lore.add("<gray>Use <white>/eco give|take|set</white> to adjust a balance.</gray>");
        lore.add("<gray>Use <white>/eco info</white> for the full breakdown.</gray>");

        return Button.display(ItemBuilder.of(Material.GOLD_BLOCK)
                .name("<gold><bold>Economy</bold></gold>")
                .lore(lore)
                .clean().build());
    }

    /**
     * Opens any player's profile.
     *
     * <p>Asks for a name through chat rather than listing online players: an
     * administrator usually wants someone who is offline, which is exactly who a
     * picker cannot show.
     */
    private Button playersTile() {
        return Button.of(ItemBuilder.of(Material.PLAYER_HEAD)
                .name("<aqua><bold>Players</bold></aqua>")
                .lore("<gray>Look up any player, online or not.</gray>",
                        "<gray>Balance, combat record, playtime, ranks.</gray>",
                        "",
                        "<gray>Click and type a name.</gray>",
                        "<dark_gray>Or use /stats [player]</dark_gray>")
                .clean().build(), click -> {
            closeLater();
            chatInput.prompt(click.player(),
                    "<aqua>Type a player name to inspect, or <white>cancel</white>.</aqua>",
                    name -> click.player().performCommand("stats " + name),
                    this::open);
        });
    }

    private Button claimsTile() {
        return Button.of(ItemBuilder.of(Material.GRASS_BLOCK)
                .name("<green><bold>Claims</bold></green>")
                .lore("<gray>Claims on this server:</gray> <white>"
                                + snapshot.claims() + "</white>",
                        "",
                        "<gray>With <white>server.claim.admin</white> you bypass</gray>",
                        "<gray>every claim's protection.</gray>",
                        "",
                        "<gray>Click to browse your own claims.</gray>",
                        "<dark_gray>Stand in a claim and use /claim info</dark_gray>")
                .clean().build(), click -> click.player().performCommand("claim menu"));
    }

    private Button shopsTile() {
        return Button.of(ItemBuilder.of(Material.CHEST)
                .name("<gold><bold>Player shops</bold></gold>")
                .lore("<gray>Shops on this server:</gray> <white>"
                                + snapshot.playerShops() + "</white>",
                        "",
                        "<gray>Click to open the directory.</gray>",
                        "<dark_gray>With server.playershop.admin you can</dark_gray>",
                        "<dark_gray>manage or close any shop you look at.</dark_gray>")
                .clean().build(), click -> click.player().performCommand("pshop directory"));
    }

    private Button auctionTile() {
        return Button.of(ItemBuilder.of(Material.ENDER_CHEST)
                .name("<light_purple><bold>Auction house</bold></light_purple>")
                .lore("<gray>Active listings:</gray> <white>"
                                + snapshot.activeListings() + "</white>",
                        "",
                        "<gray>Click to browse.</gray>",
                        "<dark_gray>/ah remove [id] withdraws a listing</dark_gray>",
                        "<dark_gray>and returns the item to its seller.</dark_gray>")
                .clean().build(), click -> click.player().performCommand("ah"));
    }

    private Button plotsTile() {
        return Button.of(ItemBuilder.of(Material.EMERALD_BLOCK)
                .name("<green><bold>Spawn plots</bold></green>")
                .lore("<gray>Plots defined:</gray> <white>" + snapshot.plots() + "</white>",
                        "",
                        "<gray>Click to open the district.</gray>",
                        "<dark_gray>/plot create [id] [radius] [price] [rent]</dark_gray>",
                        "<dark_gray>/plot setprice | setrent | setperiod | status</dark_gray>",
                        "<dark_gray>/plot sweep runs rent collection now</dark_gray>")
                .clean().build(), click -> click.player().performCommand("plot district"));
    }

    private Button leaderboardTile() {
        return Button.of(ItemBuilder.of(Material.OAK_SIGN)
                .name("<yellow><bold>Leaderboards</bold></yellow>")
                .lore("<gray>Place floating rankings in the world.</gray>",
                        "",
                        "<dark_gray>/leaderboard create [stat] [id] [size]</dark_gray>",
                        "<dark_gray>/leaderboard remove | list | refresh</dark_gray>",
                        "",
                        "<gray>Click to list what is placed.</gray>")
                .clean().build(), click -> click.player().performCommand("leaderboard list"));
    }

    /**
     * The "is anything wrong" tile.
     *
     * <p>Surfaces the handful of internal states that indicate trouble before a
     * player reports it: a closed database, a backed-up audit queue, a schema
     * that is not what this build expects.
     */
    private Button diagnosticsTile(EconomyService economy) {
        var services = plugin.services();
        IntegrationManager integrations = services.get(IntegrationManager.class);

        boolean healthy = snapshot.databaseOpen() && snapshot.auditBacklog() < 1_000;

        List<String> lore = new ArrayList<>();
        lore.add("<gray>Database:</gray> " + (snapshot.databaseOpen()
                ? "<green>open</green>" : "<red>CLOSED</red>"));
        lore.add("<gray>Schema version:</gray> <white>" + snapshot.schemaVersion() + "</white>");
        lore.add("<gray>Audit queue:</gray> " + (snapshot.auditBacklog() < 1_000
                ? "<green>" : "<red>") + snapshot.auditBacklog()
                + (snapshot.auditBacklog() < 1_000 ? "</green>" : "</red>"));
        lore.add("<gray>Services:</gray> <white>"
                + services.startOrder().size() + "</white>");
        lore.add("<gray>Permissions:</gray> <white>"
                + services.get(PermissionService.class).provider() + "</white>");
        lore.add("");
        lore.add("<dark_gray>Integrations:</dark_gray>");
        lore.add(integrationLine("Geyser", integrations.hasGeyser()));
        lore.add(integrationLine("Floodgate", integrations.hasFloodgate()));


        return Button.display(ItemBuilder.of(healthy ? Material.LIME_DYE : Material.RED_DYE)
                .name(healthy
                        ? "<green><bold>Diagnostics</bold></green>"
                        : "<red><bold>Diagnostics - attention needed</bold></red>")
                .lore(lore)
                .glow(!healthy)
                .clean().build());
    }

    private static String integrationLine(String name, boolean present) {
        return "  <gray>" + name + ":</gray> "
                + (present ? "<green>yes</green>" : "<dark_gray>no</dark_gray>");
    }

    private Button reloadTile() {
        return Button.of(ItemBuilder.of(Material.REPEATER)
                .name("<aqua><bold>Reload configuration</bold></aqua>")
                .lore("<gray>Re-reads every config file.</gray>",
                        "",
                        "<gray>Reloads are transactional: a bad edit</gray>",
                        "<gray>is reported and the running settings</gray>",
                        "<gray>stay in effect.</gray>",
                        "",
                        "<gray>Click to reload.</gray>")
                .clean().build(), click -> {
            if (!permissions.require(click.player(), com.streakysmp.permission.Permissions.ADMIN)) {
                return;
            }
            click.player().performCommand("servercore reload");
            open();
        });
    }

}
