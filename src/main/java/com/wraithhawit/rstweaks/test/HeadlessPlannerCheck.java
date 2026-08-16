package com.wraithhawit.rstweaks.test;

/**
 * Runs {@link PlannerExecutabilitySelfTest} from a plain JVM, with no Minecraft and no
 * mod loader, so the planner can be verified between builds instead of only in-game.
 *
 * <p>Kept in the mod source rather than a test source set because the planner's
 * dependencies are the Refined Storage API and nothing else, and a second source set
 * would need its own copy of the compile classpath to reach it.
 *
 * <p>Not referenced by any mod code — dead weight in the jar, and worth it.
 */
public final class HeadlessPlannerCheck {
    private HeadlessPlannerCheck() {
    }

    public static void main(final String[] args) {
        final CraftingPlanSelfTest.Result planner = PlannerExecutabilitySelfTest.run();
        final CraftingPlanSelfTest.Result maxAmount = MaxCraftableSelfTest.run();

        final int scenarios = planner.scenarios() + maxAmount.scenarios();
        final java.util.List<String> failures = new java.util.ArrayList<>(planner.failures());
        failures.addAll(maxAmount.failures());

        System.out.println("scenarios: " + scenarios
            + "  (planner " + planner.scenarios() + ", max-craftable " + maxAmount.scenarios() + ")");
        if (failures.isEmpty()) {
            System.out.println("PASS");
            return;
        }
        System.out.println("FAIL (" + failures.size() + ")");
        failures.forEach(f -> System.out.println("  - " + f));
        System.exit(1);
    }
}
