package com.servercore.economy;

import java.util.UUID;

/**
 * One entry in the append-only ledger.
 *
 * @param id          monotonic identifier, the reference a player or admin quotes
 * @param from        who paid, or null where money was created (e.g. selling to the server)
 * @param to          who received, or null where money was destroyed (a sink)
 * @param amount      minor units received by {@code to}
 * @param fee         minor units taken on top of {@code amount}, paid by {@code from}
 */
public record Transaction(
        long id,
        long createdAt,
        TransactionType type,
        UUID from,
        UUID to,
        long amount,
        long fee,
        String description) {

    /** Total debited from the payer: what the recipient got, plus any fee. */
    public long totalDebited() {
        return amount + fee;
    }

    /** True if this entry moved money out of {@code viewer}'s account. */
    public boolean isOutgoingFor(UUID viewer) {
        return viewer.equals(from);
    }

    /**
     * The signed amount as it affected {@code viewer}.
     *
     * <p>Negative for money leaving, positive for money arriving. Used directly by
     * the transaction history GUI.
     */
    public long signedAmountFor(UUID viewer) {
        if (viewer.equals(from)) {
            return -totalDebited();
        }
        if (viewer.equals(to)) {
            return amount;
        }
        return 0L;
    }
}
