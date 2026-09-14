package com.streakysmp.plot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rent lifecycle.
 *
 * <p>These are the rules a tenant will notice if they are wrong: being charged
 * twice, losing a shop without warning, or getting free weeks because the server
 * was offline. All of it is plain arithmetic over timestamps, so all of it is
 * tested here.
 */
class SpawnPlotTest {

    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();
    private static final long WEEK = Duration.ofDays(7).toMillis();
    private static final long HOUR = Duration.ofHours(1).toMillis();

    private static SpawnPlot plot() {
        return SpawnPlot.available("p1", "world", 0, 0, 9, 9,
                25_000_00L, 5_000_00L, RentPeriod.WEEKLY);
    }

    @Nested
    @DisplayName("geometry and pricing")
    class Basics {

        @Test
        void boundsAreInclusiveAndNormalised() {
            SpawnPlot reversed = SpawnPlot.available("p", "world", 9, 9, 0, 0,
                    1L, 1L, RentPeriod.WEEKLY);
            assertEquals(0, reversed.minX());
            assertEquals(9, reversed.maxX());
            assertEquals(10, reversed.width());
            assertEquals(100L, reversed.area());
        }

        @Test
        void containmentAndOverlap() {
            SpawnPlot a = plot();
            assertTrue(a.contains("world", 0, 0));
            assertTrue(a.contains("world", 9, 9));
            assertFalse(a.contains("world", 10, 9));
            assertFalse(a.contains("nether", 5, 5));

            SpawnPlot adjacent = SpawnPlot.available("p2", "world", 10, 0, 19, 9,
                    1L, 1L, RentPeriod.WEEKLY);
            assertFalse(a.overlaps(adjacent), "touching plots do not overlap");

            SpawnPlot overlapping = SpawnPlot.available("p3", "world", 9, 9, 19, 19,
                    1L, 1L, RentPeriod.WEEKLY);
            assertTrue(a.overlaps(overlapping));
        }

        @Test
        void rentPeriodsHaveSensibleDurations() {
            assertEquals(Duration.ofDays(1), RentPeriod.DAILY.duration());
            assertEquals(Duration.ofDays(7), RentPeriod.WEEKLY.duration());
            assertEquals(Duration.ofDays(30), RentPeriod.MONTHLY.duration(),
                    "a month is 30 days so rent falls on a predictable schedule");
        }
    }

    @Nested
    @DisplayName("purchase")
    class Purchase {

        @Test
        @DisplayName("buying sets the first rent one period out, not immediately")
        void firstRentIsOnePeriodAway() {
            long now = 1_000_000L;
            SpawnPlot owned = plot().purchasedBy(ALICE, now);

            assertEquals(PlotStatus.OWNED, owned.status());
            assertTrue(owned.isOwner(ALICE));
            assertFalse(owned.isOwner(BOB));
            assertEquals(now + WEEK, owned.rentDueAt(),
                    "charging rent the instant someone buys would be a nasty surprise");
            assertFalse(owned.rentDue(now));
            assertNull(owned.graceEndsAt());
        }

        @Test
        void onlyAvailablePlotsArePurchasable() {
            assertTrue(PlotStatus.AVAILABLE.purchasable());
            assertFalse(PlotStatus.OWNED.purchasable());
            assertFalse(PlotStatus.RENT_OVERDUE.purchasable());
            assertFalse(PlotStatus.EXPIRED.purchasable(),
                    "an expired plot still belongs to its owner until it is released");
            assertFalse(PlotStatus.DISABLED.purchasable());
        }
    }

    @Nested
    @DisplayName("rent falling due")
    class RentDue {

        @Test
        void rentBecomesDueAfterThePeriod() {
            long now = 1_000_000L;
            SpawnPlot owned = plot().purchasedBy(ALICE, now);

            assertFalse(owned.rentDue(now + WEEK - 1));
            assertTrue(owned.rentDue(now + WEEK));
            assertEquals(Duration.ZERO, owned.untilRentDue(now + WEEK));
            assertEquals(Duration.ofMillis(WEEK), owned.untilRentDue(now));
        }

