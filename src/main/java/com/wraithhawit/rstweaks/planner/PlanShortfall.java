package com.wraithhawit.rstweaks.planner;

import com.refinedmods.refinedstorage.api.autocrafting.preview.Preview;
import com.refinedmods.refinedstorage.api.autocrafting.preview.PreviewBuilder;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.wraithhawit.rstweaks.planner.CraftingGraph.Graph;
import com.wraithhawit.rstweaks.planner.CraftingGraph.PatternEffect;
import com.wraithhawit.rstweaks.planner.CraftingGraph.ResourceClass;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

/**
 * Works out <em>what you are short of</em> when the program has proved a request
 * impossible.
 *
 * <p>Asking for ten buckets' worth of lava with four lava buckets in the network used to
 * preview "missing 10000mB lava" — true, useless, and phrased as though the fluid itself
 * were the thing you needed to find. The honest answer names the containers:
 *
 * <pre>{@code   lava_bucket   available 4   missing 6
 *   lava                        to craft 10000mB }</pre>
 *
 * <p><b>Those numbers cannot be salvaged, only computed.</b> An infeasible integer
 * program leaves no plan behind to read shortfalls off, and Refined Storage's own
 * calculator has not accumulated its available/missing counts at the moment it throws
 * {@code PatternCycleDetectedException} — that was tried, shipped as v0.2.45, and reverted
 * for leaving Start enabled on an impossible craft. See the warning in
 * {@code PreviewCraftingCalculatorListenerMixin}.
 *
 * <p>So the same program is solved a second time with an <b>external supply</b> variable
 * added to the classes that could be short. Unlimited outside supply makes it feasible;
 * what the solution then draws beyond what the network holds is the shortfall, and the
 * pattern counts say what would be crafted.
 */
public final class PlanShortfall {
    private PlanShortfall() {
    }

    /**
     * @return the preview to show, or {@code null} if nothing better than the caller's
     *     fallback could be worked out — an exhausted solver budget, arithmetic that
     *     overflowed, or a re-solve that found nothing missing at all.
     */
    @Nullable
    public static Preview diagnose(final Graph graph,
                                   final ResourceKey target,
                                   final long amount,
                                   final int maxNodes,
                                   final int maxPivots,
                                   final int maxDepth) {
        if (graph.targetClass() < 0 || graph.patterns().isEmpty() || amount <= 0L) {
            return null;
        }
        final int patternCount = graph.patterns().size();
        final int classCount = graph.classes().size();
        final long[] initial = PlanSimulator.initialPool(graph.classes());

        final boolean[] allowed = externalSupplyAllowed(graph);
        final int[] supplyVariable = new int[classCount];
        int variables = patternCount;
        for (int c = 0; c < classCount; c++) {
            supplyVariable[c] = allowed[c] ? variables++ : -1;
        }
        if (variables == patternCount) {
            // Nothing may be supplied from outside, so the re-solve is the same program
            // that just failed. Nothing to learn from running it again.
            return null;
        }

        final BranchAndBound.Result result;
        try {
            final Rational[][] rows = new Rational[classCount][];
            final Rational[] bounds = new Rational[classCount];
            for (int c = 0; c < classCount; c++) {
                rows[c] = row(graph, c, variables, supplyVariable[c]);
                bounds[c] = c == graph.targetClass()
                    ? Rational.of(amount)
                    : Rational.of(-initial[c]);
            }
            final Rational[] objective = new Rational[variables];
            // One per crafting step and one per unit supplied from outside, so the answer
            // is the smallest shortfall rather than the first one found. Weighting the two
            // equally is safe because outside supply is only offered where crafting cannot
            // substitute for it: a leaf has no pattern to run instead, and a class inside
            // the target's own cycle can only be made by consuming the target.
            Arrays.fill(objective, Rational.ONE);
            result = BranchAndBound.solve(rows, bounds, objective, maxNodes, maxPivots, maxDepth);
        } catch (final ArithmeticException overflow) {
            return null;
        }
        if (result.values() == null) {
            return null;
        }
        return describe(graph, target, amount, initial, result.values());
    }

