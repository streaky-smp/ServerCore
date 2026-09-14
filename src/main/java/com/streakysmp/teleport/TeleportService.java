package com.streakysmp.teleport;

import com.streakysmp.config.ConfigManager;
import com.streakysmp.core.Scheduling;
import com.streakysmp.core.Service;
import com.streakysmp.notify.Messages;
import com.streakysmp.notify.NotificationService;
import com.streakysmp.util.Durations;
import com.streakysmp.util.Text;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Teleport requests and the warm-up that follows an accepted one.
 *
 * <p>Entirely in memory. Requests live for under a minute and a warm-up for a few
 * seconds, so persisting either would add a schema for state that is meaningless
 * after a restart.
 *
 * <h2>Why the warm-up matters</h2>
 * Without one, {@code /tpa} is an instant escape from combat. The warm-up, and
 * cancelling it on movement or damage, is what stops teleporting being a
 * get-out-of-jail card -- which is why both are on by default.
 */
public final class TeleportService implements Service, Listener, ConfigManager.Reloadable {

    /** Distance below which a location is treated as unchanged. */
    private static final double MIN_TOLERANCE = 0.05d;

    private final Plugin plugin;
    private final NotificationService notifications;
    private final Scheduling scheduling;

    /** Requests indexed by target, since accepting looks them up that way. */
    private final Map<UUID, List<TeleportRequest>> incoming = new ConcurrentHashMap<>();

    /** Warm-ups currently running, by the player being teleported. */
    private final Map<UUID, Warmup> warmups = new ConcurrentHashMap<>();

    /** Last completed teleport per player, for the cooldown. */
    private final Map<UUID, Long> lastTeleport = new ConcurrentHashMap<>();

    private volatile TeleportSettings settings;
    private BukkitTask expiryTask;

    public TeleportService(Plugin plugin,
                           NotificationService notifications,
                           Scheduling scheduling) {
        this.plugin = plugin;
        this.notifications = notifications;
        this.scheduling = scheduling;
    }

    /** A teleport waiting out its warm-up. */
    private record Warmup(UUID traveller, Location origin, BukkitTask task) {
    }

    /** Why a request could not be created or accepted. */
    public enum Outcome {
        SUCCESS,
        DISABLED,
        SELF,
        ALREADY_PENDING,
        TOO_MANY_PENDING,
        NO_REQUEST,
        COOLDOWN,
        TARGET_OFFLINE,
        UNSAFE_DESTINATION,
        ALREADY_TELEPORTING
    }

    @Override
    public void load(ConfigManager.ConfigBundle configs) {
        this.settings = TeleportSettings.from(configs.main().section("tpa"));
    }

    public TeleportSettings settings() {
        TeleportSettings snapshot = settings;
        if (snapshot == null) {
            throw new IllegalStateException("Teleport settings accessed before configuration loaded");
        }
        return snapshot;
    }

