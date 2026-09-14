package com.streakysmp.leaderboard;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.Optional;
import java.util.UUID;

/**
 * An operator-placed leaderboard in the world.
 *
 * <p>The world is stored by name rather than by a resolved {@link World}: a world
 * may not be loaded when the row is read, and a leaderboard in an unloaded world
 * should be skipped quietly, not crash startup.
 *
 * @param id    short identifier used in commands
 * @param size  how many players to list
 * @param title MiniMessage heading, or null to use the statistic's default
 */
public record Leaderboard(
        String id,
        LeaderboardStat stat,
        String worldName,
        double x,
        double y,
        double z,
        int size,
        String title,
        long createdAt,
        UUID createdBy) {

    /** Bounds that keep a display readable and its query cheap. */
    public static final int MIN_SIZE = 1;
    public static final int MAX_SIZE = 15;

    public Leaderboard {
        if (size < MIN_SIZE || size > MAX_SIZE) {
            throw new IllegalArgumentException(
                    "Leaderboard size must be " + MIN_SIZE + "-" + MAX_SIZE + ", got " + size);
        }
    }

    /** The location, if its world is currently loaded. */
    public Optional<Location> location() {
        World world = Bukkit.getWorld(worldName);
        return world == null ? Optional.empty() : Optional.of(new Location(world, x, y, z));
    }

    public String displayTitle() {
        return title == null || title.isBlank() ? stat.defaultTitle() : title;
    }
}
