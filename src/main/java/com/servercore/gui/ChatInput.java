package com.servercore.gui;

import com.servercore.core.Scheduling;
import com.servercore.core.Service;
import com.servercore.util.Text;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Collects a line of text from a player through chat.
 *
 * <p>Chat is used in preference to an anvil-rename GUI or a sign editor because
 * it is the only text-entry method that behaves the same on Java and on Bedrock
 * through Geyser. Anvil text does not reliably round-trip, and sign editing on
 * Bedrock opens a native dialog whose contents the server sees differently.
 *
 * <p>Captured messages are cancelled so a search term never reaches public chat.
 */
public final class ChatInput implements Service, Listener {

    /** Refuses absurd input before it reaches a search query or a shop name. */
    private static final int MAX_INPUT_LENGTH = 256;

    private final Plugin plugin;
    private final Scheduling scheduling;
    private final long timeoutTicks;

    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();

    public ChatInput(Plugin plugin, Scheduling scheduling, long timeoutTicks) {
        this.plugin = plugin;
        this.scheduling = scheduling;
        this.timeoutTicks = timeoutTicks;
    }

    private record Pending(Consumer<String> onInput, Runnable onCancel, BukkitTask timeout) {
    }

    @Override
    public void onEnable() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @Override
    public void onDisable() {
        HandlerList.unregisterAll(this);
        for (Pending p : pending.values()) {
            p.timeout().cancel();
        }
        pending.clear();
    }

    /**
     * Prompts {@code player} and calls back with what they type.
     *
     * <p>Both callbacks run on the main thread. A prompt already outstanding for
     * this player is cancelled first, so a player cannot stack prompts by
     * clicking a search button repeatedly.
     *
     * @param onInput  receives the typed text, trimmed and length-limited
     * @param onCancel invoked if the player types {@code cancel}, or the prompt
     *                 times out; typically reopens the menu they came from
     */
    public void prompt(Player player, String promptMiniMessage, Consumer<String> onInput, Runnable onCancel) {
        UUID id = player.getUniqueId();
        cancelExisting(id, false);

        BukkitTask timeout = scheduling.syncLater(() -> {
            if (pending.remove(id) != null && player.isOnline()) {
                player.sendMessage(Text.mm("<gray>Input timed out.</gray>"));
                safely(onCancel);
            }
        }, timeoutTicks);

        pending.put(id, new Pending(onInput, onCancel, timeout));
        player.sendMessage(Text.mm(promptMiniMessage));
    }

    /** Cancels any outstanding prompt for a player. */
    public void cancel(Player player) {
        cancelExisting(player.getUniqueId(), true);
    }

    public boolean isAwaitingInput(Player player) {
        return pending.containsKey(player.getUniqueId());
    }

    private void cancelExisting(UUID id, boolean invokeCallback) {
        Pending existing = pending.remove(id);
        if (existing == null) {
            return;
        }
        existing.timeout().cancel();
        if (invokeCallback) {
            scheduling.sync(() -> safely(existing.onCancel()));
        }
    }

    /**
     * Intercepts chat from a player with an outstanding prompt.
     *
     * <p>Runs at LOWEST so the message is cancelled before chat plugins format or
     * broadcast it. Not {@code ignoreCancelled}, because a captured prompt should
     * still be consumed even if another plugin already cancelled the message.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        Pending waiting = pending.remove(player.getUniqueId());
        if (waiting == null) {
            return;
        }
        event.setCancelled(true);
        waiting.timeout().cancel();

        String raw = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        String input = raw.length() > MAX_INPUT_LENGTH ? raw.substring(0, MAX_INPUT_LENGTH) : raw;

        // This handler is on Paper's async chat thread; everything downstream
        // touches the Bukkit API and must be back on the main thread.
        scheduling.sync(() -> {
            if (!player.isOnline()) {
                return;
            }
            if (input.isEmpty() || input.equalsIgnoreCase("cancel")) {
                safely(waiting.onCancel());
            } else {
                safely(() -> waiting.onInput().accept(input));
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Pending removed = pending.remove(event.getPlayer().getUniqueId());
        if (removed != null) {
            removed.timeout().cancel();
        }
    }

    private void safely(Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "Chat input callback failed", e);
        }
    }

    /** Case-insensitive cancel keyword, exposed for prompt text. */
    public static String cancelKeyword() {
        return "cancel".toLowerCase(Locale.ROOT);
    }
}
