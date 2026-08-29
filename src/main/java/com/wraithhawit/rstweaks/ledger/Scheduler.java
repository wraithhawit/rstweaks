package com.wraithhawit.rstweaks.ledger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 04: turning flows into an order.
 *
 * <p>A solved plan says "run this recipe 40 times and that one 12 times". It does not say
 * <em>when</em>, and for anything with a cycle in it the order is the whole problem: you must
 * duplicate the smithing template before you spend it, and empty a bucket before you can fill the
 * next one. Equations have no time in them. This puts the time back.
 *
 * <p>The search is a backtracking greedy, ported from Nodrance's {@code ExecutionPlanner} (see
 * ATTRIBUTION.md). At each step it asks which recipes it can afford <em>right now</em>, takes the
 * most promising, applies as large a batch as the inventory allows, and recurses; when an ordering
 * dead-ends it rolls the batch back and tries a smaller one, then a different recipe.
 *
 * <h2>What "afford" means, and why it is not the raw ingredient list</h2>
 *
 * <p>Affordability is read from {@link Recipe#consumed}, which is the planner's per-iteration
 * effect and already carries the one distinction that matters: <b>gross for ordinary resources,
 * net for pooled ones</b>. You must physically hold the catalyst and the three milk buckets before
 * the recipe can run, so those are counted in full. A tool is counted in the uses one iteration
 * actually costs — charging the full crystal would say a fresh one is good for exactly one craft
 * and then stall, which is the answer a naive port gives.
 *
 * <h2>Deliberately not optimal</h2>
 *
 * <p>Feasible, priority-respecting and explainable beats optimal, and costs an order of magnitude
 * less to compute and to trust. When the search runs out of budget it says so by name rather than
 * returning something plausible — a scheduler that silently gives up is indistinguishable from one
 * that was never installed.
 *
 * <p>Free of Minecraft and Refined Storage types, like everything else in this package, so the
 * whole thing runs in a plain JVM in microseconds.
 */
public final class Scheduler {
    /** How many batch attempts the search may make before giving up and saying so. */
    public static final int DEFAULT_BUDGET = 20_000;

    private Scheduler() {
    }

    /**
     * One recipe as the scheduler sees it: what an iteration needs, what it gives back, and how
     * strongly the player wants it preferred.
     *
     * @param consumed what one iteration must have available, per column
     * @param produced what one iteration returns, per column
     */
    public record Recipe(int id, Map<Integer, Long> consumed, Map<Integer, Long> produced, long priority) {
        public Recipe {
            consumed = Map.copyOf(consumed);
            produced = Map.copyOf(produced);
        }

        public static Recipe of(final int id,
                                final Map<Integer, Long> consumed,
                                final Map<Integer, Long> produced) {
            return new Recipe(id, consumed, produced, 0L);
        }
    }

    /** Run this recipe this many times, now. */
    public record Step(int recipeId, long times) {
        public Step {
            if (times <= 0L) {
                throw new IllegalArgumentException("a step running " + times + " times is not a step");
            }
        }
    }

    /**
     * An order, or the reason there is not one.
     *
     * <p>Never both, and never neither. {@code declined} is written for a player to read in a log,
     * because that is the difference between a one-line diagnosis and a two-hour investigation.
     */
    public record Result(List<Step> steps, @Nullable String declined) {
        public Result {
            steps = List.copyOf(steps);
        }

        public static Result ordered(final List<Step> steps) {
            return new Result(steps, null);
        }

        public static Result declined(final String reason) {
            return new Result(List.of(), reason);
        }

        public boolean succeeded() {
            return this.declined == null;
        }
    }

    public static Result order(final List<Recipe> recipes,
                               final Map<Integer, Long> iterations,
                               final Map<Integer, Long> startingStock) {
        return order(recipes, iterations, startingStock, DEFAULT_BUDGET);
    }

    /**
     * @param iterations   how many times each recipe id must run, from the solver
     * @param startingStock what is available before anything runs, per column — the plan's working
     *                      capital, which phase 03 already found
     * @param budget       batch attempts before the search gives up
     */
    public static Result order(final List<Recipe> recipes,
                               final Map<Integer, Long> iterations,
                               final Map<Integer, Long> startingStock,
                               final int budget) {
        final Map<Integer, Long> remaining = new LinkedHashMap<>();
        long total = 0L;
        for (final Recipe recipe : recipes) {
            final long times = iterations.getOrDefault(recipe.id(), 0L);
            if (times < 0L) {
                return Result.declined("recipe " + recipe.id() + " was asked to run " + times
                    + " times, which is not an order but a bug");
            }
            if (times > 0L) {
                remaining.put(recipe.id(), times);
                total = Math.addExact(total, times);
            }
        }
        if (total == 0L) {
            return Result.ordered(List.of());
        }

        final Map<Integer, Long> inventory = new LinkedHashMap<>(startingStock);
        final Map<Integer, Boolean> inCycle = cyclicRecipes(recipes);
        final List<Step> plan = new ArrayList<>();
        final int[] attemptsLeft = {budget};

        if (search(recipes, inCycle, remaining, inventory, total, plan, attemptsLeft)) {
            return Result.ordered(plan);
        }
        if (attemptsLeft[0] <= 0) {
            return Result.declined("no order found within " + budget + " attempts; the plan may still"
                + " be runnable, but proving it costs more than it is worth");
        }
        return Result.declined("no order can run this plan: " + stuck(recipes, remaining, inventory));
    }

    private static boolean search(final List<Recipe> recipes,
                                  final Map<Integer, Boolean> inCycle,
                                  final Map<Integer, Long> remaining,
                                  final Map<Integer, Long> inventory,
                                  final long total,
                                  final List<Step> plan,
                                  final int[] attemptsLeft) {
        if (total == 0L) {
            return true;
        }
        for (final Candidate candidate : candidates(recipes, inCycle, remaining, inventory)) {
            for (final long batch : batchAttempts(candidate)) {
                if (attemptsLeft[0]-- <= 0) {
                    return false;
                }
                apply(candidate.recipe(), batch, inventory);
                remaining.put(candidate.recipe().id(), candidate.remaining() - batch);
                append(plan, candidate.recipe(), batch, inCycle);

                if (search(recipes, inCycle, remaining, inventory, total - batch, plan, attemptsLeft)) {
                    return true;
                }

                shrink(plan, candidate.recipe(), batch);
                remaining.put(candidate.recipe().id(), candidate.remaining());
                rollback(candidate.recipe(), batch, inventory);
            }
        }
        return false;
    }

    /**
     * What can run now, best first.
     *
     * <p>Recipes inside a cycle come first, and that is the ordering insight worth keeping: the
     * bucket has to come back before the next one can be filled, so a cycle left until last is a
     * cycle that has already deadlocked. After that, larger affordable batches first — fewer, wider
     * steps — then the recipe with most left to do, then the player's priority, then the id so the
     * same plan always orders the same way.
     */
    private static List<Candidate> candidates(final List<Recipe> recipes,
                                              final Map<Integer, Boolean> inCycle,
                                              final Map<Integer, Long> remaining,
                                              final Map<Integer, Long> inventory) {
        final List<Candidate> candidates = new ArrayList<>();
        for (final Recipe recipe : recipes) {
            final long left = remaining.getOrDefault(recipe.id(), 0L);
            if (left <= 0L) {
                continue;
            }
            final long affordable = Math.min(left, maxAffordable(recipe, inventory));
            if (affordable > 0L) {
                candidates.add(new Candidate(recipe, left, affordable));
            }
        }
        candidates.sort(Comparator
            .<Candidate, Boolean>comparing(c -> inCycle.getOrDefault(c.recipe().id(), false)).reversed()
            .thenComparing(Candidate::affordable, Comparator.reverseOrder())
            .thenComparing(Candidate::remaining, Comparator.reverseOrder())
            .thenComparing(c -> c.recipe().priority(), Comparator.reverseOrder())
            .thenComparing(c -> c.recipe().id()));
        return candidates;
    }

    /**
     * The batch sizes worth trying, widest first.
     *
     * <p>All of it, then half, then one. Three attempts rather than every size between: the whole
     * batch is what makes the plan cheap to execute, one is what unblocks an ordering that needs
     * something else to run in between, and half is the compromise that resolves most of the rest.
     */
    private static List<Long> batchAttempts(final Candidate candidate) {
        final long max = candidate.affordable();
        final List<Long> attempts = new ArrayList<>(3);
        attempts.add(max);
        if (max > 2L) {
            attempts.add(max / 2L);
        }
        if (max > 1L) {
            attempts.add(1L);
        }
        return attempts;
    }

    private static long maxAffordable(final Recipe recipe, final Map<Integer, Long> inventory) {
        long batch = Long.MAX_VALUE;
        for (final Map.Entry<Integer, Long> need : recipe.consumed().entrySet()) {
            if (need.getValue() <= 0L) {
                continue;
            }
            batch = Math.min(batch, inventory.getOrDefault(need.getKey(), 0L) / need.getValue());
            if (batch == 0L) {
                return 0L;
            }
        }
        return batch;
    }

    private static void apply(final Recipe recipe, final long batch, final Map<Integer, Long> inventory) {
        recipe.consumed().forEach((column, amount) ->
            inventory.merge(column, -Math.multiplyExact(amount, batch), Long::sum));
        recipe.produced().forEach((column, amount) ->
            inventory.merge(column, Math.multiplyExact(amount, batch), Long::sum));
    }

    private static void rollback(final Recipe recipe, final long batch, final Map<Integer, Long> inventory) {
        recipe.produced().forEach((column, amount) ->
            inventory.merge(column, -Math.multiplyExact(amount, batch), Long::sum));
        recipe.consumed().forEach((column, amount) ->
            inventory.merge(column, Math.multiplyExact(amount, batch), Long::sum));
    }

    /**
     * Consecutive runs of the same recipe become one step — but never inside a cycle, where the
     * whole point of the order is that something else happens in between.
     */
    private static void append(final List<Step> plan,
                               final Recipe recipe,
                               final long batch,
                               final Map<Integer, Boolean> inCycle) {
        if (!inCycle.getOrDefault(recipe.id(), false) && !plan.isEmpty()) {
            final Step last = plan.getLast();
            if (last.recipeId() == recipe.id()) {
                plan.set(plan.size() - 1, new Step(recipe.id(), last.times() + batch));
                return;
            }
        }
        plan.add(new Step(recipe.id(), batch));
    }

    private static void shrink(final List<Step> plan, final Recipe recipe, final long batch) {
        if (plan.isEmpty()) {
            return;
        }
        final Step last = plan.getLast();
        if (last.recipeId() != recipe.id()) {
            return;
        }
        if (last.times() == batch) {
            plan.removeLast();
        } else {
            plan.set(plan.size() - 1, new Step(recipe.id(), last.times() - batch));
        }
    }

    /**
     * Which recipes sit on a cycle: those that can reach themselves through "I produce something
     * you consume".
     *
     * <p>Plain reachability rather than a strongly-connected-components algorithm. A pruned
     * subgraph is dozens of recipes, not thousands, and the quadratic version is one that can be
     * read and believed.
     */
    private static Map<Integer, Boolean> cyclicRecipes(final List<Recipe> recipes) {
        final Map<Integer, List<Integer>> edges = new LinkedHashMap<>();
        for (final Recipe from : recipes) {
            final List<Integer> targets = new ArrayList<>();
            for (final Recipe to : recipes) {
                if (from.produced().keySet().stream().anyMatch(to.consumed()::containsKey)) {
                    targets.add(to.id());
                }
            }
            edges.put(from.id(), targets);
        }
        final Map<Integer, Boolean> inCycle = new LinkedHashMap<>();
        for (final Recipe recipe : recipes) {
            inCycle.put(recipe.id(), reaches(recipe.id(), recipe.id(), edges, new java.util.HashSet<>()));
        }
        return inCycle;
    }

    private static boolean reaches(final int from,
                                   final int target,
                                   final Map<Integer, List<Integer>> edges,
                                   final java.util.Set<Integer> visited) {
        for (final int next : edges.getOrDefault(from, List.of())) {
            if (next == target) {
                return true;
            }
            if (visited.add(next) && reaches(next, target, edges, visited)) {
                return true;
            }
        }
        return false;
    }

    /** Names what the search was waiting for, for a message a player is meant to act on. */
    private static String stuck(final List<Recipe> recipes,
                                final Map<Integer, Long> remaining,
                                final Map<Integer, Long> inventory) {
        final Map<Integer, Long> shortest = new LinkedHashMap<>();
        for (final Recipe recipe : recipes) {
            if (remaining.getOrDefault(recipe.id(), 0L) <= 0L) {
                continue;
            }
            recipe.consumed().forEach((column, amount) -> {
                final long short_ = amount - inventory.getOrDefault(column, 0L);
                if (short_ > 0L) {
                    shortest.merge(column, short_, Math::max);
                }
            });
        }
        return shortest.isEmpty()
            ? "nothing can start, and no single resource explains it"
            : "waiting on " + shortest;
    }

    private record Candidate(Recipe recipe, long remaining, long affordable) {
    }
}
