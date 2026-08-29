package com.wraithhawit.rstweaks.test;

import com.refinedmods.refinedstorage.api.autocrafting.calculation.CancellationToken;
import com.wraithhawit.rstweaks.backoff.BudgetedCancellationToken;
import com.wraithhawit.rstweaks.backoff.SlotBackoff;
import com.wraithhawit.rstweaks.backoff.SlotBackoff.Outcome;

import java.util.ArrayList;
import java.util.List;

/**
 * Self-tests for {@link SlotBackoff}, the Step Requester backoff policy. Run headlessly with
 * {@code ./gradlew backoffCheck}; exits non-zero on the first failure.
 *
 * <p><b>What this suite is not.</b> Per {@code rstweaks-gametest-harness}, nothing transforms
 * Refined Storage's or Step Crafter's bytecode in a plain JVM, so no assertion here can show
 * that {@code StepRequesterNetworkNodeMixin}'s redirects fire, that {@code startTask} is the
 * method being timed, or that the elapsed millisecond figure is real. Those need a running
 * game. What this suite covers is the decision itself — the arithmetic that decides whether a
 * slot sleeps — which is where 0.2.113's bug actually lived, and which is deterministic once
 * the timing is a parameter rather than a clock reading.
 *
 * <p>Every case below was confirmed to FAIL against the pre-0.2.113 policy (reinstated by
 * making {@code recordOutcome} reset unconditionally on success), per the discipline in
 * {@code rstweaks-gametest-harness}: a test that passes with the bug present is not a test.
 * The four marked SLOW-PATH are the ones that flipped; the rest are regression cover for the
 * failure ladder that already worked and must keep working.
 */
public final class HeadlessBackoffCheck {
    private static final int BASE = 20;
    private static final int CAP = 200;
    private static final int SLOW_MS = 10;
    /** Existing scenarios pass budgetPercent=0 so they still test the ladder in isolation. */
    private static final int CAP_TICKS = 6000;

    private static final List<String> FAILURES = new ArrayList<>();
    private static int checks;

    private HeadlessBackoffCheck() {
    }

    public static void main(final String[] args) {
        final CraftingPlanSelfTest.Result result = run();
        System.out.printf("scenarios: %d%n", result.scenarios());
        if (result.failures().isEmpty()) {
            System.out.println("PASS");
            return;
        }
        result.failures().forEach(f -> System.out.println("FAIL  " + f));
        System.out.printf("%d of %d scenarios failed%n", result.failures().size(), result.scenarios());
        System.exit(1);
    }

    /**
     * The same scenarios, returning their failures instead of printing them and calling
     * {@code System.exit}. That exit is why PIT could not drive this suite: it kills the minion
     * JVM. Shaped like {@code PatternOrderSelfTest.run()}, which already did it this way.
     *
     * <p>Resets the static counters first. A mutation run calls this thousands of times in one
     * JVM, and without the reset every call would inherit the last one's failures.
     */
    public static CraftingPlanSelfTest.Result run() {
        FAILURES.clear();
        checks = 0;
        failureArmsTheLadder();
        consecutiveFailuresDouble();
        escalationStopsAtTheCap();
        fastSuccessResetsTheLadder();

        slowSuccessArmsTheLadder();
        slowSuccessAtExactlyThresholdArms();
        slowSuccessEscalatesLikeAFailure();
        failureAndSlowSuccessShareOneLadder();

        slowThresholdOfZeroRestoresOldBehaviour();
        fastSuccessAfterSlowOneClearsIt();
        sleepingSlotIsSkippedUntilTimerExpires();
        slotsAreIndependent();
        reconfigureClearsASleepingSlot();
        capacityGrowsWithoutLosingState();
        outOfRangeSlotIsInert();

        costFloorScalesWithTheCalculation();
        costFloorBoundsTheFiveSecondTimeout();
        costFloorIsAFloorNotACeiling();
        costFloorAppliesToFailuresToo();
        costFloorRespectsItsCap();
        costFloorOfZeroLeavesTheLadderAlone();
        costFloorGivesAtLeastOneTick();

        budgetStartsAtTheConfiguredValue();
        budgetDoublesWhenOurTokenExpires();
        budgetStopsAtRefinedStoragesOwnTimeout();
        aCheapSuccessGivesTheBudgetBack();
        budgetIsPerSlot();
        tokenExpiresOnItsBudget();
        tokenHonoursTheDelegate();
        tokenDoesNotClaimBudgetExpiryForADelegateCancel();
        budgetLadderStopsBelowTheFiveSecondFreeze();

        return new CraftingPlanSelfTest.Result(checks, List.copyOf(FAILURES));
    }

