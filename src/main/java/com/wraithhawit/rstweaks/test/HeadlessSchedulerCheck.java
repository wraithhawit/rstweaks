package com.wraithhawit.rstweaks.test;

import com.wraithhawit.rstweaks.ledger.Scheduler;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Self-tests for {@link Scheduler}, phase 04 of the ledger model. Run with
 * {@code ./gradlew schedulerCheck}.
 *
 * <p>The scheduler's claim is narrow and worth stating exactly: given iteration counts that already
 * balance, produce an order in which <b>every step is affordable at the moment it runs</b>. It does
 * not decide what to craft, and it does not promise the shortest order.
 *
 * <p>So the property everything below is built on is not the scheduler's own arithmetic — that
 * would be marking its own homework. It is an independent replay: walk the emitted steps against
 * the starting stock, and if any column ever goes negative, the order is wrong. A scheduler that
 * returns a plausible-looking order that deadlocks on step three is worse than one that declines.
 */
public final class HeadlessSchedulerCheck {
    private static final List<String> FAILURES = new ArrayList<>();
    private static int checks;

    // Columns, named for readability.
    private static final int BUCKET = 0;
    private static final int MILK = 1;
    private static final int MILK_BUCKET = 2;
    private static final int WHEAT = 3;
    private static final int CAKE = 4;
    private static final int TEMPLATE = 5;
    private static final int DIAMOND = 6;
    private static final int NETHERITE = 7;
    private static final int ARMOUR = 8;

    private HeadlessSchedulerCheck() {
    }

    public static void main(final String[] args) {
        final CraftingPlanSelfTest.Result result = run();
        System.out.println("scheduler checks: " + result.scenarios());
        if (result.passed()) {
            System.out.println("PASS");
            return;
        }
        result.failures().forEach(failure -> System.out.println("  FAIL " + failure));
        System.out.println(result.failures().size() + " of " + result.scenarios() + " checks failed");
        System.exit(1);
    }

    public static CraftingPlanSelfTest.Result run() {
        FAILURES.clear();
        checks = 0;

        nothingToDoIsAnEmptyOrder();
        theContainerCycleOrdersAndRuns();
        theSeedIsDuplicatedBeforeItIsSpent();
        theGreedyBatchIsATrapAndItBacktracks();
        aRolledBackBranchLeavesNothingBehind();
        anImpossibleOrderIsRefusedByName();
        anExhaustedBudgetSaysSoRatherThanGuessing();
        aStraightLineIsOneWideStep();
        aCycleIsNotMergedIntoOneStep();
        theOrderRunsEveryIterationTheSolverAskedFor();
        everyOrderReplaysWithoutGoingNegative();
        theHeuristicsKeepTheSearchCheap();

        return new CraftingPlanSelfTest.Result(checks, List.copyOf(FAILURES));
    }

    private static void nothingToDoIsAnEmptyOrder() {
        final Scheduler.Result result = Scheduler.order(List.of(fill()), Map.of(), Map.of());
        expect("a plan with no iterations is an empty order, not a refusal",
            result.succeeded() && result.steps().isEmpty());
    }

    /**
     * The cake case, and the reason ordering exists at all. Three buckets have to go round and
     * round: fill what you have, bake, get them back, fill again. Any order that fills all
     * ninety-six before baking is unrunnable, and the totals alone cannot tell you that.
     */
    private static void theContainerCycleOrdersAndRuns() {
        final List<Scheduler.Recipe> recipes = List.of(fill(), bake());
        final Map<Integer, Long> stock = stock(BUCKET, 3L, MILK, 96L, WHEAT, 96L);
        final Scheduler.Result result = Scheduler.order(recipes,
            Map.of(fill().id(), 96L, bake().id(), 32L), stock);

        expect("the cake cycle gets an order", result.succeeded());
        expect("and it runs against three buckets from start to finish",
            replay(result.steps(), recipes, stock) == null);
        expect("with more than one step, because three buckets cannot bake thirty-two cakes at once",
            result.steps().size() > 2);
    }

    /**
     * "You might use up your only smithing template before duping it." The template is consumed by
     * both recipes, so an order that spends it first has nothing left to duplicate and dead-ends —
     * which is exactly the backtrack this exists to make.
     */
    private static void theSeedIsDuplicatedBeforeItIsSpent() {
        final List<Scheduler.Recipe> recipes = List.of(duplicate(), useTemplate());
        final Map<Integer, Long> stock = stock(TEMPLATE, 1L, DIAMOND, 21L, NETHERITE, 4L);
        final Scheduler.Result result = Scheduler.order(recipes,
            Map.of(duplicate().id(), 3L, useTemplate().id(), 4L), stock);

        expect("the seeded plan gets an order", result.succeeded());
        expect("and it never spends a template it does not have",
            replay(result.steps(), recipes, stock) == null);
        expect("the duplication comes first",
            !result.steps().isEmpty() && result.steps().getFirst().recipeId() == duplicate().id());
    }

