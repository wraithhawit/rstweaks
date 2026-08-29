package com.wraithhawit.rstweaks.planner;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Direct tests for {@link BranchAndBound}, which had none — the plan scenarios reached it only
 * through whole crafting graphs.
 *
 * <p>What a mutation run said was unpinned is the search's honesty rather than its answers: the
 * node budget, the depth cap, the incumbent bound, and the push of the second child. All four are
 * the machinery behind the {@code complete} flag, whose whole purpose is the distinction in the
 * record javadoc — "only a complete search that found nothing proves the program infeasible; an
 * incomplete one proves nothing at all". That distinction is the {@code cap-is-not-a-proof}
 * failure mode, and nothing was holding it in place.
 *
 * <p>Minimise {@code c·x} subject to {@code A x >= b}, {@code x >= 0}, integral.
 */
class BranchAndBoundTest {

    private static Rational[] v(final long... values) {
        final Rational[] r = new Rational[values.length];
        for (int i = 0; i < values.length; i++) {
            r[i] = Rational.of(values[i]);
        }
        return r;
    }

    private static Rational[][] m(final long[]... rows) {
        final Rational[][] a = new Rational[rows.length][];
        for (int i = 0; i < rows.length; i++) {
            a[i] = v(rows[i]);
        }
        return a;
    }

    /** The relaxation gives 3/2; rounding it is not the answer, branching is. */
    @Test
    void roundsUpToAnIntegerOptimum() {
        final BranchAndBound.Result r =
            BranchAndBound.solve(m(new long[]{2L}), v(3L), v(1L), 500, 500, 30);
        assertTrue(r.complete());
        assertArrayEquals(new long[]{2L}, r.values());
    }

    /**
     * The case that pins the second child. The relaxation puts x at 13/4; the "x >= 4" child gives
     * a feasible {@code [4,0]} at cost 8, and the true optimum {@code [2,1]} at cost 7 is only
     * reachable through the "x <= 3" child. Drop that push and the search still returns a valid
     * plan — just a more expensive one — which is exactly the kind of regression no existing test
     * could see.
     */
    @Test
    void exploresBothChildrenNotJustTheUpperOne() {
        final BranchAndBound.Result r =
            BranchAndBound.solve(m(new long[]{4L, 5L}), v(13L), v(2L, 3L), 500, 500, 30);
        assertTrue(r.complete());
        assertArrayEquals(new long[]{2L, 1L}, r.values());
    }

    @Test
    void respectsAnUpperBoundRow() {
        // 2x + y >= 3 with y <= 2, written as -y >= -2. x = 0 would need y = 3, so it is out.
        final BranchAndBound.Result r = BranchAndBound.solve(
            m(new long[]{2L, 1L}, new long[]{0L, -1L}), v(3L, -2L), v(10L, 1L), 500, 500, 30);
        assertTrue(r.complete());
        assertArrayEquals(new long[]{1L, 1L}, r.values());
    }

    /** A complete search that found nothing. This one, and only this one, is a proof. */
    @Test
    void contradictoryConstraintsAreProvedInfeasible() {
        final BranchAndBound.Result r = BranchAndBound.solve(
            m(new long[]{1L}, new long[]{-1L}), v(1L, 1L), v(1L), 500, 500, 30);
        assertTrue(r.complete(), "a refuted program must report a complete search");
        assertNull(r.values());
    }

    /**
     * Exhausting the node budget must never look like the above. Same null values, opposite
     * meaning, and the flag is the only thing carrying the difference.
     */
    @Test
    void anExhaustedNodeBudgetIsNotAProof() {
        final BranchAndBound.Result r =
            BranchAndBound.solve(m(new long[]{2L}), v(3L), v(1L), 1, 500, 30);
        assertFalse(r.complete(), "a search that ran out of nodes must not claim completeness");
        assertNull(r.values());
    }

    @Test
    void anExhaustedDepthCapIsNotAProof() {
        final BranchAndBound.Result r =
            BranchAndBound.solve(m(new long[]{2L}), v(3L), v(1L), 500, 500, 0);
        assertFalse(r.complete());
        assertNull(r.values());
    }

    /**
     * The depth cap can also stop short while holding a workable-but-suboptimal plan, which is the
     * shape that matters in game: the player gets a craft that works and costs more than it had to.
     * Also the case that pins {@code child()}'s depth arithmetic — count depth the wrong way and
     * the cap never fires, so this returns the true optimum and claims to be complete.
     */
    @Test
    void aCappedSearchMayReturnASuboptimalPlanAndSaySo() {
        final BranchAndBound.Result deep =
            BranchAndBound.solve(m(new long[]{4L, 5L}), v(13L), v(2L, 3L), 500, 500, 2);
        assertTrue(deep.complete());
        assertArrayEquals(new long[]{2L, 1L}, deep.values());

        final BranchAndBound.Result shallow =
            BranchAndBound.solve(m(new long[]{4L, 5L}), v(13L), v(2L, 3L), 500, 500, 1);
        assertFalse(shallow.complete(), "depth 1 cannot settle this program");
        assertNotNull(shallow.values(), "but it did find a usable plan");
        assertArrayEquals(new long[]{4L, 0L}, shallow.values());
    }

    /** Two ways to spend, and the cheaper one is not the one depth-first reaches first. */
    @Test
    void keepsTheCheaperOfCompetingIntegerSolutions() {
        final BranchAndBound.Result r =
            BranchAndBound.solve(m(new long[]{2L, 2L}), v(3L), v(3L, 2L), 500, 500, 30);
        assertTrue(r.complete());
        assertArrayEquals(new long[]{0L, 2L}, r.values());
    }
}
