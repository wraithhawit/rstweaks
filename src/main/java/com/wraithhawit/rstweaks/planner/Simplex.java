package com.wraithhawit.rstweaks.planner;

import java.util.ArrayList;
import java.util.List;

/**
 * Two-phase simplex over exact {@link Rational} arithmetic.
 *
 * <p>Solves: minimise {@code c·x} subject to {@code A x >= b}, {@code x >= 0}.
 *
 * <p>The crafting problem is naturally all-{@code >=}: every resource row says
 * "end with at least this much", and the target row says "produce at least the
 * requested amount". Surplus variables turn those into equalities, which leaves no
 * obvious basis, so phase one minimises a sum of artificial variables to find one.
 *
 * <p>Uses <b>Bland's rule</b> for pivot selection rather than steepest-edge. It is
 * slower, but it is the only rule that provably cannot cycle, and a solver that
 * spins forever inside a crafting request would hang the autocrafting thread. These
 * matrices are small enough that the speed difference does not matter.
 */
public final class Simplex {
    /**
     * Thrown when the pivot cap is reached before the tableau settles.
     *
     * <p>Distinct from a {@code null} return, which means the constraints are genuinely
     * contradictory. Both used to be {@code null}, and callers read the pair as one answer --
     * "no solution" -- which is how a solver that merely ran out of budget came to be reported
     * to the player as a craft that is impossible.
     */
    public static final class PivotLimitExceeded extends RuntimeException {
        private static final long serialVersionUID = 1L;

        PivotLimitExceeded() {
            super("pivot cap reached before the tableau settled");
        }
    }

    /** Sentinel returned when the constraints admit no solution at all. */
    public static final long[] INFEASIBLE = null;

    private final int rows;
    private final int cols;
    private final Rational[][] tableau;
    private final int[] basis;

    private Simplex(final int rows, final int cols) {
        this.rows = rows;
        this.cols = cols;
        this.tableau = new Rational[rows + 1][cols + 1];
        this.basis = new int[rows];
    }

    /**
     * @param a         constraint coefficients, {@code rows x n}
     * @param b         right-hand sides, length {@code rows}; rows mean {@code A x >= b}
     * @param c         objective coefficients to minimise, length {@code n}
     * @param maxPivots hard cap so a pathological problem cannot stall the caller
     * @return optimal {@code x} as rationals, or {@code null} if infeasible or the cap was hit
     */
    public static Rational[] solve(final Rational[][] a,
                                   final Rational[] b,
                                   final Rational[] c,
                                   final int maxPivots) {
        final int m = a.length;
        if (m == 0) {
            final Rational[] trivial = new Rational[c.length];
            java.util.Arrays.fill(trivial, Rational.ZERO);
            return trivial;
        }
        final int n = c.length;

        // Normalise so every b >= 0; a negative rhs flips the row's direction.
        final Rational[][] rowsA = new Rational[m][];
        final Rational[] rhs = new Rational[m];
        final boolean[] flipped = new boolean[m];
        for (int i = 0; i < m; i++) {
            rowsA[i] = a[i].clone();
            rhs[i] = b[i];
            if (rhs[i].signum() < 0) {
                flipped[i] = true;
                rhs[i] = rhs[i].negate();
                for (int j = 0; j < n; j++) {
                    rowsA[i][j] = rowsA[i][j].negate();
                }
            }
        }

        // Columns: n structural, m surplus, m artificial.
        final int surplusBase = n;
        final int artificialBase = n + m;
        final int total = n + m + m;

        final Simplex s = new Simplex(m, total);
        for (int i = 0; i < m; i++) {
            for (int j = 0; j < n; j++) {
                s.tableau[i][j] = rowsA[i][j];
            }
            for (int j = n; j < total; j++) {
                s.tableau[i][j] = Rational.ZERO;
            }
            // A ">=" row gets -1 surplus; a flipped row was "<=" and gets +1 slack.
            s.tableau[i][surplusBase + i] = flipped[i] ? Rational.ONE : Rational.ONE.negate();
            s.tableau[i][artificialBase + i] = Rational.ONE;
            s.tableau[i][total] = rhs[i];
            s.basis[i] = artificialBase + i;
        }

        // Phase one: minimise w = sum of artificials.
        //
        // With every artificial basic at cost 1, the reduced cost of a non-artificial
        // column j is +sum_i a_ij, and the current objective value is sum_i rhs_i.
        // Entering a column with a positive reduced cost drives w down, so the row is
        // built as a *sum* of the constraint rows -- subtracting them instead inverts
        // every sign and makes the ratio test find no leaving row.
        for (int j = 0; j <= total; j++) {
            s.tableau[m][j] = Rational.ZERO;
        }
        for (int i = 0; i < m; i++) {
            for (int j = 0; j <= total; j++) {
                s.tableau[m][j] = s.tableau[m][j].add(s.tableau[i][j]);
            }
        }
        for (int j = artificialBase; j < total; j++) {
            s.tableau[m][j] = Rational.ZERO;
        }

        if (!s.pivotToOptimality(artificialBase, maxPivots)) {
            throw new PivotLimitExceeded();
        }
        // tableau[m][total] now holds w. Non-zero means artificials remain basic at a
        // positive level, so the constraints are contradictory.
        if (!s.tableau[m][total].isZero()) {
            return null;
        }

        // Drive any artificial still in the basis (at zero) out, so phase two
        // cannot reintroduce it and silently violate a constraint.
        for (int i = 0; i < m; i++) {
            if (s.basis[i] < artificialBase) {
                continue;
            }
            int replacement = -1;
            for (int j = 0; j < artificialBase; j++) {
                if (!s.tableau[i][j].isZero()) {
                    replacement = j;
                    break;
                }
            }
            if (replacement >= 0) {
                s.pivot(i, replacement);
            }
        }

        // Phase two: the real objective, with artificial columns forced out of play.
        for (int j = 0; j <= total; j++) {
            s.tableau[m][j] = Rational.ZERO;
        }
        for (int j = 0; j < n; j++) {
            s.tableau[m][j] = c[j].negate();
        }
        for (int i = 0; i < m; i++) {
            final int bv = s.basis[i];
            if (bv < n && !s.tableau[m][bv].isZero()) {
                final Rational factor = s.tableau[m][bv];
                for (int j = 0; j <= total; j++) {
                    s.tableau[m][j] = s.tableau[m][j].subtract(factor.multiply(s.tableau[i][j]));
                }
            }
        }

        if (!s.pivotToOptimality(artificialBase, maxPivots)) {
            throw new PivotLimitExceeded();
        }

        final Rational[] x = new Rational[n];
        java.util.Arrays.fill(x, Rational.ZERO);
        for (int i = 0; i < m; i++) {
            if (s.basis[i] < n) {
                x[s.basis[i]] = s.tableau[i][total];
            }
        }
        return x;
    }

