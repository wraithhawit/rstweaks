package com.wraithhawit.rstweaks.storage;

import com.refinedmods.refinedstorage.api.network.Network;

import javax.annotation.Nullable;

/**
 * Reaches the network behind a Crafting Grid.
 *
 * <p>{@code CraftingGridBlockEntity.getNetwork()} is private and the {@code CraftingGrid} interface
 * exposes only what the crafting matrix needs, so a slot holding a {@code CraftingGrid} has no way
 * to ask about storage. A mixin can shadow the private method and hand it out through this.
 */
public interface GridNetworkAccess {
    /** The network this grid is attached to, or {@code null} if it is not attached to one. */
    @Nullable
    Network rstweaks$network();
}
