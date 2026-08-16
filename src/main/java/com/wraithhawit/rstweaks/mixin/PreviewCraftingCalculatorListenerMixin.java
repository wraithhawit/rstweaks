package com.wraithhawit.rstweaks.mixin;

import com.refinedmods.refinedstorage.api.autocrafting.calculation.CancellationToken;
import com.refinedmods.refinedstorage.api.autocrafting.calculation.CraftingCalculator;
import com.refinedmods.refinedstorage.api.autocrafting.calculation.CraftingCalculatorImpl;
import com.refinedmods.refinedstorage.api.autocrafting.preview.Preview;
import com.refinedmods.refinedstorage.api.autocrafting.preview.PreviewBuilder;
import com.refinedmods.refinedstorage.api.autocrafting.preview.PreviewCraftingCalculatorListener;
import com.refinedmods.refinedstorage.api.autocrafting.task.TaskPlan;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.wraithhawit.rstweaks.planner.LpCraftingPlanner;
import com.wraithhawit.rstweaks.planner.PlanPreview;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes the crafting request screen agree with the plan the task will actually run.
 *
 * <p>Refined Storage builds the preview and the task through separate code paths, so
 * hooking only the task left the grid still reporting stock RS's numbers â€” 3000
 * buckets for a craft that would use 4. Beyond being confusing, a preview claiming
 * thousands of missing containers can prevent the request from being submitted.
 *
 * <p>Both hooks call the same planner with the same inputs, so the screen and the
 * task cannot disagree; if the planner declines here, it declines there too and
 * stock RS renders the preview exactly as before.
 */
/*
 * Reporting the impossible case here, rather than letting it fall through, is deliberate.
 * Stock Refined Storage's calculator is depth-first, so on a graph containing a fluid
 * substitution pair -- a water bucket makes water, water makes a water bucket -- it walks the
 * cycle and throws PatternCycleDetectedException before it can conclude that the request is
 * simply short of ingredients. The player then sees "cyclical pattern detected" when the real
 * answer is "not enough lava", which reads as a broken pattern rather than an empty barrel.
 *
 * The solver has already proved the request unsatisfiable by that point, so the honest thing is
 * to say so instead of asking a calculator we know will cycle on it.
 */
@Mixin(PreviewCraftingCalculatorListener.class)
public abstract class PreviewCraftingCalculatorListenerMixin {
    @Inject(method = "calculatePreview", at = @At("HEAD"), cancellable = true)
    private static void rstweaks$previewLinearPlan(
        final CraftingCalculator calculator,
        final ResourceKey resource,
        final long amount,
        final CancellationToken cancellationToken,
        final CallbackInfoReturnable<Preview> cir
    ) {
        if (!(calculator instanceof CraftingCalculatorImpl impl)) {
            return;
        }
        final CraftingCalculatorImplAccessor accessor = (CraftingCalculatorImplAccessor) impl;
        final LpCraftingPlanner.Attempt attempt = LpCraftingPlanner.attempt(
            accessor.rstweaks$patternRepository(),
            accessor.rstweaks$rootStorage(),
            resource,
            amount
        );
        if (attempt.plan() != null) {
            cir.setReturnValue(PlanPreview.from(attempt.plan()));
        } else if (attempt.impossible()) {
            // What the request is actually short of, computed by re-solving with outside supply
            // allowed -- "lava_bucket: available 4, missing 6" rather than "missing 10000mB lava".
            // See PlanShortfall.
            //
            // Swallowing Refined Storage's PatternCycleDetectedException and letting it build the
            // preview was tried instead, on the theory that its calculator had already recorded
            // those counts before it noticed the cycle. It had not. The result was an empty preview
            // with Start ENABLED on an impossible craft, which is far worse than a thin message. Do
            // not reinstate that without first proving the builder holds those counts at the moment
            // it throws.
            final Preview shortfall = LpCraftingPlanner.shortfall(attempt, resource, amount);
            // The fallback names only the target, which is a poor message but a safe one: the
            // request is proved unsatisfiable, so Start must not be offered either way.
            cir.setReturnValue(shortfall != null
                ? shortfall
                : PreviewBuilder.create().addMissing(resource, amount).build());
        }
    }
}
