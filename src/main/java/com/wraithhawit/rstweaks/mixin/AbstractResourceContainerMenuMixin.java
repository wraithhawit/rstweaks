package com.wraithhawit.rstweaks.mixin;

import com.refinedmods.refinedstorage.common.support.containermenu.AbstractResourceContainerMenu;
import com.wraithhawit.rstweaks.storage.FluidSwapFillable;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Server-side tick for any menu that wants it.
 *
 * <p>{@code broadcastChanges()} is declared here rather than on the Pattern Grid menu, which is
 * why the hook sits on this class. Every other resource menu falls straight through the
 * {@code instanceof}.
 */
@Mixin(AbstractResourceContainerMenu.class)
public abstract class AbstractResourceContainerMenuMixin {
    @Inject(method = "broadcastChanges", at = @At("HEAD"))
    private void rstweaks$fluidSubstitutionAutoFill(final CallbackInfo ci) {
        if (this instanceof FluidSwapFillable fillable) {
            fillable.rstweaks$autoFillFluidSubstitution();
        }
    }
}