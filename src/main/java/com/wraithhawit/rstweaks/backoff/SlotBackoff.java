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
        }
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
                                 final int slowMs) {
        if (!this.inRange(slot)) {
            return Outcome.RESET;
        }
        if (!success) {
            this.escalate(slot, baseTicks, maxTicks);
            return Outcome.FAILED;
        }
        // The branch this class was written for. The original mixin reset unconditionally
        // here, on the premise that only failures are expensive. Measured false on
        // 2026-08-23: the costly calculations succeeded, reset their own slot, and reran on
        // the next tick — 34.8% of the server thread against 45 failures in 100 seconds.
        if (slowMs > 0 && elapsedMs >= slowMs) {
            this.escalate(slot, baseTicks, maxTicks);
            return Outcome.SLOW;
        }
        this.remaining[slot] = 0;
        this.interval[slot] = 0;
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

    private boolean inRange(final int slot) {
        return slot >= 0 && slot < this.remaining.length;
    }
}
