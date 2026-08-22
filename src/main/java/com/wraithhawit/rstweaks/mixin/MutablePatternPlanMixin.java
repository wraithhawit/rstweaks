package com.wraithhawit.rstweaks.mixin;

import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.wraithhawit.rstweaks.Config;
import com.wraithhawit.rstweaks.Stats;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

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
    @Mutable
    @Shadow
    @Final
    private Map<Integer, Map<ResourceKey, Long>> ingredients;

    /**
     * Ingredient indices whose map this plan exclusively owns and may mutate in
     * place. Lazily created: Mixin's handling of instance field initializers on
     * {@code @Unique} fields is unreliable, so this must not depend on one.
     */
    @Unique
    private Set<Integer> rstweaks$ownedIndices;


    /**
     * Whether this plan exclusively owns its <em>outer</em> ingredients map.
     *
     * <p>The inner maps were already shared by {@link #rstweaks$shareInsteadOfCopying}; this shares
     * the map that holds them. Profiling showed why it was worth going further: with the inner maps
     * shared, {@code copy} still cost 39% of the server thread on a busy autocrafting network, and
     * a breakdown of the method put 67% of that in its own loop and 29% in {@code HashMap.put} --
     * that is, entirely in rebuilding the outer map entry by entry. The share itself measured 0.05%.
     *
     * <p>Defaults to {@code false}, which is the safe direction: a plan that has not yet been told
     * it owns its map will take a private copy before its first write. That costs one needless copy
     * of an empty map on a fresh plan and cannot be wrong. Mixin's handling of {@code @Unique} field
     * initializers is unreliable, so the default must be the harmless one rather than the true one.
     */
    @Unique
    private boolean rstweaks$ownsOuterMap;

    /**
     * Skips {@code copy}'s loop entirely by handing it nothing to iterate.
     *
     * <p>The loop's whole job is to fill the new plan's outer map, and
     * {@link #rstweaks$shareOuterMap} gives that plan this one's map instead -- so the loop has
     * nothing left to do. Emptying the iteration is how it is removed without rewriting a
     * package-private method this mixin cannot even name the return type of.
     */
    @Redirect(
        method = "copy",
        at = @At(value = "INVOKE", target = "Ljava/util/Map;entrySet()Ljava/util/Set;")
    )
    private Set<Map.Entry<Integer, Map<ResourceKey, Long>>> rstweaks$skipCopyingEntries(
        final Map<Integer, Map<ResourceKey, Long>> source
    ) {
        if (!Config.lazyPatternPlanCopy) {
            return source.entrySet();
        }
        return Collections.emptySet();
    }

    /**
     * Gives the fresh copy this plan's outer map, and drops both plans' claim to it.
     *
     * <p>Runs after {@code copy} has built an empty plan, which is exactly what is wanted: the
     * constructor's own map is discarded unread, and the two plans leave sharing one map and one set
     * of inner maps. Whichever writes first takes its own copies.
     */
    @Inject(method = "copy", at = @At("RETURN"))
    private void rstweaks$shareOuterMap(final CallbackInfoReturnable<Object> cir) {
        if (!Config.lazyPatternPlanCopy) {
            return;
        }
        final Object copy = cir.getReturnValue();
        if (!(copy instanceof MutablePatternPlanAccessor accessor)) {
            return;
        }
        accessor.rstweaks$setIngredients(this.ingredients);
        // Neither plan may now write in place: not to the outer map, and not to any inner map it
        // holds. Clearing both claims is what forces the copy-before-write on the next write.
        this.rstweaks$ownsOuterMap = false;
        if (this.rstweaks$ownedIndices != null) {
            this.rstweaks$ownedIndices.clear();
        }
        ++Stats.patternPlanCopiesAvoided;
    }
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
        // The outer map first: computeIfAbsent below mutates it, and it may be shared with another
        // plan. Copying it does not copy the inner maps, so the per-index guard below is still what
        // protects those.
        if (!this.rstweaks$ownsOuterMap) {
            this.ingredients = new HashMap<>(this.ingredients);
            this.rstweaks$ownsOuterMap = true;
        }
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
