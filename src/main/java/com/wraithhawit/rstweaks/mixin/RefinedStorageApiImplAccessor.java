package com.wraithhawit.rstweaks.mixin;

import com.refinedmods.refinedstorage.common.RefinedStorageApiImpl;
import com.refinedmods.refinedstorage.common.support.slotreference.CompositeSlotReferenceProvider;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reads Refined Storage's composite slot-reference provider — the list of every place a grid can
 * be kept.
 *
 * <p>A grid does not have to be in your inventory. Refined Storage's Curios integration registers a
 * provider through {@code RefinedStorageApi.addSlotReferenceProvider}, so a Wireless Grid worn in a
 * Curios slot is a perfectly ordinary grid that {@code player.getInventory()} cannot see — which is
 * exactly how this mod failed to find one until somebody wore theirs.
 *
 * <p>The API lets anyone <em>add</em> a provider and nobody read the composite back, so this is the
 * only way to ask "where are this player's grids" and get an answer that includes what other mods
 * have contributed. Doing it this way rather than depending on Curios directly means a backpack or
 * trinket integration nobody here has heard of also works, for the same reason the supported-item
 * list is ids rather than classes.
 *
 * <p>Safe on both sides: Refined Storage runs this same composite on the client for its own
 * open-grid keybinds ({@code useSlotReferencedItem}), so every registered provider is already
 * expected to answer for a client player.
 */
@Mixin(RefinedStorageApiImpl.class)
public interface RefinedStorageApiImplAccessor {
    @Accessor("slotReferenceProvider")
    CompositeSlotReferenceProvider rstweaks$getSlotReferenceProvider();
}
