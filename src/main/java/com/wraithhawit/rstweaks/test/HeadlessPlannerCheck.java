package com.wraithhawit.rstweaks.test;

/**
 * Runs {@link PlannerExecutabilitySelfTest} from a plain JVM, with no Minecraft and no
 * mod loader, so the planner can be verified between builds instead of only in-game.
 *
 * <p>Kept in the mod source because it predates {@code src/test}, and because it is the entry
 * point that {@code ./gradlew plannerCheck} runs. The claim that once stood here -- that a second
 * source set could not reach the planner's compile classpath -- is no longer true. {@code src/test}
 * does exactly that, and {@code PlannerSuiteTest} drives these same suites from JUnit so that a
 * mutation run can measure what they actually pin.
 *
 * <p>Not referenced by any mod code — dead weight in the jar, and worth it.
 */
public final class HeadlessPlannerCheck {
    private HeadlessPlannerCheck() {
    }

    public static void main(final String[] args) {
        final CraftingPlanSelfTest.Result planner = PlannerExecutabilitySelfTest.run();
        final CraftingPlanSelfTest.Result maxAmount = MaxCraftableSelfTest.run();
        final CraftingPlanSelfTest.Result parity = LedgerParitySelfTest.run();

        final int scenarios = planner.scenarios() + maxAmount.scenarios() + parity.scenarios();
        final java.util.List<String> failures = new java.util.ArrayList<>(planner.failures());
        failures.addAll(maxAmount.failures());
        failures.addAll(parity.failures());

        System.out.println("scenarios: " + scenarios
            + "  (planner " + planner.scenarios() + ", max-craftable " + maxAmount.scenarios()
            + ", ledger parity " + parity.scenarios() + ")");

        if (failures.isEmpty()) {
            System.out.println("PASS");
            return;
        }
        System.out.println("FAIL (" + failures.size() + ")");
        failures.forEach(f -> System.out.println("  - " + f));
        System.exit(1);
    }
}
