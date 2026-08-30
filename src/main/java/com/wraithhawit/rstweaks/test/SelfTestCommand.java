package com.wraithhawit.rstweaks.test;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.wraithhawit.rstweaks.ChatReporter;
import com.wraithhawit.rstweaks.Stats;
import com.wraithhawit.rstweaks.RSTweaks;


import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
  * {@code /rstweaks selftest} — runs every self-test and reports in chat; a name after it narrows
 * the run to one category or group. See {@link SelfTests} for the catalogue.
 *
 * <p>This exists alongside the gametest because gametests only register when
 * {@code neoforge.enabledGameTestNamespaces} includes this mod, which is not set on
 * a normal instance. The command needs no launch flags and runs in whatever world
 * you are already in, so it is the one that actually gets used.
 */
public final class SelfTestCommand {
    private SelfTestCommand() {
    }

    /**
     * Prints a report and returns the command result.
     *
     * <p>Every failure is printed, never a summary of them: this is the output somebody acts on,
     * and a truncated list of what is wrong with autocrafting is worse than none. The per-category
     * breakdown goes out on a pass as well, because "392 checks" says nothing about whether the
     * category you cared about actually ran.
     */
    private static int report(final CommandSourceStack source, final String what,
                              final SelfTests.Report result) {
        if (result.passed()) {
            source.sendSuccess(() -> Component.literal("[rstweaks] " + what + " PASSED - "
                + result.scenarios() + " checks").withStyle(ChatFormatting.GREEN), false);
            result.perCategory().forEach((category, count) -> source.sendSuccess(
                () -> Component.literal("  " + category + ": " + count)
                    .withStyle(ChatFormatting.DARK_GRAY), false));
            RSTweaks.LOGGER.info("[rstweaks] {} passed ({} checks) {}", what, result.scenarios(),
                result.perCategory());
            return result.scenarios();
        }
        source.sendFailure(Component.literal("[rstweaks] " + what + " FAILED - "
            + result.failures().size() + " of " + result.scenarios() + " checks")
            .withStyle(ChatFormatting.RED));
        for (final String failure : result.failures()) {
            source.sendFailure(Component.literal("  " + failure).withStyle(ChatFormatting.RED));
            RSTweaks.LOGGER.error("[rstweaks] {} failure: {}", what, failure);
        }
        return 0;
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
            // Bare selftest runs EVERYTHING. A name after it narrows to one category or group --
            // /rstweaks selftest crafting -- for when you already know which half of the mod you
            // are suspicious of and would rather not wait for the rest.
            //
            // The categories are disjoint, so a full run never executes the same scenario twice and
            // the total is a real total. See SelfTests for the catalogue.
            .then(Commands.literal("selftest")
                .then(Commands.argument("category", StringArgumentType.word())
                    .suggests((context, builder) -> {
                        SelfTests.names().forEach(builder::suggest);
                        return builder.buildFuture();
                    })
                    .executes(context -> {
                        final CommandSourceStack source = context.getSource();
                        final String category = StringArgumentType.getString(context, "category");
                        final SelfTests.Report report = SelfTests.run(category);
                        if (report == null) {
                            source.sendFailure(Component.literal("[rstweaks] no such self-test: "
                                + category + ". Try one of: "
                                + String.join(", ", SelfTests.names()))
                                .withStyle(ChatFormatting.RED));
                            return 0;
                        }
                        return report(source, "self-test [" + category + "]", report);
                    }))
                .executes(context -> {
                    final CommandSourceStack source = context.getSource();
                    source.sendSuccess(() -> Component.literal(
                            "[rstweaks] running the full self-test suite; add a name to narrow it ("
                                + String.join(", ", SelfTests.names()) + ")")
                        .withStyle(ChatFormatting.GRAY), false);
                    return report(source, "full self-test", SelfTests.runAll());
                }));
        event.getDispatcher().register(root);
    }
}
