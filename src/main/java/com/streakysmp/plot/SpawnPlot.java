package com.streakysmp.plot;

import java.time.Duration;
import java.util.UUID;

/**
 * A commercial plot in the spawn district.
 *
 * <p>Holds no Bukkit types, so the rent lifecycle -- the part with dates and
 * money in it -- is unit-testable without a server.
 *
 * <p>Bounds are inclusive and normalised, matching claims.
 *
 * @param rentDueAt    when the next payment falls due, or null when unowned
 * @param graceEndsAt  when the grace period expires, or null when rent is current
 * @param rentPaid     total rent collected over the plot's life, for reporting
 */
public record SpawnPlot(
        String id,
        String worldName,
        int minX,
        int minZ,
        int maxX,
        int maxZ,
        long purchasePrice,
        long rentPrice,
        RentPeriod rentPeriod,
        UUID owner,
        PlotStatus status,
        Long purchasedAt,
        Long rentDueAt,
        Long graceEndsAt,
        long rentPaid,
        long createdAt) {

    public SpawnPlot {
        if (minX > maxX || minZ > maxZ) {
            throw new IllegalArgumentException(
                    "Plot bounds must be normalised: x " + minX + ".." + maxX
                            + ", z " + minZ + ".." + maxZ);
        }
    }

    /** Builds an available plot from any two corners. */
    public static SpawnPlot available(String id, String worldName,
                                      int x1, int z1, int x2, int z2,
                                      long purchasePrice, long rentPrice, RentPeriod period) {
        return new SpawnPlot(id, worldName,
                Math.min(x1, x2), Math.min(z1, z2), Math.max(x1, x2), Math.max(z1, z2),
                purchasePrice, rentPrice, period,
                null, PlotStatus.AVAILABLE, null, null, null, 0L, System.currentTimeMillis());
    }

    public int width() {
        return maxX - minX + 1;
    }

    public int depth() {
        return maxZ - minZ + 1;
    }

    public long area() {
        return (long) width() * depth();
    }

    public boolean contains(String world, int x, int z) {
        return worldName.equals(world) && x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }

    public boolean overlaps(SpawnPlot other) {
        return worldName.equals(other.worldName)
                && minX <= other.maxX && maxX >= other.minX
                && minZ <= other.maxZ && maxZ >= other.minZ;
    }

    public boolean isOwner(UUID player) {
        return owner != null && owner.equals(player);
    }

    /** Whether rent is currently due or overdue. */
    public boolean rentDue(long now) {
        return rentDueAt != null && now >= rentDueAt;
    }

    /** Time until the next payment, or zero if it is already due. */
    public Duration untilRentDue(long now) {
        if (rentDueAt == null) {
            return Duration.ZERO;
        }
        return Duration.ofMillis(Math.max(0L, rentDueAt - now));
    }

    /** Time left in the grace period, or zero if there is none running. */
    public Duration graceRemaining(long now) {
        if (graceEndsAt == null) {
            return Duration.ZERO;
        }
        return Duration.ofMillis(Math.max(0L, graceEndsAt - now));
    }

    public boolean inGracePeriod() {
        return graceEndsAt != null && status == PlotStatus.RENT_OVERDUE;
    }

    public boolean graceExpired(long now) {
        return graceEndsAt != null && now >= graceEndsAt;
    }

    /** Centre of the plot, for teleporting a viewer to it. */
    public int centreX() {
        return minX + width() / 2;
    }

    public int centreZ() {
        return minZ + depth() / 2;
    }

    // --- Transitions --------------------------------------------------------

    /** Marks the plot sold, with the first rent falling due one period from now. */
    public SpawnPlot purchasedBy(UUID buyer, long now) {
        return new SpawnPlot(id, worldName, minX, minZ, maxX, maxZ,
                purchasePrice, rentPrice, rentPeriod, buyer, PlotStatus.OWNED,
                now, now + rentPeriod.duration().toMillis(), null, rentPaid, createdAt);
    }

    /**
     * Records a successful rent payment.
     *
     * <p>The next due date advances from the <em>previous</em> due date, not from
     * now. Advancing from now would quietly grant free time whenever a payment
     * landed late -- after a grace period, say, or after server downtime.
     */
    public SpawnPlot rentPaid(long amount, long now) {
        return new SpawnPlot(id, worldName, minX, minZ, maxX, maxZ,
                purchasePrice, rentPrice, rentPeriod, owner, PlotStatus.OWNED,
                purchasedAt, nextDueAfterPayment(now), null,
                rentPaid + amount, createdAt);
    }

    /**
     * When the next payment falls due after one has just been made.
     *
     * <p>Advances from the previous due date so a late payment grants no free
     * time, then skips forward in whole periods until the result is strictly in
     * the future. Without that second step a plot many periods overdue would land
     * its new due date on or before {@code now} and be charged again in the very
     * next sweep, draining the tenant who just paid.
     *
     * <p>Skipping in whole periods keeps rent falling on the same point in the
     * cycle rather than drifting to whenever the late payment happened to land.
     */
    private long nextDueAfterPayment(long now) {
        long period = rentPeriod.duration().toMillis();
        long next = (rentDueAt == null ? now : rentDueAt) + period;
        if (next <= now) {
            long periodsBehind = (now - next) / period + 1;
            next += periodsBehind * period;
        }
        return next;
    }

    /** Begins the grace period after a failed payment. */
    public SpawnPlot enterGrace(long graceEnds) {
        return new SpawnPlot(id, worldName, minX, minZ, maxX, maxZ,
                purchasePrice, rentPrice, rentPeriod, owner, PlotStatus.RENT_OVERDUE,
                purchasedAt, rentDueAt, graceEnds, rentPaid, createdAt);
    }

    /** Marks the plot expired once the grace period has run out. */
    public SpawnPlot expire() {
        return new SpawnPlot(id, worldName, minX, minZ, maxX, maxZ,
                purchasePrice, rentPrice, rentPeriod, owner, PlotStatus.EXPIRED,
                purchasedAt, rentDueAt, graceEndsAt, rentPaid, createdAt);
    }

    /** Returns the plot to the market, clearing all tenancy state. */
    public SpawnPlot release() {
        return new SpawnPlot(id, worldName, minX, minZ, maxX, maxZ,
                purchasePrice, rentPrice, rentPeriod, null, PlotStatus.AVAILABLE,
                null, null, null, rentPaid, createdAt);
    }

    public SpawnPlot withStatus(PlotStatus newStatus) {
        return new SpawnPlot(id, worldName, minX, minZ, maxX, maxZ,
                purchasePrice, rentPrice, rentPeriod, owner, newStatus,
                purchasedAt, rentDueAt, graceEndsAt, rentPaid, createdAt);
    }

    public SpawnPlot withPricing(long newPurchasePrice, long newRentPrice, RentPeriod newPeriod) {
        return new SpawnPlot(id, worldName, minX, minZ, maxX, maxZ,
                newPurchasePrice, newRentPrice, newPeriod, owner, status,
                purchasedAt, rentDueAt, graceEndsAt, rentPaid, createdAt);
    }

    public SpawnPlot withOwner(UUID newOwner) {
        return new SpawnPlot(id, worldName, minX, minZ, maxX, maxZ,
                purchasePrice, rentPrice, rentPeriod, newOwner, status,
                purchasedAt, rentDueAt, graceEndsAt, rentPaid, createdAt);
    }
}