        /**
         * The downtime rule. A payment landing late must not push the next one a
         * full period past the late date, or a server offline for three days
         * hands every tenant three free days -- repeatedly.
         */
        @Test
        @DisplayName("a late payment does not grant free time")
        void latePaymentDoesNotGrantFreeTime() {
            long purchased = 1_000_000L;
            SpawnPlot owned = plot().purchasedBy(ALICE, purchased);
            long firstDue = owned.rentDueAt();

            // Paid two days late, e.g. the server was down.
            long paidAt = firstDue + Duration.ofDays(2).toMillis();
            SpawnPlot afterPayment = owned.rentPaid(5_000_00L, paidAt);

            assertEquals(firstDue + WEEK, afterPayment.rentDueAt(),
                    "the next due date advances from the old due date, not from now");
            assertEquals(PlotStatus.OWNED, afterPayment.status());
            assertNull(afterPayment.graceEndsAt(), "paying clears the grace period");
            assertEquals(5_000_00L, afterPayment.rentPaid());
        }

        @Test
        @DisplayName("rent paid accumulates over the plot's life")
        void rentPaidAccumulates() {
            long now = 1_000_000L;
            SpawnPlot owned = plot().purchasedBy(ALICE, now);

            SpawnPlot after = owned
                    .rentPaid(5_000_00L, now + WEEK)
                    .rentPaid(5_000_00L, now + 2 * WEEK);

            assertEquals(10_000_00L, after.rentPaid());
        }

        /**
         * Guards against the opposite mistake: if a plot has been unpaid for many
         * periods, a single payment must not leave the due date still in the past
         * and immediately trigger another charge in the same sweep.
         */
        @Test
        @DisplayName("paying after a very long lapse still moves the due date into the future")
        void payingAfterLongLapseMovesDueDateForward() {
            long purchased = 1_000_000L;
            SpawnPlot owned = plot().purchasedBy(ALICE, purchased);

            // Ten weeks late.
            long veryLate = owned.rentDueAt() + 10 * WEEK;
            SpawnPlot after = owned.rentPaid(5_000_00L, veryLate);

            assertTrue(after.rentDueAt() > veryLate,
                    "otherwise the next sweep charges again immediately, draining the tenant");
        }
    }

    @Nested
    @DisplayName("grace period")
    class Grace {

        @Test
        @DisplayName("a failed payment starts a grace period and keeps the shop open")
        void failedPaymentStartsGrace() {
            long now = 1_000_000L;
            SpawnPlot owned = plot().purchasedBy(ALICE, now);
            long graceEnds = now + WEEK + 48 * HOUR;

            SpawnPlot overdue = owned.enterGrace(graceEnds);

            assertEquals(PlotStatus.RENT_OVERDUE, overdue.status());
            assertTrue(overdue.inGracePeriod());
            assertTrue(overdue.status().shopsMayTrade(),
                    "the spec is explicit that a shop must not close the moment a payment fails");
            assertEquals(graceEnds, overdue.graceEndsAt());
            assertTrue(overdue.isOwner(ALICE), "the plot is still theirs");
        }

        @Test
        void graceRemainingCountsDown() {
            long now = 1_000_000L;
            SpawnPlot overdue = plot().purchasedBy(ALICE, now).enterGrace(now + 48 * HOUR);

            assertEquals(Duration.ofHours(48), overdue.graceRemaining(now));
            assertEquals(Duration.ofHours(24), overdue.graceRemaining(now + 24 * HOUR));
            assertEquals(Duration.ZERO, overdue.graceRemaining(now + 72 * HOUR),
                    "never reports negative time remaining");
            assertFalse(overdue.graceExpired(now + 47 * HOUR));
            assertTrue(overdue.graceExpired(now + 48 * HOUR));
        }

