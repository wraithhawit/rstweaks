package com.wraithhawit.rstweaks.storage;

import com.refinedmods.refinedstorage.common.api.support.resource.ResourceContainer;

import javax.annotation.Nullable;

import net.minecraft.nbt.CompoundTag;

/**
 * Where the tab you are <em>not</em> looking at keeps its pattern.
 *
 * <p>The fluid substitution tab is a relayout of Refined Storage's processing matrix, so both tabs
 * write to the same two containers — which is why building a machine recipe and then making a
 * bucket pattern used to be the same piece of paper. Rather than adding containers to the block
 * entity, which would mean widening the {@code PatternGridData} record the menu is built from and
 * syncing them by hand, the inactive tab's contents are parked here as NBT and swapped into the
 * live containers when you change tabs.
 *
 * <p>That keeps the sync problem from existing at all: the containers being written are the ones
 * Refined Storage already sends to the client every tick, so the other tab's pattern appears by
 * the same route the auto-fill does.
 *
 * <p>Implemented by the <em>block entity</em> rather than the menu, so closing the grid does not
 * throw the hidden half away. It is persisted alongside the pattern the grid is holding.
 */
public interface FluidSwapStash {
    /**
     * Which tab was live when this grid was last touched.
     *
     * <p>Kept because the swap is a toggle: without knowing what is already loaded, reopening a
     * grid that was left on the fluid tab and having the client announce the tab would swap the
     * live pattern out for the stashed one and show the wrong matrix.
     */
    boolean rstweaks$fluidTabOpen();

    void rstweaks$setFluidTabOpen(boolean open);

    /**
     * The fluid tab's own input matrix, separate from the Processing tab's.
     *
     * <p>Replaces the copy-in/copy-out stash above. Two containers that both exist at once are what
     * let two players hold the same grid open on different tabs without moving each other's pattern
     * — slots belong to a menu, containers belong to the block entity, so each menu can be pointed
     * at the pair its own tab needs. The stash could never express that: it has one live matrix and
     * one parked one, no matter how many people are looking.
     */
    ResourceContainer rstweaks$fluidInput();

    /** The fluid tab's own output matrix. */
    ResourceContainer rstweaks$fluidOutput();
}