    @Override
    public void onEnable() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        // Prune expired requests once a second so senders learn promptly.
        expiryTask = scheduling.syncTimer(this::pruneExpired, 20L, 20L);
    }

    @Override
    public void onDisable() {
        HandlerList.unregisterAll(this);
        if (expiryTask != null) {
            expiryTask.cancel();
            expiryTask = null;
        }
        for (Warmup warmup : warmups.values()) {
            warmup.task().cancel();
        }
        warmups.clear();
        incoming.clear();
    }

    // -------------------------------------------------------------- requests

    /**
     * Creates a request from {@code requester} to {@code target}.
     *
     * <p>Main thread.
     */
    public Outcome request(Player requester, Player target, TeleportRequest.Direction direction) {
        TeleportSettings config = settings();
        if (!config.enabled()) {
            return Outcome.DISABLED;
        }
        if (requester.getUniqueId().equals(target.getUniqueId())) {
            return Outcome.SELF;
        }

        long cooldown = remainingCooldownMillis(requester.getUniqueId());
        if (cooldown > 0) {
            return Outcome.COOLDOWN;
        }

        List<TeleportRequest> pending = incoming.computeIfAbsent(
                target.getUniqueId(), ignored -> new ArrayList<>());

        synchronized (pending) {
            long now = System.currentTimeMillis();
            pending.removeIf(request -> request.hasExpired(now));

            // A second request from the same player replaces nothing and just
            // spams the target, so it is refused rather than queued.
            boolean duplicate = pending.stream()
                    .anyMatch(request -> request.requester().equals(requester.getUniqueId()));
            if (duplicate) {
                return Outcome.ALREADY_PENDING;
            }
            if (pending.size() >= config.maxPendingPerPlayer()) {
                return Outcome.TOO_MANY_PENDING;
            }

            pending.add(new TeleportRequest(requester.getUniqueId(), target.getUniqueId(),
                    direction, now, now + config.requestExpiry().toMillis()));
        }
        return Outcome.SUCCESS;
    }

    /** Pending requests addressed to a player, newest last. */
    public List<TeleportRequest> pendingFor(UUID target) {
        List<TeleportRequest> pending = incoming.get(target);
        if (pending == null) {
            return List.of();
        }
        synchronized (pending) {
            long now = System.currentTimeMillis();
            pending.removeIf(request -> request.hasExpired(now));
            return List.copyOf(pending);
        }
    }

    /** The oldest pending request for a target, which is what a bare accept uses. */
    public Optional<TeleportRequest> oldestFor(UUID target) {
        List<TeleportRequest> pending = pendingFor(target);
        return pending.isEmpty() ? Optional.empty() : Optional.of(pending.getFirst());
    }

    /**
     * Accepts a request and starts the warm-up.
     *
     * <p>Main thread.
     */
    public Outcome accept(Player target, TeleportRequest request) {
        if (!remove(request)) {
            return Outcome.NO_REQUEST;
        }
        Player traveller = plugin.getServer().getPlayer(request.traveller());
        Player anchor = plugin.getServer().getPlayer(request.destination());
        if (traveller == null || anchor == null) {
            return Outcome.TARGET_OFFLINE;
        }
        if (warmups.containsKey(traveller.getUniqueId())) {
            return Outcome.ALREADY_TELEPORTING;
        }

        Location destination = anchor.getLocation();
        if (settings().requireSafeDestination() && !isSafe(destination)) {
            return Outcome.UNSAFE_DESTINATION;
        }
        beginWarmup(traveller, anchor);
        return Outcome.SUCCESS;
    }

    /** Removes a request without accepting it. */
    public boolean deny(TeleportRequest request) {
        return remove(request);
    }

    /** Cancels a request the sender created. */
    public boolean cancelFrom(UUID requester, UUID target) {
        List<TeleportRequest> pending = incoming.get(target);
        if (pending == null) {
            return false;
        }
        synchronized (pending) {
            return pending.removeIf(request -> request.requester().equals(requester));
        }
    }

    private boolean remove(TeleportRequest request) {
        List<TeleportRequest> pending = incoming.get(request.target());
        if (pending == null) {
            return false;
        }
        synchronized (pending) {
            return pending.remove(request);
        }
    }

    private void pruneExpired() {
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, List<TeleportRequest>> entry : incoming.entrySet()) {
            List<TeleportRequest> pending = entry.getValue();
            List<TeleportRequest> expired;
            synchronized (pending) {
                expired = pending.stream().filter(request -> request.hasExpired(now)).toList();
                pending.removeAll(expired);
            }
            for (TeleportRequest request : expired) {
                notifications.notifyPlayer(request.requester(), "tpa.expired-sender", Messages.of());
            }
        }
    }

    // --------------------------------------------------------------- warm-up

    /**
     * Starts the countdown, telling the player not to move.
     *
     * <p>The destination is re-read when the countdown finishes rather than
     * captured now: a player who walks somewhere better during those five seconds
     * should be met where they actually are.
     */
    private void beginWarmup(Player traveller, Player anchor) {
        TeleportSettings config = settings();
        UUID travellerId = traveller.getUniqueId();
        UUID anchorId = anchor.getUniqueId();

        if (!config.hasWarmUp()) {
            performTeleport(traveller, anchor);
            return;
        }

        int seconds = (int) Math.max(1, config.warmUp().toSeconds());
        notifications.info(traveller, "tpa.warmup-start",
                Messages.of("seconds", String.valueOf(seconds)));

        Location origin = traveller.getLocation().clone();
        final int[] remaining = {seconds};

        BukkitTask task = scheduling.syncTimer(() -> {
            Player current = plugin.getServer().getPlayer(travellerId);
            Player target = plugin.getServer().getPlayer(anchorId);
            if (current == null || target == null) {
                cancelWarmup(travellerId, "tpa.cancelled-offline");
                return;
            }
            if (--remaining[0] > 0) {
                notifications.actionBar(current, Text.mm(
                        "<yellow>Teleporting in <white>" + remaining[0] + "</white>...</yellow>"));
                return;
            }
            warmups.remove(travellerId);
            performTeleport(current, target);
        }, 20L, 20L);

        warmups.put(travellerId, new Warmup(travellerId, origin, task));
    }

    private void performTeleport(Player traveller, Player anchor) {
        Location destination = anchor.getLocation();
        if (settings().requireSafeDestination() && !isSafe(destination)) {
            notifications.error(traveller, "tpa.unsafe", Messages.of());
            return;
        }
        traveller.teleport(destination);
        lastTeleport.put(traveller.getUniqueId(), System.currentTimeMillis());
        notifications.success(traveller, "tpa.teleported",
                Messages.of("name", Text.escape(anchor.getName())));
    }

    /** Stops a running warm-up and tells the player why. */
    public void cancelWarmup(UUID traveller, String messageKey) {
        Warmup warmup = warmups.remove(traveller);
        if (warmup == null) {
            return;
        }
        warmup.task().cancel();
        Player player = plugin.getServer().getPlayer(traveller);
        if (player != null) {
            notifications.error(player, messageKey, Messages.of());
        }
    }

    public boolean isTeleporting(UUID player) {
        return warmups.containsKey(player);
    }

    public long remainingCooldownMillis(UUID player) {
        TeleportSettings config = settings();
        if (!config.hasCooldown()) {
            return 0L;
        }
        Long last = lastTeleport.get(player);
        if (last == null) {
            return 0L;
        }
        return Math.max(0L, config.cooldown().toMillis() - (System.currentTimeMillis() - last));
    }

    public String formattedCooldown(UUID player) {
        return Durations.remaining(Duration.ofMillis(remainingCooldownMillis(player)));
    }

    // ---------------------------------------------------------------- safety

    /**
     * Whether a destination will not immediately kill or suffocate the traveller.
     *
     * <p>Checks the two blocks a player occupies are passable, that there is
     * something to stand on, and that the floor is not lava or fire. It is not a
     * guarantee -- a mob can still be waiting -- but it rules out the failure modes
     * that are the teleport's own fault.
     */
    public static boolean isSafe(Location location) {
        if (location == null || location.getWorld() == null) {
            return false;
        }
        Block feet = location.getBlock();
        Block head = feet.getRelative(0, 1, 0);
        Block floor = feet.getRelative(0, -1, 0);

        if (!feet.isPassable() || !head.isPassable()) {
            return false;
        }
        if (isHarmful(feet.getType()) || isHarmful(head.getType()) || isHarmful(floor.getType())) {
            return false;
        }
        // Something solid underfoot, or the player arrives in freefall. Checking a
        // short distance down allows for landing on the far side of a slab or step.
        for (int drop = 1; drop <= 4; drop++) {
            Block below = feet.getRelative(0, -drop, 0);
            if (!below.isPassable()) {
                return true;
            }
            if (isHarmful(below.getType())) {
                return false;
            }
        }
        return false;
    }

    private static boolean isHarmful(Material material) {
        return material == Material.LAVA
                || material == Material.FIRE
                || material == Material.SOUL_FIRE
                || material == Material.CAMPFIRE
                || material == Material.SOUL_CAMPFIRE
                || material == Material.MAGMA_BLOCK
                || material == Material.CACTUS
                || material == Material.SWEET_BERRY_BUSH
                || material == Material.POWDER_SNOW
                || material == Material.WITHER_ROSE;
    }

    // ---------------------------------------------------------------- events

    /**
     * Cancels a warm-up when the player moves.
     *
     * <p>Compares against the origin rather than the previous tick, so slow
     * drifting cannot creep past the check one fraction of a block at a time.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (warmups.isEmpty() || !settings().cancelOnMove()) {
            return;
        }
        Warmup warmup = warmups.get(event.getPlayer().getUniqueId());
        if (warmup == null) {
            return;
        }
        Location to = event.getTo();
        if (!to.getWorld().equals(warmup.origin().getWorld())) {
            cancelWarmup(event.getPlayer().getUniqueId(), "tpa.cancelled-moved");
            return;
        }
        double tolerance = Math.max(MIN_TOLERANCE, settings().moveTolerance());
        if (to.distanceSquared(warmup.origin()) > tolerance * tolerance) {
            cancelWarmup(event.getPlayer().getUniqueId(), "tpa.cancelled-moved");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (warmups.isEmpty() || !settings().cancelOnDamage()) {
            return;
        }
        if (event.getEntity() instanceof Player player
                && warmups.containsKey(player.getUniqueId())) {
            cancelWarmup(player.getUniqueId(), "tpa.cancelled-damage");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        Warmup warmup = warmups.remove(id);
        if (warmup != null) {
            warmup.task().cancel();
        }
        incoming.remove(id);
        // Drop any requests this player sent to others.
        for (List<TeleportRequest> pending : incoming.values()) {
            synchronized (pending) {
                pending.removeIf(request -> request.requester().equals(id));
            }
        }
        lastTeleport.remove(id);
    }
}