    /** @param columnLimit columns at or beyond this index are never chosen to enter. */
    private boolean pivotToOptimality(final int columnLimit, final int maxPivots) {
        for (int iterations = 0; iterations < maxPivots; iterations++) {
            // Bland's rule: lowest index with a positive reduced cost.
            int enter = -1;
            for (int j = 0; j < columnLimit; j++) {
                if (this.tableau[this.rows][j].signum() > 0) {
                    enter = j;
                    break;
                }
            }
            if (enter < 0) {
                return true;
            }

            int leave = -1;
            Rational best = null;
            for (int i = 0; i < this.rows; i++) {
                if (this.tableau[i][enter].signum() <= 0) {
                    continue;
                }
                final Rational ratio = this.tableau[i][this.cols].divide(this.tableau[i][enter]);
                final int cmp = best == null ? -1 : ratio.compareTo(best);
                // Bland's rule again on ties: prefer the smallest basis index.
                if (cmp < 0 || (cmp == 0 && this.basis[i] < this.basis[leave])) {
                    best = ratio;
                    leave = i;
                }
            }
            if (leave < 0) {
                // Unbounded. Crafting objectives are bounded below by zero, so this
                // means the model is malformed; refuse rather than guess.
                return false;
            }
            this.pivot(leave, enter);
        }
        return false;
    }

    private void pivot(final int row, final int col) {
        final Rational p = this.tableau[row][col];
        for (int j = 0; j <= this.cols; j++) {
            this.tableau[row][j] = this.tableau[row][j].divide(p);
        }
        for (int i = 0; i <= this.rows; i++) {
            if (i == row) {
                continue;
            }
            final Rational factor = this.tableau[i][col];
            if (factor.isZero()) {
                continue;
            }
            for (int j = 0; j <= this.cols; j++) {
                this.tableau[i][j] = this.tableau[i][j].subtract(factor.multiply(this.tableau[row][j]));
            }
        }
        this.basis[row] = col;
    }

    /** Convenience for callers building rows incrementally. */
    public static final class Builder {
        private final int variables;
        private final List<Rational[]> rows = new ArrayList<>();
        private final List<Rational> rhs = new ArrayList<>();

        public Builder(final int variables) {
            this.variables = variables;
        }

        /** Adds {@code coefficients · x >= bound}. */
        public Builder atLeast(final Rational[] coefficients, final Rational bound) {
            this.rows.add(coefficients);
            this.rhs.add(bound);
            return this;
        }

        public Rational[][] matrix() {
            return this.rows.toArray(new Rational[0][]);
        }

        public Rational[] bounds() {
            return this.rhs.toArray(new Rational[0]);
        }

        public int variables() {
            return this.variables;
        }
    }
}
