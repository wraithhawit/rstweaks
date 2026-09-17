package com.wraithhawit.rstweaks.mixin;

import com.refinedmods.refinedstorage.common.api.support.resource.PlatformResourceKey;
import com.refinedmods.refinedstorage.common.api.support.resource.RecipeModIngredientConverter;
import com.refinedmods.refinedstorage.common.support.resource.CompositeRecipeModIngredientConverter;

import dev.emi.emi.api.stack.EmiIngredient;

import java.util.Optional;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Stops EMI's tooltip, recipe and usage lookups failing over a Refined Storage grid when the JEI
 * integration is installed alongside the EMI one.
 *
 * <p>Both of Refined Storage's EMI stack providers ask the shared converter for "the ingredient"
 * and cast whatever comes back to {@link EmiIngredient}:
 *
 * <pre>{@code
 * RefinedStorageApi.INSTANCE.getIngredientConverter().convertToIngredient(resource)
 *     .map(ingredient -> new EmiStackInteraction((EmiIngredient) ingredient, null, false))
 * }</pre>
 *
 * <p>The shared converter is a composite that returns the first answer from a {@code HashSet} of
 * every registered converter. {@code refinedstorage-jei-integration} registers one that turns an
 * {@code ItemResource} into a plain {@code ItemStack}, so on any launch where the set happens to
 * put it first, every hover over a grid slot throws {@code ClassCastException}. EMI catches it and
 * draws "Error rendering tooltip" over the item's own tooltip, and the same exception kills R/U and
 * clicks on grid resources ("Error while handling key press"). The order is per launch, not per
 * server or world, which is why it looks intermittent.
 *
 * <p>The fix asks each registered converter in turn and keeps the first answer EMI can actually
 * use. That is still the pack's converters deciding — Mekanism's chemical converter is found the
 * same way as the item and fluid one — rather than this mod re-deriving an {@code EmiStack} from a
 * resource and losing whatever an addon registers.
 */
@Mixin(targets = {
    "com.refinedmods.refinedstorage.emi.common.GridEmiStackProvider",
    "com.refinedmods.refinedstorage.emi.common.ResourceEmiStackProvider"
})
public abstract class EmiStackProviderMixin {
    @Redirect(
        method = "getStackAt",
        at = @At(
            value = "INVOKE",
            target = "Lcom/refinedmods/refinedstorage/common/api/support/resource/"
                + "RecipeModIngredientConverter;convertToIngredient("
                + "Lcom/refinedmods/refinedstorage/common/api/support/resource/PlatformResourceKey;)"
                + "Ljava/util/Optional;"
        )
    )
    private Optional<Object> rstweaks$emiIngredientOnly(
        final RecipeModIngredientConverter converter,
        final PlatformResourceKey resource
    ) {
        if (converter instanceof CompositeRecipeModIngredientConverter composite) {
            for (final RecipeModIngredientConverter each
                : ((CompositeRecipeModIngredientConverterAccessor) composite).rstweaks$getConverters()) {
                final Optional<Object> ingredient = each.convertToIngredient(resource);
                if (ingredient.isPresent() && ingredient.get() instanceof EmiIngredient) {
                    return ingredient;
                }
            }
            return Optional.empty();
        }
        // Not the composite Refined Storage installs. Nothing to choose between, so only refuse
        // the answer that would throw.
        return converter.convertToIngredient(resource).filter(EmiIngredient.class::isInstance);
    }
}
