package com.wraithhawit.rstweaks.mixin;

import com.refinedmods.refinedstorage.api.autocrafting.calculation.CancellationToken;
import com.refinedmods.refinedstorage.api.autocrafting.calculation.CraftingCalculator;
import com.refinedmods.refinedstorage.api.autocrafting.calculation.CraftingCalculatorImpl;
import com.refinedmods.refinedstorage.api.autocrafting.craftability.IsCraftableCraftingCalculatorListener;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.wraithhawit.rstweaks.Config;
import com.wraithhawit.rstweaks.RSTweaks;
import com.wraithhawit.rstweaks.planner.LpCraftingPlanner;
import com.wraithhawit.rstweaks.planner.MaxCraftable;

import javax.annotation.Nullable;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Answers the request screen's <b>Max</b> button with the planner that will actually run the craft.
 *
 * <p>Refined Storage reaches its crafting calculator through three separate listeners, and until now
 * this mod hooked two of them: {@code TaskPlanCraftingCalculatorListener} for starting a craft and
 * {@code PreviewCraftingCalculatorListener} for the preview. The third,
 * {@link IsCraftableCraftingCalculatorListener}, backs {@code getMaxAmount} — so the Max button was
 * the one place still answered entirely by stock Refined Storage.
 *
 * <p>That matters for reusable tools. Damage lives in an item's component patch, so
 * {@code crystal@0} and {@code crystal@37} are different resources, and Refined Storage matches
 * ingredients exactly. A pattern encoded with a fresh crystal is therefore <em>not craftable at
 * all</em> against a worn one, and {@code binarySearchMaxAmount} says so in a specific way: its
 * doubling loop fails on the first probe, {@code low == high == 1}, and it returns <b>0</b>. Where
 * the ingredient does match exactly it counts whole items instead of the uses left in them, which
 * is the other reported symptom — a number that looks like the container count.
 *
 * <p>Neither is a miscount. The max path simply never had durability applied to it.
 *
 * <h2>Same search, better oracle</h2>
 *
 * <p>The algorithm here is Refined Storage's: double until it fails, then binary search the gap.
 * Only the question changes — instead of asking the recursive calculator whether {@code n} is
 * craftable, it asks the linear planner, which models a worn tool as a supply of uses.
 *
 * <p>The planner has three answers and all three are used. A plan means yes. {@code impossible}
 * means the integer program was <em>proved</em> infeasible, which is a real no. Anything else is
 * {@code DECLINED} — the planner saying this graph is not its business, which is the common case
 * for an ordinary recipe with no byproducts or cycles. On a decline this returns without cancelling,
 * so stock Refined Storage answers exactly as it does today. A recipe that hands a tool back has a
 * byproduct by definition, so the durability case does reach the planner.
 */
@Mixin(IsCraftableCraftingCalculatorListener.class)
public abstract class IsCraftableCraftingCalculatorListenerMixin {
    @Inject(method = "binarySearchMaxAmount", at = @At("HEAD"), cancellable = true)
    private static void rstweaks$maxFromLinearPlan(final CraftingCalculator calculator,
                                                   final ResourceKey resource,
                                                   final CancellationToken cancellationToken,
                                                   final CallbackInfoReturnable<Long> cir) {
        if (!Config.lpPlanner || !(calculator instanceof CraftingCalculatorImpl impl)) {
            return;
        }
        try {
            final Long max = MaxCraftable.search(
                amount -> rstweaks$craftable(impl, resource, amount),
                cancellationToken::isCancelled);
            if (max != null) {
                cir.setReturnValue(max);
            }
        } catch (final RuntimeException | LinkageError e) {
            // Falling through costs the correct answer for durable tools and nothing else: the
            // player gets stock Refined Storage's number, which is what they had before this.
            RSTweaks.LOGGER.warn("[rstweaks] could not compute a max craftable amount", e);
        }
    }


    /**
     * @return {@code TRUE} if the planner produced a plan, {@code FALSE} if it proved the request
     *     impossible, and {@code null} if it declined — which is not an answer and must not be read
     *     as one.
     */
    @Unique
    @Nullable
    private static Boolean rstweaks$craftable(final CraftingCalculatorImpl calculator,
                                              final ResourceKey resource,
                                              final long amount) {
        final CraftingCalculatorImplAccessor accessor = (CraftingCalculatorImplAccessor) calculator;
        final LpCraftingPlanner.Attempt attempt = LpCraftingPlanner.attempt(
            accessor.rstweaks$patternRepository(),
            accessor.rstweaks$rootStorage(),
            resource,
            amount
        );
        if (attempt.plan() != null) {
            return Boolean.TRUE;
        }
        return attempt.impossible() ? Boolean.FALSE : null;
    }
}
