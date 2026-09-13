package com.servercore.core;

/**
 * A managed subsystem with a controlled lifecycle.
 *
 * <p>Services start in registration order and stop in reverse, so a service may
 * safely depend on anything registered before it.
 */
public interface Service {

    /**
     * Brings the service up. Throwing aborts plugin startup, which is
     * deliberate: a framework that silently half-starts is worse than one that
     * refuses to start.
     */
    default void onEnable() throws Exception {
    }

    /**
     * Tears the service down. Implementations must not throw. Shutdown runs even
     * when startup failed partway, so this may be called on a service that never
     * fully enabled.
     */
    default void onDisable() {
    }

    default String serviceName() {
        return getClass().getSimpleName();
    }
}
