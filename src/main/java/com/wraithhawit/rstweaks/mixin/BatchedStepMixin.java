package com.wraithhawit.rstweaks.mixin;

import com.refinedmods.refinedstorage.api.autocrafting.Ingredient;
import com.refinedmods.refinedstorage.api.autocrafting.Pattern;
import com.refinedmods.refinedstorage.api.autocrafting.PatternLayout;
import com.refinedmods.refinedstorage.api.autocrafting.task.ExternalPatternSinkProvider;
import com.refinedmods.refinedstorage.api.autocrafting.task.TaskListener;
import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.api.resource.list.MutableResourceList;
import com.refinedmods.refinedstorage.api.storage.root.RootStorage;
import com.wraithhawit.rstweaks.Config;
import com.wraithhawit.rstweaks.RSTweaks;
import com.wraithhawit.rstweaks.Stats;
import com.wraithhawit.rstweaks.pattern.PatternStepResults;
import com.wraithhawit.rstweaks.planner.Durability;
import com.wraithhawit.rstweaks.storage.TaskPatternInternals;

import java.util.LinkedHashMap;
import java.util.Map;

import javax.annotation.Nullable;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Phase 05: runs many iterations of one pattern in a single extraction and insertion.
 *
 * <p>Refined Storage steps a task by calling {@code step()} once per iteration, and each call
 * builds two resource lists, extracts twice and inserts per output. Fed ~10<sup>5</sup> steps a
 * tick by a multiblock crafter, that loop is 94–99% of the server thread in both profiles taken of
 * this pack. Doing N iterations at once replaces N extractions and N insertions with one of each;
 * the ingredient budget is still drawn N times, but that is map arithmetic rather than allocation
 * and network traffic.
 *
 * <h2>What it refuses to touch</h2>
 *
 * <p>This is the only optimization in this mod that changes when items move, so the guard is
 * deliberately wider than the arithmetic strictly requires. It batches nothing that
 *
 * <ul>
 *   <li><b>has byproducts</b> — which is every container, every catalyst and every tool. Their
 *       byproducts go through {@link InternalTaskPatternMixin}, which ages a returned tool from
 *       what was actually consumed <em>this iteration</em>. Batching that would age one tool for N
 *       iterations and the crystal would stop wearing out;</li>
 *   <li><b>uses something that wears out</b> — {@link AbstractTaskPatternMixin} substitutes a worn
 *       tool from inside {@code extractAll}, and a batched path does not call it;</li>
 *   <li><b>consumes what it produces</b> — self-duplication, where iteration two needs what
 *       iteration one made. Checked against outputs directly, which is exact here: two wear levels
 *       of a tool are different resources, and only the durability guard above catches those.</li>
 * </ul>
 *
 * <p>Everything else falls through to Refined Storage's own stepping, unchanged. A pattern this
 * cannot prove safe costs the optimization and nothing else.
 *
 * <h2>Why it cannot throw</h2>
 *
 * <p>{@code TaskContainer.step} treats <em>any</em> exception out of a task step as completion: it
 * logs, marks the task complete, notifies listeners and drops it — internal storage and all. So a
 * throw from here would not degrade autocrafting, it would <b>destroy the items the task was
 * holding</b> and tell the player it succeeded.
 *
 * <p>The decision half is therefore wrapped, and it is also arranged so that a failure has nothing
 * to undo: everything is computed against copies, and the point of no return is a single block at
 * the end that mutates the budget, the storage and the counter together.
 */
@Mixin(targets = "com.refinedmods.refinedstorage.api.autocrafting.task.InternalTaskPattern")
public abstract class BatchedStepMixin {
    @Shadow
    private long iterationsRemaining;

    @Shadow
    private void returnOutput(final MutableResourceList internalStorage,
                              final RootStorage rootStorage,
                              final ResourceAmount output) {
        throw new AssertionError("shadow");
    }

    @Inject(method = "step", at = @At("HEAD"), cancellable = true, require = 0)
    private void rstweaks$batchStep(final MutableResourceList internalStorage,
                                    final RootStorage rootStorage,
                                    final ExternalPatternSinkProvider sinkProvider,
                                    final TaskListener listener,
                                    final CallbackInfoReturnable<Object> cir) {
        if (!Config.batchedExecution || this.iterationsRemaining <= 1L
            || !PatternStepResults.available()) {
            return;
        }
        try {
            rstweaks$tryBatch(internalStorage, rootStorage, cir);
        } catch (RuntimeException | LinkageError e) {
            // Not cancelling leaves Refined Storage's own step to run, which is the behaviour
            // without this mixin. Logged rather than swallowed, because a batch that silently
            // stops batching is indistinguishable from one that was never installed.
            RSTweaks.LOGGER.error("[rstweaks] batched stepping failed for {}; this iteration runs "
                + "the way Refined Storage would. The task continues.",
                ((TaskPatternInternals) this).rstweaks$pattern(), e);
        }
    }