    // ---- the failure ladder, which already worked ------------------------------------

    private static void failureArmsTheLadder() {
        final SlotBackoff backoff = slots(4);
        final Outcome outcome = backoff.recordOutcome(0, false, 0, BASE, CAP, SLOW_MS, 0, CAP_TICKS);
        expect("a failure reports FAILED", outcome == Outcome.FAILED);
        expect("a failure sleeps the slot", backoff.isSleeping(0));
        expect("a failure starts at the base interval", backoff.intervalOf(0) == BASE);
    }

    private static void consecutiveFailuresDouble() {
        final SlotBackoff backoff = slots(4);
        backoff.recordOutcome(0, false, 0, BASE, CAP, SLOW_MS, 0, CAP_TICKS);
        backoff.recordOutcome(0, false, 0, BASE, CAP, SLOW_MS, 0, CAP_TICKS);
        expect("two failures double the interval", backoff.intervalOf(0) == BASE * 2);
        backoff.recordOutcome(0, false, 0, BASE, CAP, SLOW_MS, 0, CAP_TICKS);
        expect("three failures double again", backoff.intervalOf(0) == BASE * 4);
    }

    private static void escalationStopsAtTheCap() {
        final SlotBackoff backoff = slots(4);
        for (int i = 0; i < 20; i++) {
            backoff.recordOutcome(0, false, 0, BASE, CAP, SLOW_MS, 0, CAP_TICKS);
        }
        expect("escalation stops at the cap", backoff.intervalOf(0) == CAP);
    }

    private static void fastSuccessResetsTheLadder() {
        final SlotBackoff backoff = slots(4);
        backoff.recordOutcome(0, false, 0, BASE, CAP, SLOW_MS, 0, CAP_TICKS);
        backoff.recordOutcome(0, false, 0, BASE, CAP, SLOW_MS, 0, CAP_TICKS);
        final Outcome outcome = backoff.recordOutcome(0, true, 1, BASE, CAP, SLOW_MS, 0, CAP_TICKS);
        expect("a fast success reports RESET", outcome == Outcome.RESET);
        expect("a fast success clears the interval", backoff.intervalOf(0) == 0);
        expect("a fast success wakes the slot", !backoff.isSleeping(0));
    }

    // ---- SLOW-PATH: the four that fail against the pre-0.2.113 policy -----------------

    private static void slowSuccessArmsTheLadder() {
        final SlotBackoff backoff = slots(4);
        // The exact shape measured on 2026-08-23: the calculation SUCCEEDED and cost ~400ms.
        final Outcome outcome = backoff.recordOutcome(0, true, 400, BASE, CAP, SLOW_MS, 0, CAP_TICKS);
        expect("a slow success reports SLOW", outcome == Outcome.SLOW);
        expect("a slow success sleeps the slot", backoff.isSleeping(0));
        expect("a slow success starts at the base interval", backoff.intervalOf(0) == BASE);
    }

    private static void slowSuccessAtExactlyThresholdArms() {
        final SlotBackoff backoff = slots(4);
        final Outcome atThreshold = backoff.recordOutcome(0, true, SLOW_MS, BASE, CAP, SLOW_MS, 0, CAP_TICKS);
        expect("elapsed == threshold counts as slow", atThreshold == Outcome.SLOW);

        final SlotBackoff other = slots(4);
        final Outcome below = other.recordOutcome(0, true, SLOW_MS - 1, BASE, CAP, SLOW_MS, 0, CAP_TICKS);
        expect("one millisecond under the threshold is fast", below == Outcome.RESET);
        expect("a fast success does not sleep the slot", !other.isSleeping(0));
    }

