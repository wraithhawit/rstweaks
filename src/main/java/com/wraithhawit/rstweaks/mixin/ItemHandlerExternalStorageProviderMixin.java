package com.wraithhawit.rstweaks.mixin;

import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import com.wraithhawit.rstweaks.storage.TypedExternalStorageProvider;

import org.spongepowered.asm.mixin.Mixin;

/**
 * Declares that an item handler serves items and nothing else.
 *
 * <p>Mirrors the guard already inside {@code ItemHandlerExtractableStorage.extract}:
 *
 * <pre>{@code   resource instanceof ItemResource itemResource ? ... : 0L }</pre>
 *
 * <p>The point is not to make that check faster — it is to let
 * {@link CompositeExternalStorageProviderMixin} skip the call entirely. On a network
 * where something pulls energy every tick, the composite asks every item inventory in
 * the network whether it has any energy, and the answer costs more than it looks:
 * measured at 3.55% of a whole server thread on a struggling instance.
 */
@Mixin(targets = "com.refinedmods.refinedstorage.neoforge.storage.externalstorage."
    + "ItemHandlerExternalStorageProvider")
public abstract class ItemHandlerExternalStorageProviderMixin
    implements TypedExternalStorageProvider {

    @Override
    public boolean rstweaks$serves(final ResourceKey resource) {
        return resource instanceof ItemResource;
    }
}