    /**
     * The case that makes backtracking do any work at all.
     *
     * <p>Everything above happens to succeed on the first choice, which a mutation run said out
     * loud: {@code rollback} and {@code shrink} could both be deleted and the suite stayed green.
     * The greedy has to be handed a batch that is a genuine trap.
     *
     * <p>Stamping turns a plate into a shell; assembling needs a plate <em>and</em> a shell and
     * hands two plates back. With two plates and two stampings to do, taking the whole affordable
     * batch — stamp twice — spends both plates and strands the assembly forever. The order that
     * works is stamp, assemble, stamp, and the only way to reach it is to roll the wide batch back
     * and try a narrow one.
     */
    private static void theGreedyBatchIsATrapAndItBacktracks() {
        final int plate = 10;
        final int shell = 11;
        final int widget = 12;
        final Scheduler.Recipe stamp = Scheduler.Recipe.of(30,
            Map.of(plate, 1L), Map.of(shell, 1L));
        final Scheduler.Recipe assemble = Scheduler.Recipe.of(31,
            Map.of(plate, 1L, shell, 1L), Map.of(plate, 2L, widget, 1L));
        final List<Scheduler.Recipe> recipes = List.of(stamp, assemble);
        final Map<Integer, Long> stock = stock(plate, 2L);

        final Scheduler.Result result = Scheduler.order(recipes,
            Map.of(stamp.id(), 2L, assemble.id(), 1L), stock);

        expect("the trap is escaped", result.succeeded());
        expect("and the order it found actually runs",
            replay(result.steps(), recipes, stock) == null);
        expect("the whole affordable batch was tried and rejected",
            !result.steps().isEmpty()
                && !(result.steps().getFirst().recipeId() == stamp.id()
                     && result.steps().getFirst().times() == 2L));
    }

    /**
     * A branch that failed must leave the inventory exactly as it found it.
     *
     * <p>Rolling back the inputs and forgetting the outputs is the subtle half, and it survives
     * every test above: the phantom items sit in the search's inventory, and mostly nothing needs
     * them. Here something does. Stamping three plates, assembling one and polishing one is
     * runnable, but only in an order the greedy reaches by dead-ending twice — and if the failed
     * branch leaves its two shells behind, the search believes it can polish early and emits an
     * order that the replay then refuses. Phantom stock is how a scheduler promises a craft out of
     * items that were never made.
     */
    private static void aRolledBackBranchLeavesNothingBehind() {
        final int plate = 10;
        final int shell = 11;
        final int widget = 12;
        final int trinket = 13;
        final Scheduler.Recipe stamp = Scheduler.Recipe.of(30,
            Map.of(plate, 1L), Map.of(shell, 1L));
        final Scheduler.Recipe assemble = Scheduler.Recipe.of(31,
            Map.of(plate, 1L, shell, 1L), Map.of(plate, 2L, widget, 1L));
        final Scheduler.Recipe polish = Scheduler.Recipe.of(32,
            Map.of(shell, 2L), Map.of(trinket, 1L));
        final List<Scheduler.Recipe> recipes = List.of(stamp, assemble, polish);
        final Map<Integer, Long> stock = stock(plate, 2L);

        final Scheduler.Result result = Scheduler.order(recipes,
            Map.of(stamp.id(), 3L, assemble.id(), 1L, polish.id(), 1L), stock);

        expect("the doubly-dead-ended plan is still ordered", result.succeeded());
        expect("and no step spends a shell that a rolled-back branch imagined",
            replay(result.steps(), recipes, stock) == null);
    }

    private static void anImpossibleOrderIsRefusedByName() {
        final List<Scheduler.Recipe> recipes = List.of(bake());
        final Scheduler.Result result = Scheduler.order(recipes,
            Map.of(bake().id(), 1L), stock(WHEAT, 96L));

        expect("a plan nothing can start is refused", !result.succeeded());
        expect("and the refusal names what it was waiting for",
            result.declined() != null && result.declined().contains("waiting on"));
    }

