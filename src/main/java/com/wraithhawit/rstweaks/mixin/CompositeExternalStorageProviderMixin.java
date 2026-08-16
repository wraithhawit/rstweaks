package com.wraithhawit.rstweaks.mixin;

import com.refinedmods.refinedstorage.api.core.Action;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.api.storage.Actor;
import com.refinedmods.refinedstorage.api.storage.external.ExternalStorageProvider;
import com.wraithhawit.rstweaks.Config;
import com.wraithhawit.rstweaks.Stats;
import com.wraithhawit.rstweaks.storage.TypedExternalStorageProvider;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Stops a composite external storage asking providers that cannot possibly serve the
 * resource.
 *
 * <p>One External Storage block exposes several providers — items, fluids, and whatever
 * energy or source types other mods contribute — and {@code extract}/{@code insert} walk
 * all of them until one returns non-zero. The resource type is never consulted, so an
 * energy extraction asks every item inventory in the network whether it has any energy,
 * and an item extraction asks every energy cell whether it has any iron.
 *
 * <p>Measured on a struggling instance where Refined Types' Network Energizer pulls power
 * every tick:
 *
 * <pre>
 * NetworkEnergizerNetworkNode.extract                  12.03 ms/tick
 *   CompositeExternalStorageProvider.extract
 *     ItemHandlerExternalStorageProvider.extract        3.55%   <- asked for energy
 *     FluidHandlerExternalStorageProvider.extract       0.33%   <- asked for energy
 * </pre>
 *
 * <p>Each of those returns zero immediately; there are simply an enormous number of them.
 *
 * <p>Only providers that have declared a fixed resource type are skipped — see
 * {@link TypedExternalStorageProvider}, which is a compile-time fact rather than a cache.
 * Anything else is still asked every time, so a provider from a mod we know nothing about
 * behaves exactly as it does today.
 */
@Mixin(targets = "com.refinedmods.refinedstorage.common.storage.externalstorage."
    + "CompositeExternalStorageProvider")
public abstract class CompositeExternalStorageProviderMixin {
    private static final String PROVIDER_EXTRACT =
        "Lcom/refinedmods/refinedstorage/api/storage/external/ExternalStorageProvider;"
            + "extract(Lcom/refinedmods/refinedstorage/api/resource/ResourceKey;J"
            + "Lcom/refinedmods/refinedstorage/api/core/Action;"
            + "Lcom/refinedmods/refinedstorage/api/storage/Actor;)J";
    private static final String PROVIDER_INSERT =
        "Lcom/refinedmods/refinedstorage/api/storage/external/ExternalStorageProvider;"
            + "insert(Lcom/refinedmods/refinedstorage/api/resource/ResourceKey;J"
            + "Lcom/refinedmods/refinedstorage/api/core/Action;"
            + "Lcom/refinedmods/refinedstorage/api/storage/Actor;)J";

    @Redirect(method = "extract", at = @At(value = "INVOKE", target = PROVIDER_EXTRACT))
    private long rstweaks$skipWrongTypeOnExtract(final ExternalStorageProvider provider,
                                               final ResourceKey resource,
                                               final long amount,
                                               final Action action,
                                               final Actor actor) {
        if (skip(provider, resource)) {
            // Zero is what the call would have returned, so the loop moves on to the
            // next provider exactly as it would have.
            return 0L;
        }
        return provider.extract(resource, amount, action, actor);
    }

    @Redirect(method = "insert", at = @At(value = "INVOKE", target = PROVIDER_INSERT))
    private long rstweaks$skipWrongTypeOnInsert(final ExternalStorageProvider provider,
                                              final ResourceKey resource,
                                              final long amount,
                                              final Action action,
                                              final Actor actor) {
        if (skip(provider, resource)) {
            return 0L;
        }
        return provider.insert(resource, amount, action, actor);
    }

    private static boolean skip(final ExternalStorageProvider provider,
                                final ResourceKey resource) {
        if (!Config.skipMismatchedStorageTypes) {
            return false;
        }
        if (provider instanceof TypedExternalStorageProvider typed
            && !typed.rstweaks$serves(resource)) {
            ++Stats.mismatchedProviderCallsAvoided;
            return true;
        }
        return false;
    }
}
