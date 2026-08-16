package com.wraithhawit.rstweaks.test;

import com.refinedmods.refinedstorage.api.resource.ResourceKey;

/**
 * A tool that is recognised as durable but never actually wears down.
 *
 * <p>This is stock Refined Storage's behaviour when the byproduct is left alone: the
 * pattern was encoded with a crystal at damage 0 and returns one at damage 1, so the same
 * damage-1 crystal comes back on every iteration and the tool is effectively immortal.
 * The craft runs to completion, the plan is executable, and one crystal does sixty-four
 * jobs — which looks like a working feature and is a duplication glitch.
 *
 * <p>Used only to show that the durability conservation check has teeth.
 */
final class NeverWears extends FakeDurability {
    NeverWears(final String toolName, final int maxUses) {
        super(toolName, maxUses);
    }

    @Override
    public ResourceKey afterUses(final ResourceKey resource, final int uses) {
        return resource;
    }
}
