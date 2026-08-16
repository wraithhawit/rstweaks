package com.wraithhawit.rstweaks.planner;

import javax.annotation.Nullable;

/**
 * How many of something can be crafted, found by asking an oracle about specific amounts.
 *
 * <p>Refined Storage's own search lives in {@code IsCraftableCraftingCalculatorListener}: double
 * until it fails, then binary search the gap. This is the same shape, separated from the mixin that
 * uses it for two reasons — the mixin can then be a five-line adapter, and a binary search that
 * cannot be run outside Minecraft is a binary search whose off-by-one errors ship.
 *
 * <p>The oracle is deliberately three-valued. A planner that <em>declines</em> a graph has not said
 * the craft is impossible, it has said the question is not its business, and treating that as "no"
 * would report zero for every ordinary recipe. Only a proved infeasibility is a no.
 */
public final class MaxCraftable {
    private MaxCraftable() {
    }

    /** Answers whether a specific amount can be crafted, or {@code null} for "I cannot say". */
    @FunctionalInterface
    public interface Oracle {
        @Nullable
        Boolean craftable(long amount);
    }

    /** Lets a long search stop early without pretending it finished. */
    @FunctionalInterface
    public interface Cancelled {
        boolean check();
    }

    /**
     * A ceiling on oracle calls. Doubling is logarithmic, so 40 probes reaches past a trillion;
     * this exists because each call may be a linear program, not because the search is deep.
     */
    public static final int MAX_PROBES = 40;

    /**
     * @return the largest amount the oracle accepts, {@code 0} when it accepts none, or
     *     {@code null} when no answer was reached — the oracle declined, the caller cancelled, or
     *     the probe ceiling was hit. {@code null} means "let someone else answer", and is not zero.
     */
    @Nullable
    public static Long search(final Oracle oracle, final Cancelled cancelled) {
        int probes = 0;
        long highestYes = 0L;
        long probe = 1L;
        // Double until a failure, so highestYes is the largest amount known to work.
        while (true) {
            if (++probes > MAX_PROBES || cancelled.check()) {
                return null;
            }
            final Boolean craftable = oracle.craftable(probe);
            if (craftable == null) {
                return null;
            }
            if (!craftable) {
                break;
            }
            highestYes = probe;
            if (probe > Long.MAX_VALUE / 2L) {
                // Cannot double again without overflowing. Everything tried so far worked, so this
                // is the honest answer rather than a failure.
                return highestYes;
            }
            probe *= 2L;
        }
        // The answer is now somewhere in [highestYes, probe - 1]: highestYes worked, probe did not.
        long low = highestYes;
        long high = probe - 1L;
        while (low < high) {
            if (++probes > MAX_PROBES || cancelled.check()) {
                return null;
            }
            // Round the midpoint up, so low strictly increases and the loop cannot spin when the
            // two bounds are adjacent.
            final long midpoint = low + (high - low + 1L) / 2L;
            final Boolean craftable = oracle.craftable(midpoint);
            if (craftable == null) {
                return null;
            }
            if (craftable) {
                low = midpoint;
            } else {
                high = midpoint - 1L;
            }
        }
        return low;
    }
}
