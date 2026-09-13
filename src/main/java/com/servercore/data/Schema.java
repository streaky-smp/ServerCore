package com.servercore.data;

import java.util.ArrayList;
import java.util.List;

/**
 * The ordered list of schema migrations for the whole plugin.
 *
 * <p>Each development phase appends a new version here rather than editing an
 * earlier one. Versions already shipped are frozen; see {@link Migration}.
 *
 * <p>Conventions used throughout:
 * <ul>
 *   <li>UUIDs are stored as 36-character {@code TEXT}. Slightly larger than a
 *       16-byte blob, and worth it for being readable in a SQL console when an
 *       operator is investigating a dispute.</li>
 *   <li>Timestamps are {@code INTEGER} epoch milliseconds, UTC.</li>
 *   <li>Money is {@code INTEGER} minor units. Never a REAL -- see
 *       {@link com.servercore.util.Numbers}.</li>
 *   <li>Tables are prefixed {@code sc_} so the database can be shared with
 *       another plugin without collisions.</li>
 * </ul>
 */
public final class Schema {

    private Schema() {
    }

    public static List<Migration> migrations() {
        List<Migration> migrations = new ArrayList<>();
        migrations.add(v1Foundation());
        migrations.add(v2Economy());
        migrations.add(v3Statistics());
        migrations.add(v4Leaderboards());
        migrations.add(v5Claims());
        migrations.add(v6PlayerShops());
        migrations.add(v7SpawnPlots());
        migrations.add(v8AuctionHouse());
        return List.copyOf(migrations);
    }

    /**
     * Phase 9 auction house: fixed-price listings with a status machine.
     *
     * <p>{@code status} is the concurrency guard. A purchase flips {@code ACTIVE}
     * to {@code SOLD} with a conditional UPDATE, and only the transaction whose
     * update matches may take the item. Two buyers clicking simultaneously
     * therefore cannot both receive it -- the loser is told it has gone.
     *
     * <p>{@code item_data} is Paper's own item serialisation, so enchantments,
     * custom names, durability and data components all survive a round trip. A
     * material-plus-count column pair would quietly strip everything that makes a
     * listing worth more than its base item.
     *
     * <p>The table is deliberately shaped so bidding can be added later without a
     * migration to existing rows: a {@code bids} table and a nullable
     * {@code current_bid} would extend it rather than replace it.
     */
    private static Migration v8AuctionHouse() {
        return new Migration(8, "Auction house: listings and collection", List.of("""
                CREATE TABLE IF NOT EXISTS sc_auction_listing (
                    id           TEXT    NOT NULL PRIMARY KEY,
                    seller_uuid  TEXT    NOT NULL,
                    item_data    BLOB    NOT NULL,
                    material     TEXT    NOT NULL,
                    quantity     INTEGER NOT NULL CHECK (quantity > 0),
                    price        INTEGER NOT NULL CHECK (price >= 0),
                    display_name TEXT,
                    created_at   INTEGER NOT NULL,
                    expires_at   INTEGER NOT NULL,
                    status       TEXT    NOT NULL DEFAULT 'ACTIVE',
                    buyer_uuid   TEXT,
                    sold_at      INTEGER,
                    collected    INTEGER NOT NULL DEFAULT 0,
                    FOREIGN KEY (seller_uuid) REFERENCES sc_player (uuid) ON DELETE CASCADE
                )
                """, """
                CREATE INDEX IF NOT EXISTS idx_auction_active
                    ON sc_auction_listing (status, expires_at)
                """, """
                CREATE INDEX IF NOT EXISTS idx_auction_seller
                    ON sc_auction_listing (seller_uuid, status)
                """, """
                CREATE INDEX IF NOT EXISTS idx_auction_material
                    ON sc_auction_listing (material, status)
                """, """
                CREATE INDEX IF NOT EXISTS idx_auction_price
                    ON sc_auction_listing (status, price)
                """, """
                CREATE INDEX IF NOT EXISTS idx_auction_collect
                    ON sc_auction_listing (seller_uuid, collected, status)
                """));
    }

