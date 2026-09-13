package com.servercore.log;

/**
 * Auditable events.
 *
 * <p>Constant names are persisted verbatim in {@code sc_audit_log.action}, so
 * renaming one orphans existing history. Add new constants; do not rename old
 * ones.
 */
public enum AuditAction {

    // --- Economy -----------------------------------------------------------
    PAY,
    SHOP_TRANSACTION,
    AUCTION_TRANSACTION,
    RENT_PAYMENT,

    // --- Claims ------------------------------------------------------------
    CLAIM_CREATED,
    CLAIM_DELETED,
    CLAIM_TRANSFERRED,
    CLAIM_EXPANDED,

    // --- Shops and plots ---------------------------------------------------
    SHOP_CREATED,
    SHOP_DELETED,
    PLOT_PURCHASED,
    PLOT_RELEASED,

    // --- Administration ----------------------------------------------------
    ADMIN_BALANCE_CHANGE,
    ADMIN_ACTION,

    /**
     * Input that failed server-side validation in a way a normal client cannot
     * produce -- a click on a GUI slot that holds no button, a listing id that
     * does not exist, a quantity outside the configured bounds.
     *
     * <p>Logged rather than merely rejected, because a burst of these is the
     * signal that someone is probing the plugin with a modified client.
     */
    SECURITY_VIOLATION,

    OTHER
}
