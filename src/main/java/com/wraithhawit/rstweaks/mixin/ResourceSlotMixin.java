package com.wraithhawit.rstweaks.mixin;

import com.refinedmods.refinedstorage.common.api.support.resource.ResourceContainer;
import com.refinedmods.refinedstorage.common.support.containermenu.ResourceSlot;
import com.wraithhawit.rstweaks.storage.SlotContainerAccess;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Exposes a slot's backing container.
 *
 * <p>{@code resourceContainer} is declared on {@code ResourceSlot} itself — not a superclass — so
 * this shadow is legal, and its type is public API rather than one of the package-private
 * container classes.
 */
@Mixin(ResourceSlot.class)
public abstract class ResourceSlotMixin implements SlotContainerAccess {
    /**
     * {@code @Mutable} because the field is declared {@code final} and the fluid tab rebinds it.
     * Mixin strips the modifier at apply time; nothing outside {@link #rstweaks$rebind} writes it,
     * and Refined Storage itself only ever reads it.
     */
    @Shadow
    @Final
    @Mutable
    protected ResourceContainer resourceContainer;

    @Override
    public ResourceContainer rstweaks$container() {
        return this.resourceContainer;
    }

    @Override
    public void rstweaks$rebind(final ResourceContainer container) {
        this.resourceContainer = container;
    }
}