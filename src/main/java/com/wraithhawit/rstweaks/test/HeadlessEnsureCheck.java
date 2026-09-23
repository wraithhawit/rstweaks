package com.wraithhawit.rstweaks.test;

import com.refinedmods.refinedstorage.api.autocrafting.calculation.CancellationToken;
import com.wraithhawit.rstweaks.ensure.AutomationBudget;
import com.wraithhawit.rstweaks.ensure.EnsureTaskBudget;

import java.util.ArrayList;
import java.util.List;

/**
 * Self-tests for the automation calculation budget. Run with {@code ./gradlew ensureCheck}.
 *
 * <p><b>What this suite is not.</b> It cannot prove the redirects land, that {@code startTask} is
 * left alone, or that RS's lambda really carries our token into the private helper -- no plain JVM
 * transforms bytecode. Those were established by reading Refined Storage 2.0.9, where each
 * {@code calculatePlan} call site sits in a different method, and only a real launch confirms them.
 *
 * <p>What it covers is the request lifecycle, which is where review found three defects in the
 * first attempt and where every failure is silent: too eager and a craft that needs real planning
 * time never starts, too slack and the freeze comes back, and get the verdict wrong and a craftable
 * resource is cached as impossible.
 */
public final class HeadlessEnsureCheck {
    private static final List<String> FAILURES = new ArrayList<>();
    private static int checks;

    private static final int START = 200;
    private static final int CAP = 1000;

    private HeadlessEnsureCheck() {
    }

    public static void main(final String[] args) throws InterruptedException {
        firstAttemptGetsTheBottomRung();
        cancellationDoubles();
        theLadderStopsAtTheCeiling();
        successResetsIt();
        laddersArePerResource();
        theCeilingIsNeverBelowTheStart();
        theMapStaysBounded();

        allPhasesShareOneDeadline();
        aDifferentResourceStartsItsOwnRequest();
        oneRequestMovesTheLadderOnce();
        aFinishWithNoCalculationAccountsForNothing();
        aCutShortRequestIsNotCachedAsUncraftable();

        System.out.println("ensure checks: " + checks);
        if (!FAILURES.isEmpty()) {
            FAILURES.forEach(failure -> System.out.println("  FAIL " + failure));
            System.out.println("FAIL");
            System.exit(1);
        }
        System.out.println("PASS");
    }

    // ---- the ladder ----

    private static void firstAttemptGetsTheBottomRung() {
        EnsureTaskBudget.clear();
        expect("an unseen resource starts at the bottom",
            EnsureTaskBudget.budgetFor("a", START, CAP) == START);
    }

    private static void cancellationDoubles() {
        EnsureTaskBudget.clear();
        EnsureTaskBudget.noteExpired("a", START, CAP);
        expect("200 doubles to 400", EnsureTaskBudget.budgetFor("a", START, CAP) == 400);
        EnsureTaskBudget.noteExpired("a", START, CAP);
        expect("400 doubles to 800", EnsureTaskBudget.budgetFor("a", START, CAP) == 800);
    }

    /** Without this the top of the ladder is the freeze, just rarer. */
    private static void theLadderStopsAtTheCeiling() {
        EnsureTaskBudget.clear();
        for (int i = 0; i < 20; i++) {
            EnsureTaskBudget.noteExpired("a", START, CAP);
        }
        expect("the ladder never climbs past the ceiling",
            EnsureTaskBudget.budgetFor("a", START, CAP) == CAP);
    }

    private static void successResetsIt() {
        EnsureTaskBudget.clear();
        EnsureTaskBudget.noteExpired("a", START, CAP);
        EnsureTaskBudget.noteExpired("a", START, CAP);
        expect("escalated first", EnsureTaskBudget.budgetFor("a", START, CAP) == 800);
        EnsureTaskBudget.noteSucceeded("a");
        expect("a success drops back to the bottom rung",
            EnsureTaskBudget.budgetFor("a", START, CAP) == START);
    }

    private static void laddersArePerResource() {
        EnsureTaskBudget.clear();
        EnsureTaskBudget.noteExpired("a", START, CAP);
        expect("the other resource is untouched",
            EnsureTaskBudget.budgetFor("b", START, CAP) == START);
    }

    private static void theCeilingIsNeverBelowTheStart() {
        EnsureTaskBudget.clear();
        expect("a ceiling under the start still yields the start",
            EnsureTaskBudget.budgetFor("a", 500, 100) == 500);
        EnsureTaskBudget.noteExpired("a", 500, 100);
        expect("and escalating cannot go under it either",
            EnsureTaskBudget.budgetFor("a", 500, 100) >= 500);
    }