    /**
     * Phase 8 spawn plots: the premium commercial district and its rent.
     *
     * <p>{@code rent_due_at} is an absolute timestamp rather than a countdown, so
     * rent stays correct across restarts and downtime. A server offline for three
     * days does not hand every tenant three free days.
     *
     * <p>{@code grace_ends_at} is set when a payment first fails and cleared when
     * one succeeds, which is what lets the grace period survive a restart rather
     * than beginning again each boot.
     */
    private static Migration v7SpawnPlots() {
        return new Migration(7, "Spawn plots: commercial district and rent", List.of("""
                CREATE TABLE IF NOT EXISTS sc_plot (
                    id             TEXT    NOT NULL PRIMARY KEY,
                    world          TEXT    NOT NULL,
                    min_x          INTEGER NOT NULL,
                    min_z          INTEGER NOT NULL,
                    max_x          INTEGER NOT NULL,
                    max_z          INTEGER NOT NULL,
                    purchase_price INTEGER NOT NULL,
                    rent_price     INTEGER NOT NULL,
                    rent_period    TEXT    NOT NULL,
                    owner_uuid     TEXT,
                    status         TEXT    NOT NULL DEFAULT 'AVAILABLE',
                    purchased_at   INTEGER,
                    rent_due_at    INTEGER,
                    grace_ends_at  INTEGER,
                    rent_paid      INTEGER NOT NULL DEFAULT 0,
                    created_at     INTEGER NOT NULL,
                    CHECK (min_x <= max_x AND min_z <= max_z)
                )
                """, """
                CREATE INDEX IF NOT EXISTS idx_plot_owner ON sc_plot (owner_uuid)
                """, """
                CREATE INDEX IF NOT EXISTS idx_plot_status ON sc_plot (status)
                """, """
                CREATE INDEX IF NOT EXISTS idx_plot_rent_due ON sc_plot (rent_due_at)
                """, """
                CREATE INDEX IF NOT EXISTS idx_plot_bounds
                    ON sc_plot (world, min_x, max_x, min_z, max_z)
                """, """
                CREATE TABLE IF NOT EXISTS sc_plot_mailbox (
                    id         INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                    player_uuid TEXT   NOT NULL,
                    plot_id    TEXT    NOT NULL,
                    item_data  BLOB    NOT NULL,
                    stored_at  INTEGER NOT NULL,
                    collected  INTEGER NOT NULL DEFAULT 0,
                    FOREIGN KEY (player_uuid) REFERENCES sc_player (uuid) ON DELETE CASCADE
                )
                """, """
                CREATE INDEX IF NOT EXISTS idx_mailbox_player
                    ON sc_plot_mailbox (player_uuid, collected)
                """));
    }

