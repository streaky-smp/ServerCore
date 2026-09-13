package com.servercore.statistics;

import com.servercore.config.ConfigManager;
import com.servercore.config.ConfigView;
import com.servercore.core.Scheduling;
import com.servercore.core.Service;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Counts kills and deaths, and refuses to count farmed ones.
 *
 * <h2>What "farming" means here</h2>
 * Two players can agree to kill each other repeatedly, or one player can use a
 * second account as a victim, and either way the kill counter becomes
 * meaningless. The defence is a per-pair window: a given killer may only bank a
 * limited number of kills against the same victim within a configurable period.
 *
 * <p>Deaths are always counted. A death is a real event regardless of why it
 * happened, and not counting it would let a farmed pair inflate a K/D ratio by
 * suppressing the denominator.
 *
 * <p>The spec's other suggested defences -- alt-account detection and safe-zone
 * restrictions -- are deliberately not guessed at here. Alt detection needs
 * identity signals the server does not have, and safe zones need the claim system
 * from Phase 6. Both are better absent than approximated badly.
 */
public final class CombatTracker implements Service, Listener, ConfigManager.Reloadable {

    private final Plugin plugin;
    private final StandardStatisticsService statistics;
    private final StatisticsRepository repository;
    private final Scheduling scheduling;
    private final Logger logger;

    private volatile boolean antiFarmEnabled = true;
    private volatile Duration sameVictimWindow = Duration.ofMinutes(10);
    private volatile int maxKillsPerVictimInWindow = 1;
    private volatile boolean countMobKills = true;

    public CombatTracker(Plugin plugin,
                         StandardStatisticsService statistics,
                         StatisticsRepository repository,
                         Scheduling scheduling,
                         Logger logger) {
        this.plugin = plugin;
        this.statistics = statistics;
        this.repository = repository;
        this.scheduling = scheduling;
        this.logger = logger;
    }

    @Override
    public void load(ConfigManager.ConfigBundle configs) {
        ConfigView combat = configs.main().section("statistics").section("combat");
        this.antiFarmEnabled = combat.getBoolean("anti-farm-enabled", true);
        this.sameVictimWindow = combat.getDuration("same-victim-window", Duration.ofMinutes(10));
        this.maxKillsPerVictimInWindow = combat.getInt("max-kills-per-victim-in-window", 1, 1, 1_000);
        this.countMobKills = combat.getBoolean("count-mob-kills", true);
    }

    @Override
    public void onEnable() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @Override
    public void onDisable() {
        HandlerList.unregisterAll(this);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        UUID victimId = victim.getUniqueId();

        statistics.increment(victimId, StatisticType.DEATHS, 1L);

        Player killer = victim.getKiller();
        if (killer == null || killer.getUniqueId().equals(victimId)) {
            return;
        }
        UUID killerId = killer.getUniqueId();
        String killerName = killer.getName();
        String victimName = victim.getName();

        // The anti-farm check reads the kill log, so it has to go off-thread.
        // Everything it needs is captured as plain values first.
        scheduling.runAsync(() -> {
            boolean counted = shouldCount(killerId, victimId);
            repository.recordKill(killerId, victimId, counted);
            if (counted) {
                repository.increment(killerId, StatisticType.KILLS, 1L);
            } else {
                logger.fine("Not counting kill: " + killerName + " -> " + victimName
                        + " (within the same-victim window)");
            }
        }).exceptionally(error -> {
            logger.log(Level.WARNING, "Could not record kill "
                    + killerName + " -> " + victimName, error);
            return null;
        });
    }

    private boolean shouldCount(UUID killer, UUID victim) {
        if (!antiFarmEnabled) {
            return true;
        }
        long since = System.currentTimeMillis() - sameVictimWindow.toMillis();
        return repository.killsOfVictimSince(killer, victim, since) < maxKillsPerVictimInWindow;
    }

    /**
     * Counts mobs killed by players.
     *
     * <p>Buffered rather than written immediately: a player at a mob farm can
     * produce hundreds of these a minute, and one database write each would be a
     * self-inflicted performance problem.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        if (!countMobKills || event.getEntity() instanceof Player) {
            return;
        }
        Player killer = event.getEntity().getKiller();
        if (killer == null) {
            return;
        }
        statistics.incrementBuffered(killer.getUniqueId(), StatisticType.MOBS_KILLED, 1L);
    }
}
