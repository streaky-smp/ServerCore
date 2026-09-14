package com.streakysmp.core;

import com.streakysmp.data.PlayerRecord;
import com.streakysmp.data.PlayerRepository;
import com.streakysmp.economy.StandardEconomyService;
import com.streakysmp.integration.IntegrationManager;
import com.streakysmp.plot.PlotService;
import com.streakysmp.statistics.StandardStatisticsService;
import com.streakysmp.notify.StandardNotificationService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Maintains the player table, opens accounts, and delivers anything that arrived
 * while a player was away.
 *
 * <p>Every database write here is asynchronous. A join handler that blocks on I/O
 * stalls the tick for everyone already online.
 */
public final class PlayerSessionListener implements Listener {

    private final PlayerRepository players;
    private final StandardNotificationService notifications;
    private final IntegrationManager integrations;
    private final StandardEconomyService economy;
    private final StandardStatisticsService statistics;
    private final PlotService plots;
    private final Scheduling scheduling;
    private final Logger logger;

    public PlayerSessionListener(PlayerRepository players,
                                 StandardNotificationService notifications,
                                 IntegrationManager integrations,
                                 StandardEconomyService economy,
                                 StandardStatisticsService statistics,
                                 PlotService plots,
                                 Scheduling scheduling,
                                 Logger logger) {
        this.players = players;
        this.notifications = notifications;
        this.integrations = integrations;
        this.economy = economy;
        this.statistics = statistics;
        this.plots = plots;
        this.scheduling = scheduling;
        this.logger = logger;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        String name = player.getName();

        // Resolved on the main thread while the player object is definitely valid,
        // then handed to the worker as a plain value.
        PlayerRecord.Platform platform = integrations.isBedrockPlayer(id)
                ? PlayerRecord.Platform.BEDROCK
                : PlayerRecord.Platform.JAVA;

        // Start the playtime clock immediately, before any database work, so a
        // slow write cannot cost the player the first seconds of their session.
        statistics.beginSession(id);

        scheduling.runAsync(() -> {
            // Order matters: the account and notification rows both reference
            // sc_player by foreign key, so the player row must exist first.
            players.touch(id, name, platform);
            economy.ensureAccount(id);
        }).whenComplete((ignored, error) -> {
            if (error != null) {
                logger.log(Level.WARNING, "Could not initialise session for " + name, error);
                return;
            }
            notifications.deliverPending(player);
            // Rent warnings matter more than any other notification: everything
            // else can wait, losing a shop cannot.
            scheduling.runAsync(() -> plots.warnAtLogin(id))
                    .exceptionally(warnError -> {
                        logger.log(Level.WARNING,
                                "Could not deliver rent warnings to " + name, warnError);
                        return null;
                    });
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        String name = event.getPlayer().getName();
        long now = System.currentTimeMillis();

        // Cooldowns are per-session and in memory; dropping the entry keeps the
        // map from growing with every player the server has ever seen.
        economy.forgetCooldown(id);
        // Banks the final slice of playtime for this session.
        statistics.endSession(id);

        scheduling.runAsync(() -> players.markSeen(id, now))
                .exceptionally(error -> {
                    logger.log(Level.WARNING, "Could not record quit for " + name, error);
                    return null;
                });
    }
}