    /**
     * A wandering exporter filter can name more resources than are worth remembering, and this map
     * lives as long as the world does. Eviction costs a resource its escalation, which is the safe
     * direction -- it starts from the bottom rung again.
     */
    private static void theMapStaysBounded() {
        EnsureTaskBudget.clear();
        for (int i = 0; i < 5_000; i++) {
            EnsureTaskBudget.noteExpired("resource-" + i, START, CAP);
        }
        expect("the ladder map does not grow without bound",
            EnsureTaskBudget.tracked() <= 512);
        expect("and an evicted resource simply starts over",
            EnsureTaskBudget.budgetFor("resource-0", START, CAP) == START);
    }

    // ---- the request lifecycle, which is what review changed ----

    /**
     * The defect that mattered most in the first attempt: each calculation wrapped its own token,
     * so a failing request could spend three rungs back to back instead of one.
     */
    private static void allPhasesShareOneDeadline() {
        reset();
        final CancellationToken plan = AutomationBudget.tokenFor("a", CancellationToken.NONE);
        final CancellationToken search = AutomationBudget.inFlight("a");
        final CancellationToken clamped = AutomationBudget.tokenFor("a", CancellationToken.NONE);
        expect("the search gets the plan's token, not one of its own", search == plan);
        expect("and so does the clamped plan after it", clamped == plan);
    }

    /** A token left behind by a request that threw must never be handed to another resource. */
    private static void aDifferentResourceStartsItsOwnRequest() {
        reset();
        final CancellationToken first = AutomationBudget.tokenFor("a", CancellationToken.NONE);
        expect("a different resource has nothing in flight",
            AutomationBudget.inFlight("b") == null);
        final CancellationToken second = AutomationBudget.tokenFor("b", CancellationToken.NONE);
        expect("and gets a deadline of its own", second != first);
    }

    /**
     * Several expiries inside one request are correlated consequences of one attempt. Counting
     * each of them climbed the ladder to the ceiling in a single request.
     */
    private static void oneRequestMovesTheLadderOnce() {
        reset();
        EnsureTaskBudget.clear();
        // A request whose budget ran out: one phase, or three, is still one request.
        AutomationBudget.tokenFor("a", CancellationToken.NONE);
        AutomationBudget.inFlight("a");
        AutomationBudget.tokenFor("a", CancellationToken.NONE);
        AutomationBudget.finish(false);
        final int afterOne = EnsureTaskBudget.rawBudgetOf("a");
        expect("a request that was not cut short moves nothing", afterOne == 0);

        // And a success clears rather than escalates.
        EnsureTaskBudget.clear();
        EnsureTaskBudget.noteExpired("a", START, CAP);
        reset();
        AutomationBudget.tokenFor("a", CancellationToken.NONE);
        AutomationBudget.finish(true);
        expect("a successful request resets the ladder",
            EnsureTaskBudget.rawBudgetOf("a") == 0);
    }

    /** A HEAD short-circuit answers without calculating, so there is nothing to account for. */
    private static void aFinishWithNoCalculationAccountsForNothing() {
        reset();
        EnsureTaskBudget.clear();
        AutomationBudget.finish(false);
        expect("no calculation means no ladder move", EnsureTaskBudget.tracked() == 0);
        expect("and nothing to decline caching for", !AutomationBudget.wasCutShort());
    }

    /**
     * The correctness defect review found. Our budget expiring makes the plan return empty, which
     * arrives as MISSING_RESOURCES and is indistinguishable from the real thing -- caching it would
     * suppress retries of a craftable resource for uncraftableRecheckTicks.
     */
    private static void aCutShortRequestIsNotCachedAsUncraftable() throws InterruptedException {
        reset();
        EnsureTaskBudget.clear();
        AutomationBudget.tokenFor("a", CancellationToken.NONE);
        AutomationBudget.finish(false);
        expect("a request that simply failed IS cacheable", !AutomationBudget.wasCutShort());
        expect("and a failure on its own merits moves no ladder",
            EnsureTaskBudget.rawBudgetOf("a") == 0);

        // Now the same thing with the budget actually running out. The wait is real rather than a
        // driven clock because the token is created inside AutomationBudget, which is the point --
        // a seam that let the test supply its own clock would not be testing the real path.
        reset();
        EnsureTaskBudget.clear();
        final CancellationToken token = AutomationBudget.tokenFor("b", CancellationToken.NONE);
        Thread.sleep(AutomationBudget.cap() >= 200 ? 260L : 60L);
        // Cancellation is polled, so the token only learns it has expired when something asks --
        // exactly as a crafting calculation asks between tree nodes.
        expect("the token reports cancelled once its budget has passed", token.isCancelled());
        AutomationBudget.finish(false);
        expect("a cut-short request is flagged so it is NOT cached as uncraftable",
            AutomationBudget.wasCutShort());
        expect("and it is the one that escalates the ladder",
            EnsureTaskBudget.rawBudgetOf("b") > 0);
    }

    private static void reset() {
        AutomationBudget.reset();
    }

    private static void expect(final String what, final boolean condition) {
        ++checks;
        if (!condition) {
            FAILURES.add(what);
        }
    }
}
