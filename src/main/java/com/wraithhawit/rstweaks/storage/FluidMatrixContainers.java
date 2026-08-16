package com.wraithhawit.rstweaks.storage;

import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.common.api.RefinedStorageApi;
import com.refinedmods.refinedstorage.common.api.support.resource.PlatformResourceKey;
import com.refinedmods.refinedstorage.common.api.support.resource.ResourceContainer;
import com.refinedmods.refinedstorage.common.api.support.resource.ResourceFactory;
import com.refinedmods.refinedstorage.common.support.resource.ResourceContainerImpl;

import java.util.Set;
import java.util.function.ToLongFunction;

/**
 * The fluid tab's own pair of matrix containers, built from public Refined Storage API only.
 *
 * <p><b>An earlier attempt shipped a class inside Refined Storage's own package to call its
 * package-private container factory. That does not work on NeoForge and must not be retried.</b>
 * Mods are loaded as named modules on the module path, so two modules exporting the same package is
 * a hard error — {@code java.lang.module.ResolutionException: Modules refinedstorage and rstweaks
 * export package ...} — raised during module resolution, before the game starts. Reflection is no
 * better: the package is not opened to us, so {@code setAccessible} would throw.
 *
 * <p>So these are plain {@link ResourceContainerImpl}s, which is everything the fluid tab actually
 * needs. What they lack against Refined Storage's {@code ProcessingMatrixInputResourceContainer} is
 * the fuzzy allowed-alternatives machinery — {@code setAllowedTagIds} and {@code getIngredient} —
 * and a fluid substitution has no use for it. A swap is one exact container against one exact fluid;
 * {@code FluidSwap.soleItem} rejects an input slot offering alternatives outright, because which
 * container came back would depend on which was taken and the pattern cannot say. Storing
 * alternatives here would only preserve something the feature refuses to honour.
 *
 * <p>The encode path still runs through Refined Storage's own container, so nothing about how a
 * pattern is written changes — see the borrow in {@code PatternGridContainerMenuMixin}.
 */
public final class FluidMatrixContainers {
    private FluidMatrixContainers() {
    }

    /** Matches the 9x9 the Pattern Grid uses, so a rebound slot index is always in range. */
    private static final int SIZE = 81;

    /**
     * The input side, which <b>must</b> carry {@link ProcessingInputContainer}.
     *
     * <p>{@code rstweaks$inputSlots()} finds the matrix's input slots by testing their container
     * against that marker, which a mixin applies to Refined Storage's own
     * {@code ProcessingMatrixInputResourceContainer}. A plain {@link ResourceContainerImpl} does not
     * carry it, so once the fluid tab rebound its slots to one, the input list came back empty and
     * auto-fill silently had nothing to fill — the bucket went in and the other side stayed blank.
     * That was the 0.2.72 regression; 0.2.71 did not have it only because its container really was
     * Refined Storage's class.
     */
    public static ResourceContainer createInput() {
        return new FluidMatrixInput(
            SIZE,
            FluidMatrixContainers::processingPatternLimit,
            RefinedStorageApi.INSTANCE.getItemResourceFactory(),
            RefinedStorageApi.INSTANCE.getAlternativeResourceFactories());
    }

    /** The output side, where no marker is needed — output slots are found by identity. */
    public static ResourceContainer createOutput() {
        return new ResourceContainerImpl(
            SIZE,
            FluidMatrixContainers::processingPatternLimit,
            RefinedStorageApi.INSTANCE.getItemResourceFactory(),
            RefinedStorageApi.INSTANCE.getAlternativeResourceFactories());
    }

    /** Subclassing is the only way to wear the marker: we cannot mixin onto our own class. */
    private static final class FluidMatrixInput extends ResourceContainerImpl
        implements ProcessingInputContainer {
        private FluidMatrixInput(final int size,
                                 final ToLongFunction<ResourceKey> maxAmountProvider,
                                 final ResourceFactory primaryResourceFactory,
                                 final Set<ResourceFactory> alternativeResourceFactories) {
            super(size, maxAmountProvider, primaryResourceFactory, alternativeResourceFactories);
        }
    }

    /**
     * The same rule as {@code PatternGridBlockEntity.getProcessingPatternLimit}, which is private.
     *
     * <p>One line, and reimplementing it is safe in a way that reimplementing the container was not:
     * it asks the resource for its own limit, so it stays correct for any resource type a pack adds.
     */
    private static long processingPatternLimit(final ResourceKey resource) {
        return resource instanceof PlatformResourceKey platformResource
            ? platformResource.getProcessingPatternLimit()
            : 1L;
    }
}
