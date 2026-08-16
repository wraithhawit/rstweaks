package com.wraithhawit.rstweaks.mixin;

import com.refinedmods.refinedstorage.api.autocrafting.Pattern;
import com.refinedmods.refinedstorage.api.autocrafting.task.TaskPlan;
import com.refinedmods.refinedstorage.api.core.Action;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.api.resource.list.MutableResourceList;
import com.refinedmods.refinedstorage.api.resource.list.ResourceList;
import com.wraithhawit.rstweaks.Config;
import com.wraithhawit.rstweaks.RSTweaks;
import com.wraithhawit.rstweaks.planner.Durability;
import com.wraithhawit.rstweaks.storage.TaskConsumption;
import com.wraithhawit.rstweaks.storage.WornToolAware;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets a crafting task use the tool it actually has, whatever state of wear it is in.
 *
 * <p>A pattern records the exact resources it was encoded with, and damage lives in an
 * item's component patch, so {@code crystal@0} and {@code crystal@1} are different
 * resources. A pattern encoded with a fresh Mystical Agriculture infusion crystal
 * therefore asks for a fresh crystal on every iteration — while the task's internal
 * storage, after the first craft, holds one at damage 1. The exact match fails and the
 * task idles forever.
 *
 * <p>This substitutes at the point the ingredients meet the storage: if the requested
 * resource is a tool that wears out and is not present, but the same tool at some other
 * wear level is, that is what gets used. Which one was taken is remembered so
 * {@link InternalTaskPatternMixin} can hand back the right one — Applied Energistics does
 * the same thing through {@code IPatternDetails.IInput.getRemainingKey}, deriving the
 * remainder from the item actually consumed rather than from the encoded template.
 *
 * <p>Substituting here rather than in {@code calculateIterationInputs} is deliberate:
 * this is the only place with both the ingredient list and the internal storage in scope,
 * and it leaves Refined Storage's per-ingredient budget accounting untouched. The budget
 * totals {@code amount × iterations} because the plan credits the returned tool as
 * supply, so it still lasts exactly as many iterations as there are — it merely names the
 * wrong wear levels, which is what this corrects.
 */
@Mixin(targets = "com.refinedmods.refinedstorage.api.autocrafting.task.AbstractTaskPattern")
public abstract class AbstractTaskPatternMixin implements WornToolAware {
    /**
     * The tools taken this iteration, so each byproduct can be aged to match.
     *
     * <p>A list rather than one field: a recipe may burn two different tools, and recording only
     * the last one meant the other's byproduct came back as encoded — a repaired tool, which is
     * durability created from nothing.
     *
     * <p><b>Deliberately not {@code final} and deliberately without an inline initializer.</b>
     * It had both from 0.2.57 until 0.2.64, and the initializer did not reach the instance:
     * {@code rstweaks$substituteWornTool} threw {@code NullPointerException} on
     * {@code rstweaks$consumed.clear()}, which is the first statement it runs and sits above the
     * {@code durabilityAwarePlanning} check, so the config could not switch it off. Refined
     * Storage catches that in {@code TaskContainer.step}, sets {@code completed = true}, fires
     * {@code taskCompleted} — the toast — and drops the task <em>with its internal storage still
     * in it</em>. Materials extracted, no output, "task completed": items destroyed.
     *
     * <p>{@code ItemHandlerExtractableStorageMixin} already carried this lesson in a comment —
     * Mixin handles field initialisers unreliably — and this class did not follow it. Every
     * {@code @Unique} instance field here now defaults to null and is read through an accessor
     * that copes, which is correct however Mixin chooses to treat the initializer.
     */
    @Unique
    @Nullable
    private List<ResourceKey> rstweaks$consumed;

    /**
     * Captured at construction, because that is the only moment the task's plan is in
     * scope. A pattern has no other way to learn what its siblings need.
     *
     * <p>Null-safe for the same reason as {@link #rstweaks$consumed}. This one never threw,
     * because the {@code <init>} injector below assigns it on every path — which is precisely
     * what disguised the problem: the injector ran, so the class looked initialised.
     */
    @Unique
    @Nullable
    private Set<ResourceKey> rstweaks$taskConsumes;

    /** The record of tools taken, created on first use rather than at construction. */
    @Unique
    private List<ResourceKey> rstweaks$consumedList() {
        List<ResourceKey> consumed = this.rstweaks$consumed;
        if (consumed == null) {
            consumed = new ArrayList<>(1);
            this.rstweaks$consumed = consumed;
        }
        return consumed;
    }

