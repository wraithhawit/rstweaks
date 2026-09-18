package com.wraithhawit.rstweaks.sink;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import javax.annotation.Nullable;

/**
 * Remembers, for the length of one stepping burst, which sinks have already said no.
 *
 * <h2>Why this exists</h2>
 *
 * {@code ExternalTaskPattern.getSinkThatIsAcceptingResources} walks every sink, simulating a full
 * {@code accept} on each, and returns null only after exhausting the list. That is one full
 * validation sweep per {@code step()} -- and {@code step()} does not run once per tick:
 *
 * <pre>{@code   int steps = stepBehavior.getSteps(pattern.getKey());
 *   for (int i = 0; i < steps; i++) {
 *       pattern.getValue().step(...);
 *   } }</pre>
 *
 * <p>rsmbac supplies that budget from its structure -- {@code StructureStepBehavior.stepsPerTick},
 * the sum of the multiblock's CPU tiers -- so the cost of a tick where every machine is busy is
 * <b>steps x sinks x validation</b>, asking the same question with the same inputs and getting the
 * same answer every time. Measured on a survival world with a large multiblock and mekmm Stamping
 * Factories attached: {@code getSinkThatIsAcceptingResources} was 35.47% of the server thread,
 * essentially all of it inside Mekanism's {@code isItemValidForInsertion}, which runs a full recipe
 * lookup per probe.
 *
 * <h2>What is cached, and what bounds it</h2>
 *
 * <p><b>Rejections only, never an acceptance.</b> A sink that accepts is used immediately, so
 * there is nothing to remember; a sink that refused is the only answer worth reusing. The same
 * distinction is what justified {@code CachedFailedInsertInventoryHandlerMixin}, and for the same
 * reason: a cached rejection can delay a craft, a cached acceptance can send items into a machine
 * that no longer has room.
 *
 * <p>Three things bound the staleness:
 *
 * <ul>
 *   <li><b>One burst.</b> {@link #newBurst()} is called from {@code TaskImpl.step}, once per task
 *       per tick, and every cache whose epoch is older than the current one empties on next use.
 *       Nothing survives into another tick.</li>
 *   <li><b>Any acceptance clears it.</b> Accepting inserts resources, which is the one thing this
 *       code does that changes what a sink will say next. Inserting makes a sink fuller rather
 *       than emptier, so a rejection would stay true anyway -- it is dropped regardless, because
 *       being right about that requires reasoning about every sink implementation from outside.</li>
 *   <li><b>Identity, not equality.</b> Sinks come from {@code AutocraftingNetworkComponentImpl}'s
 *       stored {@code sinksByPatternLayout} map and are long-lived objects, so identity is their
 *       real identity. {@code getKey()} is {@code @Nullable} with a null default and cannot be
 *       relied on.</li>
 * </ul>
 *
 * <p><b>The staleness that remains</b> is a sink whose answer changes mid-tick for a reason other
 * than our own insert -- another task pulling from a machine's output slot and freeing it, say.
 * The craft it would have placed happens on the next tick instead. That is the same bound the
 * existing failed-insert cache already accepts.
 *
 * <p>Held per {@code ExternalTaskPattern} rather than globally: the answer depends on the
 * pattern's inputs, and a per-instance set needs no compound key. Two instances of the same
 * pattern each pay their own first sweep, which does not defeat this -- for K instances taking M
 * attempts against S busy sinks, expensive probes fall from roughly K x M x S to K x S.
 *
 * <p><b>The assumption this rests on, stated so it can be disproved:</b> that within one burst,
 * and without an acceptance in between, the resources a pattern offers a sink do not change.
 * {@code calculateIterationInputs(SIMULATE)} is recomputed on every attempt, and only sink
 * identity is keyed -- so if those inputs can differ between attempts, a refusal recorded for one
 * set of resources would be reused for another. Nothing extracts from the task's internal storage
 * without an acceptance, which is why this is believed to hold, but it has not been proven against
 * every sink implementation and it is not something this class can check. If it is ever false the
 * cost is a craft placed later than it could have been, never resources sent somewhere they should
 * not go -- acceptances are not cached, so no craft is placed on a stale yes.
 */
public final class SinkRejectionCache {
    /**
     * Bumped once per {@code TaskImpl.step}. A counter rather than a game tick because this class
     * carries no Minecraft types, and because the burst -- not the tick -- is the real scope: it
     * is exactly the window in which {@code steps} repeats the same question. It counts task-step
     * invocations, not ticks, and does not need to be either unique or monotonic per tick; all
     * that is asked of it is that it change.
     *
     * <p>Atomic rather than a plain {@code long}, although task stepping is believed to be
     * server-thread-only. A lost update here is not a lost cache hit, it is a <em>wrong</em>
     * answer: two interleaved increments can leave the counter back on a value a cache has already
     * recorded as seen, and that cache then keeps the previous burst's refusals instead of
     * dropping them. One uncontended {@code incrementAndGet} per task per tick is not worth
     * reasoning about that for, and the belief about the thread is not one this class can check.
     * It does <b>not</b> make the per-instance maps below thread-safe, and is not meant to --
     * those belong to one {@code ExternalTaskPattern}, which belongs to one task.
     */
    private static final AtomicLong BURST = new AtomicLong();

    private final Set<Object> rejected = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<Object, Object> results = new IdentityHashMap<>();
    private long seenBurst = Long.MIN_VALUE;

    /** Starts a new stepping burst. Existing caches empty themselves on their next use. */
    public static void newBurst() {
        BURST.incrementAndGet();
    }

    /** Test seam: the current burst number. */
    public static long currentBurst() {
        return BURST.get();
    }

    private void rstweaksSyncBurst() {
        final long now = BURST.get();
        if (this.seenBurst != now) {
            this.seenBurst = now;
            this.rejected.clear();
            this.results.clear();
        }
    }

    /**
     * The answer this sink already gave in this burst, or {@code null} if it must be asked.
     *
     * <p>Never returns an acceptance: those are not recorded.
     */
    @Nullable
    public Object cachedRejection(final Object sink) {
        this.rstweaksSyncBurst();
        if (!this.rejected.contains(sink)) {
            return null;
        }
        return this.results.get(sink);
    }

    /**
     * Files what a sink actually answered.
     *
     * <p>An acceptance empties the cache instead of being stored, because the caller is about to
     * insert into that sink. Whether a result counts as an acceptance is the caller's to say:
     * this class holds no Refined Storage types, so that {@code HeadlessSinkCheck} can exercise
     * it in a plain JVM, the same reason {@code SlotBackoff} holds none.
     */
    public void record(final Object sink, final Object result, final boolean accepted) {
        this.rstweaksSyncBurst();
        if (accepted) {
            this.rejected.clear();
            this.results.clear();
            return;
        }
        this.rejected.add(sink);
        this.results.put(sink, result);
    }

    /** Test seam: how many sinks are currently remembered as refusing. */
    public int size() {
        this.rstweaksSyncBurst();
        return this.rejected.size();
    }
}
