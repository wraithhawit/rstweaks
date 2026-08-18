package com.wraithhawit.rstweaks.planner;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import javax.annotation.Nullable;

/**
 * Integer solutions via branch and bound over {@link Simplex}.
 *
 * <p>Crafting counts must be whole numbers — "craft 2.5 buckets" is not a plan — but
 * rounding an LP relaxation up is not safe: it can consume more of a scarce input
 * than exists, producing a plan that deadlocks at execution. So fractional variables
 * are branched on properly.
 *
 * <p>Depth-first with a node budget and an incumbent bound. Depth-first because the
 * first integer solution found is usually near-optimal for crafting graphs (the
 * relaxation is nearly integral), and finding *a* workable plan quickly matters more
 * than proving optimality — this runs while a player waits.
 */
public final class BranchAndBound {
    private BranchAndBound() {
    }

    /**
     * @param values   the best integer solution found, or {@code null} if none was
     * @param complete whether the search space was explored to exhaustion. Only a complete
     *     search that found nothing proves the program infeasible; an incomplete one proves
     *     nothing at all, and callers must not report it as an impossible request.
     */
    public record Result(@Nullable long[] values, boolean complete) {
    }

    private record Node(List<Rational[]> extraRows, List<Rational> extraBounds, int depth) {
    }

    /**
     * @param maxNodes  node budget; exhausting it returns the best solution found so far
     * @param maxPivots per-LP pivot cap handed to {@link Simplex}
     * @return never {@code null}. The {@code values} may be {@code null}, and the
     *     {@code complete} flag is what says whether that means "proved there is none" or
     *     merely "ran out of budget looking".
     */
    public static Result solve(final Rational[][] a,
                               final Rational[] b,
                               final Rational[] c,
                               final int maxNodes,
                               final int maxPivots,
                               final int maxDepth) {
        final int n = c.length;
        final Deque<Node> stack = new ArrayDeque<>();
        stack.push(new Node(List.of(), List.of(), 0));

        long[] incumbent = null;
        Rational incumbentCost = null;
        int nodes = 0;
        // Cleared by every path that abandons work without settling the question: the node
        // budget, the depth cap, an overflowed branch, and a pivot cap inside Simplex. Miss any
        // one of them and a search that simply gave up is indistinguishable from a proof.
        boolean complete = true;

        while (!stack.isEmpty()) {
            if (++nodes > maxNodes) {
                complete = false;
                break;
            }
            final Node node = stack.pop();

            final int extra = node.extraRows().size();
            final Rational[][] am = Arrays.copyOf(a, a.length + extra);
            final Rational[] bm = Arrays.copyOf(b, b.length + extra);
            for (int i = 0; i < extra; i++) {
                am[a.length + i] = node.extraRows().get(i);
                bm[b.length + i] = node.extraBounds().get(i);
            }

            final Rational[] x;
            try {
                x = Simplex.solve(am, bm, c, maxPivots);
            } catch (final ArithmeticException overflow) {
                // Exact arithmetic ran out of headroom on this branch. Abandoning
                // the branch is correct: we lose a possible solution, never gain a
                // wrong one -- but losing one means the search is no longer a proof.
                complete = false;
                continue;
            } catch (final Simplex.PivotLimitExceeded pivotCap) {
                // Same shape: this branch is unsettled, not refuted.
                complete = false;
                continue;
            }
            if (x == null) {
                // Genuinely contradictory constraints on this branch -- Simplex throws rather
                // than returning null when it merely ran out of pivots, so pruning is sound.
                continue;
            }

            Rational cost = Rational.ZERO;
            for (int j = 0; j < n; j++) {
                cost = cost.add(c[j].multiply(x[j]));
            }
            if (incumbentCost != null && cost.compareTo(incumbentCost) >= 0) {
                continue;
            }

            int branchVar = -1;
            Rational worst = Rational.ZERO;
            for (int j = 0; j < n; j++) {
                if (x[j].isIntegral()) {
                    continue;
                }
                final Rational distance = x[j].fractionalDistance();
                if (branchVar < 0 || distance.compareTo(worst) > 0) {
                    branchVar = j;
                    worst = distance;
                }
            }

            if (branchVar < 0) {
                final long[] solution = new long[n];
                for (int j = 0; j < n; j++) {
                    solution[j] = x[j].floor();
                }
                incumbent = solution;
                incumbentCost = cost;
                continue;
            }

            if (node.depth() >= maxDepth) {
                complete = false;
                continue;
            }

            final long floor = x[branchVar].floor();

            // x[branchVar] >= floor + 1
            final Rational[] up = zeroRow(n);
            up[branchVar] = Rational.ONE;
            stack.push(child(node, up, Rational.of(floor + 1L)));

            // x[branchVar] <= floor, expressed as -x >= -floor
            final Rational[] down = zeroRow(n);
            down[branchVar] = Rational.ONE.negate();
            stack.push(child(node, down, Rational.of(-floor)));
        }

        return new Result(incumbent, complete);
    }

    private static Node child(final Node parent, final Rational[] row, final Rational bound) {
        final List<Rational[]> rows = new ArrayList<>(parent.extraRows());
        final List<Rational> bounds = new ArrayList<>(parent.extraBounds());
        rows.add(row);
        bounds.add(bound);
        return new Node(rows, bounds, parent.depth() + 1);
    }

    private static Rational[] zeroRow(final int n) {
        final Rational[] row = new Rational[n];
        Arrays.fill(row, Rational.ZERO);
        return row;
    }
}
