package com.servercore.command;

import com.servercore.core.Scheduling;
import com.servercore.data.PlayerRecord;
import com.servercore.data.PlayerRepository;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Resolves a typed player name to an identity.
 *
 * <p>Online players resolve instantly from the server. Everyone else is looked up
 * in the player table, which is what makes {@code /pay} work for someone who is
 * offline.
 *
 * <p>Names are never used as identity beyond this point. A name can be released
 * and claimed by somebody else, so the resolved UUID is "the player who last used
 * that name on this server" -- correct for paying a friend, and the reason the
 * confirmation dialog shows the name back to the sender before anything moves.
 */
public final class PlayerLookup {

    private final PlayerRepository players;
    private final Scheduling scheduling;

    public PlayerLookup(PlayerRepository players, Scheduling scheduling) {
        this.players = players;
        this.scheduling = scheduling;
    }

    /** A resolved player. */
    public record Target(UUID uuid, String name, boolean online) {
    }

    /**
     * Resolves asynchronously, since the offline path hits the database.
     *
     * <p>The returned future completes on a worker thread; deliver its result with
     * {@link Scheduling#thenSync} before touching the Bukkit API.
     */
    public CompletableFuture<Optional<Target>> resolve(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return CompletableFuture.completedFuture(
                    Optional.of(new Target(online.getUniqueId(), online.getName(), true)));
        }
        return scheduling.supplyAsync(() -> players.findByName(name)
                .map(record -> new Target(record.uuid(), record.name(), false)));
    }

    /**
     * Name suggestions for tab-completion.
     *
     * <p>Only online players. Tab-completion runs on the main thread and must not
     * touch the database, and offering every name the server has ever seen would
     * leak the player list to anyone with a keyboard.
     */
    public List<String> suggestOnline(String partial, Player requester) {
        String needle = partial.toLowerCase(Locale.ROOT);
        List<String> names = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            // Respect vanish-style hiding: never suggest someone the requester
            // cannot see.
            if (requester != null && !requester.canSee(player)) {
                continue;
            }
            if (player.getName().toLowerCase(Locale.ROOT).startsWith(needle)) {
                names.add(player.getName());
            }
        }
        return names;
    }

    /** Blocking lookup for code already running off the main thread. */
    public Optional<PlayerRecord> findOffline(String name) {
        return players.findByName(name);
    }

    public PlayerRepository repository() {
        return players;
    }
}