    /**
     * The budget is not a proof of impossibility, and must never be reported as one. A plan that
     * runs out of search says so in its own words, so a log reader can tell "cannot" from
     * "did not manage to".
     */
    private static void anExhaustedBudgetSaysSoRatherThanGuessing() {
        // The same cake cycle that succeeds above, with a budget too small to find its order.
        // Deliberately a solvable plan: a budget message on an unsolvable one would prove nothing,
        // since the search would have refused it anyway.
        final List<Scheduler.Recipe> recipes = List.of(fill(), bake());
        final Scheduler.Result result = Scheduler.order(recipes,
            Map.of(fill().id(), 96L, bake().id(), 32L),
            stock(BUCKET, 3L, MILK, 96L, WHEAT, 96L), 3);

        expect("a search that ran out of budget declines", !result.succeeded());
        expect("and says it was the budget, not impossibility",
            result.declined() != null && result.declined().contains("attempts"));
    }

    private static void aStraightLineIsOneWideStep() {
        final Scheduler.Recipe smelt = Scheduler.Recipe.of(20,
            Map.of(WHEAT, 1L), Map.of(CAKE, 1L));
        final Scheduler.Result result = Scheduler.order(List.of(smelt),
            Map.of(20, 64L), stock(WHEAT, 64L));

        expect("sixty-four iterations with nothing in the way are one step",
            result.succeeded() && result.steps().size() == 1);
        expect("and that step runs all sixty-four",
            result.steps().getFirst().times() == 64L);
    }

    /**
     * The other half of that: inside a cycle, merging would erase the ordering. Two fills and a
     * bake and two more fills is not "four fills and a bake", however much shorter that reads.
     */
    private static void aCycleIsNotMergedIntoOneStep() {
        final List<Scheduler.Recipe> recipes = List.of(fill(), bake());
        final Map<Integer, Long> stock = stock(BUCKET, 3L, MILK, 12L, WHEAT, 12L);
        final Scheduler.Result result = Scheduler.order(recipes,
            Map.of(fill().id(), 12L, bake().id(), 4L), stock);

        expect("a cycle keeps its steps apart", result.succeeded() && result.steps().size() >= 4);
        expect("and still runs", replay(result.steps(), recipes, stock) == null);
    }

    /** Ordering may not quietly change what the solver decided. */
    private static void theOrderRunsEveryIterationTheSolverAskedFor() {
        final List<Scheduler.Recipe> recipes = List.of(fill(), bake());
        final Map<Integer, Long> asked = Map.of(fill().id(), 96L, bake().id(), 32L);
        final Scheduler.Result result = Scheduler.order(recipes, asked,
            stock(BUCKET, 3L, MILK, 96L, WHEAT, 96L));

        final Map<Integer, Long> ran = new LinkedHashMap<>();
        result.steps().forEach(step -> ran.merge(step.recipeId(), step.times(), Long::sum));
        expect("every iteration the solver asked for is in the order, and no others",
            ran.equals(asked));
    }

    /**
     * The property, over random cycles.
     *
     * <p>Each trial builds a container cycle with a random number of containers and a random amount
     * of work, which is the shape most likely to deadlock. Every order that comes back is replayed
     * independently; a single negative balance anywhere is a failure. Orders that are declined are
     * counted, not failed — declining is allowed, returning a broken order is not.
     */
    private static void everyOrderReplaysWithoutGoingNegative() {
        final Random random = new Random(20260829L);
        int broken = 0;
        int declined = 0;
        for (int trial = 0; trial < 500; trial++) {
            final long bakes = 1L + random.nextInt(12);
            final long perBake = 1L + random.nextInt(4);
            // At least as many containers as one bake needs at once. Fewer is not "hard to
            // order", it is impossible -- you can never hold enough filled buckets at the same
            // time -- and asserting that the search finds an order for those would be asserting
            // something false.
            final long containers = perBake + random.nextInt(4);
            final Scheduler.Recipe fill = Scheduler.Recipe.of(1,
                Map.of(BUCKET, 1L, MILK, 1L), Map.of(MILK_BUCKET, 1L));
            final Scheduler.Recipe bake = Scheduler.Recipe.of(2,
                Map.of(MILK_BUCKET, perBake, WHEAT, 1L), Map.of(CAKE, 1L, BUCKET, perBake));
            final List<Scheduler.Recipe> recipes = List.of(fill, bake);
            final Map<Integer, Long> stock = stock(BUCKET, containers,
                MILK, bakes * perBake, WHEAT, bakes);

            final Scheduler.Result result = Scheduler.order(recipes,
                Map.of(1, bakes * perBake, 2, bakes), stock);
            if (!result.succeeded()) {
                declined++;
                continue;
            }
            if (replay(result.steps(), recipes, stock) != null) {
                broken++;
            }
        }
        expect("no order ever spends what it does not have, over five hundred random cycles",
            broken == 0);
        // A cycle with enough containers to run at all is always orderable, so a decline here
        // would mean the search is failing on plans that are plainly runnable.
        expect("and the search finds an order for all of them", declined == 0);
    }

