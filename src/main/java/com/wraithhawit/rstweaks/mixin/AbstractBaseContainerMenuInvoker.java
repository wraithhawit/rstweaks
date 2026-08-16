package com.wraithhawit.rstweaks.mixin;

import com.refinedmods.refinedstorage.common.support.AbstractBaseContainerMenu;
import com.refinedmods.refinedstorage.common.support.containermenu.Property;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Opens up Refined Storage's own menu property registration.
 *
 * <p>{@code registerProperty} is protected, and this mixin's target is not in our hierarchy, so it
 * cannot simply be called. An {@code @Invoker} is the narrow way in — no shadowed state, no change
 * to the class hierarchy, and no second sync mechanism alongside the one Refined Storage already
 * runs. It wraps a vanilla {@code DataSlot}, so the value reaches the client through the same
 * container packets everything else uses.
 */
@Mixin(AbstractBaseContainerMenu.class)
public interface AbstractBaseContainerMenuInvoker {
    @Invoker("registerProperty")
    <T> void rstweaks$registerProperty(Property<T> property);
}
