package com.wraithhawit.rstweaks.planner;

import com.refinedmods.refinedstorage.api.autocrafting.Pattern;
import com.refinedmods.refinedstorage.api.autocrafting.task.TaskPlan;
import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.wraithhawit.rstweaks.planner.CraftingGraph.Graph;
import com.wraithhawit.rstweaks.planner.CraftingGraph.IngredientSlot;
import com.wraithhawit.rstweaks.planner.CraftingGraph.PatternEffect;
import com.wraithhawit.rstweaks.planner.CraftingGraph.ResourceClass;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

/**
 * Turns a {@link CraftingGraph} into equations, solves them, and emits a
 * {@link TaskPlan} that Refined Storage's existing executor can run unchanged.
 *
 * <p>One variable per pattern (how many times it is crafted), one row per resource
 * class:
 *
 * <pre>{@code   sum_p  x_p * (produced_p(c) - consumed_p(c))  >=  required(c) - initial(c) }</pre>
 *
 * <p>The target row demands net production of the requested amount rather than
 * "finish with at least this much", matching Refined Storage: asking for 64 cobble
 * crafts 64 more, it does not top you up to 64.
 *
 * <p>A solution that satisfies the equations is not necessarily runnable, so every
 * candidate goes through {@link PlanSimulator}. When it deadlocks, the blocking
 * class gets a seed row forcing genuine surplus production of it, and we re-solve.
 * That loop is what turns the bucket cycle from "craft 0 buckets and deadlock" into
 * "craft 3 buckets and recycle them".
 */
public final class PlanMatrix {
    private PlanMatrix() {
    }

    /**
     * Outcome of a solve. Carries a reason on failure because "no plan" has several
     * very different causes — an infeasible program, an exhausted budget, and a
     * solution the simulator rejected each need a different fix, and collapsing them
     * into one message makes the log useless for diagnosis.
     */
    public record Outcome(TaskPlan plan, String failure) {
        static Outcome ok(final TaskPlan plan) {
            return new Outcome(plan, null);
        }

        static Outcome failed(final String reason) {
            return new Outcome(null, reason);
        }
    }

