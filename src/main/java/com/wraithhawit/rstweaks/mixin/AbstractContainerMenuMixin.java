package com.wraithhawit.rstweaks.mixin;

import com.wraithhawit.rstweaks.RSTweaks;
import com.wraithhawit.rstweaks.storage.FluidSwapFillable;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Receives "the fluid substitution tab is open" from the client.
 *
 * <p>The tab is a client-side widget over Refined Storage's PROCESSING type, so the server has no
 * way to know it is showing — which is why auto-fill used to run in the ordinary Processing tab
 * too. This is the missing half of that: one bit, sent on entering and leaving the tab.
 *
 * <p>Targets the vanilla menu rather than Refined Storage's because {@code clickMenuButton} is
 * declared here and nowhere below — a mixin can only inject into a method its target actually
 * carries. Every other menu in the game falls through the {@code instanceof} on the first line.
 *
 * <p>Returning {@code true} is not decoration: the server broadcasts container changes straight
 * after a button click that reports it handled something, and that broadcast is when the auto-fill
 * runs.
 */
@Mixin(AbstractContainerMenu.class)
public abstract class AbstractContainerMenuMixin {
    @Inject(method = "clickMenuButton", at = @At("HEAD"), cancellable = true)
    private void rstweaks$fluidTabChanged(final Player player,
                                          final int id,
                                          final CallbackInfoReturnable<Boolean> cir) {
        if ((id != FluidSwapFillable.RSTWEAKS_FLUID_TAB_ON
            && id != FluidSwapFillable.RSTWEAKS_FLUID_TAB_OFF)
            || !(this instanceof FluidSwapFillable fillable)) {
            return;
        }
        final boolean open = id == FluidSwapFillable.RSTWEAKS_FLUID_TAB_ON;
        fillable.rstweaks$setFluidTab(open);
        // Every one of these moves a pattern between the live matrix and the stash, so the log is
        // the record of what happened to someone's grid. It fires only on a tab change.
        RSTweaks.LOGGER.info("[rstweaks] {} switched a pattern grid to the {} tab",
            player.getName().getString(), open ? "fluid substitution" : "processing");
        cir.setReturnValue(true);
    }
}
