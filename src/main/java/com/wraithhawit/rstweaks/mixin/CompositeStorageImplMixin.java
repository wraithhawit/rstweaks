package com.wraithhawit.rstweaks.mixin;

import com.refinedmods.refinedstorage.api.core.Action;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.api.resource.list.MutableResourceList;
import com.refinedmods.refinedstorage.api.storage.Actor;
import com.refinedmods.refinedstorage.api.storage.composite.CompositeStorageImpl;
import com.wraithhawit.rstweaks.Config;
import com.wraithhawit.rstweaks.Stats;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Stops an extraction asking every storage in the network for something the network does not have.
 *
 * <p>{@code extract} walks {@code extractSources} unconditionally:
 *
 * <pre>{@code   for (Storage source : extractSources) source.compositeExtract(resource, ...); }</pre>
 *
 * <p>Every exporter, pattern provider and crafting step extracts on its own schedule, and the
 * overwhelming majority of those calls are for a resource that particular network has none of — so
 * the cost is the walk, not the work. Measured on a struggling instance: the composite and proxy
 * extract layers came to roughly 12% of the whole server thread, against 3% for the innermost
 * inventory scan they exist to reach.
 *
 * <p><b>No new cache.</b> The answer comes from {@code list}, the {@link MutableResourceList} the
 * composite already maintains of everything its sources hold — the same list the grid displays and
 * the same one {@code RootStorage.get} answers from. Nothing is added that could go stale
 * independently, and it self-heals the instant Refined Storage's own bookkeeping updates, because
 * it <em>is</em> that bookkeeping.
 *
 * <p><b>Default off since 0.2.63, and the reason is worth keeping.</b> The paragraph that used to
 * sit here argued the list could only ever be <em>late</em> — up to about two seconds while
 * {@code ExternalStorage.detectChanges()} caught up — and that nothing could therefore be lost. That
 * understated it. {@code ExternalStorage.compositeInsert} returns an {@code amountForList} of zero,
 * so an insert into an external storage contributes nothing to the list directly; the list gains the
 * item only because {@code insert} then calls {@code detectChanges()}, which <b>diffs a fresh
 * {@code provider.iterator()} snapshot against a cache</b>. A diff can be wrong, not just late. If
 * the item leaves the inventory before the snapshot is taken — a pipe, a router, a void upgrade,
 * anything pulling on the same tick — the diff sees no addition and the list never gains it, while
 * {@code CompositeStorageImpl.insert} still reports a full insert to its caller.
 *
 * <p>That desync predates this mixin and was harmless: the grid showed nothing, but every real
 * extraction still walked the storages and found the items. This mixin is what makes it terminal —
 * extraction returns zero the instant the list says zero, so items physically sitting in a drawer
 * become both invisible and unreachable, which from the game is indistinguishable from deletion.
 * Opened by a report of a completed autocrafting task whose output never appeared (rstweaks 0.2.62,
 * LavaSurf, 2026-08-13); the mechanism is proven, that it caused that specific report is not.
 *
 * <p>Presence is never asserted from the list — any non-zero reading falls through to the ordinary
 * walk, so a partial or stale <em>positive</em> costs one normal extraction and nothing else.
 */
@Mixin(CompositeStorageImpl.class)
public abstract class CompositeStorageImplMixin {
    @Shadow
    @Final
    private MutableResourceList list;

    @Inject(method = "extract", at = @At("HEAD"), cancellable = true)
    private void rstweaks$skipWhenNetworkHasNone(final ResourceKey resource,
                                                 final long amount,
                                                 final Action action,
                                                 final Actor actor,
                                                 final CallbackInfoReturnable<Long> cir) {
        if (!Config.skipEmptyCompositeExtract) {
            return;
        }
        if (this.list.get(resource) <= 0L) {
            // Zero is exactly what the walk would have returned, so every caller sees the result it
            // already handles: an exporter waits, a task stays queued, nothing is dropped.
            ++Stats.emptyExtractsAvoided;
            cir.setReturnValue(0L);
        }
    }
}