    public static Outcome solve(final Graph graph,
                                final ResourceKey target,
                                final long amount,
                                final int maxNodes,
                                final int maxPivots,
                                final int maxDepth,
                                final int maxRepairs,
                                final int maxSimPasses) {
        if (graph.targetClass() < 0 || graph.patterns().isEmpty()) {
            return Outcome.failed("target has no producing pattern in the subgraph");
        }
        final int patternCount = graph.patterns().size();
        final int classCount = graph.classes().size();

        final Rational[] objective = new Rational[patternCount];
        Arrays.fill(objective, Rational.ONE);

        final long[] initial = PlanSimulator.initialPool(graph.classes());
        final Map<Integer, Long> seeds = new HashMap<>();

        for (int attempt = 0; attempt <= maxRepairs; attempt++) {
            final List<Rational[]> rows = new ArrayList<>();
            final List<Rational> bounds = new ArrayList<>();

            for (int c = 0; c < classCount; c++) {
                final Rational[] row = netRow(graph.effects(), patternCount, c);
                rows.add(row);
                bounds.add(c == graph.targetClass()
                    ? Rational.of(amount)
                    : Rational.of(-initial[c]));
            }
            // Seed rows: demand real surplus of a class the simulation stalled on.
            seeds.forEach((cls, required) -> {
                rows.add(netRow(graph.effects(), patternCount, cls));
                bounds.add(Rational.of(required));
            });

            final BranchAndBound.Result result;
            try {
                result = BranchAndBound.solve(
                    rows.toArray(new Rational[0][]),
                    bounds.toArray(new Rational[0]),
                    objective,
                    maxNodes,
                    maxPivots,
                    maxDepth
                );
            } catch (final ArithmeticException overflow) {
                return Outcome.failed("exact arithmetic overflowed building the tableau");
            }
            if (result == null) {
                // Distinguish the two very different meanings of "no solution":
                // a first attempt means the recipes genuinely cannot reach the target
                // from current stock; a later one means our own seed constraint made
                // an otherwise-solvable problem impossible.
                return Outcome.failed(attempt == 0
                    ? "no integer solution -- " + diagnoseInfeasibility(graph, initial)
                    : "seed constraint made the program infeasible after " + attempt
                        + " repair(s); seeded classes=" + describeSeeds(graph, seeds));
            }

            // What the task must extract up front for this solution to actually run.
            // Not the same question as "is it executable given the whole network", and
            // conflating the two is what made rice slimeballs deadlock: see
            // workingCapital.
            final long[] required = workingCapital(
                graph, result.values(), initial, classCount, maxSimPasses);
            if (required != null) {
                final TaskPlan plan = emit(graph, target, amount, result.values(), required);
                return plan == null
                    ? Outcome.failed("solved, but the plan could not be emitted "
                        + "(no root pattern produces the target)")
                    : Outcome.ok(plan);
            }

            // Not executable even drawing on everything the network holds, so the
            // solution itself is wrong rather than merely under-provisioned. Re-solve
            // with a seed constraint on whatever it stalled on.
            final PlanSimulator.Outcome outcome = PlanSimulator.simulate(
                result.values(), initial, graph.effects(), classCount, maxSimPasses);
            if (outcome.executable()) {
                // Runs against the whole network but could not be provisioned from a
                // requirements list. Re-solving will not help — the solution is fine and
                // the gap is in how much capital we asked for — so say so plainly rather
                // than reporting it as a stall the seed loop can fix.
                return Outcome.failed("plan runs against network stock but its working "
                    + "capital could not be resolved within what the network holds");
            }
            if (outcome.blockingClass() < 0) {
                return Outcome.failed("simulation stalled with no identifiable blocking "
                    + "resource, or exceeded maxSimulationPasses");
            }
            // Grow rather than replace: a later stall on the same class means the
            // previous seed was not enough. Doubling converges in a couple of rounds
            // where adding the observed shortfall would creep up one container at a
            // time -- the simulation only ever reports the shortfall for a single
            // iteration, not how many the cycle needs in flight.
            seeds.merge(outcome.blockingClass(), Math.max(1L, outcome.shortfall()),
                (existing, add) -> Math.max(Math.multiplyExact(existing, 2L), existing + add));
        }
        return Outcome.failed("simulation never became executable within " + maxRepairs
            + " seed repairs; seeded classes=" + describeSeeds(graph, seeds));
    }

