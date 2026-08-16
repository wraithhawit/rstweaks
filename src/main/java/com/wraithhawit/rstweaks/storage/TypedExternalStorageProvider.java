package com.wraithhawit.rstweaks.storage;

import com.refinedmods.refinedstorage.api.resource.ResourceKey;

/**
 * Implemented (via mixin) by external storage providers whose resource type is a fixed
 * property of the class rather than something that depends on the world.
 *
 * <p>An item handler can never hold energy. That is not a cache, an observation or a
 * guess — it is true at compile time, so a composite that skips such a provider is
 * skipping a call whose answer is provably zero. Nothing to invalidate, nothing to go
 * stale, and no way for it to be wrong.
 *
 * <p>Which matters, because the tempting version of this optimization is to remember
 * that a provider returned zero and stop asking. That conflates "wrong type" with
 * "empty right now", and a drawer filled by a pipe would silently stop being seen.
 * Providers that do not implement this interface are always asked, every time.
 */
public interface TypedExternalStorageProvider {
    /**
     * @return {@code false} only when this provider could not possibly serve the
     *     resource, whatever the state of the world.
     */
    boolean rstweaks$serves(ResourceKey resource);
}
