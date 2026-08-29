package com.wraithhawit.rstweaks.backoff;

import com.refinedmods.refinedstorage.api.autocrafting.calculation.CancellationToken;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gaps a mutation run found in {@link BudgetedCancellationToken}, which the 96-scenario backoff
 * suite otherwise covers well.
 */
class BudgetedCancellationTokenTest {

    /** Records what was asked of it, so delegation can be observed rather than assumed. */
    private static final class RecordingToken implements CancellationToken {
        private boolean cancelled;

        @Override
        public boolean isCancelled() {
            return this.cancelled;
        }

        @Override
        public void cancel() {
            this.cancelled = true;
        }
    }

    /** A clock that can be driven forward without sleeping. */
    private static final class Clock {
        private long nanos;

        Clock(final long start) {
            this.nanos = start;
        }

        long get() {
            return this.nanos;
        }

        void advanceMs(final long ms) {
            this.nanos += ms * 1_000_000L;
        }
    }

    /**
     * Cancelling us must cancel whoever we wrap. Deleting that call left every existing scenario
     * passing, and it is the difference between a stopped calculation and one that keeps running
     * with nobody watching.
     */
    @Test
    void cancelPropagatesToTheDelegate() {
        final RecordingToken delegate = new RecordingToken();
        final BudgetedCancellationToken token = new BudgetedCancellationToken(delegate, 100L);

        assertFalse(delegate.isCancelled());
        token.cancel();

        assertTrue(delegate.isCancelled(), "the delegate was never told");
        assertTrue(token.isCancelled());
    }

    @Test
    void cancellingWithoutADelegateIsSafe() {
        final BudgetedCancellationToken token = new BudgetedCancellationToken(null, 100L);
        token.cancel();
        assertTrue(token.isCancelled());
    }

    /**
     * The budget is elapsed time, not absolute time. Every existing scenario starts its clock at
     * zero, where {@code now - startedAt} and {@code now + startedAt} are the same number — so the
     * subtraction could be flipped to an addition undetected. A clock that starts at a realistic
     * {@code System.nanoTime()} value tells them apart.
     */
    @Test
    void theBudgetIsMeasuredFromTheStartNotFromZero() {
        final Clock clock = new Clock(1_234_567_890_123L);
        final BudgetedCancellationToken token =
            new BudgetedCancellationToken(null, 100L, clock::get);

        assertFalse(token.isCancelled(), "cancelled before any time passed");

        clock.advanceMs(99L);
        assertFalse(token.isCancelled(), "cancelled one millisecond early");

        clock.advanceMs(1L);
        assertTrue(token.isCancelled(), "the budget should have expired");
        assertTrue(token.expiredOnBudget());
    }

    /** Once cancelled, it stays cancelled without re-consulting the clock or the delegate. */
    @Test
    void cancellationIsSticky() {
        final Clock clock = new Clock(500L);
        final BudgetedCancellationToken token =
            new BudgetedCancellationToken(null, 100L, clock::get);

        token.cancel();
        assertTrue(token.isCancelled());
        assertTrue(token.isCancelled(), "the second call disagreed with the first");
        assertFalse(token.expiredOnBudget(), "an explicit cancel is not a budget expiry");
    }

    /**
     * A delegate's own cancellation is not ours. The distinction drives whether the slot gets a
     * bigger budget next time, so reporting it wrongly wastes budget on a craft that failed on
     * its own merits.
     */
    @Test
    void aDelegateCancelIsNotABudgetExpiry() {
        final RecordingToken delegate = new RecordingToken();
        final Clock clock = new Clock(500L);
        final BudgetedCancellationToken token =
            new BudgetedCancellationToken(delegate, 100L, clock::get);

        delegate.cancel();

        assertTrue(token.isCancelled());
        assertFalse(token.expiredOnBudget(), "the delegate stopped this, not our budget");
    }
}
