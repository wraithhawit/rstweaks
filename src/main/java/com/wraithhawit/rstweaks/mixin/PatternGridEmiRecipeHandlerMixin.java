package com.wraithhawit.rstweaks.mixin;

import com.refinedmods.refinedstorage.api.resource.ResourceAmount;

import dev.emi.emi.api.stack.EmiIngredient;

import java.util.ArrayList;
import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps an ingredient's count when EMI's recipe transfer fills a Pattern Grid processing pattern.
 *
 * <p>{@code getResourceAmounts} converts each stack inside an {@link EmiIngredient} to a
 * {@link ResourceAmount} and takes the amount from that stack. For a single-stack ingredient that
 * is the ingredient's count. For a list or tag ingredient it is not: EMI keeps the count on the
 * ingredient and the stacks inside it each say one. A GregTech blast furnace recipe asking for 32 of
 * "any of [MI silicon dust, GT silicon dust]" -- the shape ATM10's unification gives many inputs --
 * arrived in the pattern as 1 (seen in ATM10(3), 2026-09-25). Every alternative is rescaled to the
 * ingredient's own amount, which is what EMI means by how many of it the recipe takes; a
 * single-stack ingredient already matches and is left as it is.
 */
@Mixin(targets = "com.refinedmods.refinedstorage.emi.common.PatternGridEmiRecipeHandler")
public abstract class PatternGridEmiRecipeHandlerMixin {
    @Inject(method = "getResourceAmounts", at = @At("RETURN"), cancellable = true)
    private static void rstweaks$useIngredientAmount(
        final EmiIngredient ingredient,
        final CallbackInfoReturnable<List<ResourceAmount>> cir
    ) {
        final long amount = ingredient.getAmount();
        final List<ResourceAmount> converted = cir.getReturnValue();
        if (amount <= 0 || converted == null) {
            return;
        }
        boolean changed = false;
        final List<ResourceAmount> rescaled = new ArrayList<>(converted.size());
        for (final ResourceAmount each : converted) {
            if (each.amount() == amount) {
                rescaled.add(each);
            } else {
                rescaled.add(new ResourceAmount(each.resource(), amount));
                changed = true;
            }
        }
        if (changed) {
            cir.setReturnValue(rescaled);
        }
    }
}
