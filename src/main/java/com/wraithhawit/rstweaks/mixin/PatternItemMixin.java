package com.wraithhawit.rstweaks.mixin;

import com.refinedmods.refinedstorage.common.autocrafting.PatternItem;
import com.refinedmods.refinedstorage.common.autocrafting.ProcessingPatternState;
import com.refinedmods.refinedstorage.common.content.DataComponents;
import com.wraithhawit.rstweaks.Config;
import com.wraithhawit.rstweaks.RSTweaks;
import com.wraithhawit.rstweaks.storage.FluidSubstitutionMark;
import com.wraithhawit.rstweaks.storage.FluidSwap;

import java.util.Optional;

import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Names a fluid substitution pattern for what it is.
 *
 * <p>Refined Storage builds the name from the pattern type, so every one of these reads
 * "Processing Pattern" — technically true, since that is how it is encoded, but unhelpful in a
 * chest of them when the whole point is that this one does not go to a machine.
 *
 * <p>Detection reads the stored component rather than resolving the pattern, because
 * {@code getDescriptionId} has neither a {@code Level} nor a resolved pattern to work from. That
 * makes it the same test {@link FluidSwap} applies everywhere else, run against the same data the
 * resolver would have read.
 *
 * <p>Nothing about the item changes — the components are untouched, so an unmodified Refined
 * Storage still reads it as the processing pattern it is. Only the label differs.
 */
@Mixin(PatternItem.class)
public abstract class PatternItemMixin {
    @Inject(method = "getDescriptionId(Lnet/minecraft/world/item/ItemStack;)Ljava/lang/String;",
        at = @At("RETURN"), cancellable = true)
    private void rstweaks$nameFluidSubstitution(final ItemStack stack,
                                              final CallbackInfoReturnable<String> cir) {
        if (Config.fluidSubstitutionPatterns && rstweaks$isFluidSwap(stack)) {
            cir.setReturnValue(RSTWEAKS_FLUID_SUBSTITUTION);
        }
    }

    /**
     * Records the mark for the tooltip, which gets the contents but never the stack.
     *
     * <p>{@code getTooltipImage} is the last point on the client where both exist. See
     * {@link FluidSubstitutionMark#rememberMarked} for what this is for and the one case it gets
     * wrong.
     */
    @Inject(method = "getTooltipImage", at = @At("HEAD"))
    private void rstweaks$rememberMark(final ItemStack stack,
                                       final CallbackInfoReturnable<Optional<TooltipComponent>> cir) {
        if (!Config.fluidSubstitutionPatterns) {
            return;
        }
        try {
            final ProcessingPatternState state =
                stack.get(DataComponents.INSTANCE.getProcessingPatternState());
            if (state != null) {
                FluidSubstitutionMark.rememberMarked(state, FluidSubstitutionMark.isMarked(stack));
            }
        } catch (final RuntimeException | LinkageError e) {
            RSTweaks.LOGGER.warn("[rstweaks] could not read a pattern's fluid substitution mark", e);
        }
    }

    /**
     * Written out rather than built with Refined Storage's {@code IdentifierUtil}, which would put
     * the key in <em>its</em> namespace and then fail to find it in our language file.
     */
    @Unique
    private static final String RSTWEAKS_FLUID_SUBSTITUTION =
        "misc.rstweaks.pattern.fluid_substitution_pattern";

    /**
     * Tooltips are built every frame a pattern is hovered, and the swap test asks the item's fluid
     * handler capability, so a failure here would be both frequent and noisy. Falling back to
     * Refined Storage's own name is the right answer for anything we cannot read.
     */
    @Unique
    private static boolean rstweaks$isFluidSwap(final ItemStack stack) {
        try {
            final boolean marked = FluidSubstitutionMark.isMarked(stack);
            if (!FluidSubstitutionMark.mayConvert(marked)) {
                // Unmarked, and unmarked patterns are no longer converted — so it will behave as an
                // ordinary processing pattern and must be named as one. A label that disagrees with
                // what the network will do with it is worse than no label.
                return false;
            }
            final ProcessingPatternState state =
                stack.get(DataComponents.INSTANCE.getProcessingPatternState());
            return state != null && FluidSwap.detect(state) != null;
        } catch (final RuntimeException | LinkageError e) {
            RSTweaks.LOGGER.warn("[rstweaks] could not name a pattern for fluid substitution", e);
            return false;
        }
    }
}
