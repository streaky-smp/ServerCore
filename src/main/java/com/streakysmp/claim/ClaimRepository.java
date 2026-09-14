package com.streakysmp.claim;

import com.streakysmp.data.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for claims, their members and their flag overrides.
 *
 * <p>A claim is read as one aggregate -- bounds, members and flags together -- so
 * the in-memory index always holds a complete picture and a protection check never
 * has to touch the database.
 */
public final class ClaimRepository {

    private final Database database;

    public ClaimRepository(Database database) {
        this.database = database;
    }

    /** Writes a claim and replaces its members and flags to match. */
    public void save(Claim claim) {
        database.inTransaction(connection -> {
            saveWithin(connection, claim);
            return null;
        });
    }

    /** Saves inside a caller's transaction, so a purchase and the claim commit together. */
    public void saveWithin(Connection connection, Claim claim) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO sc_claim
                    (id, owner_uuid, world, min_x, min_z, max_x, max_z,
                     min_chunk_x, min_chunk_z, max_chunk_x, max_chunk_z,
                     name, created_at, paid)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    owner_uuid  = excluded.owner_uuid,
                    min_x       = excluded.min_x,
                    min_z       = excluded.min_z,
                    max_x       = excluded.max_x,
                    max_z       = excluded.max_z,
                    min_chunk_x = excluded.min_chunk_x,
                    min_chunk_z = excluded.min_chunk_z,
                    max_chunk_x = excluded.max_chunk_x,
                    max_chunk_z = excluded.max_chunk_z,
                    name        = excluded.name,
                    paid        = excluded.paid
                """)) {
            ps.setString(1, claim.id());
            ps.setString(2, claim.owner().toString());
            ps.setString(3, claim.worldName());
            ps.setInt(4, claim.minX());
            ps.setInt(5, claim.minZ());
            ps.setInt(6, claim.maxX());
            ps.setInt(7, claim.maxZ());
            ps.setInt(8, claim.minChunkX());
            ps.setInt(9, claim.minChunkZ());
            ps.setInt(10, claim.maxChunkX());
            ps.setInt(11, claim.maxChunkZ());
            ps.setString(12, claim.name());
            ps.setLong(13, claim.createdAt());
            ps.setLong(14, claim.paid());
            ps.executeUpdate();
        }

        // Replace rather than diff: a claim has a handful of members, and
        // delete-then-insert inside one transaction is both simpler and
        // impossible to leave half-applied.
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM sc_claim_member WHERE claim_id = ?")) {
            ps.setString(1, claim.id());
            ps.executeUpdate();
        }
        if (!claim.members().isEmpty()) {
            try (PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO sc_claim_member (claim_id, player_uuid, trust, added_at)
                    VALUES (?, ?, ?, ?)
                    """)) {
                long now = System.currentTimeMillis();
                for (Map.Entry<UUID, TrustLevel> member : claim.members().entrySet()) {
                    ps.setString(1, claim.id());
                    ps.setString(2, member.getKey().toString());
                    ps.setString(3, member.getValue().name());
                    ps.setLong(4, now);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        }

        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM sc_claim_flag WHERE claim_id = ?")) {
            ps.setString(1, claim.id());
            ps.executeUpdate();
        }
        if (!claim.publicFlags().isEmpty()) {
            try (PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO sc_claim_flag (claim_id, flag, allowed) VALUES (?, ?, ?)
                    """)) {
                for (Map.Entry<ClaimFlag, Boolean> flag : claim.publicFlags().entrySet()) {
                    ps.setString(1, claim.id());
                    ps.setString(2, flag.getKey().name());
                    ps.setInt(3, flag.getValue() ? 1 : 0);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        }
    }

    /**
     * Whether any stored claim overlaps {@code candidate}, checked inside the
     * caller's transaction.
     *
     * <p>This is the <strong>authoritative</strong> overlap test. The in-memory
     * index is checked first for a fast rejection and a useful error message, but
     * that check happens before the transaction opens, so two players claiming the
     * same land at the same moment can both pass it. Re-testing here -- inside the
     * write transaction, which SQLite serialises -- is what makes "only one claim
     * may cover a block" actually true rather than usually true.
     *
     * @param ignoreId a claim to exclude, so resizing does not collide with itself
     */
    public static boolean overlapsWithin(Connection connection, Claim candidate, String ignoreId)
            throws SQLException {
        // Inclusive-bounds overlap on both axes. The world/chunk index makes this
        // a lookup rather than a scan.
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT 1 FROM sc_claim
                WHERE world = ?
                  AND id <> ?
                  AND min_x <= ? AND max_x >= ?
                  AND min_z <= ? AND max_z >= ?
                LIMIT 1
                """)) {
            ps.setString(1, candidate.worldName());
            ps.setString(2, ignoreId == null ? "" : ignoreId);
            ps.setInt(3, candidate.maxX());
            ps.setInt(4, candidate.minX());
            ps.setInt(5, candidate.maxZ());
            ps.setInt(6, candidate.minZ());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** Claims owned by a player, counted inside the caller's transaction. */
    public static int countOwnedWithin(Connection connection, UUID owner) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM sc_claim WHERE owner_uuid = ?")) {
            ps.setString(1, owner.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    public boolean delete(String claimId) {
        return database.inTransaction(connection -> {
            // Members and flags cascade via foreign keys, which are enforced
            // because the connection enables them.
            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM sc_claim WHERE id = ?")) {
                ps.setString(1, claimId);
                return ps.executeUpdate() > 0;
            }
        });
    }

    public Optional<Claim> byId(String claimId) {
        return database.withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT * FROM sc_claim WHERE id = ?")) {
                ps.setString(1, claimId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.<Claim>empty();
                    }
                    Claim bare = mapBare(rs);
                    return Optional.of(bare
                            .withMembers(loadMembers(connection, claimId))
                            .withPublicFlags(loadFlags(connection, claimId)));
                }
            }
        });
    }

    /**
     * Every claim, with members and flags attached.
     *
     * <p>Called once at startup to build the index. Loads members and flags in two
     * sweeps rather than per claim, so a server with thousands of claims costs
     * three queries rather than thousands.
     */
    public List<Claim> loadAll() {
        return database.withConnection(connection -> {
            Map<String, Map<UUID, TrustLevel>> members = loadAllMembers(connection);
            Map<String, Map<ClaimFlag, Boolean>> flags = loadAllFlags(connection);

            List<Claim> claims = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM sc_claim");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Claim bare = mapBare(rs);
                    claims.add(bare
                            .withMembers(members.getOrDefault(bare.id(), Map.of()))
                            .withPublicFlags(flags.getOrDefault(bare.id(), Map.of())));
                }
            }
            return List.copyOf(claims);
        });
    }

    public int countOwnedBy(UUID owner) {
        return database.withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT COUNT(*) FROM sc_claim WHERE owner_uuid = ?")) {
                ps.setString(1, owner.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        });
    }

    public int totalCount() {
        return database.withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT COUNT(*) FROM sc_claim");
                 ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        });
    }

    // --------------------------------------------------------------- mapping

    private static Claim mapBare(ResultSet rs) throws SQLException {
        return new Claim(
                rs.getString("id"),
                UUID.fromString(rs.getString("owner_uuid")),
                rs.getString("world"),
                rs.getInt("min_x"),
                rs.getInt("min_z"),
                rs.getInt("max_x"),
                rs.getInt("max_z"),
                rs.getString("name"),
                rs.getLong("created_at"),
                rs.getLong("paid"),
                Map.of(),
                Map.of());
    }

    private static Map<UUID, TrustLevel> loadMembers(Connection connection, String claimId)
            throws SQLException {
        Map<UUID, TrustLevel> members = new HashMap<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT player_uuid, trust FROM sc_claim_member WHERE claim_id = ?")) {
            ps.setString(1, claimId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    TrustLevel.byName(rs.getString("trust")).ifPresent(level ->
                            members.put(readUuid(rs), level));
                }
            }
        }
        return members;
    }

    private static Map<String, Map<UUID, TrustLevel>> loadAllMembers(Connection connection)
            throws SQLException {
        Map<String, Map<UUID, TrustLevel>> byClaim = new HashMap<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT claim_id, player_uuid, trust FROM sc_claim_member");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String claimId = rs.getString("claim_id");
                UUID player = readUuid(rs);
                TrustLevel.byName(rs.getString("trust")).ifPresent(level ->
                        byClaim.computeIfAbsent(claimId, ignored -> new HashMap<>())
                                .put(player, level));
            }
        }
        return byClaim;
    }

    private static Map<ClaimFlag, Boolean> loadFlags(Connection connection, String claimId)
            throws SQLException {
        Map<ClaimFlag, Boolean> flags = new EnumMap<>(ClaimFlag.class);
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT flag, allowed FROM sc_claim_flag WHERE claim_id = ?")) {
            ps.setString(1, claimId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    boolean allowed = rs.getInt("allowed") != 0;
                    ClaimFlag.byName(rs.getString("flag"))
                            .ifPresent(flag -> flags.put(flag, allowed));
                }
            }
        }
        return flags;
    }

    private static Map<String, Map<ClaimFlag, Boolean>> loadAllFlags(Connection connection)
            throws SQLException {
        Map<String, Map<ClaimFlag, Boolean>> byClaim = new HashMap<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT claim_id, flag, allowed FROM sc_claim_flag");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String claimId = rs.getString("claim_id");
                boolean allowed = rs.getInt("allowed") != 0;
                ClaimFlag.byName(rs.getString("flag")).ifPresent(flag ->
                        byClaim.computeIfAbsent(claimId, ignored -> new EnumMap<>(ClaimFlag.class))
                                .put(flag, allowed));
            }
        }
        return byClaim;
    }

    /** Reads the member UUID without letting a checked exception escape a lambda. */
    private static UUID readUuid(ResultSet rs) {
        try {
            return UUID.fromString(rs.getString("player_uuid"));
        } catch (SQLException e) {
            throw new com.streakysmp.data.DataAccessException("Could not read member uuid", e);
        }
    }
}
