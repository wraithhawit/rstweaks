package com.wraithhawit.rstweaks.test;

import com.wraithhawit.rstweaks.RSTweaks;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Gametest wrapper around {@link CraftingPlanSelfTest}.
 *
 * <p>Run with {@code /test run rstweaks:crafting_plan_copy_on_write}. Note that
 * NeoForge only registers gametests when the launch flag
 * {@code -Dneoforge.enabledGameTestNamespaces=rstweaks} is present — without it
 * {@link net.neoforged.neoforge.gametest.GameTestHooks#isGametestEnabled()} returns
 * false and nothing here is discoverable. Use {@code /rstweaks selftest} instead if
 * you would rather not add a launch flag; it runs the identical checks.
 *
 * <p>The assertions need no blocks, so this uses an empty 1x1x1 template. Everything
 * under test is plain records from the Refined Storage API — the world is only here
 * because gametests require one.
 */
@GameTestHolder(RSTweaks.MODID)
@PrefixGameTestTemplate(false)
public final class RSTweaksGameTests {
    private RSTweaksGameTests() {
    }

    /**
     * Asserts that the copy-on-write pattern-plan optimization produces byte-identical
     * plans to the unoptimized path across every scenario.
     */
    @GameTest(template = "rstweaks:empty", timeoutTicks = 400)
    public static void craftingPlanCopyOnWrite(final GameTestHelper helper) {
        final CraftingPlanSelfTest.Result result = CraftingPlanSelfTest.run();
        if (result.passed()) {
            RSTweaks.LOGGER.info("[rstweaks] gametest passed ({} scenarios)", result.scenarios());
            helper.succeed();
            return;
        }
        helper.fail("crafting plan diverged in " + result.failures().size()
            + " of " + result.scenarios() + " scenarios: " + String.join(" | ", result.failures()));
    }
}