    /**
     * One resource class's balance row: what the patterns net, plus the outside supply
     * this class is allowed, against what the network holds.
     */
    private static Rational[] row(final Graph graph,
                                  final int resourceClass,
                                  final int variables,
                                  final int supplyVariable) {
        final Rational[] row = new Rational[variables];
        Arrays.fill(row, Rational.ZERO);
        for (int p = 0; p < graph.effects().size(); p++) {
            final PatternEffect effect = graph.effects().get(p);
            final long produced = effect.producedByClass().getOrDefault(resourceClass, 0L);
            final long consumed = effect.consumedByClass().getOrDefault(resourceClass, 0L);
            row[p] = Rational.of(produced - consumed);
        }
        if (supplyVariable >= 0) {
            row[supplyVariable] = Rational.ONE;
        }
        return row;
    }

    /**
     * Which classes may be handed unlimited supply from outside the network.
     *
     * <p>This choice is the whole difference between a useful message and a misleading
     * one, because whatever is allowed to arrive from outside is what gets reported as
     * missing. Allow everything and the solver conjures the cheapest thing it can — "you
     * are short 2 gears" when the truth is 8 iron — because the shallower item is fewer
     * units. Allow nothing and the program is exactly the one that just failed.
     *
     * <p>Two kinds of class qualify, and no others:
     *
     * <ul>
     *   <li><b>Leaves</b>, which no pattern produces. Refined Storage reports missing
     *       resources at the leaves too, so this is what makes the two agree.</li>
     *   <li><b>Classes inside the target's own cycle</b> — reachable from the target and
     *       able to reach it again. A lava bucket has a pattern, but running it consumes
     *       the very lava being asked for, so the cycle can never bootstrap itself and the
     *       buckets are genuinely the thing you are short of.</li>
     * </ul>
     *
     * <p>The target itself is always excluded. Supplying it from outside is precisely the
     * "missing 10000mB lava" message this class exists to replace.
     */
    private static boolean[] externalSupplyAllowed(final Graph graph) {
        final int classCount = graph.classes().size();
        final boolean[] produced = new boolean[classCount];
        final Map<Integer, Set<Integer>> forward = new HashMap<>();
        final Map<Integer, Set<Integer>> backward = new HashMap<>();
        for (final PatternEffect effect : graph.effects()) {
            for (final Integer output : effect.producedByClass().keySet()) {
                produced[output] = true;
                for (final Integer input : effect.consumedByClass().keySet()) {
                    forward.computeIfAbsent(input, k -> new HashSet<>()).add(output);
                    backward.computeIfAbsent(output, k -> new HashSet<>()).add(input);
                }
            }
        }
        final Set<Integer> downstream = reachable(graph.targetClass(), forward);
        final Set<Integer> upstream = reachable(graph.targetClass(), backward);

        final boolean[] allowed = new boolean[classCount];
        for (int c = 0; c < classCount; c++) {
            allowed[c] = c != graph.targetClass()
                && (!produced[c] || (downstream.contains(c) && upstream.contains(c)));
        }
        return allowed;
    }

    private static Set<Integer> reachable(final int from, final Map<Integer, Set<Integer>> edges) {
        final Set<Integer> seen = new HashSet<>();
        final Deque<Integer> queue = new ArrayDeque<>();
        queue.add(from);
        while (!queue.isEmpty()) {
            for (final Integer next : edges.getOrDefault(queue.poll(), Set.of())) {
                if (seen.add(next)) {
                    queue.add(next);
                }
            }
        }
        return seen;
    }

