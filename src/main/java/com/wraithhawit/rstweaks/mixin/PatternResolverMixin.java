package com.wraithhawit.rstweaks.mixin;

import com.refinedmods.refinedstorage.common.autocrafting.PatternResolver;
import com.refinedmods.refinedstorage.common.autocrafting.PatternState;
import com.wraithhawit.rstweaks.storage.FluidSubstitutionMark;

import java.util.Optional;

import net.minecraft.world.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Carries the fluid substitution mark from the pattern item to the layout being built from it.
 *
 * <p>{@code getProcessingPattern} is the last place the {@code ItemStack} exists: it reads the
 * stored {@code ProcessingPatternState} and hands the ingredients and outputs to
 * {@code ResolvedProcessingPattern}'s constructor, which is where {@link
 * ResolvedProcessingPatternMixin} decides whether the layout is external or internal. That
 * constructor takes a UUID and two lists and nothing else, so the mark has to travel out of band.
 *
 * <p>Cleared at RETURN so a marked pattern cannot authorise the next one resolved on this thread.
 */
@Mixin(PatternResolver.class)
public abstract class PatternResolverMixin {
    @Inject(
        method = "getProcessingPattern(Lcom/refinedmods/refinedstorage/common/autocrafting/"
            + "PatternState;Lnet/minecraft/world/item/ItemStack;)Ljava/util/Optional;",
        at = @At("HEAD")
    )
    private void rstweaks$readMark(final PatternState patternState,
                                   final ItemStack stack,
                                   final CallbackInfoReturnable<Optional<?>> cir) {
        FluidSubstitutionMark.beginResolving(FluidSubstitutionMark.isMarked(stack));
    }

    @Inject(
        method = "getProcessingPattern(Lcom/refinedmods/refinedstorage/common/autocrafting/"
            + "PatternState;Lnet/minecraft/world/item/ItemStack;)Ljava/util/Optional;",
        at = @At("RETURN")
    )
    private void rstweaks$clearMark(final PatternState patternState,
                                    final ItemStack stack,
                                    final CallbackInfoReturnable<Optional<?>> cir) {
        FluidSubstitutionMark.endResolving();
    }
}
