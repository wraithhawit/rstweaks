package com.wraithhawit.rstweaks.storage;

import com.refinedmods.refinedstorage.common.support.resource.FluidResource;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import com.refinedmods.refinedstorage.neoforge.support.resource.VariantUtil;

import javax.annotation.Nullable;

import net.minecraft.world.item.ItemStack;

import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.IFluidHandlerItem;

/**
 * What a filled fluid container holds, and what it becomes when emptied.
 *
 * <p>Lifted out of the old {@code FluidSwap} in 0.2.80 so that the crafting-grid refill did not
 * depend on the fluid substitution feature. The two shared this code only because both need to ask
 * an item what fluid is in it — a question about the item, not about patterns — and that shared
 * helper was enough that one feature would have died with the other. Fluid substitution was removed
 * in 0.2.81; this is the part that outlived it, which was the point of splitting it out first.
 *
 * <p>Everything goes through the item's own fluid handler capability rather than hardcoding buckets,
 * so any container a pack adds is covered by the same rule.
 */
public final class FluidContainers {
    private FluidContainers() {
    }

    /** What a container holds, and what is left once it is emptied. */
    public record Contents(FluidResource fluid, long amount, ItemResource emptied) {
    }

    /**
     * What this item holds, or {@code null} if it is not a full container.
     *
     * <p>Asks the item's own fluid handler and then drains a copy, so the emptied form is whatever
     * the container itself says it becomes — a bucket for a bucket, and whatever a mod's own
     * container turns into.
     */
    @Nullable
    public static Contents of(final ItemResource resource) {
        final ItemStack stack = resource.toItemStack(1L);
        final IFluidHandlerItem handler = stack.getCapability(Capabilities.FluidHandler.ITEM);
        if (handler == null) {
            return null;
        }
        final FluidStack contained = handler.drain(Integer.MAX_VALUE, IFluidHandler.FluidAction.SIMULATE);
        if (contained.isEmpty()) {
            return null;
        }
        handler.drain(Integer.MAX_VALUE, IFluidHandler.FluidAction.EXECUTE);
        final ItemStack emptied = handler.getContainer();
        if (emptied.isEmpty() || emptied.getCount() != 1) {
            // A container that vanishes or multiplies when emptied is not something we can trade
            // one-for-one, so it is left alone.
            return null;
        }
        return new Contents(
            VariantUtil.ofFluidStack(contained),
            contained.getAmount(),
            ItemResource.ofItemStack(emptied));
    }
}
