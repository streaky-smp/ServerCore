package com.servercore.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Type-keyed registry of {@link Service} instances.
 *
 * <p>Modules resolve collaborators through the interface they depend on rather
 * than by importing a concrete class. That indirection is what keeps the
 * modules in this plugin decoupled from one another.
 *
 * <p>Registration is main-thread-only and happens during startup. Once startup
 * completes the registry is sealed and never mutated again, which is what makes
 * concurrent lookups safe without synchronization.
 */
public final class ServiceRegistry {

    private final Map<Class<?>, Service> services = new LinkedHashMap<>();
    private final List<Service> startOrder = new ArrayList<>();
    private volatile boolean sealed;

    /**
     * Registers {@code service} under {@code type}.
     *
     * @throws IllegalStateException if the registry is sealed or the type is taken
     */
    public <T extends Service> void register(Class<T> type, T service) {
        if (sealed) {
            throw new IllegalStateException("Cannot register " + type.getSimpleName() + " after startup");
        }
        if (services.containsKey(type)) {
            throw new IllegalStateException("Duplicate service registration for " + type.getName());
        }
        services.put(type, service);
        startOrder.add(service);
    }

    /**
     * Resolves a service, failing loudly if absent.
     *
     * <p>A missing service is always a wiring bug, never a runtime condition, so
     * this throws rather than returning null and deferring the failure to some
     * unrelated call site.
     */
    public <T extends Service> T get(Class<T> type) {
        Service service = services.get(type);
        if (service == null) {
            throw new IllegalStateException("No service registered for " + type.getName());
        }
        return type.cast(service);
    }

    /** Resolves a service that may legitimately be absent. */
    public <T extends Service> Optional<T> find(Class<T> type) {
        return Optional.ofNullable(services.get(type)).map(type::cast);
    }

    /** Services in registration order. */
    public List<Service> startOrder() {
        return List.copyOf(startOrder);
    }

    /** Services in reverse registration order, for shutdown. */
    public List<Service> shutdownOrder() {
        List<Service> reversed = new ArrayList<>(startOrder);
        Collections.reverse(reversed);
        return List.copyOf(reversed);
    }

    /**
     * Closes the registry to further registration.
     *
     * <p>Called once startup finishes. After this the backing map is immutable in
     * practice, which is what makes concurrent lookups safe without locking.
     */
    public void seal() {
        this.sealed = true;
    }
}