    @Unique
    private void rstweaks$tryBatch(final MutableResourceList internalStorage,
                                   final RootStorage rootStorage,
                                   final CallbackInfoReturnable<Object> cir) {
        final TaskPatternInternals self = (TaskPatternInternals) this;
        final PatternLayout layout = self.rstweaks$pattern().layout();
        if (!rstweaks$canBatch(layout)) {
            return;
        }

        // Everything below this line works on copies. Nothing the task can see changes until the
        // commit block at the end, so an exception anywhere here leaves the serial path a clean
        // slate to run on.
        final Map<Integer, Map<ResourceKey, Long>> budget = new LinkedHashMap<>();
        self.rstweaks$ingredients().forEach((slot, possibilities) ->
            budget.put(slot, new LinkedHashMap<>(possibilities)));

        final Map<ResourceKey, Long> totals = new LinkedHashMap<>();
        final long ceiling = Math.min(this.iterationsRemaining, Config.maxBatchedIterations);
        long iterations = 0L;
        while (iterations < ceiling) {
            final Map<Integer, Map<ResourceKey, Long>> draw = rstweaks$drawOne(budget, layout);
            if (draw == null) {
                break;
            }
            final Map<ResourceKey, Long> flat = new LinkedHashMap<>();
            draw.values().forEach(fromSlot -> fromSlot.forEach((resource, amount) ->
                flat.merge(resource, amount, Long::sum)));
            if (!rstweaks$affordable(internalStorage, totals, flat)) {
                // Nothing has been spent: the draw is only applied to the budget once the storage
                // has been shown to cover it. An earlier version of this loop spent the draw first
                // and then broke out, which committed a budget one iteration further on than the
                // work actually done -- ingredients quietly missing from the rest of the plan.
                break;
            }
            draw.forEach((slot, fromSlot) -> fromSlot.forEach((resource, amount) ->
                budget.get(slot).merge(resource, -amount, Long::sum)));
            flat.forEach((resource, amount) -> totals.merge(resource, amount, Long::sum));
            iterations++;
        }

        if (iterations <= 1L) {
            // One iteration is what Refined Storage already does, and doing it here would only
            // duplicate its logic for no gain.
            return;
        }

        // ---- commit ----
        self.rstweaks$ingredients().clear();
        self.rstweaks$ingredients().putAll(budget);
        totals.forEach(internalStorage::remove);
        for (final ResourceAmount output : layout.outputs()) {
            returnOutput(internalStorage, rootStorage,
                new ResourceAmount(output.resource(), Math.multiplyExact(output.amount(), iterations)));
        }
        this.iterationsRemaining -= iterations;
        Stats.batchedIterations += iterations;
        Stats.batchedSteps++;
        cir.setReturnValue(rstweaks$result());
    }

    /**
     * The guard's answer, decided once.
     *
     * <p>It cannot change: the layout is immutable and whether an item wears out is a property of
     * the item. Recomputing it per step cost <b>1.33% of the server thread</b> in profile
     * {@code 4JnFBQWZwg} — a crystal craft, where the answer is "no" every time and the whole path
     * is a refusal. Paying a walk of every ingredient against every output, plus a durability
     * lookup per input, a hundred thousand times a tick to reach the same conclusion is worse than
     * not having the optimization at all.
     */
    @Unique
    private Boolean rstweaks$batchable;

    @Unique
    private boolean rstweaks$canBatch(final PatternLayout layout) {
        final Boolean cached = this.rstweaks$batchable;
        if (cached != null) {
            return cached;
        }
        final boolean decided = rstweaks$decideCanBatch(layout);
        this.rstweaks$batchable = decided;
        return decided;
    }

    /**
     * The guard itself. Wider than the arithmetic needs, on purpose — see the class notes.
     */
    @Unique
    private boolean rstweaks$decideCanBatch(final PatternLayout layout) {
        if (!layout.byproducts().isEmpty()) {
            return false;
        }
        final Durability durability = Durability.Holder.get();
        for (final Ingredient ingredient : layout.ingredients()) {
            for (final ResourceKey input : ingredient.inputs()) {
                if (durability.isDurable(input)) {
                    return false;
                }
                for (final ResourceAmount output : layout.outputs()) {
                    if (output.resource().equals(input)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /**
     * One iteration's draw from the ingredient budget, by exactly the greedy Refined Storage uses.
     *
     * <p>Returns {@code null} when a slot cannot be satisfied, where Refined Storage throws. The
     * batch simply stops there and the serial path throws it as it always would, so this cannot
     * turn a loud failure into a quiet one.
     */
    @Nullable
    @Unique
    private Map<Integer, Map<ResourceKey, Long>> rstweaks$drawOne(
        final Map<Integer, Map<ResourceKey, Long>> budget,
        final PatternLayout layout
    ) {
        final Map<Integer, Map<ResourceKey, Long>> taken = new LinkedHashMap<>();
        for (final Map.Entry<Integer, Map<ResourceKey, Long>> slot : budget.entrySet()) {
            long needed = layout.ingredients().get(slot.getKey()).amount();
            final Map<ResourceKey, Long> fromSlot = new LinkedHashMap<>();
            for (final Map.Entry<ResourceKey, Long> possibility : slot.getValue().entrySet()) {
                final long available = Math.min(needed, possibility.getValue());
                if (available == 0L) {
                    continue;
                }
                fromSlot.merge(possibility.getKey(), available, Long::sum);
                needed -= available;
                if (needed == 0L) {
                    break;
                }
            }
            if (needed != 0L) {
                return null;
            }
            taken.put(slot.getKey(), fromSlot);
        }
        return taken;
    }

    @Unique
    private boolean rstweaks$affordable(final MutableResourceList internalStorage,
                                        final Map<ResourceKey, Long> totals,
                                        final Map<ResourceKey, Long> draw) {
        for (final Map.Entry<ResourceKey, Long> want : draw.entrySet()) {
            final long running = totals.getOrDefault(want.getKey(), 0L) + want.getValue();
            if (internalStorage.get(want.getKey()) < running) {
                return false;
            }
        }
        return true;
    }

    @Unique
    private Object rstweaks$result() {
        return this.iterationsRemaining == 0L
            ? PatternStepResults.COMPLETED
            : PatternStepResults.RUNNING;
    }
}
