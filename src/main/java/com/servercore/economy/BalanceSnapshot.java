package com.servercore.economy;

import java.util.List;
import java.util.UUID;

/**
 * Everything the balance screen displays, gathered in one worker-thread pass.
 *
 * <p>Assembled off the main thread and then handed to the GUI as an immutable
 * value. Menus render from this rather than querying as they draw, which is what
 * keeps {@code Menu#build} free of database access.
 *
 * @param rank         1-based position in the balance ranking
 * @param accountCount total accounts, so rank can be shown as "12 of 340"
 */
public record BalanceSnapshot(
        UUID uuid,
        String name,
        long balance,
        int rank,
        int accountCount,
        List<Transaction> recent) {

    public BalanceSnapshot {
        recent = List.copyOf(recent);
    }
}