    /**
     * How much of each resource class the task must extract up front, per class index,
     * or {@code null} if no amount the network can supply makes the solution run.
     *
     * <p>This is the fix for the bug that made rice slimeballs sit forever. The obvious
     * answer — net consumption, total consumed minus total produced — is what the linear
     * program computes, and it is wrong for exactly the cycles this planner exists to
     * handle. Rice slimeballs consume a water bucket and give the bucket back, so the
     * bucket's net is zero and it was omitted from the requirements entirely. Refined
     * Storage steps patterns against the task's <em>internal</em> storage, seeded only
     * from the requirements, so a plan that asks for no buckets has no bucket to fill,
     * cannot run the first iteration, and never produces the bucket that would let it.
     * The task sits in EXTRACTING_INITIAL_RESOURCES looking identical to a freeze.
     *
     * <p>A cycle needs <em>working capital</em>: one container in flight, returned and
     * reused. Net consumption of zero and required capital of zero are different claims,
     * and only the first is true.
     *
     * <p>The simulator could not catch this because it was replaying the plan against
     * everything the network holds — which included the bucket the emitted plan then
     * failed to ask for. So it is replayed here against the requirements themselves,
     * growing them until it runs. Starting from net consumption and growing finds the
     * minimum: one bucket for rice slimeballs, not the 64 that stock Refined Storage
     * demands and not the zero the equations suggest.
     */
    @Nullable
    private static long[] workingCapital(final Graph graph,
                                         final long[] iterations,
                                         final long[] available,
                                         final int classCount,
                                         final int maxSimPasses) {
        final long[] required = netConsumption(graph, iterations, classCount);
        for (int c = 0; c < classCount; c++) {
            // For an ordinary class the program constrains net consumption to what is
            // available, so this only guards against an arithmetic surprise. For a tool
            // class it is load-bearing: netConsumption asks for every use the plan will
            // burn, and this caps that at the uses the network actually holds.
            required[c] = Math.min(required[c], available[c]);
        }
        final int maxRounds = Math.min(64, classCount * 2 + 8);
        for (int round = 0; round < maxRounds; round++) {
            final PlanSimulator.Outcome outcome = PlanSimulator.simulate(
                iterations, required, graph.effects(), classCount, maxSimPasses);
            if (outcome.executable()) {
                return required;
            }
            // Not outcome.blockingClass(): the cheapest shortfall is usually a crafted
            // intermediate the network stocks none of, and asking to extract more of
            // something that does not exist gets us nowhere. Two recycled containers in
            // one graph is where this bites — the stall names the intermediates and the
            // containers alike, and only the containers can be supplied.
            int blocked = -1;
            long grownTo = 0L;
            long best = Long.MAX_VALUE;
            for (final Map.Entry<Integer, Long> candidate : outcome.shortfalls().entrySet()) {
                final int cls = candidate.getKey();
                // Double rather than add the shortfall, for the same reason the seed loop
                // does: the simulation reports what one iteration is short, never how
                // many the cycle needs in flight, so adding it creeps up one container
                // per round. Clamped to stock rather than abandoned when the doubling
                // overshoots — cakes need exactly the three buckets the network had, and
                // bailing because the doubling asked for four threw away a working plan.
                final long grown = Math.min(available[cls], Math.max(
                    Math.multiplyExact(required[cls], 2L),
                    required[cls] + Math.max(1L, candidate.getValue())));
                if (grown <= required[cls]) {
                    continue;
                }
                if (candidate.getValue() < best || (candidate.getValue() == best && cls < blocked)) {
                    best = candidate.getValue();
                    blocked = cls;
                    grownTo = grown;
                }
            }
            if (blocked < 0) {
                // Nothing left that the network can supply more of.
                return null;
            }
            required[blocked] = grownTo;
        }
        return null;
    }

    /**
     * The fewest real tools whose remaining uses cover {@code uses}, most worn first.
     *
     * <p>Finishing off a nearly spent crystal before starting a fresh one is both what a
     * player would do by hand and what keeps the requisition small. If the stored tools
     * cannot cover it, whatever exists is taken and the shortfall is left to the plan's
     * own tool-crafting patterns, which the program has already sized.
     */
    private static List<ResourceAmount> toolsCovering(final ResourceClass resourceClass,
                                                      final long uses) {
        final Durability durability = Durability.Holder.get();
        final List<Map.Entry<ResourceKey, Long>> held =
            new ArrayList<>(resourceClass.availableByResource().entrySet());
        held.sort(Comparator.comparingInt(e -> durability.usesLeft(e.getKey())));

        final List<ResourceAmount> taken = new ArrayList<>();
        long remaining = uses;
        for (final Map.Entry<ResourceKey, Long> entry : held) {
            if (remaining <= 0L) {
                break;
            }
            final int perItem = durability.usesLeft(entry.getKey());
            if (perItem <= 0) {
                continue;
            }
            // Ceiling division: a tool is taken whole, even for a single remaining use.
            final long wanted = Math.min(entry.getValue(), (remaining + perItem - 1) / perItem);
            if (wanted > 0L) {
                taken.add(new ResourceAmount(entry.getKey(), wanted));
                remaining -= Math.multiplyExact(wanted, (long) perItem);
            }
        }
        return taken;
    }