    private static void slowSuccessEscalatesLikeAFailure() {
        final SlotBackoff backoff = slots(4);
        backoff.recordOutcome(0, true, 400, BASE, CAP, SLOW_MS, 0, CAP_TICKS);
        backoff.recordOutcome(0, true, 400, BASE, CAP, SLOW_MS, 0, CAP_TICKS);
        expect("two slow successes double the interval", backoff.intervalOf(0) == BASE * 2);
        for (int i = 0; i < 20; i++) {
            backoff.recordOutcome(0, true, 400, BASE, CAP, SLOW_MS, 0, CAP_TICKS);
        }
        expect("slow successes also stop at the cap", backoff.intervalOf(0) == CAP);
    }

    private static void failureAndSlowSuccessShareOneLadder() {
        final SlotBackoff backoff = slots(4);
        // An expensive plan that sometimes cannot start at all must keep escalating rather
        // than resetting halfway on every flip between the two reasons.
        backoff.recordOutcome(0, false, 0, BASE, CAP, SLOW_MS, 0, CAP_TICKS);
        expect("flip step 1 is the base", backoff.intervalOf(0) == BASE);
        backoff.recordOutcome(0, true, 400, BASE, CAP, SLOW_MS, 0, CAP_TICKS);
        expect("a slow success continues a failure's ladder", backoff.intervalOf(0) == BASE * 2);
        backoff.recordOutcome(0, false, 0, BASE, CAP, SLOW_MS, 0, CAP_TICKS);
        expect("a failure continues a slow success's ladder", backoff.intervalOf(0) == BASE * 4);
    }

    // ---- configuration, lifetime and bookkeeping -------------------------------------

    private static void slowThresholdOfZeroRestoresOldBehaviour() {
        final SlotBackoff backoff = slots(4);
        final Outcome outcome = backoff.recordOutcome(0, true, 5_000, BASE, CAP, 0, 0, CAP_TICKS);
        expect("slowMs=0 lets even a 5s success through", outcome == Outcome.RESET);
        expect("slowMs=0 leaves the slot awake", !backoff.isSleeping(0));
    }

    private static void fastSuccessAfterSlowOneClearsIt() {
        final SlotBackoff backoff = slots(4);
        backoff.recordOutcome(0, true, 400, BASE, CAP, SLOW_MS, 0, CAP_TICKS);
        backoff.recordOutcome(0, true, 400, BASE, CAP, SLOW_MS, 0, CAP_TICKS);
        // The network got faster -- a storage filled up, a competing pattern was removed.
        // Nothing should keep punishing the slot for what it used to cost.
        backoff.recordOutcome(0, true, 1, BASE, CAP, SLOW_MS, 0, CAP_TICKS);
        expect("a fast success clears a slow ladder", backoff.intervalOf(0) == 0);
        expect("a fast success wakes a slow-slept slot", !backoff.isSleeping(0));
    }

    private static void sleepingSlotIsSkippedUntilTimerExpires() {
        final SlotBackoff backoff = slots(4);
        backoff.recordOutcome(0, true, 400, BASE, CAP, SLOW_MS, 0, CAP_TICKS);
        for (int tick = 0; tick < BASE - 1; tick++) {
            backoff.tick();
            expect("slot stays asleep for the whole interval", backoff.isSleeping(0));
        }
        backoff.tick();
        expect("slot wakes on the interval's last tick", !backoff.isSleeping(0));
        expect("waking does not clear the escalation", backoff.intervalOf(0) == BASE);
    }

    private static void slotsAreIndependent() {
        final SlotBackoff backoff = slots(4);
        backoff.recordOutcome(1, true, 400, BASE, CAP, SLOW_MS, 0, CAP_TICKS);
        expect("the slow slot sleeps", backoff.isSleeping(1));
        expect("its neighbour does not", !backoff.isSleeping(0));
        expect("nor does a later slot", !backoff.isSleeping(2));
        backoff.recordOutcome(0, true, 1, BASE, CAP, SLOW_MS, 0, CAP_TICKS);
        expect("a fast slot does not wake a slow one", backoff.isSleeping(1));
    }