    /**
     * That the candidate order and the batch sizes are actually earning their keep.
     *
     * <p>A mutation run made the point: the sort, the cycle-first preference and the choice of
     * batch sizes can all be broken without a single correctness test noticing, because the replay
     * property still holds — the search just backtracks more before it gets there. Heuristics are
     * not correctness, so they need a cost claim rather than a value claim.
     *
     * <p>So this is the same cycles as above under a budget far too small to brute-force. If a
     * heuristic stops working, the search runs out of attempts and this fails, which is the only
     * way any of it is pinned.
     */
    private static void theHeuristicsKeepTheSearchCheap() {
        final Random random = new Random(20260829L);
        int overBudget = 0;
        for (int trial = 0; trial < 200; trial++) {
            final long bakes = 1L + random.nextInt(12);
            final long perBake = 1L + random.nextInt(4);
            final long containers = perBake + random.nextInt(4);
            final Scheduler.Recipe fill = Scheduler.Recipe.of(1,
                Map.of(BUCKET, 1L, MILK, 1L), Map.of(MILK_BUCKET, 1L));
            final Scheduler.Recipe bake = Scheduler.Recipe.of(2,
                Map.of(MILK_BUCKET, perBake, WHEAT, 1L), Map.of(CAKE, 1L, BUCKET, perBake));
            final Map<Integer, Long> stock = stock(BUCKET, containers,
                MILK, bakes * perBake, WHEAT, bakes);

            if (!Scheduler.order(List.of(fill, bake),
                Map.of(1, bakes * perBake, 2, bakes), stock, 32).succeeded()) {
                overBudget++;
            }
        }
        expect("every random cycle is ordered within 32 attempts, so the heuristics are working",
            overBudget == 0);
    }

    // ------------------------------------------------------------------ fixtures

    private static Scheduler.Recipe fill() {
        return Scheduler.Recipe.of(1, Map.of(BUCKET, 1L, MILK, 1L), Map.of(MILK_BUCKET, 1L));
    }

    private static Scheduler.Recipe bake() {
        return Scheduler.Recipe.of(2,
            Map.of(MILK_BUCKET, 3L, WHEAT, 3L), Map.of(CAKE, 1L, BUCKET, 3L));
    }

    private static Scheduler.Recipe duplicate() {
        return Scheduler.Recipe.of(3,
            Map.of(TEMPLATE, 1L, DIAMOND, 7L), Map.of(TEMPLATE, 2L));
    }

    private static Scheduler.Recipe useTemplate() {
        return Scheduler.Recipe.of(4,
            Map.of(TEMPLATE, 1L, NETHERITE, 1L), Map.of(ARMOUR, 1L));
    }

    private static Map<Integer, Long> stock(final Object... pairs) {
        final Map<Integer, Long> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.merge((Integer) pairs[i], (Long) pairs[i + 1], Long::sum);
        }
        return out;
    }

    /**
     * Walks the order against the starting stock, independently of the scheduler.
     *
     * @return the first column that went negative, or {@code null} if the whole order ran
     */
    @Nullable
    private static String replay(final List<Scheduler.Step> steps,
                                 final List<Scheduler.Recipe> recipes,
                                 final Map<Integer, Long> startingStock) {
        final Map<Integer, Long> inventory = new LinkedHashMap<>(startingStock);
        for (final Scheduler.Step step : steps) {
            final Scheduler.Recipe recipe = recipes.stream()
                .filter(r -> r.id() == step.recipeId())
                .findFirst()
                .orElse(null);
            if (recipe == null) {
                return "step names recipe " + step.recipeId() + ", which is not in the plan";
            }
            for (final Map.Entry<Integer, Long> need : recipe.consumed().entrySet()) {
                final long have = inventory.getOrDefault(need.getKey(), 0L);
                final long wants = need.getValue() * step.times();
                if (have < wants) {
                    return "column " + need.getKey() + " short by " + (wants - have)
                        + " when recipe " + recipe.id() + " ran " + step.times() + " times";
                }
            }
            recipe.consumed().forEach((column, amount) ->
                inventory.merge(column, -amount * step.times(), Long::sum));
            recipe.produced().forEach((column, amount) ->
                inventory.merge(column, amount * step.times(), Long::sum));
        }
        return null;
    }

    private static void expect(final String what, final boolean ok) {
        checks++;
        if (!ok) {
            FAILURES.add(what);
        }
    }
}
