package com.wraithhawit.rstweaks.backoff;

import com.wraithhawit.rstweaks.test.CraftingPlanSelfTest;
import com.wraithhawit.rstweaks.test.HeadlessBackoffCheck;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** The 96 existing backoff scenarios, driven from JUnit so a mutation run can measure them. */
class BackoffSuiteTest {

    @Test
    void backoffScenarios() {
        final CraftingPlanSelfTest.Result result = HeadlessBackoffCheck.run();
        assertTrue(result.scenarios() > 0, "suite ran no scenarios");
        assertTrue(result.failures().isEmpty(), () -> String.join("\n", result.failures()));
    }
}
