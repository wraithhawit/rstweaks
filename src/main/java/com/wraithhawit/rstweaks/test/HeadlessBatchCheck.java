package com.wraithhawit.rstweaks.test;

import com.wraithhawit.rstweaks.ledger.BatchPolicy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Self-tests for {@link BatchPolicy}, the decision half of phase 05. Run with
 * {@code ./gradlew batchCheck}.
 *
 * <p>The property is stated against the behaviour being replaced: <b>a batch must never run an
 * iteration that N serial steps could not, and must not give up throughput on the patterns it is
 * allowed to batch.</b> The serial side is simulated independently — extract if affordable, return
 * the outputs, repeat — rather than by asking {@link BatchPolicy} a second time.
 *
 * <p><b>What that property does not catch, said plainly.</b> Deleting the self-feeding rule
 * entirely leaves it green, and finding that out is what corrected the reasoning behind the rule.
 * A batch is bounded by what the task is holding, so it can never extract more than exists; a
 * batched catalyst takes sixty-four crystals and returns sixty-four, ending where serial ends. The
 * rule prevents <em>futile</em> batches, not losses, and the assertion that pins it is the one
 * below that reads the decision's stated reason. Two different claims, and only one of them is a
 * property.
 */
public final class HeadlessBatchCheck {
    private static final List<String> FAILURES = new ArrayList<>();
    private static int checks;

    private HeadlessBatchCheck() {
    }

    public static void main(final String[] args) {
        final CraftingPlanSelfTest.Result result = run();
        System.out.println("batch checks: " + result.scenarios());
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

        aPlainRecipeBatches();
        aCatalystRefusesToBatch();
        aWornToolRefusesToBatch();
        aContainerStillBatches();
        theStepBudgetCaps();
        whatTheTaskHoldsCaps();
        nothingToRunIsIdleNotABatchOfOne();
        aPatternWithNoInputsIsStillBounded();
        everyLimitIsNamed();
        aBatchNeverRunsMoreThanSerialWould();

        return new CraftingPlanSelfTest.Result(checks, List.copyOf(FAILURES));
    }

    private static void aPlainRecipeBatches() {
        final BatchPolicy.Decision decision = BatchPolicy.decide(
            Map.of(1, 4L), Set.of(2), Map.of(1, 400L), 64L, 1000L);
        expect("a recipe that does not feed itself batches", decision.batched());
        expect("and takes the whole plan when it can afford to", decision.iterations() == 64L);
    }

    /**
     * A catalyst runs serially.
     *
     * <p>This assertion, and not the property at the bottom, is what pins the self-feeding rule:
     * delete the rule and only this fails. Worth knowing which of the two is load-bearing, because
     * the property is the one that looks like it should be.
     */
    private static void aCatalystRefusesToBatch() {
        final BatchPolicy.Decision decision = BatchPolicy.decide(
            Map.of(1, 1L, 2, 4L), Set.of(1, 3), Map.of(1, 1L, 2, 256L), 64L, 1000L);
        expect("a catalyst runs serially", decision.iterations() == 1L);
        expect("and says why", decision.reason().contains("consumes what it produces"));
    }

    /**
     * A wearing tool, and the trap in getting there.
     *
     * <p>In <b>column</b> space every wear level of a crystal is one column, so the pattern plainly
     * consumes what it produces and the rule fires. In <b>resource</b> space — which is where the
     * executor lives — {@code crystal@0} and {@code crystal@1} are two different keys and the
     * intersection is empty, so the same pattern looks perfectly batchable. That is the trap, and
     * batching it would collapse our own wear-ageing into one step for N tools.
     */
    private static void aWornToolRefusesToBatch() {
        final int crystalColumn = 1;
        final int material = 3;
        final BatchPolicy.Decision pooled = BatchPolicy.decide(
            Map.of(crystalColumn, 1L, material, 1L),
            Set.of(crystalColumn, 4),
            Map.of(crystalColumn, 64L, material, 64L), 64L, 1000L);
        expect("pooled into one column, a wearing tool runs serially", pooled.iterations() == 1L);

        final int fresh = 1;
        final int worn = 2;
        expect("but the same tool in resource space does NOT look self-feeding, which is why the"
            + " executor must not use set intersection",
            !BatchPolicy.feedsItself(Map.of(fresh, 1L, material, 1L), Set.of(worn, 4)));

        final BatchPolicy.Decision told = BatchPolicy.decide(
            Map.of(fresh, 1L, material, 1L),
            Map.of(fresh, 64L, material, 64L), 64L, 1000L, true);
        expect("and a caller that knows better can say so", told.iterations() == 1L);
    }

    /**
     * The case that must <em>not</em> be caught by the rule. Filling buckets consumes empties and
     * produces filled ones; the empties come back from the recipe that drinks them, which is a
     * different pattern and a different step.
     */
    private static void aContainerStillBatches() {
        final int bucket = 1;
        final int milk = 2;
        final int milkBucket = 3;
        final BatchPolicy.Decision decision = BatchPolicy.decide(
            Map.of(bucket, 1L, milk, 1L), Set.of(milkBucket),
            Map.of(bucket, 8L, milk, 64L), 64L, 1000L);
        expect("a container pattern still batches", decision.batched());
        expect("up to the buckets it is holding", decision.iterations() == 8L);
    }

