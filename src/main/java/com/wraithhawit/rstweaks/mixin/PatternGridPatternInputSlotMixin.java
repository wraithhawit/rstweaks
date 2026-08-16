package com.wraithhawit.rstweaks.mixin;

import com.refinedmods.refinedstorage.common.autocrafting.PatternState;
import com.refinedmods.refinedstorage.common.autocrafting.patterngrid.PatternGridContainerMenu;
import com.refinedmods.refinedstorage.common.content.DataComponents;
import com.wraithhawit.rstweaks.Config;
import com.wraithhawit.rstweaks.storage.FluidSubstitutionMark;
import com.wraithhawit.rstweaks.storage.FluidSwapFillable;

import net.minecraft.world.item.ItemStack;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Selects the matrix that belongs to an encoded pattern placed in the Pattern Grid's input slot.
 *
 * <p>Refined Storage's slot calls {@code PatternGridBlockEntity.copyPattern} before this return
 * hook. {@link PatternGridBlockEntityMixin} has therefore already copied a marked stack into the
 * fluid matrix; this hook points both menu instances at that matrix. An ordinary encoded pattern
 * points them back at the normal RS tab instead.
 */
@Mixin(targets =
    "com.refinedmods.refinedstorage.common.autocrafting.patterngrid.PatternGridContainerMenu$3")
public abstract class PatternGridPatternInputSlotMixin {
    @Shadow
    @Final
    private PatternGridContainerMenu this$0;

    @Inject(method = "set", at = @At("RETURN"))
    private void rstweaks$selectLoadedPatternTab(final ItemStack stack, final CallbackInfo ci) {
        final PatternState state = stack.get(DataComponents.INSTANCE.getPatternState());
        if (state == null || !(this.this$0 instanceof FluidSwapFillable fillable)) {
            return;
        }
        fillable.rstweaks$patternLoaded(Config.fluidSubstitutionPatterns
            && FluidSubstitutionMark.isMarked(stack));
    }
}
