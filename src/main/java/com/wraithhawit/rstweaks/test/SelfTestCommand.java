package com.wraithhawit.rstweaks.test;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.wraithhawit.rstweaks.ChatReporter;
import com.wraithhawit.rstweaks.Stats;
import com.wraithhawit.rstweaks.RSTweaks;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * {@code /rstweaks selftest} — runs {@link CraftingPlanSelfTest} and reports in chat.
 *
 * <p>This exists alongside the gametest because gametests only register when
 * {@code neoforge.enabledGameTestNamespaces} includes this mod, which is not set on
 * a normal instance. The command needs no launch flags and runs in whatever world
 * you are already in, so it is the one that actually gets used.
 */
public final class SelfTestCommand {
    private SelfTestCommand() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(final RegisterCommandsEvent event) {
        final LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("rstweaks")
            .requires(source -> source.hasPermission(2))
            // Counters on demand. The periodic summary only fires every few minutes and
            // stays silent when nothing changed, which makes "is this actually running?"
            // an awkward question to answer mid-test.
            // Counters on demand, one per line. Nothing posts them unprompted any more --
            // the periodic broadcast is off by default as of 0.2.94 -- so this is the way
            // to see them.
            .then(Commands.literal("stats").executes(context -> {
                final CommandSourceStack source = context.getSource();
                ChatReporter.sessionTotals().forEach(line -> source.sendSuccess(() -> line, false));
                // The names, not just the count. A single exporter asking for something
                // uncraftable runs a full recursive crafting calculation every recheck, which on a
                // network with many patterns can take seconds -- and nothing in game otherwise says
                // what is being asked for. Finding and fixing that exporter is the only thing that
                // ends the cost; making the calculation faster only makes it hurt less often.
                synchronized (Stats.class) {
                    if (!Stats.uncraftableResources.isEmpty()) {
                        source.sendSuccess(() -> Component.literal(
                            "[rstweaks] cannot be autocrafted (something keeps asking):")
                            .withStyle(ChatFormatting.YELLOW), false);
                        Stats.uncraftableResources.forEach((name, count) -> source.sendSuccess(
                            () -> Component.literal("  " + name + "  x" + count)
                                .withStyle(ChatFormatting.GRAY), false));
                    }
                }
                return 1;
            }))
            // Everything crafting-related in one place, including each optimization run off
            // against on. Separate from the plain selftest because this is the one to reach for
            // before trusting a build with real items in it, and because it is slower: it runs the
            // task engine six times over.
            .then(Commands.literal("selftest")
                .then(Commands.literal("crafting").executes(context -> {
                    final CommandSourceStack source = context.getSource();
                    source.sendSuccess(() -> Component.literal(
                            "[rstweaks] running crafting stability suite -- every crafting self-test,"
                                + " then each optimization off against on...")
                        .withStyle(ChatFormatting.GRAY), false);
                    final CraftingPlanSelfTest.Result result = CraftingStabilitySelfTest.run();
                    if (result.failures().isEmpty()) {
                        source.sendSuccess(() -> Component.literal("[rstweaks] crafting stable: "
                                + result.scenarios() + " checks, no failures")
                            .withStyle(ChatFormatting.GREEN), false);
                        return 1;
                    }
                    source.sendFailure(Component.literal("[rstweaks] CRAFTING UNSTABLE: "
                        + result.failures().size() + " of " + result.scenarios()
                        + " checks failed").withStyle(ChatFormatting.RED));
                    // One per line, and every one of them: this is the output somebody acts on,
                    // and a truncated list of what is wrong with autocrafting is worse than none.
                    result.failures().forEach(failure -> source.sendFailure(
                        Component.literal("  " + failure).withStyle(ChatFormatting.RED)));
                    RSTweaks.LOGGER.error("[rstweaks] crafting stability suite failed: {}",
                        result.failures());
                    return 0;
                }))
                .executes(context -> {
                final CommandSourceStack source = context.getSource();
                source.sendSuccess(() -> Component.literal("[rstweaks] running crafting plan self-test...")
                    .withStyle(ChatFormatting.GRAY), false);

                final CraftingPlanSelfTest.Result plans = CraftingPlanSelfTest.run();
                final ExtractionSelfTest.Result extraction = ExtractionSelfTest.run();
                // Runs regardless of whether lpPlanner is enabled on this server: it
                // flips the flag itself and restores it. A planner bug should be
                // findable before you turn the planner on, not after.
                final CraftingPlanSelfTest.Result lp = PlannerExecutabilitySelfTest.run();
                // Real tasks through the real executor. Only meaningful with the mixins
                // applied, which is true here and false under ./gradlew plannerCheck.
                final CraftingPlanSelfTest.Result tasks = TaskEngineSelfTest.run();

                final int scenarios = plans.scenarios() + extraction.scenarios()
                    + lp.scenarios() + tasks.scenarios();
                final List<String> failures = new ArrayList<>(plans.failures());
                extraction.failures().forEach(f -> failures.add("extraction: " + f));
                lp.failures().forEach(f -> failures.add("lp planner: " + f));
                tasks.failures().forEach(f -> failures.add("task engine: " + f));

                if (failures.isEmpty()) {
                    source.sendSuccess(() -> Component.literal(
                        "[rstweaks] PASS - " + scenarios + " scenarios ("
                            + plans.scenarios() + " crafting plans, "
                            + extraction.scenarios() + " extractions, "
                            + lp.scenarios() + " LP plans, "
                            + tasks.scenarios() + " crafting tasks)")
                        .withStyle(ChatFormatting.GREEN), false);
                    RSTweaks.LOGGER.info("[rstweaks] self-test PASSED ({} scenarios)", scenarios);
                    return scenarios;
                }

                source.sendFailure(Component.literal(
                    "[rstweaks] FAIL - " + failures.size() + " of " + scenarios + " diverged")
                    .withStyle(ChatFormatting.RED));
                for (final String failure : failures) {
                    source.sendFailure(Component.literal("  " + failure).withStyle(ChatFormatting.RED));
                    RSTweaks.LOGGER.error("[rstweaks] self-test failure: {}", failure);
                }
                return 0;
            }));
        event.getDispatcher().register(root);
    }
}
