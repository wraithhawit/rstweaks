package com.wraithhawit.rstweaks.backoff;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one gap the 96-scenario backoff suite left: on the slow-<em>success</em> branch, the call to
 * {@code applyCostFloor} can be deleted and every existing scenario still passes.
 *
 * <p>That branch is the reason this class exists. Its own comment records the measurement — on
 * 2026-08-23 the costly Step Requester calculations <em>succeeded</em>, reset their slot, and
 * reran on the next tick, at 34.8% of the server thread against only 45 failures in 100 seconds.
 * The ladder alone does not fix that; the cost floor does. The existing scenarios use elapsed
 * times small enough that the ladder already dominates the floor, so removing the floor changes
 * none of their numbers.
 */
class SlotBackoffCostFloorTest {

    /**
     * A slow success with a genuinely expensive calculation. Two seconds at a 10% budget means the
     * slot should stay quiet for twenty seconds — 400 ticks — which is far above anything the
     * ladder would produce from a base of one tick.
     */
    @Test
    void anExpensiveSuccessSleepsForTheCostFloorNotTheLadder() {
        final SlotBackoff backoff = new SlotBackoff();
        backoff.ensureCapacity(1);

        final SlotBackoff.Outcome outcome = backoff.recordOutcome(
            0, true, 2_000L, 1, 4, 100, 10, 1_000);

        assertEquals(SlotBackoff.Outcome.SLOW, outcome);
        assertEquals(400, backoff.intervalOf(0),
            "the ladder's 1 tick was used instead of the cost floor");
        assertTrue(backoff.isSleeping(0));
    }

    /** The floor is a floor: a cheap-but-slow success must not shorten an existing escalation. */
    @Test
    void theCostFloorNeverShortensTheLadder() {
        final SlotBackoff backoff = new SlotBackoff();
        backoff.ensureCapacity(1);

        // Escalate the ladder well past what a 1ms calculation could ever justify.
        for (int i = 0; i < 6; i++) {
            backoff.recordOutcome(0, false, 1L, 100, 1_000, 100, 0, 1_000);
        }
        final int laddered = backoff.intervalOf(0);
        assertTrue(laddered >= 100, "ladder did not escalate as expected: " + laddered);

        backoff.recordOutcome(0, true, 1L, 100, 1_000, 1, 10, 1_000);
        assertTrue(backoff.intervalOf(0) >= laddered,
            "the cost floor shortened the ladder instead of raising it");
    }

    /** The cap is what stops one pathological calculation from muting a slot indefinitely. */
    @Test
    void theCostFloorRespectsItsCap() {
        final SlotBackoff backoff = new SlotBackoff();
        backoff.ensureCapacity(1);

        backoff.recordOutcome(0, true, 60_000L, 1, 4, 100, 10, 200);

        assertEquals(200, backoff.intervalOf(0), "the cap did not bound the cost floor");
    }
}
