package com.wraithhawit.rstweaks.planner;

import com.wraithhawit.rstweaks.test.CraftingPlanSelfTest;
import com.wraithhawit.rstweaks.test.MaxCraftableSelfTest;
import com.wraithhawit.rstweaks.test.PlannerExecutabilitySelfTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The existing headless planner suite, driven from JUnit so PIT can measure it.
 *
 * <p>Deliberately a thin wrapper and not a transcription. The point of this class is to establish
 * an honest baseline: {@code Rational}, {@code Simplex} and {@code BranchAndBound} have no direct
 * tests, only whole-plan scenarios that happen to exercise them. Whether that indirect coverage
 * actually pins their behaviour is exactly the question a mutation run answers, and it can only
 * answer it if the existing scenarios are among the tests it re-runs.
 *
 * <p>{@code run()} returns its failures rather than calling {@code System.exit}, which is what
 * makes this wrappable at all — the {@code Headless*Check} mains around it are not.
 */
class PlannerSuiteTest {

    @Test
    void plannerScenarios() {
        final CraftingPlanSelfTest.Result result = PlannerExecutabilitySelfTest.run();
        assertTrue(result.scenarios() > 0, "suite ran no scenarios");
        assertTrue(result.failures().isEmpty(), () -> String.join("\n", result.failures()));
    }

    @Test
    void maxCraftableScenarios() {
        final CraftingPlanSelfTest.Result result = MaxCraftableSelfTest.run();
        assertTrue(result.scenarios() > 0, "suite ran no scenarios");
        assertTrue(result.failures().isEmpty(), () -> String.join("\n", result.failures()));
    }

    @Test
    void craftingPlanScenarios() {
        final CraftingPlanSelfTest.Result result = CraftingPlanSelfTest.run();
        assertTrue(result.scenarios() > 0, "suite ran no scenarios");
        assertTrue(result.failures().isEmpty(), () -> String.join("\n", result.failures()));
    }
}
