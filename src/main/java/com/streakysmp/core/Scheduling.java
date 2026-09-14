package com.streakysmp.core;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Thread routing for the plugin.
 *
 * <p>Nearly all of the Bukkit/Paper API is main-thread-only, while every
 * database call must be off it. Rather than leave that split to convention,
 * this class makes each hop explicit and provides {@link #ensureMainThread} so
 * a violation fails at the offending call site instead of corrupting world
 * state in a way that surfaces somewhere unrelated much later.
 */
public final class Scheduling {

    private final Plugin plugin;

    public Scheduling(Plugin plugin) {
        this.plugin = plugin;
    }

    public boolean isMainThread() {
        return Bukkit.isPrimaryThread();
    }

    /** Guards a Bukkit API call site. */
    public void ensureMainThread(String operation) {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException(operation
                    + " touches the Bukkit API and must run on the main thread, but ran on "
                    + Thread.currentThread().getName());
        }
    }

    /** Guards a blocking call site such as database or file I/O. */
    public void ensureOffMainThread(String operation) {
        if (Bukkit.isPrimaryThread()) {
            throw new IllegalStateException(operation + " blocks and must not run on the main thread");
        }
    }

    public BukkitTask async(Runnable task) {
        return Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
    }

    /**
     * Runs on the main thread, inline if already there.
     *
     * <p>Running inline matters for correctness rather than speed: it preserves
     * the caller's ordering instead of deferring the work to the next tick.
     */
    public void sync(Runnable task) {
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    public BukkitTask syncLater(Runnable task, long delayTicks) {
        return Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks);
    }

    public BukkitTask syncTimer(Runnable task, long delayTicks, long periodTicks) {
        return Bukkit.getScheduler().runTaskTimer(plugin, task, delayTicks, periodTicks);
    }

    public BukkitTask asyncTimer(Runnable task, long delayTicks, long periodTicks) {
        return Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, task, delayTicks, periodTicks);
    }

    /** Runs {@code supplier} off-thread and completes the returned future with its result. */
    public <T> CompletableFuture<T> supplyAsync(Supplier<T> supplier) {
        CompletableFuture<T> future = new CompletableFuture<>();
        async(() -> {
            try {
                future.complete(supplier.get());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    public CompletableFuture<Void> runAsync(Runnable runnable) {
        return supplyAsync(() -> {
            runnable.run();
            return null;
        });
    }

    /**
     * Delivers {@code future}'s outcome on the main thread.
     *
     * <p>This is the standard tail of an async database read: compute off-thread,
     * then touch the Bukkit API safely. {@code onError} also runs on the main
     * thread so it can message the player directly.
     */
    public <T> void thenSync(CompletableFuture<T> future, Consumer<T> consumer, Consumer<Throwable> onError) {
        future.whenComplete((value, error) -> sync(() -> {
            if (error != null) {
                onError.accept(unwrap(error));
            } else {
                consumer.accept(value);
            }
        }));
    }

    private static Throwable unwrap(Throwable t) {
        if (t instanceof CompletionException && t.getCause() != null) {
            return t.getCause();
        }
        return t;
    }
}
