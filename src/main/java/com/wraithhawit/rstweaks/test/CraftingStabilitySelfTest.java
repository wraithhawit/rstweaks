package com.wraithhawit.rstweaks.test;

import com.wraithhawit.rstweaks.Config;
import com.wraithhawit.rstweaks.Stats;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Everything that can go wrong with crafting, asked all at once — {@code /rstweaks selftest
 * crafting}.
 *
 * <h2>What this is for</h2>
 *
 * <p>The other suites each answer one question. This one exists to answer "is autocrafting still
 * safe", in a real world, on demand, after a run of optimizations that touched Refined Storage's
 * core crafting loop. It runs every crafting-relevant suite and then does the thing none of them
 * do individually: <b>runs the task engine with each optimization switched off and switched on, and
 * requires both to pass.</b>
 *
 * <h2>Why the differential matters more than the suites</h2>
 *
 * <p>Every scenario in {@link TaskEngineSelfTest} passes with the optimizations off — that is what
 * it is for. So running it again with them on proves nothing unless something confirms they
 * actually engaged. Each differential therefore also asserts its <em>counter moved</em>: if the
 * optimization never fired, the second run was the first run again and this says so rather than
 * going green.
 *
 * <p>That check is not hypothetical. 0.14.0 shipped a counter that was never printed anywhere, and
 * three separate optimizations shipped with no coverage because the fixture contained no repeated
 * failing simulates at all until the "tool pattern blocked behind another pattern" scenario was
 * added for exactly that reason.
 *
 * <h2>What it still cannot tell you</h2>
 *
 * <p>Nothing here runs at the scale a real base does. The caches were cleared in game at 118 million
 * and 442 million checks by the {@code verifyReplayedDecisions} and {@code verifyStepSkip} config
 * flags, and those remain the stronger evidence. This is the fast sanity check, not a replacement
 * for them.
 */
public final class CraftingStabilitySelfTest {
    private static final List<String> FAILURES = new ArrayList<>();
    private static int checks;

    private CraftingStabilitySelfTest() {
    }

    public static CraftingPlanSelfTest.Result run() {
        FAILURES.clear();
        checks = 0;

        // The planner: does it produce a plan, and one that can actually be executed.
        include("crafting plan", CraftingPlanSelfTest.run());
        include("planner executability", PlannerExecutabilitySelfTest.run());
        include("max craftable", MaxCraftableSelfTest.run());
        include("ledger parity", LedgerParitySelfTest.run());
        include("pattern order", PatternOrderSelfTest.run());

        // The executor: real tasks through the real engine, audited for items created or destroyed.
        include("task engine", TaskEngineSelfTest.run());
        include("remainders", RemainderSelfTest.run());
        include("autocrafting requests", AutocraftingRequestSelfTest.run());
        include("resource list overflow", ResourceListOverflowSelfTest.run());

        // Durability against the real item registry rather than the fake, because the substitution
        // is the feature most of the recent work touched.
        include("durability", DurabilitySelfTest.run());

        final ExtractionSelfTest.Result extraction = ExtractionSelfTest.run();
        include("external extraction",
            new CraftingPlanSelfTest.Result(extraction.scenarios(), extraction.failures()));

        // Then every optimization, off against on.
        differential("durabilityAwarePlanning",
            () -> Config.durabilityAwarePlanning, on -> Config.durabilityAwarePlanning = on, null);
        differential("reuseSimulatedSubstitution",
            () -> Config.reuseSimulatedSubstitution,
            on -> Config.reuseSimulatedSubstitution = on,
            () -> Stats.substitutionScansAvoided);
        // Isolated from the step skip, which SUBSUMES it: once skipUnchangedSteps returns IDLE up
        // front, extractAll never runs and the substitution inside it is unreachable, so the replay
        // can never fire. That is not a conflict -- the outer skip is strictly better when it
        // applies -- but it means the replay is only reachable, and therefore only testable, with
        // the skip out of the way. It still matters as the fallback whenever skipUnchangedSteps is
        // switched off.
        final boolean skipping = Config.skipUnchangedSteps;
        try {
            Config.skipUnchangedSteps = false;
            differential("reuseFailedSimulate",
                () -> Config.reuseFailedSimulate,
                on -> Config.reuseFailedSimulate = on,
                () -> Stats.simulateDecisionsReplayed);
        } finally {
            Config.skipUnchangedSteps = skipping;
        }
        differential("skipUnchangedSteps",
            () -> Config.skipUnchangedSteps,
            on -> Config.skipUnchangedSteps = on,
            () -> Stats.stepsSkipped);
        differential("batchedExecution",
            () -> Config.batchedExecution,
            on -> Config.batchedExecution = on,
            () -> Stats.batchedIterations);

        return new CraftingPlanSelfTest.Result(checks, List.copyOf(FAILURES));
    }

    /**
     * Runs the task engine with one optimization off, then on, and requires both to pass.
     *
     * @param counter what must move on the ON run, or null when the optimization has no counter —
     *                {@code durabilityAwarePlanning} is a whole feature rather than a fast path, and
     *                the scenarios that depend on it fail outright when it is off
     */
    private static void differential(final String name,
                                     final BooleanSupplier get,
                                     final Consumer<Boolean> set,
                                     final java.util.function.Supplier<Long> counter) {
        final boolean original = get.getAsBoolean();
        try {
            set.accept(false);
            final CraftingPlanSelfTest.Result off = TaskEngineSelfTest.run();
            expect(name + " off: " + describe(off), off.failures().isEmpty());

            final long before = counter == null ? 0L : counter.get();
            set.accept(true);
            final CraftingPlanSelfTest.Result on = TaskEngineSelfTest.run();
            expect(name + " on: " + describe(on), on.failures().isEmpty());

            if (counter != null) {
                expect(name + " never engaged, so the ON run was the OFF run again",
                    counter.get() - before > 0L);
            }
        } finally {
            set.accept(original);
        }
    }

    private static String describe(final CraftingPlanSelfTest.Result result) {
        return result.failures().isEmpty()
            ? result.scenarios() + " scenarios"
            : String.join(" | ", result.failures());
    }

    private static void include(final String what, final CraftingPlanSelfTest.Result result) {
        checks += result.scenarios();
        result.failures().forEach(failure -> FAILURES.add(what + ": " + failure));
    }

    private static void expect(final String what, final boolean ok) {
        checks++;
        if (!ok) {
            FAILURES.add(what);
        }
    }
}