    private static void reconfigureClearsASleepingSlot() {
        final SlotBackoff backoff = slots(4);
        backoff.recordOutcome(0, true, 400, BASE, CAP, SLOW_MS, 0, CAP_TICKS);
        backoff.reset(0);
        expect("reset wakes the slot", !backoff.isSleeping(0));
        expect("reset clears the interval", backoff.intervalOf(0) == 0);
    }

    private static void capacityGrowsWithoutLosingState() {
        final SlotBackoff backoff = slots(2);
        backoff.recordOutcome(1, true, 400, BASE, CAP, SLOW_MS, 0, CAP_TICKS);
        backoff.ensureCapacity(9);
        expect("growing keeps the sleeping slot asleep", backoff.isSleeping(1));
        expect("growing keeps its interval", backoff.intervalOf(1) == BASE);
        expect("growing adds the new slots awake", !backoff.isSleeping(8));
        backoff.ensureCapacity(4);
        expect("ensureCapacity never shrinks", backoff.capacity() == 9);
    }

    private static void outOfRangeSlotIsInert() {
        final SlotBackoff backoff = slots(2);
        // -1 is the mixin's initial currentSlot, reachable if startTask is somehow called
        // before any slot has been read. It must not throw on the server thread.
        expect("a negative slot reports RESET",
            backoff.recordOutcome(-1, false, 0, BASE, CAP, SLOW_MS, 0, CAP_TICKS) == Outcome.RESET);
        expect("a slot past the end reports RESET",
            backoff.recordOutcome(99, false, 0, BASE, CAP, SLOW_MS, 0, CAP_TICKS) == Outcome.RESET);
        expect("a negative slot is never sleeping", !backoff.isSleeping(-1));
        expect("a slot past the end is never sleeping", !backoff.isSleeping(99));
    }

    // ---- COST-FLOOR: the 0.2.116 half, which the ladder alone cannot do ---------------

    private static void costFloorScalesWithTheCalculation() {
        final SlotBackoff backoff = slots(4);
        // 5% budget: 70ms of calculation buys 1,400ms of silence = 28 ticks.
        backoff.recordOutcome(0, false, 70, BASE, CAP, SLOW_MS, 5, CAP_TICKS);
        expect("70ms at 5% sleeps 28 ticks", backoff.intervalOf(0) == 28);

        final SlotBackoff cheaper = slots(4);
        // 10% budget halves every sleep: 200ms buys 2,000ms = 40 ticks, against 80 at 5%.
        cheaper.recordOutcome(0, false, 200, BASE, CAP, SLOW_MS, 10, CAP_TICKS);
        expect("200ms at 10% sleeps 40 ticks", cheaper.intervalOf(0) == 40);

        final SlotBackoff stricter = slots(4);
        stricter.recordOutcome(0, false, 200, BASE, CAP, SLOW_MS, 5, CAP_TICKS);
        expect("200ms at 5% sleeps twice as long", stricter.intervalOf(0) == 80);

        // Below the ladder's own base the floor is simply not the binding constraint —
        // 70ms at 10% asks for 14 ticks and the 20-tick base already exceeds it.
        final SlotBackoff tiny = slots(4);
        tiny.recordOutcome(0, false, 70, BASE, CAP, SLOW_MS, 10, CAP_TICKS);
        expect("a floor under the ladder base does not lower it", tiny.intervalOf(0) == BASE);
    }

    private static void costFloorBoundsTheFiveSecondTimeout() {
        final SlotBackoff backoff = slots(4);
        // The measured worst case: TimeoutableCancellationToken.TIMEOUT_MS, burned in full.
        // At 5% that is 100 seconds of silence = 2,000 ticks. Under the old ladder this slot
        // slept 20 ticks and went straight back to spending 5s of the next second.
        backoff.recordOutcome(0, false, 5_000, BASE, CAP, SLOW_MS, 5, CAP_TICKS);
        expect("a 5s timeout sleeps 2,000 ticks", backoff.intervalOf(0) == 2_000);
        expect("which is far past the ladder cap", backoff.intervalOf(0) > CAP);
    }

