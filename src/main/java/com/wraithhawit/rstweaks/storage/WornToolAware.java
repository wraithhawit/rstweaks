package com.wraithhawit.rstweaks.storage;

import com.refinedmods.refinedstorage.api.resource.ResourceKey;

import javax.annotation.Nullable;

/**
 * Lets the byproduct half of the durability fix see which worn tool the ingredient half
 * actually took.
 *
 * <p>The two live in different mixins — ingredient extraction is on
 * {@code AbstractTaskPattern}, byproduct emission on {@code InternalTaskPattern} — and a
 * mixin cannot read another mixin's fields. Mixin can make a target implement an
 * interface, so the subclass casts to this to reach the superclass's record of what was
 * consumed this iteration.
 */
public interface WornToolAware {
    /**
     * The tool actually taken for {@code encoded}, or {@code null} when this iteration did not
     * involve one.
     *
     * <p>Asked per tool rather than answered with "the tool this iteration used", because a recipe
     * may consume more than one. A single answer meant the byproduct of every tool but the last
     * one seen came back exactly as encoded — which is to say repaired, out of nothing. Rare,
     * since two durable tools in one recipe is unusual, and a duplication bug all the same.
     */
    @Nullable
    ResourceKey rstweaks$consumedTool(ResourceKey encoded);

    /**
     * Everything the task this pattern belongs to consumes, captured when the task was
     * built. Lets a pattern tell whether something it is about to push to the network is
     * still needed by a sibling — which is the difference between a bucket cycling
     * through two patterns and a craft that jams after one iteration.
     */
    java.util.Set<ResourceKey> rstweaks$taskConsumes();
}
