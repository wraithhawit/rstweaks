package com.wraithhawit.rstweaks.mixin;

import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.wraithhawit.rstweaks.Config;
import com.wraithhawit.rstweaks.Stats;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Copy-on-write for autocrafting pattern plans.
 *
 * <p>Diagnosis: the spike profile — 25.3s of laggy ticks sampled out of 300s —
 * is dominated by crafting calculation, and {@code MutablePatternPlan.copy} is the
 * single largest self-time frame in it at 6.71%. At baseline it was 46.5% of the
 * entire server thread. Each doomed Step Requester calculation costs roughly 94ms,
 * which is a visible tick hitch.
 *
 * <p>Cause: the crafting calculator explores a tree, and snapshots the whole plan
 * at every child calculation so a failed branch can be discarded.
 * {@code MutableTaskPlan.copy} deep-copies every {@code MutablePatternPlan}, and
 * each of those allocates a fresh {@link LinkedHashMap} for every ingredient index.
 * Nearly all of that copying is wasted: the overwhelming majority of snapshots are
 * either discarded untouched or mutated at only one index.
 *
 * <p>Fix: {@code copy()} now shares the inner ingredient maps instead of duplicating
 * them, and {@code addUsedIngredient} takes a private copy of an index's map before
 * its first write since the last share. The numbers justify the trade directly — at
 * baseline {@code copy} was 46.5% of the thread against {@code addUsedIngredient}'s
 * 4.6%, so moving work from copy-time to write-time is roughly a ten-to-one win even
 * if every shared map is eventually written.
 *
 * <p>Correctness rests on one invariant: <b>no plan ever mutates a map another plan
 * can observe.</b> Sharing happens only in {@code copy()}, which clears this plan's
 * ownership claim; both the source and the new copy must then take their own map
 * before writing. Two plans sharing map {@code M} at index {@code i} each copy
 * {@code M} on their own first write, so neither ever sees the other's mutation.
 * Reads ({@code getPlan}) never mutate, and {@code iterations} is a primitive copied
 * by value.
 *
 * <p>This is the most invasive change in the mod, so it has a kill switch:
 * {@code lazyPatternPlanCopy}. Toggling it at runtime is safe in both directions,
 * because the copy-before-write guard stays active regardless — disabling only makes
 * {@code copy()} duplicate eagerly again, which is redundant rather than incorrect.
 *
 * <p>Targeted by name because {@code MutablePatternPlan} is package-private and
 * cannot be referenced from here.
 */
@Mixin(targets = "com.refinedmods.refinedstorage.api.autocrafting.task.MutablePatternPlan")
public abstract class MutablePatternPlanMixin {
    @Shadow
    private Map<Integer, Map<ResourceKey, Long>> ingredients;

    /**
     * Ingredient indices whose map this plan exclusively owns and may mutate in
     * place. Lazily created: Mixin's handling of instance field initializers on
     * {@code @Unique} fields is unreliable, so this must not depend on one.
     */
    @Unique
    private Set<Integer> rstweaks$ownedIndices;

    @Redirect(
        method = "copy",
        at = @At(value = "NEW", target = "(Ljava/util/Map;)Ljava/util/LinkedHashMap;")
    )
    private LinkedHashMap<ResourceKey, Long> rstweaks$shareInsteadOfCopying(
        final Map<ResourceKey, Long> source
    ) {
        if (!Config.lazyPatternPlanCopy) {
            return new LinkedHashMap<>(source);
        }
        // The new plan and this one now both reference `source`, so neither may
        // write to it in place. Dropping the ownership claim forces whichever
        // writes next to take its own copy first.
        if (this.rstweaks$ownedIndices != null) {
            this.rstweaks$ownedIndices.clear();
        }
        ++Stats.patternPlanCopiesAvoided;
        return (LinkedHashMap<ResourceKey, Long>) source;
    }

    /**
     * Always active, including when the optimization is disabled — it is what makes
     * toggling the kill switch safe while plans are already in flight.
     */
    @Inject(method = "addUsedIngredient", at = @At("HEAD"))
    private void rstweaks$copyBeforeWrite(final int ingredientIndex,
                                        final ResourceKey resource,
                                        final long amount,
                                        final CallbackInfo ci) {
        Set<Integer> owned = this.rstweaks$ownedIndices;
        if (owned == null) {
            owned = new HashSet<>();
            this.rstweaks$ownedIndices = owned;
        }
        if (!owned.add(ingredientIndex)) {
            return;
        }
        // First write to this index since the last share, so the map may still be
        // visible to another plan. An absent entry needs nothing: the original
        // method's computeIfAbsent will create a fresh map that only we hold.
        final Map<ResourceKey, Long> shared = this.ingredients.get(ingredientIndex);
        if (shared != null) {
            this.ingredients.put(ingredientIndex, new LinkedHashMap<>(shared));
        }
    }
}
