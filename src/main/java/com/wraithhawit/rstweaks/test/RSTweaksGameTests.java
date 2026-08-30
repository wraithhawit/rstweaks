package com.wraithhawit.rstweaks.test;

import com.wraithhawit.rstweaks.ChatReporter;
import com.wraithhawit.rstweaks.Config;
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

    /**
     * The ledger model's one game-side adapter, asked of the real item registry.
     *
     * <p>Since 0.7.2 the planner reads {@code Remainder.Holder} on the autocrafting path, so what
     * {@code ItemRemainder} answers here is what it reasons with. No headless suite can ask this:
     * they all install a fake, and a fixture agreeing with itself says nothing about the adapter.
     */
    @GameTest(template = "empty", timeoutTicks = 400)
    public static void craftingRemaindersAreReadFromTheGame(final GameTestHelper helper) {
        report(helper, "remainder", RemainderSelfTest.run());
    }

    /**
     * Batched stepping, against the real task engine, differentially.
     *
     * <p>Every scenario in the task-engine suite already passes with batching off -- that is what
     * it is for -- so running it again with batching on proves nothing on its own. The counter is
     * what makes it a test: if no iteration was batched, the second run was the first run and the
     * assertion says so instead of going green.
     */
    @GameTest(template = "empty", timeoutTicks = 400)
    public static void batchedSteppingMatchesSerial(final GameTestHelper helper) {
        final boolean originalBatching = Config.batchedExecution;
        final long before = Stats.batchedIterations;
        final CraftingPlanSelfTest.Result batched;
        try {
            Config.batchedExecution = true;
            batched = TaskEngineSelfTest.run();
        } finally {
            Config.batchedExecution = originalBatching;
        }
        final long ran = Stats.batchedIterations - before;
        if (ran == 0L) {
            helper.fail("batched stepping never engaged, so this run was the serial run again");
            return;
        }
        RSTweaks.LOGGER.info("[rstweaks] gametest batched {} iterations across {} scenarios",
            ran, batched.scenarios());
        report(helper, "batched stepping", batched);
    }

    /**
     * The substitution probe, against the real task engine.
     *
     * <p>The probe only writes on the EXECUTE half of a pair, so "switched on but never reached"
     * and "switched off" produce identical output — and this project has already shipped three
     * things whose presence was indistinguishable from their absence. This asserts the counters
     * actually move, and that they add up: every pair is either an agreement or a disagreement.
     *
     * <p>It deliberately does <em>not</em> assert zero disagreements. Whether the two passes agree
     * is the open question the probe exists to answer in a real world, at real scale, on a real
     * tool chain; pinning the answer here would be pinning the fixture's answer, not the game's.
     */
    @GameTest(template = "empty", timeoutTicks = 400)
    public static void theSubstitutionProbeActuallyFires(final GameTestHelper helper) {
        final boolean original = Config.substitutionProbe;
        final long beforePairs = Stats.substitutionPairs;
        final long beforeAgreed = Stats.substitutionAgreed;
        final long beforeDisagreed = Stats.substitutionDisagreed;
        final CraftingPlanSelfTest.Result result;
        try {
            Config.substitutionProbe = true;
            result = TaskEngineSelfTest.run();
        } finally {
            Config.substitutionProbe = original;
        }
        final long pairs = Stats.substitutionPairs - beforePairs;
        if (pairs == 0L) {
            helper.fail("the substitution probe never saw a SIMULATE/EXECUTE pair, so it measures "
                + "nothing and a clean result from it would mean nothing");
            return;
        }
        final long accounted = (Stats.substitutionAgreed - beforeAgreed)
            + (Stats.substitutionDisagreed - beforeDisagreed);
        if (accounted != pairs) {
            helper.fail("the probe counted " + pairs + " pairs but classified " + accounted
                + "; the counters do not add up and neither would the verdict");
            return;
        }
        RSTweaks.LOGGER.info("[rstweaks] gametest substitution probe saw {} pairs ({} agreed, "
                + "{} disagreed) across {} scenarios",
            pairs, Stats.substitutionAgreed - beforeAgreed,
            Stats.substitutionDisagreed - beforeDisagreed, result.scenarios());
        report(helper, "substitution probe", result);
    }

    /**
     * {@link com.wraithhawit.rstweaks.storage.ItemDurability} against the real item registry.
     *
     * <p>The task-engine scenarios install a fake, so the class that answers this in game had no
     * coverage until its {@code damage()} was rewritten to read the component patch directly.
     */
    @GameTest(template = "empty", timeoutTicks = 400)
    public static void durabilityIsReadFromTheGame(final GameTestHelper helper) {
        report(helper, "durability", DurabilitySelfTest.run());
    }

    /**
     * Headroom under Minecraft's 1024-character component limit, leaving room for the prefix this
     * message builds around the detail.
     */
    private static final int MAX_FAILURE_DETAIL = 700;

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
        // Bounded, because Minecraft refuses a component string over 1024 characters and throws
        // IllegalStateException building the failure message. A suite with a wide matrix in it can
        // fail on dozens of scenarios at once, and the result was that a legitimate FAILURE came
        // out as a crash inside the reporter -- the one moment the diagnostic matters most. The
        // full list is in the log lines above; this is the summary.
        final String detail = String.join(" | ", result.failures());
        helper.fail(what + " diverged in " + result.failures().size() + " of "
            + result.scenarios() + " scenarios (full list in the log): "
            + (detail.length() > MAX_FAILURE_DETAIL
                ? detail.substring(0, MAX_FAILURE_DETAIL) + " ..."
                : detail));
    }
}
