package com.wraithhawit.rstweaks.storage;

import com.refinedmods.refinedstorage.api.autocrafting.Pattern;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;

import java.util.Map;

/**
 * The two fields a batched step needs, which live on the wrong class to shadow.
 *
 * <p>{@code pattern} and {@code ingredients} are declared on {@code AbstractTaskPattern}, and
 * {@code BatchedStepMixin} targets its subclass {@code InternalTaskPattern}. Mixin's
 * {@code @Shadow} resolves fields against the target class alone — it does not walk the
 * hierarchy — so shadowing them from the subclass fails at apply time with "field was not located
 * in the target class", and with {@code defaultRequire = 1} that failure takes the whole task
 * engine down rather than just the optimization.
 *
 * <p>So the superclass's own mixin exposes them, and the subclass casts to this. The same
 * arrangement {@link WornToolAware} uses for the same reason.
 */
public interface TaskPatternInternals {
    /** The pattern being crafted. */
    Pattern rstweaks$pattern();

    /**
     * The task's remaining ingredient budget: per ingredient slot, which concrete resources are
     * still allocated to it and how many of each.
     *
     * <p>Live and mutable — this is the real map, drawn down one iteration at a time as the task
     * runs. A batched step has to spend it exactly as the serial path would.
     */
    Map<Integer, Map<ResourceKey, Long>> rstweaks$ingredients();
}