    private static void costFloorIsAFloorNotACeiling() {
        final SlotBackoff backoff = slots(4);
        // Escalate the ladder well past what a trivial cost would ask for.
        for (int i = 0; i < 20; i++) {
            backoff.recordOutcome(0, false, 0, BASE, CAP, SLOW_MS, 5, CAP_TICKS);
        }
        expect("ladder reached its cap", backoff.intervalOf(0) == CAP);
        // 10ms at 5% is only 4 ticks; it must not drag the slot back down to that.
        backoff.recordOutcome(0, false, 10, BASE, CAP, SLOW_MS, 5, CAP_TICKS);
        expect("a cheap cost never shortens an escalated ladder",
            backoff.intervalOf(0) >= CAP);
    }

    private static void costFloorAppliesToFailuresToo() {
        final SlotBackoff backoff = slots(4);
        // The measurement that drove this: the expensive calls FAIL, they do not succeed.
        // "slow crafts backed off" was absent from every report while the mean was 387ms.
        final Outcome outcome = backoff.recordOutcome(0, false, 5_000, BASE, CAP, SLOW_MS, 5, CAP_TICKS);
        expect("an expensive failure still reports FAILED", outcome == Outcome.FAILED);
        expect("an expensive failure gets the cost floor", backoff.intervalOf(0) == 2_000);
    }

    private static void costFloorRespectsItsCap() {
        final SlotBackoff backoff = slots(4);
        backoff.recordOutcome(0, false, 5_000, BASE, CAP, SLOW_MS, 1, 600);
        expect("the cost cap bounds an extreme budget", backoff.intervalOf(0) == 600);
        expect("a capped slot is still only asleep, not silenced forever",
            backoff.isSleeping(0));
    }

    private static void costFloorOfZeroLeavesTheLadderAlone() {
        final SlotBackoff backoff = slots(4);
        backoff.recordOutcome(0, false, 5_000, BASE, CAP, SLOW_MS, 0, CAP_TICKS);
        expect("budgetPercent=0 restores pure ladder behaviour", backoff.intervalOf(0) == BASE);
    }

    private static void costFloorGivesAtLeastOneTick() {
        final SlotBackoff backoff = slots(4);
        // 1ms at 5% is 20ms, under a tick. It must round up rather than to zero, or a slot
        // could be "escalated" to no delay at all.
        backoff.recordOutcome(0, true, 1, BASE, CAP, 1, 5, CAP_TICKS);
        expect("a sub-tick cost still sleeps", backoff.isSleeping(0));
    }


    // ---- the automation calculation budget --------------------------------------------

    private static void budgetStartsAtTheConfiguredValue() {
        final SlotBackoff backoff = slots(4);
        expect("an untouched slot gets the start budget",
            backoff.budgetFor(0, 200, 5000) == 200);
        expect("and has no raised budget recorded", backoff.rawBudgetOf(0) == 0);
    }

    private static void budgetDoublesWhenOurTokenExpires() {
        final SlotBackoff backoff = slots(4);
        backoff.noteBudgetExpired(0, 200, 5000);
        expect("one expiry doubles the budget", backoff.budgetFor(0, 200, 5000) == 400);
        backoff.noteBudgetExpired(0, 200, 5000);
        expect("two expiries double again", backoff.budgetFor(0, 200, 5000) == 800);
    }

    /**
     * The property that keeps this from being the permanent cap of
     * rstweaks-cap-is-not-a-proof: automation never gets LESS than a player would eventually
     * allow, and never more either.
     */
    private static void budgetStopsAtRefinedStoragesOwnTimeout() {
        final SlotBackoff backoff = slots(4);
        for (int i = 0; i < 30; i++) {
            backoff.noteBudgetExpired(0, 200, 5000);
        }
        expect("the budget climbs to RS's own timeout and stops",
            backoff.budgetFor(0, 200, 5000) == 5000);
    }

    private static void aCheapSuccessGivesTheBudgetBack() {
        final SlotBackoff backoff = slots(4);
        backoff.noteBudgetExpired(0, 200, 5000);
        backoff.noteBudgetExpired(0, 200, 5000);
        backoff.recordOutcome(0, true, 0, BASE, CAP, SLOW_MS, 0, CAP_TICKS);
        expect("a cheap success returns the slot to the start budget",
            backoff.budgetFor(0, 200, 5000) == 200);
    }

