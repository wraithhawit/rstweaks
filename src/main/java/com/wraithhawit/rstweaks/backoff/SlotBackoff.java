package com.wraithhawit.rstweaks.backoff;

import java.util.Arrays;

/**
 * Per-slot backoff policy for a Step Requester, with no Minecraft, Refined Storage, mixin or
 * config types anywhere in it.
 *
 * <p>This exists as its own class specifically so it can be tested. Per
 * {@code rstweaks-gametest-harness}, {@code plannerCheck} cannot test a mixin — nothing
 * transforms bytecode in a plain JVM, so a mixin-dependent assertion there exercises stock
 * code and passes whatever we do. But the <em>decision</em> the mixin makes is ordinary
 * arithmetic over slot state, and that half can be pulled out and checked exhaustively.
 * {@code StepRequesterNetworkNodeMixin} keeps only the injection points and the config reads;
 * every branch that decides whether a slot sleeps lives here.
 *
 * <p>What that split can and cannot prove is worth being blunt about. It proves the ladder
 * escalates, caps, resets on a fast success and — the bug this was written for — arms on a
 * <em>slow</em> success. It cannot prove the redirects fire, that {@code startTask} is the
 * method being timed, or that the timing is real. Only a running game shows that.
 *
 * <p>Thresholds are passed in per call rather than read from config here, so a test can drive
 * the policy without a config spec and so the live values stay hot-reloadable.
 */
public final class SlotBackoff {
    /** Ticks left before each slot may attempt a craft again. */
    private int[] remaining = new int[0];

    /** Current backoff length per slot, doubled on each consecutive escalation. */
    private int[] interval = new int[0];

    /**
     * Calculation budget per slot in milliseconds, doubled each time our own budget is what
     * cancelled the calculation. Zero means "not yet raised", i.e. use the configured start.
     *
     * <p>This is what keeps a short automation budget from becoming the mistake in
     * {@code rstweaks-cap-is-not-a-proof}. A cap that permanently refuses a large craft is a
     * bug; a budget that grows until the craft fits is a schedule.
     */
    private int[] budget = new int[0];

    /** What {@link #recordOutcome} decided, so the caller can count it. */
    public enum Outcome {
        /** Fast success. The slot was reset to no delay at all. */
        RESET,
        /** The craft could not start. The slot escalated. */
        FAILED,
        /** The craft started, but the calculation was expensive. The slot escalated. */
        SLOW
    }

    /** Grows the per-slot arrays, preserving existing state. Never shrinks. */
    public void ensureCapacity(final int size) {
        if (this.remaining.length >= size) {
            return;
        }
        this.remaining = Arrays.copyOf(this.remaining, size);
        this.interval = Arrays.copyOf(this.interval, size);
        this.budget = Arrays.copyOf(this.budget, size);
    }

    /** Number of slots currently tracked. */
    public int capacity() {
        return this.remaining.length;
    }

    /** Whether this slot is currently asleep and should be skipped entirely. */
    public boolean isSleeping(final int slot) {
        return this.inRange(slot) && this.remaining[slot] > 0;
    }

    /** Ticks every slot's timer down by one. */
    public void tick() {
        for (int slot = 0; slot < this.remaining.length; slot++) {
            if (this.remaining[slot] > 0) {
                --this.remaining[slot];
            }
        }
    }

    /** Current escalation length for a slot; 0 when it is not backing off. */
    public int intervalOf(final int slot) {
        return this.inRange(slot) ? this.interval[slot] : 0;
    }

    /** Clears a slot completely — used when the player reconfigures it. */
    public void reset(final int slot) {
        if (this.inRange(slot)) {
            this.remaining[slot] = 0;
            this.interval[slot] = 0;
            this.budget[slot] = 0;
        }
    }

    /**
     * How many milliseconds this slot's next crafting calculation may have.
     *
     * @param startMs what a slot gets before it has ever been cancelled on budget
     * @param maxMs   the ceiling, normally RS's own crafting timeout — a slot never gets more
     *                than a player-initiated craft would
     */
    public int budgetFor(final int slot, final int startMs, final int maxMs) {
        final int start = Math.max(1, startMs);
        final int cap = Math.max(start, maxMs);
        if (!this.inRange(slot) || this.budget[slot] <= 0) {
            return start;
        }
        return Math.min(this.budget[slot], cap);
    }

    /**
     * Records that our budget, rather than the calculation itself, ended the attempt. Doubles
     * what the slot gets next time so a large-but-valid craft is eventually planned.
     */
    public void noteBudgetExpired(final int slot, final int startMs, final int maxMs) {
        if (!this.inRange(slot)) {
            return;
        }
        final int start = Math.max(1, startMs);
        final int cap = Math.max(start, maxMs);
        final int previous = this.budget[slot];
        this.budget[slot] = previous <= 0 ? Math.min(start * 2, cap) : Math.min(previous * 2, cap);
    }

