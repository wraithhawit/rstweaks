package com.wraithhawit.rstweaks.planner;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Direct tests for {@link Simplex}, driven by what a mutation run said the plan scenarios do not
 * reach. The scenarios exercise the happy path thoroughly; what survived mutation was almost
 * entirely the defensive half — the paths written to stop a malformed model from becoming a wrong
 * plan. Those can be deleted today and every existing test still passes.
 *
 * <p>Solves: minimise {@code c·x} subject to {@code A x >= b}, {@code x >= 0}.
 */
class SimplexTest {

    private static Rational[][] matrix(final long[][] rows) {
        final Rational[][] a = new Rational[rows.length][];
        for (int i = 0; i < rows.length; i++) {
            a[i] = vector(rows[i]);
        }
        return a;
    }

    private static Rational[] vector(final long[] values) {
        final Rational[] v = new Rational[values.length];
        for (int i = 0; i < values.length; i++) {
            v[i] = Rational.of(values[i]);
        }
        return v;
    }

    private static Rational objective(final Rational[] c, final Rational[] x) {
        Rational total = Rational.ZERO;
        for (int j = 0; j < c.length; j++) {
            total = total.add(c[j].multiply(x[j]));
        }
        return total;
    }

    /** No constraints at all. The all-zero answer was never returned by any scenario. */
    @Test
    void anEmptySystemIsTriviallySolved() {
        final Rational[] x = Simplex.solve(
            new Rational[0][], new Rational[0], vector(new long[]{1L, 1L, 1L}), 100);
        assertNotNull(x);
        assertEquals(3, x.length);
        for (final Rational component : x) {
            assertEquals(Rational.ZERO, component);
        }
    }

    @Test
    void minimisesASimpleSystem() {
        final Rational[] c = vector(new long[]{1L, 1L});
        final Rational[] x = Simplex.solve(
            matrix(new long[][]{{1L, 1L}}), vector(new long[]{3L}), c, 100);
        assertNotNull(x);
        assertEquals(Rational.of(3L), objective(c, x));
        assertTrue(x[0].add(x[1]).compareTo(Rational.of(3L)) >= 0, "constraint violated");
        assertTrue(x[0].signum() >= 0 && x[1].signum() >= 0, "negative component");
    }

    /** The reason this solver is exact rather than floating point. */
    @Test
    void findsAFractionalOptimum() {
        final Rational[] x = Simplex.solve(
            matrix(new long[][]{{2L}}), vector(new long[]{3L}), vector(new long[]{1L}), 100);
        assertNotNull(x);
        assertEquals(Rational.of(3L, 2L), x[0]);
    }

    /** Contradictory constraints must come back null, distinctly from the cap being hit. */
    @Test
    void contradictoryConstraintsAreInfeasible() {
        final Rational[] x = Simplex.solve(
            matrix(new long[][]{{1L}, {-1L}}), vector(new long[]{1L, 1L}),
            vector(new long[]{1L}), 100);
        assertNull(x);
    }

    /**
     * Degenerate, redundant constraints. Worth having, but it does NOT reach the block that drives
     * a still-basic artificial out of the basis -- a probe showed that block runs exactly once in
     * the whole planner suite, from a much larger scenario, and deleting its {@code pivot} call
     * still fails no test. Constructing a case where skipping it actually corrupts the plan is
     * open work; see the mutation notes rather than assuming this test covers it.
     */
    @Test
    void redundantConstraintsStillSolve() {
        final Rational[] c = vector(new long[]{1L, 1L});
        final Rational[] x = Simplex.solve(
            matrix(new long[][]{{1L, 1L}, {1L, 1L}}), vector(new long[]{2L, 2L}), c, 100);
        assertNotNull(x);
        assertEquals(Rational.of(2L), objective(c, x));
        assertTrue(x[0].add(x[1]).compareTo(Rational.of(2L)) >= 0, "constraint violated");
    }

    /**
     * Running out of pivot budget is not the same answer as infeasible. The class javadoc says
     * conflating the two is how "a solver that merely ran out of budget came to be reported to the
     * player as a craft that is impossible" — but nothing tested the distinction.
     */
    @Test
    void exhaustingThePivotCapThrowsRatherThanReportingInfeasible() {
        assertThrows(Simplex.PivotLimitExceeded.class, () -> Simplex.solve(
            matrix(new long[][]{{2L}}), vector(new long[]{3L}), vector(new long[]{1L}), 0));
    }

    /** A malformed, unbounded objective must be refused rather than guessed at. */
    @Test
    void anUnboundedObjectiveIsRefused() {
        assertThrows(Simplex.PivotLimitExceeded.class, () -> Simplex.solve(
            matrix(new long[][]{{1L}}), vector(new long[]{1L}), vector(new long[]{-1L}), 100));
    }
}
