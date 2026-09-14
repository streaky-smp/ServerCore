package com.streakysmp.playershop;

import com.streakysmp.data.Database;
import org.bukkit.Material;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for player shops, their offers and their stock.
 */
public final class PlayerShopRepository {

    private final Database database;

    public PlayerShopRepository(Database database) {
        this.database = database;
    }

    /** A directory row: a shop plus the owner's name and what it trades. */
    public record DirectoryEntry(PlayerShop shop, String ownerName) {
    }

    // ------------------------------------------------------------- writing

    public void save(PlayerShop shop) {
        database.inTransaction(connection -> {
            saveWithin(connection, shop);
            return null;
        });
    }

    /** Saves the shop row only; offers are managed separately. */
    public void saveWithin(Connection connection, PlayerShop shop) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO sc_player_shop
                    (id, owner_uuid, name, description, world, x, y, z,
                     claim_id, plot_id, status, created_at, revenue)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    owner_uuid  = excluded.owner_uuid,
                    name        = excluded.name,
                    description = excluded.description,
                    claim_id    = excluded.claim_id,
                    plot_id     = excluded.plot_id,
                    status      = excluded.status,
                    revenue     = excluded.revenue
                """)) {
            ps.setString(1, shop.id());
            ps.setString(2, shop.owner().toString());
            ps.setString(3, shop.name());
            setNullable(ps, 4, shop.description());
            ps.setString(5, shop.worldName());
            ps.setInt(6, shop.x());
            ps.setInt(7, shop.y());
            ps.setInt(8, shop.z());
            setNullable(ps, 9, shop.claimId());
            setNullable(ps, 10, shop.plotId());
            ps.setString(11, shop.status().name());
            ps.setLong(12, shop.createdAt());
            ps.setLong(13, shop.revenue());
            ps.executeUpdate();
        }
    }

    /** Creates or updates one offer's prices, leaving its stock untouched. */
    public void saveOffer(String shopId, Material material, long buyPrice, long sellPrice) {
        database.inTransaction(connection -> {
            try (PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO sc_player_shop_offer
                        (shop_id, material, buy_price, sell_price, stock, created_at)
                    VALUES (?, ?, ?, ?, 0, ?)
                    ON CONFLICT(shop_id, material) DO UPDATE SET
                        buy_price  = excluded.buy_price,
                        sell_price = excluded.sell_price
                    """)) {
                ps.setString(1, shopId);
                ps.setString(2, key(material));
                ps.setLong(3, buyPrice);
                ps.setLong(4, sellPrice);
                ps.setLong(5, System.currentTimeMillis());
                ps.executeUpdate();
            }
            return null;
        });
    }

    public boolean removeOffer(String shopId, Material material) {
        return database.inTransaction(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM sc_player_shop_offer WHERE shop_id = ? AND material = ?")) {
                ps.setString(1, shopId);
                ps.setString(2, key(material));
                return ps.executeUpdate() > 0;
            }
        });
    }

    public boolean delete(String shopId) {
        return database.inTransaction(connection -> {
            // Offers cascade via the foreign key.
            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM sc_player_shop WHERE id = ?")) {
                ps.setString(1, shopId);
                return ps.executeUpdate() > 0;
            }
        });
    }

    // ------------------------------------------------------------- stock

    /**
     * Removes stock only if there is enough, inside the caller's transaction.
     *
     * <p>The conditional UPDATE is the whole safety mechanism: two customers
     * racing for the last item both see stock of one, and only the one whose
     * UPDATE matches actually gets it. Reading the stock and then deciding would
     * sell the same item twice.
     *
     * @return true if the stock was taken
     */
    public static boolean tryTakeStock(Connection connection, String shopId,
                                       Material material, int quantity) throws SQLException {
        if (quantity <= 0) {
            return true;
        }
        try (PreparedStatement ps = connection.prepareStatement("""
                UPDATE sc_player_shop_offer
                SET stock = stock - ?
                WHERE shop_id = ? AND material = ? AND stock >= ?
                """)) {
            ps.setInt(1, quantity);
            ps.setString(2, shopId);
            ps.setString(3, key(material));
            ps.setInt(4, quantity);
            return ps.executeUpdate() == 1;
        }
    }

    /** Adds stock inside the caller's transaction. */
    public static boolean addStock(Connection connection, String shopId,
                                   Material material, int quantity) throws SQLException {
        if (quantity <= 0) {
            return true;
        }
        try (PreparedStatement ps = connection.prepareStatement("""
                UPDATE sc_player_shop_offer
                SET stock = stock + ?
                WHERE shop_id = ? AND material = ?
                """)) {
            ps.setInt(1, quantity);
            ps.setString(2, shopId);
            ps.setString(3, key(material));
            return ps.executeUpdate() == 1;
        }
    }

    /** Reads one offer's stock inside the caller's transaction. */
    public static int stockOf(Connection connection, String shopId, Material material)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT stock FROM sc_player_shop_offer WHERE shop_id = ? AND material = ?")) {
            ps.setString(1, shopId);
            ps.setString(2, key(material));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /** Adds to a shop's lifetime revenue inside the caller's transaction. */
    public static void addRevenue(Connection connection, String shopId, long amount)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE sc_player_shop SET revenue = revenue + ? WHERE id = ?")) {
            ps.setLong(1, amount);
            ps.setString(2, shopId);
            ps.executeUpdate();
        }
    }

    /** Reads the status inside the caller's transaction, so a purchase re-checks it. */
    public static Optional<PlayerShopStatus> statusOf(Connection connection, String shopId)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT status FROM sc_player_shop WHERE id = ?")) {
            ps.setString(1, shopId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? PlayerShopStatus.byName(rs.getString(1)) : Optional.empty();
            }
        }
    }

    /** Reads one offer's prices inside the caller's transaction. */
    public static Optional<long[]> pricesOf(Connection connection, String shopId, Material material)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT buy_price, sell_price FROM sc_player_shop_offer "
                        + "WHERE shop_id = ? AND material = ?")) {
            ps.setString(1, shopId);
            ps.setString(2, key(material));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next()
                        ? Optional.of(new long[]{rs.getLong(1), rs.getLong(2)})
                        : Optional.empty();
            }
        }
    }

    // ------------------------------------------------------------- reading

    public Optional<PlayerShop> byId(String shopId) {
        return database.withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT * FROM sc_player_shop WHERE id = ?")) {
                ps.setString(1, shopId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.<PlayerShop>empty();
                    }
                    PlayerShop shop = mapBare(rs);
                    return Optional.of(shop.withOffers(loadOffers(connection, shopId)));
                }
            }
        });
    }

    public Optional<PlayerShop> byLocation(String worldName, int x, int y, int z) {
        return database.withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT * FROM sc_player_shop WHERE world = ? AND x = ? AND y = ? AND z = ?")) {
                ps.setString(1, worldName);
                ps.setInt(2, x);
                ps.setInt(3, y);
                ps.setInt(4, z);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.<PlayerShop>empty();
                    }
                    PlayerShop shop = mapBare(rs);
                    return Optional.of(shop.withOffers(loadOffers(connection, shop.id())));
                }
            }
        });
    }

    public List<PlayerShop> ownedBy(UUID owner) {
        return database.withConnection(connection -> {
            List<PlayerShop> shops = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT * FROM sc_player_shop WHERE owner_uuid = ? ORDER BY created_at")) {
                ps.setString(1, owner.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        shops.add(mapBare(rs));
                    }
                }
            }
            return attachOffers(connection, shops);
        });
    }

    public int countOwnedBy(UUID owner) {
        return database.withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT COUNT(*) FROM sc_player_shop WHERE owner_uuid = ?")) {
                ps.setString(1, owner.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        });
    }

    public static int countOwnedWithin(Connection connection, UUID owner) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM sc_player_shop WHERE owner_uuid = ?")) {
            ps.setString(1, owner.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /**
     * Every shop, for the directory.
     *
     * <p>Offers are loaded in one sweep rather than per shop, so a server with a
     * thousand stalls costs three queries.
     */
    public List<DirectoryEntry> directory() {
        return database.withConnection(connection -> {
            List<PlayerShop> shops = new ArrayList<>();
            Map<String, String> ownerNames = new HashMap<>();

            try (PreparedStatement ps = connection.prepareStatement("""
                    SELECT s.*, p.name AS owner_name
                    FROM sc_player_shop s
                    JOIN sc_player p ON p.uuid = s.owner_uuid
                    ORDER BY s.created_at
                    """);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    PlayerShop shop = mapBare(rs);
                    shops.add(shop);
                    ownerNames.put(shop.id(), rs.getString("owner_name"));
                }
            }

            List<PlayerShop> withOffers = attachOffers(connection, shops);
            List<DirectoryEntry> entries = new ArrayList<>(withOffers.size());
            for (PlayerShop shop : withOffers) {
                entries.add(new DirectoryEntry(shop, ownerNames.get(shop.id())));
            }
            return List.copyOf(entries);
        });
    }

    /** Shop ids that carry a given material in stock, for material search. */
    public List<String> shopIdsStocking(Material material) {
        return database.withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement("""
                    SELECT shop_id FROM sc_player_shop_offer
                    WHERE material = ? AND stock > 0 AND buy_price >= 0
                    """)) {
                ps.setString(1, key(material));
                List<String> ids = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        ids.add(rs.getString(1));
                    }
                }
                return List.copyOf(ids);
            }
        });
    }

    /** Sets status on every shop attached to a plot, used when rent lapses. */
    public int setStatusForPlot(String plotId, PlayerShopStatus status) {
        return database.inTransaction(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE sc_player_shop SET status = ? WHERE plot_id = ?")) {
                ps.setString(1, status.name());
                ps.setString(2, plotId);
                return ps.executeUpdate();
            }
        });
    }

    public List<PlayerShop> byPlot(String plotId) {
        return database.withConnection(connection -> {
            List<PlayerShop> shops = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT * FROM sc_player_shop WHERE plot_id = ?")) {
                ps.setString(1, plotId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        shops.add(mapBare(rs));
                    }
                }
            }
            return attachOffers(connection, shops);
        });
    }

    // ------------------------------------------------------------- mapping

    private static List<PlayerShop> attachOffers(Connection connection, List<PlayerShop> shops)
            throws SQLException {
        if (shops.isEmpty()) {
            return List.of();
        }
        Map<String, List<ShopOffer>> byShop = new HashMap<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM sc_player_shop_offer");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                mapOffer(rs).ifPresent(offer ->
                        byShop.computeIfAbsent(offer.shopId(), ignored -> new ArrayList<>())
                                .add(offer));
            }
        }
        List<PlayerShop> out = new ArrayList<>(shops.size());
        for (PlayerShop shop : shops) {
            out.add(shop.withOffers(byShop.getOrDefault(shop.id(), List.of())));
        }
        return List.copyOf(out);
    }

    private static List<ShopOffer> loadOffers(Connection connection, String shopId)
            throws SQLException {
        List<ShopOffer> offers = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM sc_player_shop_offer WHERE shop_id = ?")) {
            ps.setString(1, shopId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    mapOffer(rs).ifPresent(offers::add);
                }
            }
        }
        return List.copyOf(offers);
    }

    private static PlayerShop mapBare(ResultSet rs) throws SQLException {
        return new PlayerShop(
                rs.getString("id"),
                UUID.fromString(rs.getString("owner_uuid")),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("world"),
                rs.getInt("x"),
                rs.getInt("y"),
                rs.getInt("z"),
                rs.getString("claim_id"),
                rs.getString("plot_id"),
                PlayerShopStatus.byName(rs.getString("status")).orElse(PlayerShopStatus.CLOSED),
                rs.getLong("created_at"),
                rs.getLong("revenue"),
                List.of());
    }

    /**
     * Maps an offer row, skipping ones that cannot be represented.
     *
     * <p>A material removed by a Minecraft update, or an offer that somehow lost
     * both prices, is dropped from the listing rather than failing the whole load.
     * One unusable row must not take a player's entire shop offline.
     */
    private static Optional<ShopOffer> mapOffer(ResultSet rs) throws SQLException {
        Material material = Material.matchMaterial(rs.getString("material"));
        if (material == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(new ShopOffer(
                    rs.getLong("id"),
                    rs.getString("shop_id"),
                    material,
                    rs.getLong("buy_price"),
                    rs.getLong("sell_price"),
                    rs.getInt("stock"),
                    rs.getLong("created_at")));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static String key(Material material) {
        return material.name().toLowerCase(Locale.ROOT);
    }

    private static void setNullable(PreparedStatement ps, int index, String value)
            throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.VARCHAR);
        } else {
            ps.setString(index, value);
        }
    }
}
