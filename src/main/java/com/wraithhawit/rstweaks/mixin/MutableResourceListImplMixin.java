package com.wraithhawit.rstweaks.mixin;

import com.wraithhawit.rstweaks.storage.VersionedResourceList;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Counts mutations of a resource list, so a cached answer about its contents can be invalidated
 * exactly.
 *
 * <h2>What this is for</h2>
 *
 * <p>A crafting task's internal storage is a {@code MutableResourceListImpl} — {@code TaskImpl}
 * builds it with {@code MutableResourceListImpl.create()}, verified in the 2.0.9 bytecode, so this
 * is the concrete object our substitution mixin is handed and not a proxy in front of one.
 *
 * <p>Refined Storage re-steps one pattern up to 175,552 times a tick, and a pattern that cannot
 * proceed rescans that whole storage every time for an answer that has not changed. Measured on a
 * real insanium craft: <b>169,947,478 repeated failing simulates, 1,052 of which reached a
 * different answer</b> — one in 161,547.
 *
 * <p>Those 1,052 are not noise, and the log says exactly what they are: the crystal wears, a
 * new more-worn wear level appears in shared storage, and {@code findWornTool}'s "use the most worn
 * one first" rule flips the choice — fresh, then {@code @275}, then {@code @494}. In one case the
 * previous answer was <em>no substitute at all</em> and then one appeared, which a blind cache would
 * have turned into a craft that refuses to run.
 *
 * <p>Every one of them is a mutation of this list. Counting mutations therefore converts a
 * 99.99938% hunch into an exact test.
 *
 * <h2>Why a counter and not a content hash</h2>
 *
 * <p>Hashing the contents is what the cache exists to avoid — the keys are {@code ItemResource}s
 * whose {@code hashCode} walks a {@code DataComponentPatch}, and that hashing is already the largest
 * single self-time frame in the profile. A {@code long} increment is free by comparison, and a
 * counter that over-counts merely costs a recomputation while one that under-counts would be a
 * correctness bug — so it is bumped on every mutating method, whether or not the contents actually
 * moved.
 */
@Mixin(targets = "com.refinedmods.refinedstorage.api.resource.list.MutableResourceListImpl")
public abstract class MutableResourceListImplMixin implements VersionedResourceList {
    /**
     * Deliberately a primitive with no initializer.
     *
     * <p>Mixin does not run field initialisers reliably — this project lost items to exactly that,
     * on a {@code @Unique} field in {@code AbstractTaskPatternMixin} that read as null for seven
     * versions. A {@code long} defaults to zero without one, so there is nothing here to go wrong.
     */
    @Unique
    private long rstweaks$version;

    @Override
    public long rstweaks$version() {
        return this.rstweaks$version;
    }

    @Inject(method = "add(Lcom/refinedmods/refinedstorage/api/resource/ResourceKey;J)"
        + "Lcom/refinedmods/refinedstorage/api/resource/list/MutableResourceList$OperationResult;",
        at = @At("RETURN"))
    private void rstweaks$versionOnAdd(final CallbackInfoReturnable<Object> cir) {
        this.rstweaks$version++;
    }

    @Inject(method = "remove(Lcom/refinedmods/refinedstorage/api/resource/ResourceKey;J)"
        + "Lcom/refinedmods/refinedstorage/api/resource/list/MutableResourceList$OperationResult;",
        at = @At("RETURN"))
    private void rstweaks$versionOnRemove(final CallbackInfoReturnable<Object> cir) {
        this.rstweaks$version++;
    }

    @Inject(method = "clear()V", at = @At("RETURN"))
    private void rstweaks$versionOnClear(final CallbackInfo ci) {
        this.rstweaks$version++;
    }
}
