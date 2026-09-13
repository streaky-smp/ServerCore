package com.servercore.data;

import org.bukkit.Bukkit;

/**
 * Tells {@link Database} whether the calling thread is the server's main thread.
 *
 * <p>Exists so the persistence layer holds no direct reference to Bukkit. That
 * keeps two things possible: unit-testing repositories against a real SQLite file
 * with no server running, and reusing the layer unchanged if the plugin is ever
 * ported to a platform with a different threading model.
 */
@FunctionalInterface
public interface ThreadGuard {

    boolean isMainThread();

    /** The real check, backed by the running server. */
    static ThreadGuard bukkit() {
        return Bukkit::isPrimaryThread;
    }

    /**
     * Always reports "not the main thread".
     *
     * <p>For tests, where there is no server and every call is legitimately off
     * the main thread.
     */
    static ThreadGuard offMainThread() {
        return () -> false;
    }
}
