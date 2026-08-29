package com.wraithhawit.rstweaks.ledger;

import java.util.Map;
import java.util.Set;

/**
 * Phase 05, the decision half: how many iterations of one pattern may be run as a single
 * extraction and insertion.
 *
 * <p>Refined Storage steps a task by calling {@code InternalTaskPattern.step} once per iteration,
 * and each call builds two resource lists, extracts twice and inserts per output. With a mod
 * feeding it ~10<sup>5</sup> steps a tick that loop is 94–99% of the server thread in both profiles
 * we have. Doing N iterations in one pass is the only phase of the ledger model that nobody — not
 * this mod, not Nodrance — has built.
 *
 * <p>This class is only the <em>decision</em>: it holds no Minecraft types, does no extraction, and
 * answers one question with arithmetic that can be tested in a plain JVM. Whatever performs the
 * batch asks this first and runs serially whenever the answer is one.
 *
 * <h2>When a batch is futile</h2>
 *
 * <p>One rule: <b>a pattern whose inputs intersect its own outputs feeds itself across
 * iterations</b>, and a batch demands up front what only the previous iteration can supply. A worn
 * tool is the clearest case — iteration two wants the {@code crystal@1} that iteration one handed
 * back, and the plan's ingredient budget lists it precisely because it expects that. Ask for both
 * at once and the extraction cannot be satisfied.
 *
 * <p><b>In stock Refined Storage this is futile rather than dangerous</b>, and the distinction was
 * worth chasing down. An extraction is bounded by what the task is holding, so a batch can never
 * take more than exists: a catalyst batched against sixty-four crystals extracts sixty-four and
 * returns sixty-four, ending where serial ends.
 *
 * <p><b>In this mod it is dangerous, because of another tweak.</b> {@code InternalTaskPatternMixin}
 * ages a byproduct from the tool actually consumed, so the crystal comes back one step more worn
 * each iteration instead of being handed back at the damage the pattern was encoded with. Batch N
 * iterations and that ageing happens once, for N tools: the progression collapses and the tool
 * stops wearing out. Without that mixin a worn tool is a repair station; with it and without this
 * rule, a batch is a durability duplication glitch.
 *
 * <p>The real item-loss risk in this phase belongs to the executor, not here: a throw after a
 * partial extraction, on a path where {@code TaskContainer.step} treats any exception as
 * completion, logs it, and drops the task's internal storage. That is what the batched executor has
 * to be careful about, and no arithmetic in this class can help it.
 *
 * <p>A container is <em>not</em> self-feeding and batches happily: filling buckets consumes empties
 * and produces filled ones, and the empties come back from a different pattern on a different step.
 * The rule keeps the cases apart without knowing what any of them are.
 */
public final class BatchPolicy {
    private BatchPolicy() {
    }

    /**
     * @param iterations how many to run in one pass; 1 means "serial, as Refined Storage does it",
     *                   0 means nothing can run right now
     * @param reason     what limited it, for a log worth reading when a batch is unexpectedly small
     */
    public record Decision(long iterations, String reason) {
        public boolean batched() {
            return this.iterations > 1L;
        }

        public boolean idle() {
            return this.iterations == 0L;
        }
    }

    /**
     * @param needsPerIteration what one iteration extracts, per column
     * @param produces          every column the pattern returns — outputs and byproducts alike
     * @param available         what the task's internal storage holds
     * @param iterationsLeft    what remains of this pattern's plan
     * @param cap               the caller's throughput budget for this tick
     */
    public static Decision decide(final Map<Integer, Long> needsPerIteration,
                                  final Set<Integer> produces,
                                  final Map<Integer, Long> available,
                                  final long iterationsLeft,
                                  final long cap) {
        return decide(needsPerIteration, available, iterationsLeft, cap,
            feedsItself(needsPerIteration, produces));
    }

    /**
     * Whether a pattern consumes what it produces, <b>in column space</b>.
     *
     * <p>This is the subtlety that makes the overload above dangerous to call carelessly, and it
     * is worth being exact. {@code crystal@0} and {@code crystal@1} are different resources; they
     * are the same <em>column</em> only after {@link Pools} has folded a tool's wear levels
     * together. Hand this raw resource keys and a wearing tool looks like an ordinary pattern,
     * because the level it consumes and the level it returns are two different keys.
     *
     * <p>A caller that has not pooled its keys must work out the answer some other way — asking
     * whether any input is durable is enough — and use
     * {@link #decide(Map, Map, long, long, boolean)} instead of this.
     */
    public static boolean feedsItself(final Map<Integer, Long> needsPerIteration,
                                      final Set<Integer> produces) {
        return needsPerIteration.entrySet().stream()
            .anyMatch(need -> need.getValue() > 0L && produces.contains(need.getKey()));
    }

    /**
     * The decision with {@code feedsItself} already settled by a caller that knows how.
     *
     * <p>The executor uses this one: it holds {@code ResourceKey}s rather than columns, so it
     * answers the question from {@code Durability} instead of from set intersection.
     */
    public static Decision decide(final Map<Integer, Long> needsPerIteration,
                                  final Map<Integer, Long> available,
                                  final long iterationsLeft,
                                  final long cap,
                                  final boolean feedsItself) {
        if (iterationsLeft <= 0L || cap <= 0L) {
            return new Decision(0L, "nothing left to run");
        }
        if (feedsItself) {
            // Serial is not a fallback here, it is the correct answer: the next iteration is
            // meant to consume what this one hands back.
            return new Decision(Math.min(1L, affordable(needsPerIteration, available)),
                "runs serially: it consumes what it produces");
        }

        final long affordable = affordable(needsPerIteration, available);
        if (affordable == 0L) {
            return new Decision(0L, "nothing in the task's storage to run it with");
        }
        final long batch = Math.min(Math.min(affordable, iterationsLeft), cap);
        if (batch == affordable && affordable < iterationsLeft && affordable < cap) {
            return new Decision(batch, "limited by what the task is holding");
        }
        if (batch == iterationsLeft) {
            return new Decision(batch, "the rest of the plan");
        }
        return new Decision(batch, "limited by the step budget");
    }

    /**
     * How many whole iterations the storage covers.
     *
     * <p>A pattern with no inputs at all would be unbounded, so it is capped by the caller rather
     * than returned as {@link Long#MAX_VALUE} — an infinity that reaches a multiplication is an
     * overflow waiting for a big enough plan.
     */
    private static long affordable(final Map<Integer, Long> needsPerIteration,
                                   final Map<Integer, Long> available) {
        long batch = Long.MAX_VALUE;
        for (final Map.Entry<Integer, Long> need : needsPerIteration.entrySet()) {
            if (need.getValue() <= 0L) {
                continue;
            }
            batch = Math.min(batch, available.getOrDefault(need.getKey(), 0L) / need.getValue());
            if (batch == 0L) {
                return 0L;
            }
        }
        return batch;
    }
}
