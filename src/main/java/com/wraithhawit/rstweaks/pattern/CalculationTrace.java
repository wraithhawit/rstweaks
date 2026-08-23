package com.wraithhawit.rstweaks.pattern;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Records what a single crafting calculation actually spent itself on, so a five-second stall
 * can be read rather than guessed at.
 *
 * <h2>Why this exists</h2>
 *
 * A Step Requester calculation that burns Refined Storage's whole 5,000ms budget reports
 * {@code MISSING_RESOURCES} and nothing else. That answer is indistinguishable from "this cannot
 * be made", names no resource, and gives no clue which part of the search was expensive — so the
 * only way to act on it has been to theorise. This session already produced three wrong theories
 * that way.
 *
 * <p>{@code CraftingCalculatorListener} already carries both facts worth having.
 * {@code childCalculationStarted} fires exactly once per node the tree visits, and
 * {@code ingredientsExhausted} names the resource a branch ran out of. Counting those costs
 * nothing extra to compute — RS already calls them — and it turns "it stalled for five seconds"
 * into "it visited 1.4M nodes, 900k of them trying to make tin_ingot, and gave up on raw_tin
 * 12,000 times".
 *
 * <h2>What it costs</h2>
 *
 * The node counter is a single {@code long} increment on a path that runs millions of times per
 * doomed calculation, which is free next to the {@code HashMap} copying already happening there.
 *
 * <p>The <em>per-resource breakdown</em> is not free — it is a map write per node — so it does
 * not start until a calculation has already visited {@link #DETAIL_THRESHOLD} nodes. A normal
 * craft finishes far below that and pays only the increment; by the time detail collection
 * switches on, the calculation is already pathological and the overhead is irrelevant against
 * the seconds it is about to spend. That threshold is the whole reason this can be left on.
 *
 * <p>Static and unsynchronised, like {@link com.wraithhawit.rstweaks.Stats} and for the same
 * reason: crafting calculations run on the server thread. These are diagnostics, not accounting.
 */
public final class CalculationTrace {
    /**
     * Nodes a calculation must visit before the per-resource breakdown starts being recorded.
     *
     * <p>Chosen from measurement, not taste: the healthy calculations in this world run in
     * single-digit milliseconds, and a millisecond is worth roughly a thousand nodes. A
     * calculation past a hundred thousand nodes is already in trouble.
     */
    public static volatile long detailThreshold = 20_000L;

    /** Distinct resources to keep. A stall has a handful of real culprits, not hundreds. */
    private static final int KEEP = 6;

    private static String rootResource = "";
    private static long rootAmount;
    private static long nodes;
    private static boolean detailed;
    private static long detailFrom;
    private static final Map<String, long[]> BY_RESOURCE = new HashMap<>();

    private CalculationTrace() {
    }

    /** Starts a fresh trace. Called before each Step Requester {@code startTask}. */
    public static void begin(final String resource, final long amount) {
        rootResource = resource;
        rootAmount = amount;
        nodes = 0L;
        detailed = false;
        detailFrom = 0L;
        if (!BY_RESOURCE.isEmpty()) {
            BY_RESOURCE.clear();
        }
    }

    /**
     * One node of the crafting tree, named by what that node was trying to produce.
     *
     * <p>Deliberately takes the name as a lazily-supplied string rather than a {@code ResourceKey}:
     * below the detail threshold the name is never needed, and formatting an {@code ItemResource}
     * builds a whole component patch. The caller passes a supplier so nothing is built until this
     * calculation has already proven itself expensive.
     */
    public static void noteNode(final java.util.function.Supplier<String> resourceName) {
        ++nodes;
        if (!detailed) {
            if (nodes < detailThreshold) {
                return;
            }
            detailed = true;
            detailFrom = nodes;
        }
        BY_RESOURCE.computeIfAbsent(resourceName.get(), k -> new long[2])[0]++;
    }

    /** A branch that gave up because this resource ran out. */
    public static void noteExhausted(final java.util.function.Supplier<String> resourceName) {
        if (!detailed) {
            return;
        }
        BY_RESOURCE.computeIfAbsent(resourceName.get(), k -> new long[2])[1]++;
    }

    public static long nodes() {
        return nodes;
    }

    public static boolean isDetailed() {
        return detailed;
    }

    /** Nodes the breakdown actually saw, which is not the same as the nodes visited. */
    public static long observed() {
        return detailed ? nodes - detailFrom + 1 : 0L;
    }

    /**
     * The trace as readable lines, most expensive first, or empty when there is nothing to say.
     *
     * <p>The header is always emitted for a slow calculation even when no breakdown was
     * collected — a stall that visited only a few hundred nodes is itself a finding, because it
     * means the time went somewhere other than tree search and every theory about branching
     * factor is wrong for that case.
     */
    public static List<String> describe(final long elapsedMs, final boolean started) {
        final List<String> lines = new ArrayList<>(KEEP + 2);
        final long observed = observed();
        lines.add(String.format("%,dms %s calculating %,dx %s -- %,d tree nodes%s",
            elapsedMs,
            started ? "spent" : "wasted",
            rootAmount,
            rootResource,
            nodes,
            !detailed || observed >= nodes ? ""
                : String.format(" (breakdown covers the last %,d)", observed)));
        if (!detailed) {
            lines.add("  no breakdown: fewer than " + String.format("%,d", detailThreshold)
                + " nodes, so the time went somewhere other than tree search");
            return lines;
        }
        final List<Map.Entry<String, long[]>> ranked = new ArrayList<>(BY_RESOURCE.entrySet());
        ranked.sort(Comparator.comparingLong((Map.Entry<String, long[]> e) -> e.getValue()[0]).reversed());
        for (int i = 0; i < Math.min(KEEP, ranked.size()); i++) {
            final Map.Entry<String, long[]> entry = ranked.get(i);
            final long visits = entry.getValue()[0];
            final long exhausted = entry.getValue()[1];
            lines.add(String.format("  %,d nodes (%.0f%%) making %s%s",
                visits,
                observed == 0 ? 0.0 : visits * 100.0 / observed,
                entry.getKey(),
                exhausted == 0 ? "" : String.format("  -- ran out %,d times", exhausted)));
        }
        if (ranked.size() > KEEP) {
            lines.add("  ... and " + (ranked.size() - KEEP) + " more resources");
        }
        return lines;
    }
}