    /**
     * Phase 7 player shops: player-owned stalls with their own stock.
     *
     * <p>Stock is held as rows rather than as a serialised inventory. A serialised
     * blob would be opaque to SQL, so "which shops sell iron" -- the question the
     * shop directory exists to answer -- would need every shop deserialised and
     * scanned. Rows make it one indexed query.
     *
     * <p>Each offer carries its own stock count, and a purchase decrements it with
     * a conditional UPDATE in the same transaction that moves the money, so two
     * customers cannot buy the same last item.
     */
    private static Migration v6PlayerShops() {
        return new Migration(6, "Player shops: stalls, offers and stock", List.of("""
                CREATE TABLE IF NOT EXISTS sc_player_shop (
                    id          TEXT    NOT NULL PRIMARY KEY,
                    owner_uuid  TEXT    NOT NULL,
                    name        TEXT    NOT NULL,
                    description TEXT,
                    world       TEXT    NOT NULL,
                    x           INTEGER NOT NULL,
                    y           INTEGER NOT NULL,
                    z           INTEGER NOT NULL,
                    claim_id    TEXT,
                    plot_id     TEXT,
                    status      TEXT    NOT NULL DEFAULT 'OPEN',
                    created_at  INTEGER NOT NULL,
                    revenue     INTEGER NOT NULL DEFAULT 0,
                    FOREIGN KEY (owner_uuid) REFERENCES sc_player (uuid) ON DELETE CASCADE
                )
                """, """
                CREATE INDEX IF NOT EXISTS idx_player_shop_owner ON sc_player_shop (owner_uuid)
                """, """
                CREATE INDEX IF NOT EXISTS idx_player_shop_location
                    ON sc_player_shop (world, x, y, z)
                """, """
                CREATE INDEX IF NOT EXISTS idx_player_shop_status ON sc_player_shop (status)
                """, """
                CREATE TABLE IF NOT EXISTS sc_player_shop_offer (
                    id         INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                    shop_id    TEXT    NOT NULL,
                    material   TEXT    NOT NULL,
                    buy_price  INTEGER NOT NULL DEFAULT -1,
                    sell_price INTEGER NOT NULL DEFAULT -1,
                    stock      INTEGER NOT NULL DEFAULT 0 CHECK (stock >= 0),
                    created_at INTEGER NOT NULL,
                    UNIQUE (shop_id, material),
                    FOREIGN KEY (shop_id) REFERENCES sc_player_shop (id) ON DELETE CASCADE
                )
                """, """
                CREATE INDEX IF NOT EXISTS idx_offer_material ON sc_player_shop_offer (material)
                """, """
                CREATE INDEX IF NOT EXISTS idx_offer_shop ON sc_player_shop_offer (shop_id)
                """));
    }

    /**
     * Phase 6 claims: land ownership, members and per-claim flags.
     *
     * <p>Claims are rectangular in the horizontal plane and full-height. That is a
     * deliberate simplification: vertical claims mean a player can be standing
     * inside someone's claim and above their own, and every protection check
     * becomes ambiguous about which claim applies. Full-height keeps "who owns
     * this spot" a single unambiguous answer.
     *
     * <p>Bounds are stored inclusive and pre-normalised (min &lt;= max), so
     * containment is a plain comparison with no per-check sorting.
     *
     * <p>The chunk columns exist purely to make the spatial index loadable with
     * one indexed query per chunk instead of a scan over every claim in the world.
     */
    private static Migration v5Claims() {
        return new Migration(5, "Claims: land ownership, members and flags", List.of("""
                CREATE TABLE IF NOT EXISTS sc_claim (
                    id          TEXT    NOT NULL PRIMARY KEY,
                    owner_uuid  TEXT    NOT NULL,
                    world       TEXT    NOT NULL,
                    min_x       INTEGER NOT NULL,
                    min_z       INTEGER NOT NULL,
                    max_x       INTEGER NOT NULL,
                    max_z       INTEGER NOT NULL,
                    min_chunk_x INTEGER NOT NULL,
                    min_chunk_z INTEGER NOT NULL,
                    max_chunk_x INTEGER NOT NULL,
                    max_chunk_z INTEGER NOT NULL,
                    name        TEXT    NOT NULL,
                    created_at  INTEGER NOT NULL,
                    paid        INTEGER NOT NULL DEFAULT 0,
                    CHECK (min_x <= max_x AND min_z <= max_z),
                    FOREIGN KEY (owner_uuid) REFERENCES sc_player (uuid) ON DELETE CASCADE
                )
                """, """
                CREATE INDEX IF NOT EXISTS idx_claim_owner ON sc_claim (owner_uuid)
                """, """
                CREATE INDEX IF NOT EXISTS idx_claim_world_chunks
                    ON sc_claim (world, min_chunk_x, max_chunk_x, min_chunk_z, max_chunk_z)
                """, """
                CREATE TABLE IF NOT EXISTS sc_claim_member (
                    claim_id    TEXT    NOT NULL,
                    player_uuid TEXT    NOT NULL,
                    trust       TEXT    NOT NULL,
                    added_at    INTEGER NOT NULL,
                    PRIMARY KEY (claim_id, player_uuid),
                    FOREIGN KEY (claim_id) REFERENCES sc_claim (id) ON DELETE CASCADE
                )
                """, """
                CREATE INDEX IF NOT EXISTS idx_claim_member_player
                    ON sc_claim_member (player_uuid)
                """, """
                CREATE TABLE IF NOT EXISTS sc_claim_flag (
                    claim_id TEXT    NOT NULL,
                    flag     TEXT    NOT NULL,
                    allowed  INTEGER NOT NULL,
                    PRIMARY KEY (claim_id, flag),
                    FOREIGN KEY (claim_id) REFERENCES sc_claim (id) ON DELETE CASCADE
                )
                """));
    }