    /**
     * Turns the re-solved pattern counts into the rows the grid shows.
     *
     * <p>Read from the pattern counts rather than from the supply variables: a class is
     * drawn on by however much the chosen patterns consume beyond what they give back, and
     * that difference is a fact about the solution rather than about how the program was
     * relaxed to reach it.
     */
    @Nullable
    private static Preview describe(final Graph graph,
                                    final ResourceKey target,
                                    final long amount,
                                    final long[] initial,
                                    final long[] values) {
        final int classCount = graph.classes().size();
        final long[] consumed = new long[classCount];
        final long[] produced = new long[classCount];
        for (int p = 0; p < graph.effects().size(); p++) {
            final long times = values[p];
            if (times <= 0L) {
                continue;
            }
            final PatternEffect effect = graph.effects().get(p);
            effect.consumedByClass().forEach((cls, perIteration) ->
                consumed[cls] += Math.multiplyExact(perIteration, times));
            effect.producedByClass().forEach((cls, perIteration) ->
                produced[cls] += Math.multiplyExact(perIteration, times));
        }

        final PreviewBuilder builder = PreviewBuilder.create();
        boolean anythingMissing = false;
        for (int c = 0; c < classCount; c++) {
            if (c == graph.targetClass()) {
                continue;
            }
            final long drawn = consumed[c] - produced[c];
            if (drawn <= 0L) {
                // Either untouched, or handed round and given back — a bucket that comes
                // back empty is not something you are short of.
                continue;
            }
            final long fromStorage = Math.min(drawn, initial[c]);
            final long missing = drawn - fromStorage;
            anythingMissing |= missing > 0L;
            report(builder, graph.classes().get(c), fromStorage, missing);
        }
        if (!anythingMissing) {
            // The relaxation found a way through without needing anything from outside, so
            // whatever defeated the real solve was not a shortage. Saying "missing nothing"
            // would enable Start on a craft that has already been proved impossible.
            return null;
        }
        // Last, so the thing that was asked for reads as the outcome rather than as one
        // more ingredient: you are being told what it would take to craft this.
        builder.addToCraft(target, amount);
        return builder.build();
    }

    /**
     * Names concrete resources for one class's draw, keeping the stock and the shortfall on
     * the same row so it reads "available 4, missing 6" rather than as two unrelated lines.
     */
    private static void report(final PreviewBuilder builder,
                               final ResourceClass resourceClass,
                               final long fromStorage,
                               final long missing) {
        if (resourceClass.members().isEmpty()) {
            return;
        }
        if (resourceClass.tool()) {
            reportTool(builder, resourceClass, missing);
            return;
        }
        ResourceKey first = null;
        long remaining = fromStorage;
        for (final Map.Entry<ResourceKey, Long> held
            : resourceClass.availableByResource().entrySet()) {
            if (remaining <= 0L) {
                break;
            }
            final long take = Math.min(remaining, held.getValue());
            if (take <= 0L) {
                continue;
            }
            if (first == null) {
                first = held.getKey();
            }
            builder.addAvailable(held.getKey(), take);
            remaining -= take;
        }
        if (missing > 0L) {
            builder.addMissing(first == null ? resourceClass.members().getFirst() : first, missing);
        }
    }

    /**
     * A tool class is measured in crafts remaining, not items, so its shortfall has to be
     * converted back before it can be shown: six uses of a hundred-use crystal is one
     * crystal, not six. Everything held is reported as available, because a class that came
     * up short spent every use it had.
     */
    private static void reportTool(final PreviewBuilder builder,
                                   final ResourceClass resourceClass,
                                   final long missingUses) {
        resourceClass.availableByResource().forEach(builder::addAvailable);
        if (missingUses <= 0L) {
            return;
        }
        final ResourceKey fresh = resourceClass.members().getFirst();
        final int perItem = Durability.Holder.get().maxUses(fresh);
        builder.addMissing(fresh, perItem <= 0
            ? missingUses
            : (missingUses + perItem - 1) / perItem);
    }
}
