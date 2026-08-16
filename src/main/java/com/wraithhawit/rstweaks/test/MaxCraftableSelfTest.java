package com.wraithhawit.rstweaks.test;

import com.wraithhawit.rstweaks.planner.MaxCraftable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Checks the max-craftable search against oracles whose answer is known by construction.
 *
 * <p>Exists because the search backs the request screen's Max button, where a wrong answer is
 * either a refusal to craft something that is perfectly craftable, or a number the player then acts
 * on. It is a pure function over an oracle, so unlike most of this mod it can be verified without
 * Minecraft — which is the whole reason it was lifted out of the mixin.
 *
 * <p>The cases that matter are the boundaries: nothing craftable at all, exactly one, an amount
 * that lands on a power of two, one that does not, and an oracle that declines.
 */
public final class MaxCraftableSelfTest {
    private MaxCraftableSelfTest() {
    }

    private static final MaxCraftable.Cancelled NEVER = () -> false;

    public static CraftingPlanSelfTest.Result run() {
        final List<String> failures = new ArrayList<>();
        int scenarios = 0;

        // A plain threshold oracle: everything up to and including n is craftable.
        for (final long limit : new long[] {0L, 1L, 2L, 3L, 7L, 8L, 9L, 63L, 64L, 65L, 1000L, 99_999L}) {
            scenarios++;
            final Long got = MaxCraftable.search(amount -> amount <= limit, NEVER);
            if (got == null || got != limit) {
                failures.add("threshold " + limit + ": expected " + limit + ", got " + got);
            }
        }

        // Declining is not zero. This is the case that would report "0 craftable" for every
        // ordinary recipe if the three-valued oracle were ever collapsed to a boolean.
        scenarios++;
        if (MaxCraftable.search(amount -> null, NEVER) != null) {
            failures.add("a declining oracle must yield null, not a number");
        }

        // Declining partway through is still not an answer.
        scenarios++;
        final Long partial = MaxCraftable.search(amount -> amount <= 4L ? Boolean.TRUE : null, NEVER);
        if (partial != null) {
            failures.add("declining partway must yield null, got " + partial);
        }

        // Cancellation stops without inventing a result.
        scenarios++;
        if (MaxCraftable.search(amount -> Boolean.TRUE, () -> true) != null) {
            failures.add("a cancelled search must yield null");
        }

        // The probe ceiling is a real bound, not decoration: an oracle that never says no must
        // terminate rather than doubling forever.
        scenarios++;
        final AtomicInteger calls = new AtomicInteger();
        final Long unbounded = MaxCraftable.search(amount -> {
            calls.incrementAndGet();
            return Boolean.TRUE;
        }, NEVER);
        if (calls.get() > MaxCraftable.MAX_PROBES) {
            failures.add("probe ceiling exceeded: " + calls.get() + " calls");
        }
        if (unbounded == null && calls.get() < 2) {
            failures.add("an always-yes oracle should still make progress before stopping");
        }

        // Logarithmic, not linear. If the search ever degrades to counting upwards, a large answer
        // would mean tens of thousands of linear programs.
        scenarios++;
        final AtomicInteger counted = new AtomicInteger();
        MaxCraftable.search(amount -> {
            counted.incrementAndGet();
            return amount <= 100_000L;
        }, NEVER);
        if (counted.get() > 40) {
            failures.add("search took " + counted.get() + " probes for 100,000; expected ~34");
        }

        return new CraftingPlanSelfTest.Result(scenarios, failures);
    }
}