    /**
     * Total consumed minus total produced, per class, floored at zero — <b>except for a
     * tool class, which asks for everything the plan will burn.</b>
     *
     * <p>Issue #10. Netting is the right question for an item: a plan that crafts eight
     * gears and consumes eight needs none from the network. It is the wrong question for a
     * class denominated in uses, because a use is not fungible with the item that carries
     * it. A hammer with three uses left, a request needing thirty-two, and a replacement
     * worth ninety-six nets to zero — so the requisition named no hammer at all, the task
     * never saw the worn one, and its last three uses were stranded while a fresh tool did
     * all thirty-two crafts. That is the whole of the reported bug: not extraction order,
     * not a preference for pristine tools, and not something the objective could fix, since
     * both outcomes are the <em>same</em> solver solution and differ only here.
     *
     * <p>Asking for gross consumption and letting the caller clamp it to stock says the
     * honest thing instead: spend the uses you have already paid for, and let the tools this
     * plan crafts cover the rest. It changes nothing when no tool is being crafted, because
     * then production is zero and the net already equals consumption.
     */
    private static long[] netConsumption(final Graph graph,
                                         final long[] iterations,
                                         final int classCount) {
        final long[] consumed = new long[classCount];
        final long[] produced = new long[classCount];
        for (int p = 0; p < iterations.length; p++) {
            if (iterations[p] <= 0L) {
                continue;
            }
            final long times = iterations[p];
            final PatternEffect effect = graph.effects().get(p);
            effect.consumedByClass().forEach((cls, amt) ->
                consumed[cls] += Math.multiplyExact(amt, times));
            effect.producedByClass().forEach((cls, amt) ->
                produced[cls] += Math.multiplyExact(amt, times));
        }
        final long[] net = new long[classCount];
        for (int c = 0; c < classCount; c++) {
            net[c] = graph.classes().get(c).tool()
                ? consumed[c]
                : Math.max(0L, consumed[c] - produced[c]);
        }
        return net;
    }

    /**
     * Explains why the base program has no solution, by naming resources that cannot
     * possibly be balanced.
     *
     * <p>A class is a hard blocker when some pattern consumes it, no pattern produces
     * it, and the network holds none — its row demands a non-negative balance that
     * nothing can supply. That is almost always a modelling gap rather than a genuinely
     * impossible craft: an infinite fluid source, a resource obtained by some route the
     * pattern graph does not describe, or an equivalence class that split two things
     * the game treats as interchangeable.
     *
     * <p>Reporting the resource by name is the difference between "the solver said no"
     * and knowing exactly which assumption to fix.
     */
    private static String diagnoseInfeasibility(final Graph graph, final long[] initial) {
        final List<String> blockers = new ArrayList<>();
        for (int c = 0; c < graph.classes().size(); c++) {
            if (initial[c] > 0L || c == graph.targetClass()) {
                continue;
            }
            boolean consumed = false;
            boolean produced = false;
            for (final PatternEffect effect : graph.effects()) {
                if (effect.consumedByClass().getOrDefault(c, 0L) > 0L) {
                    consumed = true;
                }
                if (effect.producedByClass().getOrDefault(c, 0L) > 0L) {
                    produced = true;
                }
            }
            if (consumed && !produced) {
                final List<ResourceKey> members = graph.classes().get(c).members();
                blockers.add(members.isEmpty() ? ("class" + c) : members.getFirst().toString());
            }
        }
        if (blockers.isEmpty()) {
            return "no single unsatisfiable resource found; the constraints conflict "
                + "only in combination";
        }
        return "required with no pattern and none in storage: " + blockers;
    }

