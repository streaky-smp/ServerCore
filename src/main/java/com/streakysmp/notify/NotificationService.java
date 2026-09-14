package com.streakysmp.notify;

import com.streakysmp.core.Service;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;

/**
 * The single route for anything the player sees or hears.
 *
 * <p>Centralised so that tone, prefixing and sound are consistent, and so the
 * spec's "use notifications appropriately rather than spamming players" rule has
 * one place to be enforced rather than being re-litigated at every call site.
 */
public interface NotificationService extends Service {

    /** Neutral information. */
    void info(CommandSender target, String messageKey, Map<String, String> placeholders);

    /** An action succeeded. Carries the success sound. */
    void success(CommandSender target, String messageKey, Map<String, String> placeholders);

    /** An action was refused or failed. Carries the failure sound. */
    void error(CommandSender target, String messageKey, Map<String, String> placeholders);

    /** Sends an already-rendered component with no prefix or sound. */
    void raw(CommandSender target, Component component);

    /** Transient status text above the hotbar. */
    void actionBar(Player player, Component component);

    /** A large centre-screen title. Reserve for genuinely important moments. */
    void title(Player player, Component title, Component subtitle);

    /**
     * Plays a configured sound by its config key, e.g. {@code success} or
     * {@code auction.sold}.
     */
    void sound(Player player, String soundKey);

    /**
     * Delivers to a player whether or not they are online.
     *
     * <p>Online players see it immediately. For offline players the message is
     * stored and shown on their next join, which is what makes "your auction
     * sold" and "your rent is overdue" reliable rather than best-effort.
     *
     * <p>Safe to call from any thread.
     */
    void notifyPlayer(UUID playerId, Component message);

    /** Convenience overload resolving a message key. */
    void notifyPlayer(UUID playerId, String messageKey, Map<String, String> placeholders);
}
