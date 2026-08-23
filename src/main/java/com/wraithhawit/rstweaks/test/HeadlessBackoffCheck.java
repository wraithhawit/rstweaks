package com.wraithhawit.rstweaks.test;

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

    private static final List<String> FAILURES = new ArrayList<>();
    private static int checks;

    private HeadlessBackoffCheck() {
    }

    public static void main(final String[] args) {
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

        System.out.printf("scenarios: %d%n", checks);
        if (FAILURES.isEmpty()) {
            System.out.println("PASS");
            return;
        }
        FAILURES.forEach(f -> System.out.println("FAIL  " + f));
        System.out.printf("%d of %d scenarios failed%n", FAILURES.size(), checks);
        System.exit(1);
    }

    // ---- the failure ladder, which already worked ------------------------------------

    private static void failureArmsTheLadder() {
        final SlotBackoff backoff = slots(4);
        final Outcome outcome = backoff.recordOutcome(0, false, 0, BASE, CAP, SLOW_MS);
        expect("a failure reports FAILED", outcome == Outcome.FAILED);
        expect("a failure sleeps the slot", backoff.isSleeping(0));
        expect("a failure starts at the base interval", backoff.intervalOf(0) == BASE);
    }

    private static void consecutiveFailuresDouble() {
        final SlotBackoff backoff = slots(4);
        backoff.recordOutcome(0, false, 0, BASE, CAP, SLOW_MS);
        backoff.recordOutcome(0, false, 0, BASE, CAP, SLOW_MS);
        expect("two failures double the interval", backoff.intervalOf(0) == BASE * 2);
        backoff.recordOutcome(0, false, 0, BASE, CAP, SLOW_MS);
        expect("three failures double again", backoff.intervalOf(0) == BASE * 4);
    }

    private static void escalationStopsAtTheCap() {
        final SlotBackoff backoff = slots(4);
        for (int i = 0; i < 20; i++) {
            backoff.recordOutcome(0, false, 0, BASE, CAP, SLOW_MS);
        }
        expect("escalation stops at the cap", backoff.intervalOf(0) == CAP);
    }

    private static void fastSuccessResetsTheLadder() {
        final SlotBackoff backoff = slots(4);
        backoff.recordOutcome(0, false, 0, BASE, CAP, SLOW_MS);
        backoff.recordOutcome(0, false, 0, BASE, CAP, SLOW_MS);
        final Outcome outcome = backoff.recordOutcome(0, true, 1, BASE, CAP, SLOW_MS);
        expect("a fast success reports RESET", outcome == Outcome.RESET);
        expect("a fast success clears the interval", backoff.intervalOf(0) == 0);
        expect("a fast success wakes the slot", !backoff.isSleeping(0));
    }

    // ---- SLOW-PATH: the four that fail against the pre-0.2.113 policy -----------------

    private static void slowSuccessArmsTheLadder() {
        final SlotBackoff backoff = slots(4);
        // The exact shape measured on 2026-08-23: the calculation SUCCEEDED and cost ~400ms.
        final Outcome outcome = backoff.recordOutcome(0, true, 400, BASE, CAP, SLOW_MS);
        expect("a slow success reports SLOW", outcome == Outcome.SLOW);
        expect("a slow success sleeps the slot", backoff.isSleeping(0));
        expect("a slow success starts at the base interval", backoff.intervalOf(0) == BASE);
    }

    private static void slowSuccessAtExactlyThresholdArms() {
        final SlotBackoff backoff = slots(4);
        final Outcome atThreshold = backoff.recordOutcome(0, true, SLOW_MS, BASE, CAP, SLOW_MS);
        expect("elapsed == threshold counts as slow", atThreshold == Outcome.SLOW);

        final SlotBackoff other = slots(4);
        final Outcome below = other.recordOutcome(0, true, SLOW_MS - 1, BASE, CAP, SLOW_MS);
        expect("one millisecond under the threshold is fast", below == Outcome.RESET);
        expect("a fast success does not sleep the slot", !other.isSleeping(0));
    }

    private static void slowSuccessEscalatesLikeAFailure() {
        final SlotBackoff backoff = slots(4);
        backoff.recordOutcome(0, true, 400, BASE, CAP, SLOW_MS);
        backoff.recordOutcome(0, true, 400, BASE, CAP, SLOW_MS);
        expect("two slow successes double the interval", backoff.intervalOf(0) == BASE * 2);
        for (int i = 0; i < 20; i++) {
            backoff.recordOutcome(0, true, 400, BASE, CAP, SLOW_MS);
        }
        expect("slow successes also stop at the cap", backoff.intervalOf(0) == CAP);
    }

    private static void failureAndSlowSuccessShareOneLadder() {
        final SlotBackoff backoff = slots(4);
        // An expensive plan that sometimes cannot start at all must keep escalating rather
        // than resetting halfway on every flip between the two reasons.
        backoff.recordOutcome(0, false, 0, BASE, CAP, SLOW_MS);
        expect("flip step 1 is the base", backoff.intervalOf(0) == BASE);
        backoff.recordOutcome(0, true, 400, BASE, CAP, SLOW_MS);
        expect("a slow success continues a failure's ladder", backoff.intervalOf(0) == BASE * 2);
        backoff.recordOutcome(0, false, 0, BASE, CAP, SLOW_MS);
        expect("a failure continues a slow success's ladder", backoff.intervalOf(0) == BASE * 4);
    }

    // ---- configuration, lifetime and bookkeeping -------------------------------------

    private static void slowThresholdOfZeroRestoresOldBehaviour() {
        final SlotBackoff backoff = slots(4);
        final Outcome outcome = backoff.recordOutcome(0, true, 5_000, BASE, CAP, 0);
        expect("slowMs=0 lets even a 5s success through", outcome == Outcome.RESET);
        expect("slowMs=0 leaves the slot awake", !backoff.isSleeping(0));
    }

    private static void fastSuccessAfterSlowOneClearsIt() {
        final SlotBackoff backoff = slots(4);
        backoff.recordOutcome(0, true, 400, BASE, CAP, SLOW_MS);
        backoff.recordOutcome(0, true, 400, BASE, CAP, SLOW_MS);
        // The network got faster -- a storage filled up, a competing pattern was removed.
        // Nothing should keep punishing the slot for what it used to cost.
        backoff.recordOutcome(0, true, 1, BASE, CAP, SLOW_MS);
        expect("a fast success clears a slow ladder", backoff.intervalOf(0) == 0);
        expect("a fast success wakes a slow-slept slot", !backoff.isSleeping(0));
    }

    private static void sleepingSlotIsSkippedUntilTimerExpires() {
        final SlotBackoff backoff = slots(4);
        backoff.recordOutcome(0, true, 400, BASE, CAP, SLOW_MS);
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
        backoff.recordOutcome(1, true, 400, BASE, CAP, SLOW_MS);
        expect("the slow slot sleeps", backoff.isSleeping(1));
        expect("its neighbour does not", !backoff.isSleeping(0));
        expect("nor does a later slot", !backoff.isSleeping(2));
        backoff.recordOutcome(0, true, 1, BASE, CAP, SLOW_MS);
        expect("a fast slot does not wake a slow one", backoff.isSleeping(1));
    }

    private static void reconfigureClearsASleepingSlot() {
        final SlotBackoff backoff = slots(4);
        backoff.recordOutcome(0, true, 400, BASE, CAP, SLOW_MS);
        backoff.reset(0);
        expect("reset wakes the slot", !backoff.isSleeping(0));
        expect("reset clears the interval", backoff.intervalOf(0) == 0);
    }

    private static void capacityGrowsWithoutLosingState() {
        final SlotBackoff backoff = slots(2);
        backoff.recordOutcome(1, true, 400, BASE, CAP, SLOW_MS);
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
            backoff.recordOutcome(-1, false, 0, BASE, CAP, SLOW_MS) == Outcome.RESET);
        expect("a slot past the end reports RESET",
            backoff.recordOutcome(99, false, 0, BASE, CAP, SLOW_MS) == Outcome.RESET);
        expect("a negative slot is never sleeping", !backoff.isSleeping(-1));
        expect("a slot past the end is never sleeping", !backoff.isSleeping(99));
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
