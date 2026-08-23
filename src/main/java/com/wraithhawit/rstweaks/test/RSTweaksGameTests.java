package com.wraithhawit.rstweaks.test;

import com.wraithhawit.rstweaks.ChatReporter;
import com.wraithhawit.rstweaks.RSTweaks;
import com.wraithhawit.rstweaks.Stats;

import java.util.List;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;

import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The checks that are worthless outside a running game, and the reason issue #2 exists.
 *
 * <p>{@code ./gradlew plannerCheck} runs the solver in a plain JVM in seconds, and it is
 * the right tool for anything made of arithmetic. It cannot touch a single line of
 * {@code mixin/}: nothing transforms Refined Storage's bytecode in a bare JVM, so a
 * headless run of the tests below would exercise stock RS and pass no matter what our
 * code does. Everything here therefore has to run inside a real game, which used to mean
 * a human launching Minecraft, doing something by hand and reporting back — nine round
 * trips in one day over the 0.2.63-0.2.81 bugs.
 *
 * <p><b>Run them with {@code ./gradlew runGameTestServer}.</b> That boots a dedicated
 * server with Refined Storage staged into its mods folder, runs every test in this class
 * and exits non-zero if any fail — no client, no world, no hand actions, about a minute.
 * {@code /rstweaks selftest} runs the same assertions inside a world you are already in,
 * for when the question is about a specific pack rather than about our code.
 *
 * <p>Registration is gated on {@code -Dneoforge.enabledGameTestNamespaces=rstweaks},
 * which the Gradle run sets; without it
 * {@link net.neoforged.neoforge.gametest.GameTestHooks#isGametestEnabled()} is false and
 * none of this is discoverable.
 *
 * <p>None of the assertions need blocks, so they share an empty 1x1x1 template. The
 * fixtures are built in memory out of Refined Storage's own API; the world is here
 * because gametests require one, and because being in one is what makes the mixins real.
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
    @GameTest(template = "empty", timeoutTicks = 400)
    public static void craftingPlanCopyOnWrite(final GameTestHelper helper) {
        report(helper, "crafting plan", CraftingPlanSelfTest.run());
    }

    /**
     * Asserts that a resource's candidate patterns come back in a stable, priority-respecting
     * order from Refined Storage's real repository.
     *
     * <p>Has to be a gametest: {@code patternOrderCheck} exercises the ordering logic against a
     * stand-in, but only a running game applies {@code PatternRepositoryImplMixin} and the
     * accessor on RS's private {@code PatternHolder} record. It is also the test that would
     * have caught the mixin-package mistake, where the accessor interface compiled, unit-tested
     * and built cleanly, then threw "cannot be referenced directly" at class-load time.
     */
    @GameTest(template = "empty", timeoutTicks = 400)
    public static void patternSearchOrderIsStable(final GameTestHelper helper) {
        report(helper, "pattern order", PatternOrderSelfTest.run());
    }

    /**
     * Runs crafting tasks to completion through Refined Storage's real task engine and
     * audits the network's contents afterwards.
     *
     * <p>The bug this is aimed at destroyed items in every build from 0.2.57 to 0.2.63
     * and was found by reading a player's log, not by any test. See
     * {@link TaskEngineSelfTest}.
     */
    @GameTest(template = "empty", timeoutTicks = 600)
    public static void craftingTaskDeliversItsOutput(final GameTestHelper helper) {
        report(helper, "task engine", TaskEngineSelfTest.run());
    }

    /**
     * Extracts from an external inventory that changes underneath the slot index, and
     * asserts the index never changes the answer — including on the stale-entry exit that
     * silently deleted items up to 0.2.55.
     */
    @GameTest(template = "empty", timeoutTicks = 600)
    public static void externalExtractionMatchesUnindexed(final GameTestHelper helper) {
        final ExtractionSelfTest.Result result = ExtractionSelfTest.run();
        report(helper, "external extraction",
            new CraftingPlanSelfTest.Result(result.scenarios(), result.failures()));
    }

    /**
     * How many tasks a repeatedly-asking Exporter really starts — issue #14's open
     * question, answered by counting rather than by reading the code.
     */
    @GameTest(template = "empty", timeoutTicks = 600)
    public static void repeatedRequestsStartOneTask(final GameTestHelper helper) {
        report(helper, "autocrafting requests", AutocraftingRequestSelfTest.run());
    }

    /**
     * A network total pushed past {@code Long.MAX_VALUE} saturates instead of wrapping
     * negative, and nothing else about the resource list changes.
     *
     * <p>The scenario this pins crashed LavaSurf's server on every grid open and every
     * autocraft, and survived a relog. It is also the one test here that would report a pass
     * for the wrong reason if it ever ran outside a game: without the mixin the first scenario
     * throws, which is the bug.
     */
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void resourceTotalsSaturateInsteadOfWrapping(final GameTestHelper helper) {
        report(helper, "resource list overflow", ResourceListOverflowSelfTest.run());
    }

    /**
     * {@code /rstweaks stats} prints one counter per line, and prints them all.
     *
     * <p>Cosmetic, and still worth pinning: the counters are the only evidence most people
     * will ever see that the mixins fired, and the report is assembled by index-splitting
     * strings, which is the sort of code that throws in front of a player rather than in a
     * test. Logged as well as asserted, so a run of this suite carries an example of what
     * the command actually looks like.
     */
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void statsPrintOnePerLine(final GameTestHelper helper) {
        // Whatever the suite has already done to these, plus one guaranteed non-zero
        // counter so the "nothing has fired" branch is not what gets measured.
        ++Stats.duplicateRequestsSuppressed;
        final List<Component> lines = ChatReporter.sessionTotals();
        lines.forEach(line -> RSTweaks.LOGGER.info("[rstweaks] /rstweaks stats | {}",
            line.getString()));

        if (lines.size() < 2) {
            helper.fail("expected a header and at least one counter, got " + lines.size()
                + " line(s): " + lines.stream().map(Component::getString).toList());
            return;
        }
        for (final Component line : lines) {
            if (line.getString().isBlank()) {
                helper.fail("blank line in the stats report");
                return;
            }
        }
        helper.succeed();
    }

    private static void report(final GameTestHelper helper,
                               final String what,
                               final CraftingPlanSelfTest.Result result) {
        if (result.passed()) {
            RSTweaks.LOGGER.info("[rstweaks] gametest {} passed ({} scenarios)",
                what, result.scenarios());
            helper.succeed();
            return;
        }
        // Logged as well as thrown: the gametest summary truncates, and a multi-line
        // ledger of what the network was left holding is the whole diagnostic.
        result.failures().forEach(failure ->
            RSTweaks.LOGGER.error("[rstweaks] {} FAILURE: {}", what, failure));
        helper.fail(what + " diverged in " + result.failures().size() + " of "
            + result.scenarios() + " scenarios: " + String.join(" | ", result.failures()));
    }
}
