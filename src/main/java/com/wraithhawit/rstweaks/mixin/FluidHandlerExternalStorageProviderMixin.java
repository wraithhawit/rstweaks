package com.wraithhawit.rstweaks.mixin;

import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.common.support.resource.FluidResource;
import com.wraithhawit.rstweaks.storage.TypedExternalStorageProvider;

import org.spongepowered.asm.mixin.Mixin;

/** Declares that a fluid handler serves fluids and nothing else. See the item version. */
@Mixin(targets = "com.refinedmods.refinedstorage.neoforge.storage.externalstorage."
    + "FluidHandlerExternalStorageProvider")
public abstract class FluidHandlerExternalStorageProviderMixin
    implements TypedExternalStorageProvider {

    @Override
    public boolean rstweaks$serves(final ResourceKey resource) {
        return resource instanceof FluidResource;
    }
}
