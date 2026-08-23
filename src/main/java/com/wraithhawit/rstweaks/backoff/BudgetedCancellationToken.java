package com.wraithhawit.rstweaks.backoff;

import com.refinedmods.refinedstorage.api.autocrafting.calculation.CancellationToken;

import java.util.function.LongSupplier;

/**
 * A crafting-calculation budget for automation, shorter than the one a player gets.
 *
 * <h2>Why automation deserves a different number</h2>
 *
 * Refined Storage allows every calculation {@code TimeoutableCancellationToken.TIMEOUT_MS =
 * 5000} — five seconds <em>of the server thread</em>, a hundred ticks in which nothing else in
 * the world happens. A request that is going to fail spends all of it before saying so, and
 * measured on 2026-08-23 a single Step Requester slot did exactly that: 5,000ms and 4,574,498
 * tree nodes to conclude that a resource with no pattern behind it still had no pattern.
 *
 * <p>{@code craftingCalculationTimeoutMs} has been configurable since 0.2.112 and has
 * deliberately never been lowered, because a cancelled calculation reports
 * {@code MISSING_RESOURCES} — indistinguishable from "cannot be made" — so a global cut
 * silently refuses crafts that would have worked. That reasoning is sound for a <em>player</em>
 * clicking craft and waiting for an answer.
 *
 * <p>It does not hold for automation. A Step Requester is not waiting on an answer; it asks
 * again on a schedule regardless. Refusing it once costs a retry. Freezing the world for five
 * seconds costs everyone.
 *
 * <h2>Why a short budget here is not the mistake rstweaks made before</h2>
 *
 * The failure recorded in {@code rstweaks-cap-is-not-a-proof} was a cap that <b>permanently</b>
 * blocked late-game crafts, with no path back. This budget escalates instead: a slot whose
 * calculation is cancelled gets double the budget next time, up to
 * {@code craftingCalculationTimeoutMs}, so a genuinely large craft is planned after a few
 * attempts rather than never. See {@link SlotBackoff#budgetFor}.
 *
 * <p>Cancellation is also still honoured from below — the token Step Crafter supplied is
 * consulted, so anything that would have stopped the calculation before still does.
 */
public final class BudgetedCancellationToken implements CancellationToken {
    private final CancellationToken delegate;
    private final long budgetNanos;
    private final LongSupplier clock;
    private final long startedAt;
    private boolean cancelled;
    private boolean expired;

    public BudgetedCancellationToken(final CancellationToken delegate, final long budgetMs) {
        this(delegate, budgetMs, System::nanoTime);
    }

    /** Test seam: a clock that can be driven without sleeping. */
    public BudgetedCancellationToken(final CancellationToken delegate,
                                     final long budgetMs,
                                     final LongSupplier clock) {
        this.delegate = delegate;
        this.budgetNanos = Math.max(1L, budgetMs) * 1_000_000L;
        this.clock = clock;
        this.startedAt = clock.getAsLong();
    }

    @Override
    public boolean isCancelled() {
        if (this.cancelled) {
            return true;
        }
        // The delegate first: RS or Step Crafter may have its own reason to stop, and ours is
        // only ever an additional bound, never a way to keep calculating past theirs.
        if (this.delegate != null && this.delegate.isCancelled()) {
            this.cancelled = true;
            return true;
        }
        if (this.clock.getAsLong() - this.startedAt >= this.budgetNanos) {
            this.cancelled = true;
            this.expired = true;
            return true;
        }
        return false;
    }

    @Override
    public void cancel() {
        this.cancelled = true;
        if (this.delegate != null) {
            this.delegate.cancel();
        }
    }

    /**
     * Whether OUR budget is what stopped this calculation.
     *
     * <p>The distinction matters: a calculation cancelled by our budget may simply have needed
     * longer and should be retried with more, while one that failed on its own merits should
     * not be given a bigger budget for no reason.
     */
    public boolean expiredOnBudget() {
        return this.expired;
    }
}