    /** Names the seeded resources so a stubborn cycle is identifiable from the log. */
    private static String describeSeeds(final Graph graph, final Map<Integer, Long> seeds) {
        final List<String> parts = new ArrayList<>(seeds.size());
        seeds.forEach((cls, required) -> {
            final List<ResourceKey> members = graph.classes().get(cls).members();
            parts.add((members.isEmpty() ? ("class" + cls) : members.getFirst()) + "x" + required);
        });
        return parts.toString();
    }

    private static Rational[] netRow(final List<PatternEffect> effects,
                                     final int patternCount,
                                     final int resourceClass) {
        final Rational[] row = new Rational[patternCount];
        Arrays.fill(row, Rational.ZERO);
        for (int p = 0; p < patternCount; p++) {
            final PatternEffect effect = effects.get(p);
            final long produced = effect.producedByClass().getOrDefault(resourceClass, 0L);
            final long consumed = effect.consumedByClass().getOrDefault(resourceClass, 0L);
            row[p] = Rational.of(produced - consumed);
        }
        return row;
    }

    // ------------------------------------------------------------------ emission

    private static TaskPlan emit(final Graph graph,
                                 final ResourceKey target,
                                 final long amount,
                                 final long[] iterations,
                                 final long[] required) {
        final Pattern rootPattern = pickRootPattern(graph, iterations);
        if (rootPattern == null) {
            return null;
        }

        // Supply ledger per class: what the network holds, plus what this plan makes.
        // Consumption is drawn from it so the emitted ingredient lists name resources
        // that will genuinely be present.
        final Map<Integer, Map<ResourceKey, Long>> supply = buildSupply(graph, iterations);

        final Map<Pattern, TaskPlan.PatternPlan> patterns = new LinkedHashMap<>();
        for (int p = 0; p < iterations.length; p++) {
            if (iterations[p] <= 0L) {
                continue;
            }
            final PatternEffect effect = graph.effects().get(p);
            final Map<Integer, Map<ResourceKey, Long>> ingredients = new LinkedHashMap<>();
            for (final IngredientSlot slot : effect.slots()) {
                final long total = Math.multiplyExact(slot.amountPerIteration(), iterations[p]);
                final Map<ResourceKey, Long> allocated =
                    allocate(supply, slot.classIndex(), total, graph.classes());
                if (allocated == null) {
                    return null;
                }
                ingredients.merge(slot.ingredientIndex(), allocated, PlanMatrix::mergeAmounts);
            }
            patterns.put(effect.pattern(), new TaskPlan.PatternPlan(
                effect.pattern().equals(rootPattern), iterations[p], ingredients));
        }
        if (patterns.isEmpty()) {
            return null;
        }

        return new TaskPlan(target, amount, rootPattern, patterns,
            initialRequirements(graph, required));
    }

    private static Pattern pickRootPattern(final Graph graph, final long[] iterations) {
        for (int p = 0; p < iterations.length; p++) {
            if (iterations[p] <= 0L) {
                continue;
            }
            final PatternEffect effect = graph.effects().get(p);
            if (effect.producedByClass().containsKey(graph.targetClass())) {
                return effect.pattern();
            }
        }
        return null;
    }

    private static Map<Integer, Map<ResourceKey, Long>> buildSupply(final Graph graph,
                                                                    final long[] iterations) {
        final Map<Integer, Map<ResourceKey, Long>> supply = new HashMap<>();
        for (final ResourceClass cls : graph.classes()) {
            // Storage first: preferring what already exists avoids planning around an
            // intermediate the network could simply hand over.
            supply.put(cls.index(), new LinkedHashMap<>(cls.availableByResource()));
        }
        for (int p = 0; p < iterations.length; p++) {
            if (iterations[p] <= 0L) {
                continue;
            }
            final Pattern pattern = graph.patterns().get(p);
            for (final ResourceAmount output : pattern.layout().outputs()) {
                addSupply(supply, graph, output, iterations[p]);
            }
            for (final ResourceAmount byproduct : pattern.layout().byproducts()) {
                addSupply(supply, graph, byproduct, iterations[p]);
            }
        }
        return supply;
    }

