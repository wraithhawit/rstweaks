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
        final CraftingPlanSelfTest.Result result = PlannerExecutabilitySelfTest.run();
        System.out.println("scenarios: " + result.scenarios());
        if (result.failures().isEmpty()) {
            System.out.println("PASS");
            return;
        }
        System.out.println("FAIL (" + result.failures().size() + ")");
        result.failures().forEach(f -> System.out.println("  - " + f));
        System.exit(1);
    }
}