    /**
     * Phase 5 leaderboards: the placements operators create in the world.
     *
     * <p>Only the placement is stored. The display entities themselves are
     * deliberately not persisted into the world -- they are recreated from these
     * rows on every startup, which is what stops abandoned holograms from
     * accumulating in the world file forever.
     */
    private static Migration v4Leaderboards() {
        return new Migration(4, "Leaderboards: physical placements", List.of("""
                CREATE TABLE IF NOT EXISTS sc_leaderboard (
                    id         TEXT    NOT NULL PRIMARY KEY,
                    stat       TEXT    NOT NULL,
                    world      TEXT    NOT NULL,
                    x          REAL    NOT NULL,
                    y          REAL    NOT NULL,
                    z          REAL    NOT NULL,
                    size       INTEGER NOT NULL,
                    title      TEXT,
                    created_at INTEGER NOT NULL,
                    created_by TEXT
                )
                """, """
                CREATE INDEX IF NOT EXISTS idx_leaderboard_world ON sc_leaderboard (world)
                """));
    }

    /**
     * Phase 4 statistics: a generic counter table and the kill log.
     *
     * <p>Statistics are stored as {@code (player, key, value)} rows rather than as
     * columns. The spec lists a dozen statistics that might be added later --
     * blocks broken, distance travelled, items sold -- and a column per statistic
     * would mean a migration for each. A key-value shape absorbs new counters with
     * no schema change at all.
     *
     * <p>The kill log exists solely to answer "has this player killed that player
     * recently", which is what makes kill-farming detectable. It is pruned on a
     * schedule; it is not a permanent history.
     */
    private static Migration v3Statistics() {
        return new Migration(3, "Statistics: counters and kill log", List.of("""
                CREATE TABLE IF NOT EXISTS sc_statistic (
                    player_uuid TEXT    NOT NULL,
                    stat_key    TEXT    NOT NULL,
                    value       INTEGER NOT NULL DEFAULT 0,
                    updated_at  INTEGER NOT NULL,
                    PRIMARY KEY (player_uuid, stat_key),
                    FOREIGN KEY (player_uuid) REFERENCES sc_player (uuid) ON DELETE CASCADE
                )
                """, """
                CREATE INDEX IF NOT EXISTS idx_statistic_leaderboard
                    ON sc_statistic (stat_key, value DESC)
                """, """
                CREATE TABLE IF NOT EXISTS sc_kill (
                    id           INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                    killer_uuid  TEXT    NOT NULL,
                    victim_uuid  TEXT    NOT NULL,
                    killed_at    INTEGER NOT NULL,
                    counted      INTEGER NOT NULL DEFAULT 1
                )
                """, """
                CREATE INDEX IF NOT EXISTS idx_kill_pair
                    ON sc_kill (killer_uuid, victim_uuid, killed_at DESC)
                """, """
                CREATE INDEX IF NOT EXISTS idx_kill_time ON sc_kill (killed_at)
                """));
    }