    private static void budgetIsPerSlot() {
        final SlotBackoff backoff = slots(4);
        backoff.noteBudgetExpired(1, 200, 5000);
        expect("the expiring slot is raised", backoff.budgetFor(1, 200, 5000) == 400);
        expect("its neighbour is not", backoff.budgetFor(0, 200, 5000) == 200);
    }

    // ---- the token itself, on a driven clock ------------------------------------------

    private static void tokenExpiresOnItsBudget() {
        final long[] now = {0};
        final BudgetedCancellationToken token =
            new BudgetedCancellationToken(null, 200, () -> now[0]);
        expect("not cancelled immediately", !token.isCancelled());
        now[0] = 199L * 1_000_000L;
        expect("not cancelled one millisecond short", !token.isCancelled());
        now[0] = 200L * 1_000_000L;
        expect("cancelled at the budget", token.isCancelled());
        expect("and says the budget is what did it", token.expiredOnBudget());
    }

    /** Our budget is an ADDITIONAL bound, never a licence to keep going past someone else's. */
    private static void tokenHonoursTheDelegate() {
        final boolean[] delegateCancelled = {false};
        final CancellationToken delegate = new CancellationToken() {
            @Override
            public boolean isCancelled() {
                return delegateCancelled[0];
            }

            @Override
            public void cancel() {
                delegateCancelled[0] = true;
            }
        };
        final long[] now = {0};
        final BudgetedCancellationToken token =
            new BudgetedCancellationToken(delegate, 5000, () -> now[0]);
        expect("not cancelled while the delegate is not", !token.isCancelled());
        delegateCancelled[0] = true;
        expect("cancelled as soon as the delegate is", token.isCancelled());
        token.cancel();
        expect("cancelling ours cancels the delegate too", delegateCancelled[0]);
    }

    private static void tokenDoesNotClaimBudgetExpiryForADelegateCancel() {
        final boolean[] delegateCancelled = {true};
        final CancellationToken delegate = new CancellationToken() {
            @Override
            public boolean isCancelled() {
                return delegateCancelled[0];
            }

            @Override
            public void cancel() {
                delegateCancelled[0] = true;
            }
        };
        final BudgetedCancellationToken token =
            new BudgetedCancellationToken(delegate, 5000, () -> 0L);
        expect("cancelled by the delegate", token.isCancelled());
        // If this were wrong, every unrelated cancellation would enlarge the slot's budget
        // until automation was back to freezing the world for five seconds.
        expect("but that is not a budget expiry", !token.expiredOnBudget());
    }


    /**
     * The hole 0.2.123 left, pinned. Its ladder capped at RS's own 5,000ms, so the top rung was
     * still a full five-second freeze -- measured climbing 200, 400, 800, 1,600, 3,200, 5,005ms
     * on one request. Automation's ceiling must be its own, and well under RS's.
     */
    private static void budgetLadderStopsBelowTheFiveSecondFreeze() {
        final SlotBackoff backoff = slots(4);
        final int automationCap = 1000;
        for (int i = 0; i < 30; i++) {
            backoff.noteBudgetExpired(0, 200, automationCap);
        }
        final int settled = backoff.budgetFor(0, 200, automationCap);
        expect("the ladder stops at the automation ceiling", settled == automationCap);
        expect("which is nowhere near a five-second freeze", settled <= 1000);

        // And the rungs on the way up are the ones actually observed in game.
        final SlotBackoff climb = slots(4);
        final int[] expected = {400, 800, 1000, 1000};
        for (int i = 0; i < expected.length; i++) {
            climb.noteBudgetExpired(0, 200, automationCap);
            expect("rung " + i + " is " + expected[i],
                climb.budgetFor(0, 200, automationCap) == expected[i]);
        }
    }

    // ---- harness ---------------------------------------------------------------------

    private static SlotBackoff slots(final int size) {
        final SlotBackoff backoff = new SlotBackoff();
        backoff.ensureCapacity(size);
        return backoff;
    }

    private static void expect(final String what, final boolean condition) {
        ++checks;
        if (!condition) {
            FAILURES.add(what);
        }
    }
}
