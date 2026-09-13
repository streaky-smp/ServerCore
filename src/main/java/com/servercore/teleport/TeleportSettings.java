package com.servercore.teleport;

import com.servercore.config.ConfigView;

import java.time.Duration;

/**
 * Immutable snapshot of the teleport configuration.
 *
 * @param warmUp          delay before the teleport happens, so it can be interrupted
 * @param cancelOnMove    whether moving during the warm-up cancels it
 * @param cancelOnDamage  whether taking damage during the warm-up cancels it
 * @param moveTolerance   how far a player may drift before it counts as moving
 */
public record TeleportSettings(
        boolean enabled,
        Duration requestExpiry,
        Duration cooldown,
        Duration warmUp,
        boolean cancelOnMove,
        boolean cancelOnDamage,
        double moveTolerance,
        boolean requireSafeDestination,
        int maxPendingPerPlayer) {

    public static TeleportSettings from(ConfigView tpa) {
        return new TeleportSettings(
                tpa.getBoolean("enabled", true),
                tpa.getDuration("request-expiry", Duration.ofSeconds(60)),
                tpa.getDuration("cooldown", Duration.ofSeconds(30)),
                tpa.getDuration("warm-up", Duration.ofSeconds(5)),
                tpa.getBoolean("cancel-on-move", true),
                tpa.getBoolean("cancel-on-damage", true),
                // A small tolerance rather than zero: standing still still
                // produces sub-block jitter from mounts, boats and pistons, and
                // cancelling on that would make the warm-up impossible to finish.
                readTolerance(tpa),
                tpa.getBoolean("require-safe-destination", true),
                tpa.getInt("max-pending-per-player", 5, 1, 50));
    }

    private static double readTolerance(ConfigView tpa) {
        long raw = tpa.getMoney("move-tolerance", 50L, 2);
        return raw / 100.0d;
    }

    public boolean hasWarmUp() {
        return !warmUp.isZero() && !warmUp.isNegative();
    }

    public boolean hasCooldown() {
        return !cooldown.isZero() && !cooldown.isNegative();
    }
}
