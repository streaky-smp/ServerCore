package com.streakysmp.leaderboard;

import com.streakysmp.config.ConfigManager;
import com.streakysmp.config.ConfigView;
import com.streakysmp.core.Scheduling;
import com.streakysmp.core.Service;
import com.streakysmp.economy.AccountRepository;
import com.streakysmp.economy.EconomyService;
import com.streakysmp.statistics.StatisticsRepository;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Physical leaderboards, rendered as floating text in the world.
 *
 * <h2>Why display entities are not persisted</h2>
 * Each board is a {@link TextDisplay} marked {@code setPersistent(false)}, so it
 * is never written into the world save. Every one is recreated from the database
 * at startup. That inverts the usual hologram-plugin failure mode, where deleted
 * or crashed-out holograms accumulate in the world file and have to be hunted
 * down by hand: here the database is the only source of truth, and anything in
 * the world that is not backed by a row is removed on sight.
 *
 * <h2>A platform limitation, stated plainly</h2>
 * The specification shows leaderboards with right-aligned value columns.
 * Minecraft's default font is proportional, not monospaced, so padding with
 * spaces produces alignment that drifts with the characters in each player's
 * name, and it drifts differently again on Bedrock. Rather than ship a fragile
 * approximation, entries are laid out as {@code #1 Name - value} on one line,
 * which reads correctly in every font on both platforms.
 */
public final class LeaderboardService implements Service, Listener, ConfigManager.Reloadable {

    /** Marks a display entity as ours, so strays can be identified and removed. */
    private final NamespacedKey markerKey;

    private final Plugin plugin;
    private final LeaderboardRepository repository;
    private final StatisticsRepository statistics;
    private final AccountRepository accounts;
    private final EconomyService economy;
    private final Scheduling scheduling;
    private final Logger logger;

    /** Board id to the entity currently rendering it. */
    private final Map<String, UUID> spawned = new ConcurrentHashMap<>();

    private volatile Duration updateInterval = Duration.ofSeconds(60);
    private volatile double viewRange = 4.0d;

    private BukkitTask updateTask;

    public LeaderboardService(Plugin plugin,
                              LeaderboardRepository repository,
                              StatisticsRepository statistics,
                              AccountRepository accounts,
                              EconomyService economy,
                              Scheduling scheduling,
                              Logger logger) {
        this.plugin = plugin;
        this.repository = repository;
        this.statistics = statistics;
        this.accounts = accounts;
        this.economy = economy;
        this.scheduling = scheduling;
        this.logger = logger;
        this.markerKey = new NamespacedKey(plugin, "leaderboard");
    }

    /** One row of a rendered board. */
    public record Entry(int position, String name, long value) {
    }

    @Override
    public void load(ConfigManager.ConfigBundle configs) {
        ConfigView section = configs.main().section("leaderboards");
        this.updateInterval = section.getDuration("update-interval", Duration.ofSeconds(60));
        this.viewRange = section.getInt("view-range", 4, 1, 64);
    }

    @Override
    public void onEnable() {
        removeStrayDisplays();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);

        long ticks = Math.max(20L, updateInterval.toSeconds() * 20L);
        // First refresh after a short delay so worlds are fully loaded.
        updateTask = scheduling.syncTimer(this::refreshAll, 100L, ticks);
    }

    /**
     * Refreshes shortly after a player joins.
     *
     * <p>A board whose chunk is unloaded is skipped rather than force-loading it,
     * so with an empty server no boards exist at all. Without this hook the first
     * player to arrive would stare at empty air until the next scheduled refresh.
     * The delay lets their chunks finish loading first.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        scheduling.syncLater(this::refreshAll, 40L);
    }

    @Override
    public void onDisable() {
        HandlerList.unregisterAll(this);
        if (updateTask != null) {
            updateTask.cancel();
            updateTask = null;
        }
        // Remove our displays so a reload does not leave two of everything.
        for (UUID entityId : List.copyOf(spawned.values())) {
            removeEntity(entityId);
        }
        spawned.clear();
    }

    /**
     * Deletes every display entity tagged as ours.
     *
     * <p>Run at startup. Because displays are non-persistent this should find
     * nothing in normal operation, but a {@code /reload} or an unclean shutdown
     * can leave them behind, and two boards stacked in the same spot is a
     * confusing thing to debug.
     */
    private void removeStrayDisplays() {
        int removed = 0;
        for (World world : Bukkit.getWorlds()) {
            for (TextDisplay display : world.getEntitiesByClass(TextDisplay.class)) {
                if (display.getPersistentDataContainer().has(markerKey, PersistentDataType.STRING)) {
                    display.remove();
                    removed++;
                }
            }
        }
        if (removed > 0) {
            logger.info("Removed " + removed + " leftover leaderboard display(s).");
        }
    }

    // ------------------------------------------------------------- lifecycle

    /**
     * Creates a board and renders it immediately.
     *
     * @return the created board, or empty if the id is already taken
     */
    public Optional<Leaderboard> create(String id, LeaderboardStat stat, Location location,
                                        int size, String title, UUID creator) {
        String normalised = id.toLowerCase(java.util.Locale.ROOT);
        if (repository.exists(normalised)) {
            return Optional.empty();
        }
        Leaderboard board = new Leaderboard(normalised, stat,
                location.getWorld().getName(), location.getX(), location.getY(), location.getZ(),
                size, title, System.currentTimeMillis(), creator);
        repository.save(board);
        return Optional.of(board);
    }

    /** Removes a board and its display. */
    public boolean remove(String id) {
        String normalised = id.toLowerCase(java.util.Locale.ROOT);
        UUID entityId = spawned.remove(normalised);
        if (entityId != null) {
            scheduling.sync(() -> removeEntity(entityId));
        }
        return repository.delete(normalised);
    }

    public List<Leaderboard> all() {
        return repository.all();
    }

    public LeaderboardRepository repository() {
        return repository;
    }

    // -------------------------------------------------------------- refresh

    /** Re-queries every board and updates its display. Safe to call from a command. */
    public void refreshAll() {
        scheduling.runAsync(() -> {
            List<Leaderboard> boards = repository.all();
            Map<String, List<Entry>> rendered = new HashMap<>();
            for (Leaderboard board : boards) {
                rendered.put(board.id(), fetchEntries(board));
            }
            scheduling.sync(() -> applyAll(boards, rendered));
        }).exceptionally(error -> {
            logger.log(Level.WARNING, "Leaderboard refresh failed", error);
            return null;
        });
    }

    /** Runs on a worker thread. */
    private List<Entry> fetchEntries(Leaderboard board) {
        List<Entry> entries = new ArrayList<>();
        if (board.stat() == LeaderboardStat.MONEY) {
            List<AccountRepository.BalanceEntry> top = accounts.topBalances(board.size());
            for (int i = 0; i < top.size(); i++) {
                entries.add(new Entry(i + 1, top.get(i).name(), top.get(i).balance()));
            }
            return entries;
        }

        var statistic = board.stat().statistic().orElse(null);
        if (statistic == null) {
            return entries;
        }
        List<StatisticsRepository.Ranked> top = statistics.top(statistic, board.size());
        for (int i = 0; i < top.size(); i++) {
            entries.add(new Entry(i + 1, top.get(i).name(), top.get(i).value()));
        }
        return entries;
    }

    /** Runs on the main thread. */
    private void applyAll(List<Leaderboard> boards, Map<String, List<Entry>> rendered) {
        java.util.Set<String> live = new java.util.HashSet<>();
        for (Leaderboard board : boards) {
            live.add(board.id());
            try {
                apply(board, rendered.getOrDefault(board.id(), List.of()));
            } catch (Exception e) {
                logger.log(Level.WARNING, "Could not render leaderboard '" + board.id() + "'", e);
            }
        }
        // Drop displays whose rows have gone (deleted by another means).
        for (String id : List.copyOf(spawned.keySet())) {
            if (!live.contains(id)) {
                UUID entityId = spawned.remove(id);
                removeEntity(entityId);
            }
        }
    }

    private void apply(Leaderboard board, List<Entry> entries) {
        Location location = board.location().orElse(null);
        if (location == null) {
            // World not loaded. Not an error; try again next interval.
            return;
        }
        if (!location.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
            // Spawning into an unloaded chunk would force-load it, which is not
            // something a display board should do to a server's memory profile.
            return;
        }

        TextDisplay display = resolveDisplay(board, location);
        if (display == null) {
            return;
        }
        display.text(render(board, entries));
    }

    private TextDisplay resolveDisplay(Leaderboard board, Location location) {
        UUID existingId = spawned.get(board.id());
        if (existingId != null) {
            var entity = Bukkit.getEntity(existingId);
            if (entity instanceof TextDisplay display && display.isValid()) {
                if (!display.getLocation().equals(location)) {
                    display.teleport(location);
                }
                return display;
            }
            spawned.remove(board.id());
        }
        return spawnDisplay(board, location);
    }

    private TextDisplay spawnDisplay(Leaderboard board, Location location) {
        TextDisplay display = location.getWorld().spawn(location, TextDisplay.class, spawned -> {
            spawned.setBillboard(Display.Billboard.CENTER);
            spawned.setAlignment(TextDisplay.TextAlignment.CENTER);
            spawned.setViewRange((float) viewRange);
            spawned.setShadowed(true);
            spawned.setSeeThrough(false);
            // Never written to the world save; recreated from the database.
            spawned.setPersistent(false);
            spawned.getPersistentDataContainer().set(
                    markerKey, PersistentDataType.STRING, board.id());
        });
        this.spawned.put(board.id(), display.getUniqueId());
        return display;
    }

    private void removeEntity(UUID entityId) {
        if (entityId == null) {
            return;
        }
        var entity = Bukkit.getEntity(entityId);
        if (entity != null) {
            entity.remove();
        }
    }

    // -------------------------------------------------------------- rendering

    private Component render(Leaderboard board, List<Entry> entries) {
        return LeaderboardRenderer.render(
                board.displayTitle(), board.stat(), entries, economy.money());
    }
}
