package com.wraithhawit.rstweaks.test;

import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.wraithhawit.rstweaks.planner.Durability;

import javax.annotation.Nullable;

/**
 * A {@link Durability} with no Minecraft behind it, so wear can be tested headlessly.
 *
 * <p>Tools are named {@code "<tool>@<damage>"} — {@code "crystal@0"} is pristine,
 * {@code "crystal@99"} has one use left when {@code maxUses} is 100. That mirrors how
 * Minecraft encodes damage in the item's component patch, where a crystal at damage 0 and
 * the same crystal at damage 1 are genuinely different resources to Refined Storage,
 * which is the whole reason this feature is hard.
 */
class FakeDurability implements Durability {
    private final java.util.Set<String> toolNames;
    private final int maxUses;

    FakeDurability(final String toolName, final int maxUses) {
        this(java.util.Set.of(toolName), maxUses);
    }

    /**
     * Several distinct tools, for the fuzzy case.
     *
     * <p>A fuzzy ingredient accepts a whole family — every ore hammer, every crystal — and those
     * are <em>different tools</em>, not wear levels of one. {@link #sameTool} therefore has to say
     * no between them, which is exactly what made the planner split a fuzzy slot into one class per
     * tool and then demand the first. See issue #9.
     */
    FakeDurability(final java.util.Set<String> toolNames, final int maxUses) {
        this.toolNames = java.util.Set.copyOf(toolNames);
        this.maxUses = maxUses;
    }

    /** {@code crystal@7} for a tool worn seven times. */
    static String worn(final String tool, final int damage) {
        return tool + "@" + damage;
    }

    @Nullable
    private String[] split(final ResourceKey resource) {
        final String id = String.valueOf(resource);
        final int at = id.indexOf('@');
        if (at < 0 || !toolNames.contains(id.substring(0, at))) {
            return null;
        }
        return new String[] {id.substring(0, at), id.substring(at + 1)};
    }

    @Override
    public boolean isDurable(final ResourceKey resource) {
        return split(resource) != null;
    }

    @Override
    public int maxUses(final ResourceKey resource) {
        return isDurable(resource) ? maxUses : 0;
    }

    @Override
    public int usesLeft(final ResourceKey resource) {
        final String[] parts = split(resource);
        return parts == null ? 0 : Math.max(0, maxUses - Integer.parseInt(parts[1]));
    }

    @Nullable
    @Override
    public ResourceKey afterUses(final ResourceKey resource, final int uses) {
        final String[] parts = split(resource);
        if (parts == null || uses <= 0) {
            return resource;
        }
        final int worn = Integer.parseInt(parts[1]) + uses;
        // Matches vanilla: the tool is destroyed once damage reaches its maximum, and
        // the caller reads null as "that was its last use".
        return worn >= maxUses ? null : PlannerExecutabilitySelfTest.res(worn(parts[0], worn));
    }

    @Override
    public boolean sameTool(final ResourceKey a, final ResourceKey b) {
        final String[] left = split(a);
        final String[] right = split(b);
        // Same tool means same item at a different wear level. Two different hammers are not
        // interchangeable wear levels of each other, and saying otherwise here would hide the
        // very bug these fixtures exist to catch.
        return left != null && right != null && left[0].equals(right[0]);
    }
}