    private static void theStepBudgetCaps() {
        final BatchPolicy.Decision decision = BatchPolicy.decide(
            Map.of(1, 1L), Set.of(2), Map.of(1, 10_000L), 10_000L, 64L);
        expect("the step budget caps the batch", decision.iterations() == 64L);
        expect("and is named as the reason", decision.reason().contains("step budget"));
    }

    private static void whatTheTaskHoldsCaps() {
        final BatchPolicy.Decision decision = BatchPolicy.decide(
            Map.of(1, 3L), Set.of(2), Map.of(1, 10L), 64L, 1000L);
        expect("three per iteration out of ten is three iterations", decision.iterations() == 3L);
        expect("and it says what ran out", decision.reason().contains("holding"));
    }

    private static void nothingToRunIsIdleNotABatchOfOne() {
        final BatchPolicy.Decision empty = BatchPolicy.decide(
            Map.of(1, 1L), Set.of(2), Map.of(), 64L, 1000L);
        expect("with nothing in storage the answer is idle", empty.idle());

        final BatchPolicy.Decision done = BatchPolicy.decide(
            Map.of(1, 1L), Set.of(2), Map.of(1, 64L), 0L, 1000L);
        expect("with no iterations left the answer is idle", done.idle());
    }

    /**
     * A pattern with nothing to extract would otherwise be affordable {@link Long#MAX_VALUE} times,
     * and that number reaching a multiplication is an overflow waiting for a big enough plan.
     */
    private static void aPatternWithNoInputsIsStillBounded() {
        final BatchPolicy.Decision decision = BatchPolicy.decide(
            Map.of(), Set.of(1), Map.of(), 1_000_000L, 64L);
        expect("an input-free pattern is capped, not infinite", decision.iterations() == 64L);
    }

    private static void everyLimitIsNamed() {
        expect("a full-plan batch says so", BatchPolicy.decide(
            Map.of(1, 1L), Set.of(2), Map.of(1, 100L), 10L, 1000L).reason().contains("rest of the plan"));
    }

    /**
     * The property, and the reason this class exists.
     *
     * <p>Over random patterns and random stock: whatever batch is offered, N serial steps simulated
     * independently must be able to run at least that many. A batch larger than the serial run is
     * an extraction for a craft that could not have happened, which is items taken out of a
     * player's system for nothing.
     */
    private static void aBatchNeverRunsMoreThanSerialWould() {
        final Random random = new Random(20260829L);
        int tooMany = 0;
        int tooFew = 0;
        for (int trial = 0; trial < 3000; trial++) {
            final Map<Integer, Long> needs = new LinkedHashMap<>();
            for (int column = 1; column <= 1 + random.nextInt(3); column++) {
                needs.put(column, 1L + random.nextInt(4));
            }
            final Set<Integer> produces = new LinkedHashSet<>();
            for (int i = 0; i < 1 + random.nextInt(2); i++) {
                // Deliberately allowed to overlap the inputs, so self-feeding patterns are part of
                // the sample rather than something the fixture quietly avoids.
                produces.add(1 + random.nextInt(5));
            }
            final Map<Integer, Long> stock = new LinkedHashMap<>();
            for (int column = 1; column <= 5; column++) {
                stock.put(column, (long) random.nextInt(40));
            }
            final long left = 1L + random.nextInt(50);
            final long cap = 1L + random.nextInt(50);

            final long batch = BatchPolicy.decide(needs, produces, stock, left, cap).iterations();
            final long serial = serialRuns(needs, produces, stock, Math.min(left, cap));
            if (batch > serial) {
                tooMany++;
            }
            // Only meaningful for patterns that do not feed themselves: for the others, serial
            // legitimately runs further than a batch ever could, and refusing to batch is the
            // point rather than a shortfall.
            if (batch < serial && needs.keySet().stream().noneMatch(produces::contains)) {
                tooFew++;
            }
        }
        expect("a batch never runs an iteration the serial path could not, over three thousand"
            + " random patterns", tooMany == 0);
        expect("and gives up nothing on the patterns it is allowed to batch", tooFew == 0);
    }

    /** Serial stepping, simulated the way Refined Storage does it: extract, return outputs, repeat. */
    private static long serialRuns(final Map<Integer, Long> needs,
                                   final Set<Integer> produces,
                                   final Map<Integer, Long> stock,
                                   final long limit) {
        final Map<Integer, Long> pool = new LinkedHashMap<>(stock);
        long ran = 0L;
        while (ran < limit) {
            boolean affordable = true;
            for (final Map.Entry<Integer, Long> need : needs.entrySet()) {
                if (pool.getOrDefault(need.getKey(), 0L) < need.getValue()) {
                    affordable = false;
                    break;
                }
            }
            if (!affordable) {
                return ran;
            }
            needs.forEach((column, amount) -> pool.merge(column, -amount, Long::sum));
            produces.forEach(column -> pool.merge(column, 1L, Long::sum));
            ran++;
        }
        return ran;
    }

    private static void expect(final String what, final boolean ok) {
        checks++;
        if (!ok) {
            FAILURES.add(what);
        }
    }
}
