package com.wraithhawit.rstweaks.planner;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Direct tests for {@link Rational}, which had none.
 *
 * <p>It was covered only indirectly, through whole-plan scenarios in
 * {@code PlannerExecutabilitySelfTest}. A mutation run put a number on what that was worth: 53%
 * of mutants killed, the weakest class in the planner. The pattern behind the survivors was that
 * the plan scenarios overwhelmingly work in whole numbers, so the two things this class exists to
 * get right — exact fractions across simplex pivots, and a canonical sign — were barely exercised.
 *
 * <p>Two survivors are left deliberately and are equivalent mutants, not gaps:
 * {@code this.den / g} in {@code add} may be replaced with {@code this.den * g} without changing
 * any result (it scales the lcm and both numerators by the same {@code g}, which {@code of}
 * reduces back out), and the {@code <= 0} in {@code fractionalDistance} only differs from
 * {@code < 0} at exactly one half, where both branches return one half.
 */
class RationalTest {

    // ---- sign normalisation -------------------------------------------------------------
    // Nothing in the suite ever built a Rational with a negative denominator, so this whole
    // branch was unpinned -- including both negations, which equals() and signum() rely on.

    @Test
    void negativeDenominatorMovesToTheNumerator() {
        assertEquals(-1, Rational.of(1L, -2L).signum());
        assertEquals(Rational.of(-1L, 2L), Rational.of(1L, -2L));
        assertEquals("-1/2", Rational.of(1L, -2L).toString());
    }

    @Test
    void twoNegativesMakeAPositive() {
        assertEquals(1, Rational.of(-4L, -6L).signum());
        assertEquals(Rational.of(2L, 3L), Rational.of(-4L, -6L));
    }

    @Test
    void alwaysInLowestTerms() {
        assertEquals(Rational.of(1L, 2L), Rational.of(2L, 4L));
        assertEquals(Rational.of(-1L, 2L), Rational.of(3L, -6L));
        assertEquals(Rational.of(1L, 2L).hashCode(), Rational.of(50L, 100L).hashCode());
        assertNotEquals(Rational.of(1L, 2L), Rational.of(1L, 3L));
        // Not a style assertion: the planner keys collections on Rational, and a hashCode
        // mutated to a constant still passes every equality test while turning every such
        // map into a linked list. Distinct values must hash distinctly.
        assertNotEquals(Rational.of(1L, 2L).hashCode(), Rational.of(1L, 3L).hashCode());
        assertNotEquals(Rational.of(1L, 2L).hashCode(), Rational.of(2L, 1L).hashCode());
    }

    @Test
    void signNormalisationRefusesToOverflow() {
        assertThrows(ArithmeticException.class, () -> Rational.of(Long.MIN_VALUE, -1L));
        assertThrows(ArithmeticException.class, () -> Rational.of(1L, Long.MIN_VALUE));
    }

    @Test
    void zeroDenominatorIsRejected() {
        assertThrows(ArithmeticException.class, () -> Rational.of(1L, 0L));
    }

    // ---- arithmetic on actual fractions -------------------------------------------------

    @Test
    void addsUnlikeDenominators() {
        assertEquals(Rational.of(5L, 6L), Rational.of(1L, 2L).add(Rational.of(1L, 3L)));
        assertEquals(Rational.of(5L, 12L), Rational.of(1L, 4L).add(Rational.of(1L, 6L)));
        assertEquals(Rational.ZERO, Rational.of(1L, 3L).add(Rational.of(-1L, 3L)));
    }

    @Test
    void subtractsAndMultipliesAndDivides() {
        assertEquals(Rational.of(1L, 6L), Rational.of(1L, 2L).subtract(Rational.of(1L, 3L)));
        assertEquals(Rational.of(1L, 6L), Rational.of(1L, 2L).multiply(Rational.of(1L, 3L)));
        assertEquals(Rational.of(3L, 2L), Rational.of(1L, 2L).divide(Rational.of(1L, 3L)));
        assertEquals(Rational.of(-3L, 2L), Rational.of(-2L, 3L).reciprocal());
    }

    @Test
    void divisionByZeroIsRejected() {
        assertThrows(ArithmeticException.class, () -> Rational.of(1L, 2L).divide(Rational.ZERO));
    }

    /** The documented contract: overflow throws rather than wrapping into a wrong plan. */
    @Test
    void overflowThrowsRatherThanWrapping() {
        assertThrows(ArithmeticException.class,
            () -> Rational.of(Long.MAX_VALUE).add(Rational.ONE));
        assertThrows(ArithmeticException.class,
            () -> Rational.of(Long.MIN_VALUE).negate());
        assertThrows(ArithmeticException.class,
            () -> Rational.of(Long.MAX_VALUE).multiply(Rational.of(2L)));
    }

    // ---- rounding and the branching heuristic -------------------------------------------

    @Test
    void floorRoundsTowardsNegativeInfinity() {
        assertEquals(1L, Rational.of(3L, 2L).floor());
        assertEquals(-2L, Rational.of(-3L, 2L).floor());
        assertEquals(4L, Rational.of(4L).floor());
        assertEquals(-4L, Rational.of(-4L).floor());
    }

    /**
     * The branch-and-bound "most fractional variable" heuristic. Both arms survived mutation:
     * negating the comparison changed no plan any scenario asserted, so which variable the solver
     * branches on next was not pinned by anything.
     */
    @Test
    void fractionalDistanceIsMeasuredToTheNearestInteger() {
        assertEquals(Rational.of(1L, 3L), Rational.of(7L, 3L).fractionalDistance());
        assertEquals(Rational.of(1L, 3L), Rational.of(5L, 3L).fractionalDistance());
        assertEquals(Rational.of(1L, 2L), Rational.of(3L, 2L).fractionalDistance());
        assertEquals(Rational.ZERO, Rational.of(4L).fractionalDistance());
    }

    @Test
    void integralAndZeroAndOrdering() {
        assertTrue(Rational.of(4L).isIntegral());
        assertFalse(Rational.of(1L, 2L).isIntegral());
        assertTrue(Rational.ZERO.isZero());
        assertFalse(Rational.ONE.isZero());
        assertTrue(Rational.of(1L, 3L).compareTo(Rational.of(1L, 2L)) < 0);
        assertTrue(Rational.of(1L, 2L).compareTo(Rational.of(1L, 3L)) > 0);
        assertEquals(0, Rational.of(2L, 4L).compareTo(Rational.of(1L, 2L)));
    }
}
