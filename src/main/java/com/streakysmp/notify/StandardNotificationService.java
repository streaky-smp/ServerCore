package com.streakysmp.notify;

import com.streakysmp.config.ConfigManager;
import com.streakysmp.config.ConfigView;
import com.streakysmp.core.Scheduling;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Default {@link NotificationService}.
 *
 * <p>Sounds are addressed by {@link Key} rather than by a {@code Sound} constant.
 * Minecraft's sound registry changes shape between versions -- it is an interface
 * rather than an enum in this one -- and an unknown key simply does not play,
 * which is a far better failure mode than refusing to start because a sound was
 * renamed upstream.
 */
public final class StandardNotificationService implements NotificationService, ConfigManager.Reloadable {

    private final Messages messages;
    private final NotificationRepository repository;
    private final Scheduling scheduling;
    private final Logger logger;

    private volatile boolean soundsEnabled = true;
    private volatile Map<String, Sound> sounds = Map.of();

    public StandardNotificationService(Messages messages,
                                       NotificationRepository repository,
                                       Scheduling scheduling,
                                       Logger logger) {
        this.messages = messages;
        this.repository = repository;
        this.scheduling = scheduling;
        this.logger = logger;
    }

    @Override
    public void load(ConfigManager.ConfigBundle configs) {
        ConfigView notifications = configs.main().section("notifications");
        this.soundsEnabled = notifications.getBoolean("sounds-enabled", true);

        Map<String, Sound> parsed = new HashMap<>();
        notifications.optionalSection("sounds").ifPresent(section -> {
            for (String key : section.keys()) {
                String spec = section.getString(key, "");
                parseSound(spec).ifPresent(sound -> parsed.put(key, sound));
            }
        });
        this.sounds = Map.copyOf(parsed);
    }

    /**
     * Parses {@code namespace:name} with optional {@code |volume|pitch}.
     *
     * <p>Returns empty for unparseable input rather than throwing: a bad sound
     * name should not stop the server, and the missing sound is self-evident.
     */
    private java.util.Optional<Sound> parseSound(String spec) {
        if (spec == null || spec.isBlank()) {
            return java.util.Optional.empty();
        }
        String[] parts = spec.split("\\|");
        try {
            Key key = Key.key(parts[0].trim());
            float volume = parts.length > 1 ? Float.parseFloat(parts[1].trim()) : 1.0f;
            float pitch = parts.length > 2 ? Float.parseFloat(parts[2].trim()) : 1.0f;
            return java.util.Optional.of(Sound.sound(key, Sound.Source.MASTER, volume, pitch));
        } catch (Exception e) {
            logger.warning("Ignoring unparseable sound specification: '" + spec + "'");
            return java.util.Optional.empty();
        }
    }

    @Override
    public void info(CommandSender target, String messageKey, Map<String, String> placeholders) {
        target.sendMessage(messages.prefixed(messageKey, placeholders));
    }

    @Override
    public void success(CommandSender target, String messageKey, Map<String, String> placeholders) {
        target.sendMessage(messages.prefixed(messageKey, placeholders));
        if (target instanceof Player player) {
            sound(player, "success");
        }
    }

    @Override
    public void error(CommandSender target, String messageKey, Map<String, String> placeholders) {
        target.sendMessage(messages.prefixed(messageKey, placeholders));
        if (target instanceof Player player) {
            sound(player, "error");
        }
    }

    @Override
    public void raw(CommandSender target, Component component) {
        target.sendMessage(component);
    }

    @Override
    public void actionBar(Player player, Component component) {
        player.sendActionBar(component);
    }

    @Override
    public void title(Player player, Component title, Component subtitle) {
        player.showTitle(Title.title(title, subtitle, Title.Times.times(
                Duration.ofMillis(300), Duration.ofSeconds(3), Duration.ofMillis(500))));
    }

    @Override
    public void sound(Player player, String soundKey) {
        if (!soundsEnabled) {
            return;
        }
        Sound sound = sounds.get(soundKey);
        if (sound != null) {
            player.playSound(sound);
        }
    }

    @Override
    public void notifyPlayer(UUID playerId, Component message) {
        Player online = Bukkit.getPlayer(playerId);
        if (online != null && online.isOnline()) {
            // Already on the right thread in the common case; sync() runs inline.
            scheduling.sync(() -> online.sendMessage(message));
            return;
        }
        String serialised = MiniMessage.miniMessage().serialize(message);
        scheduling.runAsync(() -> repository.queue(playerId, serialised))
                .exceptionally(error -> {
                    logger.log(Level.WARNING, "Could not queue offline notification for " + playerId, error);
                    return null;
                });
    }

    @Override
    public void notifyPlayer(UUID playerId, String messageKey, Map<String, String> placeholders) {
        notifyPlayer(playerId, messages.prefixed(messageKey, placeholders));
    }

    /**
     * Shows and clears a returning player's queued messages.
     *
     * <p>Called from the join listener. Reads on a worker thread and delivers on
     * the main thread.
     */
    public void deliverPending(Player player) {
        UUID id = player.getUniqueId();
        scheduling.thenSync(
                scheduling.supplyAsync(() -> repository.takePending(id)),
                pending -> {
                    if (pending.isEmpty() || !player.isOnline()) {
                        return;
                    }
                    player.sendMessage(messages.prefixed("notifications.while-away",
                            Messages.of("count", String.valueOf(pending.size()))));
                    for (String markup : pending) {
                        player.sendMessage(MiniMessage.miniMessage().deserialize(markup));
                    }
                },
                error -> logger.log(Level.WARNING, "Could not deliver queued notifications to "
                        + player.getName(), error));
    }

    /** Sound keys currently configured, for diagnostics. */
    public List<String> configuredSoundKeys() {
        return List.copyOf(sounds.keySet());
    }
}