    /** Current escalated budget for a slot, or 0 when it has never been raised. */
    public int rawBudgetOf(final int slot) {
        return this.inRange(slot) ? this.budget[slot] : 0;
    }

    /**
     * Records what a craft attempt did and sleeps the slot if it should.
     *
     * @param elapsedMs how long the calculation took; ignored when {@code success} is false
     * @param slowMs    threshold above which a SUCCESSFUL calculation still backs off;
     *                  {@code 0} disables that entirely and restores failure-only behaviour
     */
    public Outcome recordOutcome(final int slot,
                                 final boolean success,
                                 final long elapsedMs,
                                 final int baseTicks,
                                 final int maxTicks,
                                 final int slowMs,
                                 final int budgetPercent,
                                 final int costCapTicks) {
        if (!this.inRange(slot)) {
            return Outcome.RESET;
        }
        if (!success) {
            this.escalate(slot, baseTicks, maxTicks);
            this.applyCostFloor(slot, elapsedMs, budgetPercent, costCapTicks);
            return Outcome.FAILED;
        }
        // The branch this class was written for. The original mixin reset unconditionally
        // here, on the premise that only failures are expensive. Measured false on
        // 2026-08-23: the costly calculations succeeded, reset their own slot, and reran on
        // the next tick — 34.8% of the server thread against 45 failures in 100 seconds.
        if (slowMs > 0 && elapsedMs >= slowMs) {
            this.escalate(slot, baseTicks, maxTicks);
            this.applyCostFloor(slot, elapsedMs, budgetPercent, costCapTicks);
            return Outcome.SLOW;
        }
        this.remaining[slot] = 0;
        this.interval[slot] = 0;
        // A cheap success means the slot no longer needs an enlarged budget. Leaving it raised
        // would let one hard craft permanently license long calculations from that slot.
        this.budget[slot] = 0;
        return Outcome.RESET;
    }

    /**
     * Doubles the wait up to the cap.
     *
     * <p>Failures and slow successes share one ladder deliberately: a slot is either cheap
     * enough to run every tick or it is not, and which of the two reasons put it to sleep does
     * not change how long it should sleep. Sharing also means a slot that alternates between
     * them — an expensive plan that sometimes cannot start at all — keeps escalating instead
     * of resetting halfway on every flip.
     */
    private void escalate(final int slot, final int baseTicks, final int maxTicks) {
        final int base = Math.max(1, baseTicks);
        final int cap = Math.max(base, maxTicks);
        final int previous = this.interval[slot];
        final int next = previous <= 0 ? base : Math.min(previous * 2, cap);
        this.interval[slot] = next;
        this.remaining[slot] = next;
    }

    /**
     * Raises a slot's sleep to whatever its own cost demands, on top of the fixed ladder.
     *
     * <p>The ladder alone cannot bound an expensive slot, and measurement on 2026-08-23 is
     * what showed it. Three Step Requesters spent 60,434ms of a 110-second window inside
     * {@code startTask} across 156 calls — a 387ms mean with the worst pinned at exactly
     * 5,000ms, which is {@code TimeoutableCancellationToken.TIMEOUT_MS}. A slot whose single
     * calculation costs five seconds and then sleeps the ladder's ten-second cap is still
     * eating a third of the server thread, and at the ladder's 20-tick base it eats 83%.
     * A fixed sleep cannot answer a variable cost.
     *
     * <p>So the sleep is expressed as a budget instead: a slot may occupy at most
     * {@code budgetPercent} of the server thread, therefore a calculation costing
     * {@code elapsedMs} must be followed by {@code elapsedMs * (100 / budgetPercent)}
     * milliseconds of silence. At 5% a 5,000ms timeout sleeps 100 seconds; a 70ms calculation
     * sleeps 1.4 seconds. Cheap slots are barely touched, and the expensive ones are bounded
     * by arithmetic rather than by a guess.
     *
     * <p>Taken as a floor, never a ceiling — {@code Math.max} against the ladder — so repeated
     * failures still escalate normally and a slot can only ever sleep longer than before, not
     * shorter. {@code budgetPercent} of 0 disables this and restores pure ladder behaviour.
     */
    private void applyCostFloor(final int slot,
                                final long elapsedMs,
                                final int budgetPercent,
                                final int costCapTicks) {
        if (budgetPercent <= 0 || elapsedMs <= 0) {
            return;
        }
        final long silenceMs = elapsedMs * (100L / Math.min(100, budgetPercent));
        // 50ms per tick. Rounded up so a sub-tick calculation still yields at least one tick.
        final long ticks = Math.min((silenceMs + 49L) / 50L, Math.max(1, costCapTicks));
        if (ticks > this.remaining[slot]) {
            this.remaining[slot] = (int) ticks;
        }
        if (ticks > this.interval[slot]) {
            this.interval[slot] = (int) ticks;
        }
    }

    private boolean inRange(final int slot) {
        return slot >= 0 && slot < this.remaining.length;
    }
}
