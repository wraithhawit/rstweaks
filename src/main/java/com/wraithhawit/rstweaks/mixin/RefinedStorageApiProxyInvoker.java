package com.wraithhawit.rstweaks.mixin;

import com.refinedmods.refinedstorage.common.api.RefinedStorageApi;
import com.refinedmods.refinedstorage.common.api.RefinedStorageApiProxy;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Unwraps {@code RefinedStorageApi.INSTANCE} to the implementation behind it.
 *
 * <p>{@code INSTANCE} is a proxy that forwards everything to a delegate Refined Storage installs in
 * its own mod constructor. The one thing this mod needs and the interface does not expose is the
 * slot-reference provider — see {@link RefinedStorageApiImplAccessor} for why. Getting to it means
 * getting past the proxy first.
 *
 * <p>Not an invention: Universal Grid reaches the same field through the same pair of accessors
 * ({@code InvokerRefinedStorageApiProxy}, {@code AccessorRefinedStorageApiImpl}), which is a fair
 * sign this is the seam rather than a way around one.
 */
@Mixin(RefinedStorageApiProxy.class)
public interface RefinedStorageApiProxyInvoker {
    @Invoker("ensureLoaded")
    RefinedStorageApi rstweaks$ensureLoaded();
}
