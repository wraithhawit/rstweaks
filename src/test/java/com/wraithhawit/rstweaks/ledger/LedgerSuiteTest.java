package com.wraithhawit.rstweaks.ledger;

import com.wraithhawit.rstweaks.test.CraftingPlanSelfTest;
import com.wraithhawit.rstweaks.test.HeadlessLedgerCheck;
import com.wraithhawit.rstweaks.test.HeadlessSchedulerCheck;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ledger suite, driven from JUnit so PIT can measure it.
 *
 * <p>A thin wrapper, like {@code PlannerSuiteTest}. It exists because a suite that passes on its
 * first run proves nothing on its own — this project has already shipped two tests that were green
 * with the feature ripped out — and a mutation run is the cheapest way to find out whether these
 * scenarios actually fail when the model is wrong. Run it with
 * {@code ./gradlew mutationTest -PpitTarget=com.wraithhawit.rstweaks.ledger.*}.
 */
class LedgerSuiteTest {

    @Test
    void ledgerScenarios() {
        final CraftingPlanSelfTest.Result result = HeadlessLedgerCheck.run();
        assertTrue(result.scenarios() > 0, "suite ran no scenarios");
        assertTrue(result.failures().isEmpty(), () -> String.join("\n", result.failures()));
    }

    @Test
    void schedulerScenarios() {
        final CraftingPlanSelfTest.Result result = HeadlessSchedulerCheck.run();
        assertTrue(result.scenarios() > 0, "suite ran no scenarios");
        assertTrue(result.failures().isEmpty(), () -> String.join("\n", result.failures()));
    }
}
