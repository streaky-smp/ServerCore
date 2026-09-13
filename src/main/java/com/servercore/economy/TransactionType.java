package com.servercore.economy;

/**
 * What moved money.
 *
 * <p>Constant names are persisted verbatim in {@code sc_transaction.type}.
 * Renaming one orphans existing history, so add constants rather than rename.
 *
 * <p>Each type is also classified by its effect on the total money supply, which
 * is what makes it possible to answer "is this economy inflating?" from the
 * ledger alone.
 */
public enum TransactionType {

    /** Player to player, via {@code /pay}. Neutral: transfers, never creates. */
    PAY(Flow.TRANSFER),

    /** Player bought from the server shop. Sink: the money leaves circulation. */
    SHOP_BUY(Flow.SINK),

    /** Player sold to the server shop. Source: new money enters circulation. */
    SHOP_SELL(Flow.SOURCE),

    /** Player bought an auction listing. Neutral. */
    AUCTION_BUY(Flow.TRANSFER),

    /** Proceeds paid to an auction seller. Neutral, paired with AUCTION_BUY. */
    AUCTION_SALE(Flow.TRANSFER),

    /** Auction listing fees and sales tax. Sink. */
    AUCTION_FEE(Flow.SINK),

    CLAIM_PURCHASE(Flow.SINK),
    CLAIM_EXPANSION(Flow.SINK),
    PLOT_PURCHASE(Flow.SINK),
    SHOP_RENT(Flow.SINK),
    PLAYERSHOP_PURCHASE(Flow.TRANSFER),

    /** A fee charged on a player-to-player transfer. Sink. */
    TRANSFER_FEE(Flow.SINK),

    /**
     * An administrator changed a balance directly.
     *
     * <p>Classified as neither source nor sink because it can be either; economy
     * reports must show admin adjustments separately rather than folding them
     * into organic figures.
     */
    ADMIN(Flow.ADMIN),

    OTHER(Flow.ADMIN);

    /** Effect on the total money supply. */
    public enum Flow {
        /** Creates money. */
        SOURCE,
        /** Destroys money. */
        SINK,
        /** Moves money between players without changing the total. */
        TRANSFER,
        /** Manual intervention; excluded from organic economy figures. */
        ADMIN
    }

    private final Flow flow;

    TransactionType(Flow flow) {
        this.flow = flow;
    }

    public Flow flow() {
        return flow;
    }

    /** Human-readable label for GUIs. */
    public String displayName() {
        String lower = name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }
}