    @Nullable
    @Override
    public ResourceKey rstweaks$consumedTool(final ResourceKey encoded) {
        final List<ResourceKey> consumed = this.rstweaks$consumed;
        if (consumed == null || consumed.isEmpty()) {
            return null;
        }
        final Durability durability = Durability.Holder.get();
        for (final ResourceKey taken : consumed) {
            if (durability.sameTool(taken, encoded)) {
                return taken;
            }
        }
        return null;
    }

    @Override
    public Set<ResourceKey> rstweaks$taskConsumes() {
        final Set<ResourceKey> consumes = this.rstweaks$taskConsumes;
        return consumes == null ? Set.of() : consumes;
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void rstweaks$rememberTaskConsumption(final Pattern pattern,
                                                 final TaskPlan.PatternPlan plan,
                                                 final CallbackInfo ci) {
        this.rstweaks$taskConsumes = TaskConsumption.current();
    }

    /**
     * Wrapped so that nothing this optimization does can ever kill a task.
     *
     * <p>{@code TaskContainer.step} treats <em>any</em> exception out of a task step as
     * completion: it logs, marks the task completed, notifies listeners and drops it — internal
     * storage and all. So a throw from here does not degrade autocrafting, it <b>destroys the
     * items the task was holding</b> and tells the player it succeeded. That is what the null
     * field did for seven versions. Substituting a worn tool is an optimization; failing to do it
     * must cost the optimization and nothing else.
     */
    @Inject(method = "extractAll", at = @At("HEAD"))
    private void rstweaks$substituteWornTool(final ResourceList inputs,
                                           final MutableResourceList internalStorage,
                                           final Action action,
                                           final CallbackInfoReturnable<Boolean> cir) {
        try {
            rstweaks$trySubstituteWornTool(inputs, internalStorage);
        } catch (RuntimeException | LinkageError e) {
            RSTweaks.LOGGER.error("[rstweaks] durability substitution failed; leaving this "
                + "iteration's ingredients untouched. The task continues.", e);
        }
    }

    @Unique
    private void rstweaks$trySubstituteWornTool(final ResourceList inputs,
                                               final MutableResourceList internalStorage) {
        // Cleared every call, including the SIMULATE that precedes each EXECUTE, so a
        // tool recorded by one iteration can never age the byproduct of the next.
        this.rstweaks$consumedList().clear();
        if (!Config.durabilityAwarePlanning) {
            return;
        }
        final Durability durability = Durability.Holder.get();
        if (!(inputs instanceof MutableResourceList mutableInputs)) {
            return;
        }

        // Collected before applying: rewriting the list while iterating its key set
        // would throw, and the substitution has to be decided against a stable view.
        final List<ResourceKey[]> swaps = new ArrayList<>(1);
        for (final ResourceKey wanted : inputs.getAll()) {
            if (!durability.isDurable(wanted)) {
                continue;
            }
            final long needed = inputs.get(wanted);
            if (internalStorage.get(wanted) >= needed) {
                // The encoded wear level happens to be the one in hand.
                this.rstweaks$consumedList().add(wanted);
                continue;
            }
            final ResourceKey substitute = findWornTool(internalStorage, durability, wanted, needed);
            if (substitute != null) {
                swaps.add(new ResourceKey[] {wanted, substitute});
                this.rstweaks$consumedList().add(substitute);
            }
        }

        for (final ResourceKey[] swap : swaps) {
            final long amount = inputs.get(swap[0]);
            mutableInputs.remove(swap[0], amount);
            mutableInputs.add(swap[1], amount);
        }
    }

    /** The most worn matching tool that still covers this iteration. */
    @Unique
    @Nullable
    private static ResourceKey findWornTool(final MutableResourceList internalStorage,
                                            final Durability durability,
                                            final ResourceKey wanted,
                                            final long needed) {
        ResourceKey best = null;
        int bestLeft = Integer.MAX_VALUE;
        for (final ResourceKey candidate : internalStorage.getAll()) {
            if (!durability.sameTool(wanted, candidate)
                || internalStorage.get(candidate) < needed) {
                continue;
            }
            // Finish off the most worn one first, so a nearly spent crystal is used up
            // before a fresh one is touched. Same reasoning as the planner's requisition.
            final int left = durability.usesLeft(candidate);
            if (left > 0 && left < bestLeft) {
                bestLeft = left;
                best = candidate;
            }
        }
        return best;
    }
}
