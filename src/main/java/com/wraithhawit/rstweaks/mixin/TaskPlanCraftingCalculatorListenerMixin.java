package com.wraithhawit.rstweaks.mixin;

import com.refinedmods.refinedstorage.api.autocrafting.Pattern;
import com.refinedmods.refinedstorage.api.autocrafting.calculation.Amount;
import com.refinedmods.refinedstorage.api.autocrafting.calculation.CancellationToken;
import com.refinedmods.refinedstorage.api.autocrafting.calculation.CraftingCalculatorListener;
import com.refinedmods.refinedstorage.api.autocrafting.calculation.CraftingCalculator;
import com.refinedmods.refinedstorage.api.autocrafting.calculation.CraftingCalculatorImpl;
import com.refinedmods.refinedstorage.api.autocrafting.task.MutableTaskPlan;
import com.refinedmods.refinedstorage.api.autocrafting.task.TaskPlan;
import com.refinedmods.refinedstorage.api.autocrafting.task.TaskPlanCraftingCalculatorListener;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.wraithhawit.rstweaks.Config;
import com.wraithhawit.rstweaks.pattern.CalculationTrace;
import com.wraithhawit.rstweaks.planner.LpCraftingPlanner;

import java.util.Optional;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Offers each craft request to the LP planner before Refined Storage's recursive tree
 * gets it.
 *
 * <p>{@code calculatePlan} is the right seam because it is the boundary where a
 * request becomes a {@link TaskPlan}. Everything downstream — {@code TaskImpl},
 * {@code InternalTaskPattern}, the whole executor — is built purely from that record
 * (see {@code TaskImpl} lines 60-74), so producing one by other means needs no
 * further changes. Hooking the tree itself would have meant reimplementing
 * scheduling too, which is where this attempt has historically stalled.
 *
 * <p>The planner declines by returning {@code null}, in which case this injector does
 * nothing and stock RS runs untouched. It declines for every acyclic, byproduct-free
 * craft by design, so the common path is unchanged.
 */
@Mixin(TaskPlanCraftingCalculatorListener.class)
public abstract class TaskPlanCraftingCalculatorListenerMixin {
    @Inject(method = "calculatePlan", at = @At("HEAD"), cancellable = true)
    private static void rstweaks$tryLinearPlanner(
        final CraftingCalculator calculator,
        final ResourceKey resource,
        final long amount,
        final CancellationToken cancellationToken,
        final CallbackInfoReturnable<Optional<TaskPlan>> cir
    ) {
        // Only CraftingCalculatorImpl carries the repository and storage we need; any
        // other implementation is someone else's and must be left alone.
        if (!(calculator instanceof CraftingCalculatorImpl impl)) {
            return;
        }
        final CraftingCalculatorImplAccessor accessor = (CraftingCalculatorImplAccessor) impl;
        final TaskPlan plan = LpCraftingPlanner.tryPlan(
            accessor.rstweaks$patternRepository(),
            accessor.rstweaks$rootStorage(),
            resource,
            amount
        );
        if (plan != null) {
            cir.setReturnValue(Optional.of(plan));
        }
    }

    /**
     * One crafting-tree node. RS calls this exactly once per node it visits, so counting it
     * costs nothing beyond an increment and turns a five-second stall into a number.
     *
     * <p>The resource name is passed as a supplier because building it formats an
     * {@code ItemResource} with its whole component patch, and below
     * {@link CalculationTrace#DETAIL_THRESHOLD} nodes it is never needed.
     */
    @Inject(method = "childCalculationStarted", at = @At("HEAD"))
    private void rstweaks(final Pattern childPattern,
                                    final ResourceKey resource,
                                    final Amount amount,
                                    final CallbackInfoReturnable<CraftingCalculatorListener<MutableTaskPlan>> cir) {
        if (Config.traceSlowCalculations) {
            CalculationTrace.noteNode(resource::toString);
        }
    }

    /**
     * A branch that gave up. This is the line that names what the search actually ran out of,
     * which nothing in RS otherwise reports -- a cancelled or failed calculation returns
     * MISSING_RESOURCES and no resource at all.
     */
    @Inject(method = "ingredientsExhausted", at = @At("HEAD"))
    private void rstweaks(final ResourceKey resource,
                                         final long amount,
                                         final CallbackInfo ci) {
        if (Config.traceSlowCalculations) {
            CalculationTrace.noteExhausted(resource::toString);
        }
    }
}
