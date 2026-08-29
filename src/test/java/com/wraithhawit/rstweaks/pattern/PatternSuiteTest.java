package com.wraithhawit.rstweaks.pattern;

import com.wraithhawit.rstweaks.test.CraftingPlanSelfTest;
import com.wraithhawit.rstweaks.test.HeadlessPatternOrderCheck;
import com.wraithhawit.rstweaks.test.HeadlessTraceCheck;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The existing pattern-ordering and calculation-trace scenarios, driven from JUnit.
 *
 * <p>Pattern ordering is worth measuring specifically: per {@code priorityqueue-pattern-order} it
 * was the root cause of a real lag incident, because {@code PriorityQueue.stream()} does not yield
 * priority order and Refined Storage searches alternatives in whatever order it is handed.
 */
class PatternSuiteTest {

    private static void expectClean(final CraftingPlanSelfTest.Result result) {
        assertTrue(result.scenarios() > 0, "suite ran no scenarios");
        assertTrue(result.failures().isEmpty(), () -> String.join("\n", result.failures()));
    }

    @Test
    void patternOrderScenarios() {
        expectClean(HeadlessPatternOrderCheck.run());
    }

    @Test
    void calculationTraceScenarios() {
        expectClean(HeadlessTraceCheck.run());
    }
}
