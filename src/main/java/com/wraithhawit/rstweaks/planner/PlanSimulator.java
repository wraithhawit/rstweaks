package com.wraithhawit.rstweaks.planner;

import com.wraithhawit.rstweaks.planner.CraftingGraph.PatternEffect;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Checks that a solver solution can actually be executed, by replaying Refined
 * Storage's own scheduling.
 *
 * <p>This exists because the linear program is satisfied by solutions that cannot
 * run. The bucket case proves it: with no buckets in storage, the equations happily
 * return "craft 1000 cakes, 3000 milk buckets, 0 buckets" — the cycle nets to zero,
 * every constraint holds, and executing it deadlocks on the first iteration because
 * there is no bucket to start with. Equations describe a steady state; execution has
 * to get there one step at a time.
 *
 * <p>{@code InternalTaskPattern.step()} takes exactly one iteration per call, returns
 * {@code IDLE} when that iteration's inputs are not present, and otherwise consumes
 * inputs and produces outputs <em>and byproducts</em>. Replaying that greedily is a
 * faithful feasibility test: if every iteration can be retired here, the real
 * executor can retire them too, because it makes the same choice with the same
 * information.
 *
 * <p>On deadlock it names the resource class everything is waiting on, so the caller
 * can add a seed constraint and re-solve rather than giving up.
 */
public final class PlanSimulator {
    private PlanSimulator() {
    }

    /**
     * @param shortfalls every class a stalled pattern is waiting on, and by how much.
     *     The single {@code blockingClass} is the cheapest of these, which is the right
     *     choice for the seed loop but not for provisioning: the cheapest shortfall is
     *     often a crafted intermediate the network holds none of, and no amount of
     *     "extract more of it" will help. A caller that can see what the network stocks
     *     needs the whole set so it can pick a candidate that can actually be supplied.
     */
    public record Outcome(boolean executable,
                          int blockingClass,
                          long shortfall,
                          Map<Integer, Long> shortfalls) {
        public static Outcome ok() {
            return new Outcome(true, -1, 0L, Map.of());
        }

        static Outcome stalled(final Map<Integer, Long> shortfalls,
                               final int blockingClass,
                               final long shortfall) {
            return new Outcome(false, blockingClass, shortfall, Map.copyOf(shortfalls));
        }
    }

    /**
     * @param iterations  solver solution, one entry per pattern
     * @param initial     starting amount per resource class, drawn from the network
     * @param effects     per-pattern consumption/production in class indices
     * @param classCount  number of resource classes
     */
    public static Outcome simulate(final long[] iterations,
                                   final long[] initial,
                                   final List<PatternEffect> effects,
                                   final int classCount,
                                   final int maxPasses) {
        final long[] pool = initial.clone();
        final long[] remaining = iterations.clone();

        long outstanding = 0L;
        for (final long count : remaining) {
            outstanding += count;
        }
        if (outstanding == 0L) {
            return Outcome.ok();
        }

        // Greedy to a fixed point: keep retiring whatever iteration can run. Each
        // pass must retire at least one, otherwise nothing will ever become
        // available and we are deadlocked.
        //
        // A cycle retires only as many iterations as the containers in flight allow,
        // so passes scale with iteration count. The budget stops a huge request from
        // spending real time here; exceeding it reports "not executable" so the
        // caller falls back to stock rather than shipping something unverified.
        int passes = 0;
        while (outstanding > 0L) {
            if (++passes > maxPasses) {
                return new Outcome(false, -1, 0L, Map.of());
            }
            boolean progressed = false;
            for (int p = 0; p < remaining.length; p++) {
                if (remaining[p] <= 0L) {
                    continue;
                }
                final PatternEffect effect = effects.get(p);
                final long runnable = maxRunnable(effect, pool, remaining[p]);
                if (runnable <= 0L) {
                    continue;
                }
                apply(effect, pool, runnable);
                remaining[p] -= runnable;
                outstanding -= runnable;
                progressed = true;
            }
            if (!progressed) {
                return diagnose(remaining, pool, effects, classCount);
            }
        }
        return Outcome.ok();
    }

    /** How many iterations of this pattern the current pool supports, capped by what is left. */
    private static long maxRunnable(final PatternEffect effect, final long[] pool, final long cap) {
        long runnable = cap;
        for (final Map.Entry<Integer, Long> entry : effect.consumedByClass().entrySet()) {
            final long need = entry.getValue();
            if (need <= 0L) {
                continue;
            }
            final long have = pool[entry.getKey()];
            if (have < need) {
                return 0L;
            }
            runnable = Math.min(runnable, have / need);
        }
        return runnable;
    }

    private static void apply(final PatternEffect effect, final long[] pool, final long times) {
        effect.consumedByClass().forEach((cls, amount) -> pool[cls] -= amount * times);
        effect.producedByClass().forEach((cls, amount) -> pool[cls] += amount * times);
    }

    /**
     * Picks the resource class to seed. Among everything the stalled patterns are
     * short of, choose the <em>smallest</em> shortfall.
     *
     * <p>Seeding the largest looks appealing — it unblocks the most work — but it
     * over-constrains. In the cake case the biggest shortfall is 3 milk buckets, and
     * demanding three more of those means brewing 3003 from 3000 milk, which makes
     * the whole program infeasible and loses a plan that existed. The smallest
     * shortfall is the cheapest thing that can break the deadlock, and it is almost
     * always the recycled container itself, since a container is consumed one per
     * iteration while the things made from it are consumed in batches.
     */
    private static Outcome diagnose(final long[] remaining,
                                    final long[] pool,
                                    final List<PatternEffect> effects,
                                    final int classCount) {
        final Map<Integer, Long> shortfalls = new HashMap<>();
        for (int p = 0; p < remaining.length; p++) {
            if (remaining[p] <= 0L) {
                continue;
            }
            for (final Map.Entry<Integer, Long> entry : effects.get(p).consumedByClass().entrySet()) {
                final long missing = entry.getValue() - pool[entry.getKey()];
                if (missing > 0L) {
                    shortfalls.merge(entry.getKey(), missing, Math::max);
                }
            }
        }
        if (shortfalls.isEmpty()) {
            return new Outcome(false, -1, 0L, Map.of());
        }
        int cheapestClass = -1;
        long cheapestAmount = Long.MAX_VALUE;
        for (final Map.Entry<Integer, Long> entry : shortfalls.entrySet()) {
            // Ties broken by the lower class index so repeated solves of the same graph
            // make the same choice; HashMap order would make a plan depend on hashing.
            if (entry.getValue() < cheapestAmount
                || (entry.getValue() == cheapestAmount && entry.getKey() < cheapestClass)) {
                cheapestAmount = entry.getValue();
                cheapestClass = entry.getKey();
            }
        }
        return cheapestClass < 0
            ? new Outcome(false, -1, 0L, Map.copyOf(shortfalls))
            : Outcome.stalled(shortfalls, cheapestClass, cheapestAmount);
    }

    /** Convenience for callers that keep class availability as a list. */
    public static long[] initialPool(final List<CraftingGraph.ResourceClass> classes) {
        final List<Long> values = new ArrayList<>(classes.size());
        for (final CraftingGraph.ResourceClass cls : classes) {
            values.add(cls.available());
        }
        final long[] pool = new long[values.size()];
        for (int i = 0; i < pool.length; i++) {
            pool[i] = values.get(i);
        }
        return pool;
    }
}