    /**
     * Phase 2 economy: accounts and the transaction ledger.
     *
     * <p>{@code balance} is a non-negative integer count of minor units, enforced
     * by a CHECK constraint. The constraint is a genuine safety net rather than
     * decoration: if a bug ever produced an unguarded debit, the database refuses
     * the write instead of quietly creating a negative balance that later reads as
     * free money when it wraps or is summed.
     *
     * <p>The ledger is append-only. Transactions are never updated or deleted, so
     * the sum of the ledger always explains the current balances -- which is what
     * makes a dispute investigable.
     */
    private static Migration v2Economy() {
        return new Migration(2, "Economy: accounts and transaction ledger", List.of("""
                CREATE TABLE IF NOT EXISTS sc_account (
                    uuid       TEXT    NOT NULL PRIMARY KEY,
                    balance    INTEGER NOT NULL DEFAULT 0 CHECK (balance >= 0),
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    FOREIGN KEY (uuid) REFERENCES sc_player (uuid) ON DELETE CASCADE
                )
                """, """
                CREATE INDEX IF NOT EXISTS idx_account_balance ON sc_account (balance DESC)
                """, """
                CREATE TABLE IF NOT EXISTS sc_transaction (
                    id          INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                    created_at  INTEGER NOT NULL,
                    type        TEXT    NOT NULL,
                    from_uuid   TEXT,
                    to_uuid     TEXT,
                    amount      INTEGER NOT NULL,
                    fee         INTEGER NOT NULL DEFAULT 0,
                    description TEXT
                )
                """, """
                CREATE INDEX IF NOT EXISTS idx_transaction_from
                    ON sc_transaction (from_uuid, created_at DESC)
                """, """
                CREATE INDEX IF NOT EXISTS idx_transaction_to
                    ON sc_transaction (to_uuid, created_at DESC)
                """, """
                CREATE INDEX IF NOT EXISTS idx_transaction_created
                    ON sc_transaction (created_at DESC)
                """));
    }

    /**
     * Phase 1 foundation: player identity and the audit log.
     *
     * <p>Every other table in later phases references {@code sc_player(uuid)},
     * so player rows are created on first join before anything else can need
     * them.
     */
    private static Migration v1Foundation() {
        return new Migration(1, "Foundation: players and audit log", List.of("""
                CREATE TABLE IF NOT EXISTS sc_player (
                    uuid        TEXT    NOT NULL PRIMARY KEY,
                    name        TEXT    NOT NULL,
                    name_lower  TEXT    NOT NULL,
                    first_seen  INTEGER NOT NULL,
                    last_seen   INTEGER NOT NULL,
                    platform    TEXT    NOT NULL DEFAULT 'JAVA'
                )
                """, """
                CREATE INDEX IF NOT EXISTS idx_player_name_lower ON sc_player (name_lower)
                """, """
                CREATE INDEX IF NOT EXISTS idx_player_last_seen ON sc_player (last_seen)
                """, """
                CREATE TABLE IF NOT EXISTS sc_audit_log (
                    id          INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                    created_at  INTEGER NOT NULL,
                    action      TEXT    NOT NULL,
                    actor_uuid  TEXT,
                    actor_name  TEXT,
                    target_uuid TEXT,
                    target_name TEXT,
                    amount      INTEGER,
                    object_id   TEXT,
                    details     TEXT
                )
                """, """
                CREATE INDEX IF NOT EXISTS idx_audit_created_at ON sc_audit_log (created_at)
                """, """
                CREATE INDEX IF NOT EXISTS idx_audit_actor ON sc_audit_log (actor_uuid, created_at)
                """, """
                CREATE INDEX IF NOT EXISTS idx_audit_action ON sc_audit_log (action, created_at)
                """, """
                CREATE TABLE IF NOT EXISTS sc_notification (
                    id          INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                    player_uuid TEXT    NOT NULL,
                    created_at  INTEGER NOT NULL,
                    message     TEXT    NOT NULL,
                    delivered   INTEGER NOT NULL DEFAULT 0,
                    FOREIGN KEY (player_uuid) REFERENCES sc_player (uuid) ON DELETE CASCADE
                )
                """, """
                CREATE INDEX IF NOT EXISTS idx_notification_pending
                    ON sc_notification (player_uuid, delivered, created_at)
                """));
    }
}
