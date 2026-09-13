package com.servercore.permission;

/**
 * Every permission node the plugin checks.
 *
 * <p>Centralised so that nodes are declared once and referenced by constant.
 * Scattering string literals through the codebase is how a node ends up
 * misspelled in one place and silently ungated forever.
 *
 * <p>Nodes are also declared in {@code plugin.yml} with their defaults and
 * parent/child relationships, so {@code server.admin} implies the individual
 * admin nodes. Keep the two in step.
 */
public final class Permissions {

    private Permissions() {
    }

    /** Root admin node. Implies every {@code *.admin} node below. */
    public static final String ADMIN = "server.admin";

    // --- Navigation --------------------------------------------------------
    public static final String MENU_USE = "server.menu.use";

    // --- Economy -----------------------------------------------------------
    public static final String ECONOMY_BALANCE = "server.economy.balance";
    public static final String ECONOMY_BALANCE_OTHERS = "server.economy.balance.others";
    public static final String ECONOMY_PAY = "server.economy.pay";
    public static final String ECONOMY_ADMIN = "server.economy.admin";

    // --- Server shop -------------------------------------------------------
    public static final String SHOP_USE = "server.shop.use";
    public static final String SHOP_ADMIN = "server.shop.admin";

    // --- Auction house -----------------------------------------------------
    public static final String AUCTION_USE = "server.auction.use";
    public static final String AUCTION_CREATE = "server.auction.create";
    public static final String AUCTION_ADMIN = "server.auction.admin";

    // --- Claims ------------------------------------------------------------
    public static final String CLAIM_CREATE = "server.claim.create";
    public static final String CLAIM_EXPAND = "server.claim.expand";
    public static final String CLAIM_ADMIN = "server.claim.admin";

    // --- Player shops ------------------------------------------------------
    public static final String PLAYERSHOP_CREATE = "server.playershop.create";
    public static final String PLAYERSHOP_ADMIN = "server.playershop.admin";

    // --- Spawn plots -------------------------------------------------------
    public static final String PLOT_PURCHASE = "server.plot.purchase";
    public static final String PLOT_ADMIN = "server.plot.admin";

    // --- Teleport ----------------------------------------------------------
    public static final String TPA_USE = "server.tpa.use";
    public static final String TPA_ADMIN = "server.tpa.admin";

    // --- Statistics and leaderboards ---------------------------------------
    public static final String STATS_VIEW = "server.stats.view";
    public static final String LEADERBOARD_ADMIN = "server.leaderboard.admin";
}
