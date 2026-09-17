package com.wraithhawit.rstweaks.mixin;

import com.refinedmods.refinedstorage.common.api.support.resource.RecipeModIngredientConverter;
import com.refinedmods.refinedstorage.common.support.resource.CompositeRecipeModIngredientConverter;

import java.util.Collection;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reads every recipe-mod converter Refined Storage has been handed, one at a time.
 *
 * <p>The composite answers {@code convertToIngredient} with whichever registered converter speaks
 * first, and the converters sit in a {@code HashSet} — identity hash codes, so the order is decided
 * afresh every launch. With both the JEI and EMI integrations installed that answer is a JEI
 * {@code ItemStack} on some launches and an {@code EmiStack} on others, and nothing in the API lets
 * a caller say which kind it can use. See {@link EmiStackProviderMixin}.
 */
@Mixin(CompositeRecipeModIngredientConverter.class)
public interface CompositeRecipeModIngredientConverterAccessor {
    @Accessor("converters")
    Collection<RecipeModIngredientConverter> rstweaks$getConverters();
}
