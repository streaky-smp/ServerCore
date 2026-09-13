package com.servercore.claim;

import com.servercore.config.ConfigManager;
import com.servercore.data.Database;
import com.servercore.economy.EconomyService;
import com.servercore.economy.TransactionType;
import com.servercore.log.AuditAction;
import com.servercore.log.AuditEntry;
import com.servercore.log.AuditLog;
import com.servercore.util.Text;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Default {@link ClaimService}.
 *
 * <p>All state lives in {@link ClaimIndex}, which is loaded once at startup and
 * kept in step with the database. Protection checks read only from the index, so
 * a block break costs a map lookup rather than a query.
 *
 * <p>Money and land move together. Creating or expanding a claim charges through
 * {@link EconomyService#debitWithin} inside the same transaction that writes the
 * claim row, so there is no window in which one exists without the other.
 */
public final class StandardClaimService implements ClaimService, ConfigManager.Reloadable {

    /** Claim names are shown in GUIs and visualisations, so keep them sane. */
    private static final int MAX_NAME_LENGTH = 32;

    private final Database database;
    private final ClaimRepository repository;
    private final ClaimIndex index;
    private final EconomyService economy;
    private final AuditLog auditLog;
    private final Logger logger;

    private volatile ClaimSettings settings = null;

    public StandardClaimService(Database database,
                                ClaimRepository repository,
                                ClaimIndex index,
                                EconomyService economy,
                                AuditLog auditLog,
                                Logger logger) {
        this.database = database;
        this.repository = repository;
        this.index = index;
        this.economy = economy;
        this.auditLog = auditLog;
        this.logger = logger;
    }

    @Override
    public void load(ConfigManager.ConfigBundle configs) {
        int fractionDigits = configs.main()
                .section("economy").section("currency")
                .getInt("fraction-digits", 2, 0, 4);
        this.settings = ClaimSettings.from(configs.main().section("claims"), fractionDigits);
    }

    @Override
    public ClaimSettings settings() {
        ClaimSettings snapshot = settings;
        if (snapshot == null) {
            throw new IllegalStateException("Claim settings accessed before configuration was loaded");
        }
        return snapshot;
    }

    /**
     * Loads every claim into the index.
     *
     * <p>Called from the plugin after migration, on a worker thread. Until this
     * completes the index is empty, which means claims are unprotected -- so it
     * runs before the server finishes starting.
     */
    public void loadIndex() {
        List<Claim> claims = repository.loadAll();
        index.clear();
        index.putAll(claims);
        logger.info("Loaded " + claims.size() + " claim(s) across "
                + index.indexedChunkCount() + " indexed chunk(s).");
    }

    // ------------------------------------------------------------- queries

    @Override
    public Optional<Claim> claimAt(String worldName, int x, int z) {
        return index.claimAt(worldName, x, z);
    }

    @Override
    public Optional<Claim> byId(String claimId) {
        return index.byId(claimId);
    }

    @Override
    public List<Claim> ownedBy(UUID owner) {
        return index.ownedBy(owner);
    }

    @Override
    public List<Claim> memberOf(UUID player) {
        return index.memberOf(player);
    }

    @Override
    public List<Claim> near(String worldName, int x, int z, int radius) {
        return index.near(worldName, x, z, radius);
    }

    @Override
    public boolean allows(UUID player, String worldName, int x, int z, ClaimFlag flag) {
        Claim claim = index.claimAt(worldName, x, z).orElse(null);
        // Unclaimed land is unprotected. Anything else would turn a claim plugin
        // into a whitelist for the entire world.
        return claim == null || claim.allows(player, flag);
    }

    @Override
    public int claimCount() {
        return index.size();
    }

    // ------------------------------------------------------------ mutations

    @Override
    public ClaimResult create(UUID owner, String worldName,
                              int x1, int z1, int x2, int z2, String name) {
        ClaimSettings config = settings();
        if (!config.enabled() || !config.allowsWorld(worldName)) {
            return ClaimResult.failure(ClaimResult.Outcome.DISABLED);
        }

        String cleanName = sanitiseName(name);
        if (cleanName == null) {
            return ClaimResult.failure(ClaimResult.Outcome.INVALID_NAME);
        }

        String id = UUID.randomUUID().toString().substring(0, 8);
        Claim candidate = Claim.of(id, owner, worldName, x1, z1, x2, z2, cleanName,
                System.currentTimeMillis(), 0L);

        ClaimResult.Outcome sizeProblem = checkSize(candidate, config);
        if (sizeProblem != null) {
            return ClaimResult.failure(sizeProblem);
        }
        if (config.hasClaimLimit() && index.ownedBy(owner).size() >= config.maxClaimsPerPlayer()) {
            return ClaimResult.failure(ClaimResult.Outcome.TOO_MANY_CLAIMS);
        }
        if (index.wouldOverlap(candidate, null)) {
            return ClaimResult.failure(ClaimResult.Outcome.OVERLAPS);
        }

        long cost;
        try {
            cost = config.priceForNew(candidate.area());
        } catch (ArithmeticException e) {
            return ClaimResult.failure(ClaimResult.Outcome.TOO_LARGE);
        }

        Claim paid = candidate.withBounds(x1, z1, x2, z2, cost);
        ClaimResult result = chargeAndSave(owner, paid, cost,
                TransactionType.CLAIM_PURCHASE, "Claim " + id + " (" + paid.area() + " blocks)",
                null, config.hasClaimLimit() ? config.maxClaimsPerPlayer() : null);

        if (result.isSuccess()) {
            index.put(paid);
            auditLog.record(AuditEntry.builder(AuditAction.CLAIM_CREATED)
                    .actor(owner, null)
                    .amount(cost)
                    .object(id)
                    .details(paid.worldName() + " " + paid.minX() + "," + paid.minZ()
                            + " to " + paid.maxX() + "," + paid.maxZ())
                    .build());
        }
        return result;
    }

    @Override
    public ClaimResult resize(UUID actor, String claimId, int x1, int z1, int x2, int z2) {
        Claim existing = index.byId(claimId).orElse(null);
        if (existing == null) {
            return ClaimResult.failure(ClaimResult.Outcome.NOT_FOUND);
        }
        // Resizing is an owner-only action: a manager who can reshape the claim
        // could annex land the owner never agreed to pay for.
        if (!existing.isOwner(actor)) {
            return ClaimResult.failure(ClaimResult.Outcome.NOT_PERMITTED);
        }

        ClaimSettings config = settings();
        Claim candidate = existing.withBounds(x1, z1, x2, z2, existing.paid());
        if (candidate.minX() == existing.minX() && candidate.maxX() == existing.maxX()
                && candidate.minZ() == existing.minZ() && candidate.maxZ() == existing.maxZ()) {
            return ClaimResult.failure(ClaimResult.Outcome.NO_CHANGE);
        }

        ClaimResult.Outcome sizeProblem = checkSize(candidate, config);
        if (sizeProblem != null) {
            return ClaimResult.failure(sizeProblem);
        }
        if (index.wouldOverlap(candidate, claimId)) {
            return ClaimResult.failure(ClaimResult.Outcome.OVERLAPS);
        }

        long added = candidate.area() - existing.area();
        if (added <= 0) {
            // Shrinking is free and refunds nothing. Refunding per block would let
            // a player farm the difference between purchase and expansion prices.
            Claim shrunk = candidate;
            try {
                repository.save(shrunk);
            } catch (Exception e) {
                logger.log(Level.SEVERE, "Could not shrink claim " + claimId, e);
                return ClaimResult.failure(ClaimResult.Outcome.FAILED);
            }
            index.put(shrunk);
            auditClaimChange(actor, shrunk, "shrunk to " + shrunk.area() + " blocks", 0L);
            return ClaimResult.success(shrunk, 0L);
        }

        long cost;
        try {
            cost = config.priceForExpansion(added);
        } catch (ArithmeticException e) {
            return ClaimResult.failure(ClaimResult.Outcome.TOO_LARGE);
        }

        Claim expanded = candidate.withBounds(x1, z1, x2, z2, existing.paid() + cost);
        ClaimResult result = chargeAndSave(actor, expanded, cost,
                TransactionType.CLAIM_EXPANSION,
                "Expanded claim " + claimId + " by " + added + " blocks",
                claimId, null);

        if (result.isSuccess()) {
            index.put(expanded);
            auditLog.record(AuditEntry.builder(AuditAction.CLAIM_EXPANDED)
                    .actor(actor, null)
                    .amount(cost)
                    .object(claimId)
                    .details("+" + added + " blocks")
                    .build());
        }
        return result;
    }

    @Override
    public ClaimResult delete(UUID actor, String claimId) {
        Claim existing = index.byId(claimId).orElse(null);
        if (existing == null) {
            return ClaimResult.failure(ClaimResult.Outcome.NOT_FOUND);
        }
        if (!existing.isOwner(actor)) {
            return ClaimResult.failure(ClaimResult.Outcome.NOT_PERMITTED);
        }

        long refund = settings().refundFor(existing.paid());
        try {
            database.inTransaction(connection -> {
                if (refund > 0) {
                    economy.creditWithin(connection, existing.owner(), refund,
                            TransactionType.CLAIM_PURCHASE, "Refund for deleted claim " + claimId);
                }
                try (var ps = connection.prepareStatement("DELETE FROM sc_claim WHERE id = ?")) {
                    ps.setString(1, claimId);
                    ps.executeUpdate();
                }
                return null;
            });
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Could not delete claim " + claimId, e);
            return ClaimResult.failure(ClaimResult.Outcome.FAILED);
        }

        index.remove(claimId);
        auditLog.record(AuditEntry.builder(AuditAction.CLAIM_DELETED)
                .actor(actor, null)
                .amount(refund)
                .object(claimId)
                .details("refunded " + refund)
                .build());
        return ClaimResult.success(existing, refund);
    }

    @Override
    public ClaimResult rename(UUID actor, String claimId, String newName) {
        Claim existing = index.byId(claimId).orElse(null);
        if (existing == null) {
            return ClaimResult.failure(ClaimResult.Outcome.NOT_FOUND);
        }
        if (!existing.isOwner(actor)) {
            return ClaimResult.failure(ClaimResult.Outcome.NOT_PERMITTED);
        }
        String cleanName = sanitiseName(newName);
        if (cleanName == null) {
            return ClaimResult.failure(ClaimResult.Outcome.INVALID_NAME);
        }
        return persist(existing.withName(cleanName), actor, "renamed to " + cleanName);
    }

    @Override
    public ClaimResult transfer(UUID actor, String claimId, UUID newOwner) {
        Claim existing = index.byId(claimId).orElse(null);
        if (existing == null) {
            return ClaimResult.failure(ClaimResult.Outcome.NOT_FOUND);
        }
        if (!existing.isOwner(actor)) {
            return ClaimResult.failure(ClaimResult.Outcome.NOT_PERMITTED);
        }
        if (existing.isOwner(newOwner)) {
            return ClaimResult.failure(ClaimResult.Outcome.NO_CHANGE);
        }
        ClaimSettings config = settings();
        if (config.hasClaimLimit() && index.ownedBy(newOwner).size() >= config.maxClaimsPerPlayer()) {
            return ClaimResult.failure(ClaimResult.Outcome.TOO_MANY_CLAIMS);
        }

        // The new owner should not also appear as a member of their own claim.
        Map<UUID, TrustLevel> members = new HashMap<>(existing.members());
        members.remove(newOwner);

        Claim transferred = existing.withOwner(newOwner).withMembers(members);
        ClaimResult result = persist(transferred, actor, "transferred to " + newOwner);
        if (result.isSuccess()) {
            auditLog.record(AuditEntry.builder(AuditAction.CLAIM_TRANSFERRED)
                    .actor(actor, null)
                    .target(newOwner, null)
                    .object(claimId)
                    .build());
        }
        return result;
    }

    @Override
    public ClaimResult setMember(UUID actor, String claimId, UUID member, TrustLevel trust) {
        Claim existing = index.byId(claimId).orElse(null);
        if (existing == null) {
            return ClaimResult.failure(ClaimResult.Outcome.NOT_FOUND);
        }
        if (!canManage(existing, actor)) {
            return ClaimResult.failure(ClaimResult.Outcome.NOT_PERMITTED);
        }
        if (existing.isOwner(member)) {
            return ClaimResult.failure(ClaimResult.Outcome.NO_CHANGE);
        }
        // A manager must not be able to promote someone to their own level and
        // create a second manager the owner never sanctioned.
        if (!existing.isOwner(actor) && trust.atLeast(TrustLevel.MANAGE)) {
            return ClaimResult.failure(ClaimResult.Outcome.NOT_PERMITTED);
        }

        Map<UUID, TrustLevel> members = new HashMap<>(existing.members());
        members.put(member, trust);
        return persist(existing.withMembers(members), actor,
                "set " + member + " to " + trust.name());
    }

    @Override
    public ClaimResult removeMember(UUID actor, String claimId, UUID member) {
        Claim existing = index.byId(claimId).orElse(null);
        if (existing == null) {
            return ClaimResult.failure(ClaimResult.Outcome.NOT_FOUND);
        }
        if (!canManage(existing, actor)) {
            return ClaimResult.failure(ClaimResult.Outcome.NOT_PERMITTED);
        }
        if (!existing.members().containsKey(member)) {
            return ClaimResult.failure(ClaimResult.Outcome.NO_CHANGE);
        }
        Map<UUID, TrustLevel> members = new HashMap<>(existing.members());
        members.remove(member);
        return persist(existing.withMembers(members), actor, "removed " + member);
    }

    @Override
    public ClaimResult setFlag(UUID actor, String claimId, ClaimFlag flag, Boolean publicAccess) {
        Claim existing = index.byId(claimId).orElse(null);
        if (existing == null) {
            return ClaimResult.failure(ClaimResult.Outcome.NOT_FOUND);
        }
        if (!existing.isOwner(actor)) {
            return ClaimResult.failure(ClaimResult.Outcome.NOT_PERMITTED);
        }
        // EnumMap's copy constructor throws on an empty map -- it cannot infer
        // the enum type -- so build it from the class and copy explicitly.
        Map<ClaimFlag, Boolean> flags = new java.util.EnumMap<>(ClaimFlag.class);
        flags.putAll(existing.publicFlags());
        if (publicAccess == null) {
            flags.remove(flag);
        } else {
            flags.put(flag, publicAccess);
        }
        return persist(existing.withPublicFlags(flags), actor,
                "flag " + flag.name() + " = " + publicAccess);
    }

    // ------------------------------------------------------------- internals

    /** Owner, or a member trusted to MANAGE. */
    private static boolean canManage(Claim claim, UUID actor) {
        if (claim.isOwner(actor)) {
            return true;
        }
        TrustLevel trust = claim.trustOf(actor);
        return trust != null && trust.atLeast(TrustLevel.MANAGE);
    }

    /**
     * Charges the player and writes the claim in one transaction.
     *
     * <p>The debit is a conditional update inside the transaction, so a refused
     * charge rolls back the claim write with it.
     */
    private ClaimResult chargeAndSave(UUID payer, Claim claim, long cost,
                                      TransactionType type, String description,
                                      String ignoreIdForOverlap, Integer claimLimit) {
        try {
            return database.inTransaction(connection -> {
                // Re-check inside the transaction. The index check before this
                // point is advisory: two players claiming the same land in the
                // same instant both pass it, and only a test performed inside the
                // serialised write transaction can actually exclude the second.
                if (ClaimRepository.overlapsWithin(connection, claim, ignoreIdForOverlap)) {
                    return ClaimResult.failure(ClaimResult.Outcome.OVERLAPS);
                }
                if (claimLimit != null
                        && ClaimRepository.countOwnedWithin(connection, payer) >= claimLimit) {
                    return ClaimResult.failure(ClaimResult.Outcome.TOO_MANY_CLAIMS);
                }
                if (cost > 0 && !economy.debitWithin(connection, payer, cost, type, description)) {
                    long balance = com.servercore.economy.AccountRepository.balance(connection, payer);
                    return ClaimResult.insufficientFunds(cost, Math.max(0L, cost - balance));
                }
                repository.saveWithin(connection, claim);
                return ClaimResult.success(claim, cost);
            });
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Claim purchase failed and was rolled back", e);
            return ClaimResult.failure(ClaimResult.Outcome.FAILED);
        }
    }

    /** Saves a metadata-only change (no money involved). */
    private ClaimResult persist(Claim claim, UUID actor, String what) {
        try {
            repository.save(claim);
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Could not save claim " + claim.id(), e);
            return ClaimResult.failure(ClaimResult.Outcome.FAILED);
        }
        index.put(claim);
        auditClaimChange(actor, claim, what, 0L);
        return ClaimResult.success(claim, 0L);
    }

    private void auditClaimChange(UUID actor, Claim claim, String what, long amount) {
        AuditEntry.Builder builder = AuditEntry.builder(AuditAction.ADMIN_ACTION)
                .actor(actor, null)
                .object(claim.id())
                .details(what);
        if (amount != 0L) {
            builder.amount(amount);
        }
        auditLog.record(builder.build());
    }

    private ClaimResult.Outcome checkSize(Claim candidate, ClaimSettings config) {
        if (candidate.width() < config.minSideLength() || candidate.depth() < config.minSideLength()) {
            return ClaimResult.Outcome.TOO_SMALL;
        }
        if (candidate.width() > config.maxSideLength() || candidate.depth() > config.maxSideLength()) {
            return ClaimResult.Outcome.TOO_LARGE;
        }
        if (candidate.area() > config.maxArea()) {
            return ClaimResult.Outcome.TOO_LARGE;
        }
        return null;
    }

    /**
     * Trims and escapes a player-supplied claim name.
     *
     * @return the cleaned name, or null if it is unusable
     */
    private static String sanitiseName(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_NAME_LENGTH) {
            return null;
        }
        // Escaped at the boundary: the name is rendered inside MiniMessage
        // templates in GUIs and visualisations.
        return Text.escape(trimmed);
    }

    public ClaimIndex index() {
        return index;
    }

    public ClaimRepository repository() {
        return repository;
    }

    /** Lowercased world set, exposed for the diagnostics screen. */
    public String enabledWorldsDescription() {
        ClaimSettings config = settings();
        return config.enabledWorlds().isEmpty()
                ? "all worlds"
                : String.join(", ", config.enabledWorlds()).toLowerCase(Locale.ROOT);
    }
}