    private static void addSupply(final Map<Integer, Map<ResourceKey, Long>> supply,
                                  final Graph graph,
                                  final ResourceAmount produced,
                                  final long times) {
        final Integer cls = graph.classOf().get(produced.resource());
        if (cls == null) {
            return;
        }
        supply.computeIfAbsent(cls, k -> new LinkedHashMap<>())
            .merge(produced.resource(), Math.multiplyExact(produced.amount(), times), Long::sum);
    }

    /** Draws {@code total} of a class from the supply ledger, naming concrete resources. */
    private static Map<ResourceKey, Long> allocate(final Map<Integer, Map<ResourceKey, Long>> supply,
                                                   final int classIndex,
                                                   final long total,
                                                   final List<ResourceClass> classes) {
        final Map<ResourceKey, Long> out = new LinkedHashMap<>();
        long remaining = total;
        final Map<ResourceKey, Long> available = supply.getOrDefault(classIndex, new LinkedHashMap<>());
        for (final Map.Entry<ResourceKey, Long> entry : available.entrySet()) {
            if (remaining <= 0L) {
                break;
            }
            final long take = Math.min(remaining, entry.getValue());
            if (take <= 0L) {
                continue;
            }
            out.merge(entry.getKey(), take, Long::sum);
            entry.setValue(entry.getValue() - take);
            remaining -= take;
        }
        if (remaining > 0L) {
            // Ledger came up short. Attribute the rest to the class's first member so
            // the plan is still well-formed; the simulator has already proven the
            // amounts balance, so this only picks which concrete resource is named.
            final List<ResourceKey> members = classes.get(classIndex).members();
            if (members.isEmpty()) {
                return null;
            }
            out.merge(members.getFirst(), remaining, Long::sum);
        }
        return out;
    }

    private static Map<ResourceKey, Long> mergeAmounts(final Map<ResourceKey, Long> a,
                                                       final Map<ResourceKey, Long> b) {
        final Map<ResourceKey, Long> merged = new LinkedHashMap<>(a);
        b.forEach((k, v) -> merged.merge(k, v, Long::sum));
        return merged;
    }

    /**
     * What the task pulls from the network up front, named as concrete resources.
     *
     * <p>Takes the amounts {@link #workingCapital} resolved rather than recomputing net
     * consumption. A recycled container nets to zero but still needs one in flight, and
     * emitting the net was what left rice slimeball tasks with no bucket to start on.
     */
    private static List<ResourceAmount> initialRequirements(final Graph graph,
                                                            final long[] required) {
        final List<ResourceAmount> requirements = new ArrayList<>();
        for (int cls = 0; cls < required.length; cls++) {
            long net = required[cls];
            if (net <= 0L) {
                continue;
            }
            final ResourceClass resourceClass = graph.classes().get(cls);
            if (resourceClass.tool()) {
                // This class counts crafts remaining, and the task has to be handed
                // items. Take worn tools first so a nearly-spent crystal is finished off
                // before a fresh one is touched, and stop as soon as the requested uses
                // are covered — the whole point is to requisition one crystal rather
                // than one per craft.
                requirements.addAll(toolsCovering(resourceClass, net));
                continue;
            }
            // Name resources the network actually holds, so the task extracts
            // something real rather than an arbitrary member of the class.
            for (final Map.Entry<ResourceKey, Long> held : resourceClass.availableByResource().entrySet()) {
                if (net <= 0L) {
                    break;
                }
                final long take = Math.min(net, held.getValue());
                if (take > 0L) {
                    requirements.add(new ResourceAmount(held.getKey(), take));
                    net -= take;
                }
            }
            if (net > 0L && !resourceClass.members().isEmpty()) {
                requirements.add(new ResourceAmount(resourceClass.members().getFirst(), net));
            }
        }
        return requirements;
    }
}