        @Test
        @DisplayName("paying during the grace period restores normal state")
        void payingDuringGraceRecovers() {
            long now = 1_000_000L;
            SpawnPlot overdue = plot().purchasedBy(ALICE, now).enterGrace(now + WEEK + 48 * HOUR);

            SpawnPlot recovered = overdue.rentPaid(5_000_00L, now + WEEK + HOUR);

            assertEquals(PlotStatus.OWNED, recovered.status());
            assertNull(recovered.graceEndsAt());
            assertFalse(recovered.inGracePeriod());
            assertTrue(recovered.status().shopsMayTrade());
        }
    }

    @Nested
    @DisplayName("expiry and release")
    class Expiry {

        @Test
        @DisplayName("expiring closes shops but keeps the plot with its owner")
        void expiringKeepsOwnership() {
            long now = 1_000_000L;
            SpawnPlot expired = plot().purchasedBy(ALICE, now)
                    .enterGrace(now + WEEK + 48 * HOUR)
                    .expire();

            assertEquals(PlotStatus.EXPIRED, expired.status());
            assertFalse(expired.status().shopsMayTrade(), "trading stops");
            assertTrue(expired.isOwner(ALICE),
                    "the owner keeps the plot for now, so they can still pay and recover it");
            assertTrue(expired.status().rentApplies(),
                    "rent still applies, so paying up is possible");
        }

        @Test
        @DisplayName("releasing clears every trace of the tenancy")
        void releaseClearsTenancy() {
            long now = 1_000_000L;
            SpawnPlot released = plot().purchasedBy(ALICE, now)
                    .enterGrace(now + WEEK)
                    .expire()
                    .release();

            assertEquals(PlotStatus.AVAILABLE, released.status());
            assertNull(released.owner());
            assertNull(released.rentDueAt());
            assertNull(released.graceEndsAt());
            assertNull(released.purchasedAt());
            assertTrue(released.status().purchasable());
        }

        @Test
        @DisplayName("lifetime rent collected survives a release, for reporting")
        void rentHistorySurvivesRelease() {
            long now = 1_000_000L;
            SpawnPlot released = plot().purchasedBy(ALICE, now)
                    .rentPaid(5_000_00L, now + WEEK)
                    .release();

            assertEquals(5_000_00L, released.rentPaid(),
                    "an operator should still be able to see what a plot has earned");
        }

        @Test
        @DisplayName("the full unpaid path runs available to available")
        void fullLifecycle() {
            long now = 1_000_000L;

            SpawnPlot p = plot();
            assertEquals(PlotStatus.AVAILABLE, p.status());

            p = p.purchasedBy(ALICE, now);
            assertEquals(PlotStatus.OWNED, p.status());

            p = p.enterGrace(now + WEEK + 48 * HOUR);
            assertEquals(PlotStatus.RENT_OVERDUE, p.status());
            assertTrue(p.status().shopsMayTrade());

            p = p.expire();
            assertEquals(PlotStatus.EXPIRED, p.status());
            assertFalse(p.status().shopsMayTrade());

            p = p.release();
            assertEquals(PlotStatus.AVAILABLE, p.status());
            assertNull(p.owner());
        }
    }

    @Nested
    @DisplayName("administrative states")
    class Admin {

        @Test
        void disabledPlotsAreNeitherOwnedNorSold() {
            SpawnPlot disabled = plot().withStatus(PlotStatus.DISABLED);
            assertFalse(disabled.status().purchasable());
            assertFalse(disabled.status().hasOwner());
            assertFalse(disabled.status().rentApplies(),
                    "a withdrawn plot must not keep billing anyone");
        }

        @Test
        void pricingCanBeChangedWithoutDisturbingTenancy() {
            long now = 1_000_000L;
            SpawnPlot owned = plot().purchasedBy(ALICE, now);
            SpawnPlot repriced = owned.withPricing(30_000_00L, 6_000_00L, RentPeriod.MONTHLY);

            assertEquals(30_000_00L, repriced.purchasePrice());
            assertEquals(RentPeriod.MONTHLY, repriced.rentPeriod());
            assertTrue(repriced.isOwner(ALICE));
            assertEquals(owned.rentDueAt(), repriced.rentDueAt(),
                    "changing the price must not reset when rent is due");
        }
    }
}
