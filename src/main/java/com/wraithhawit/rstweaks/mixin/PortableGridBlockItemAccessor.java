package com.wraithhawit.rstweaks.mixin;

import com.refinedmods.refinedstorage.common.storage.portablegrid.PortableGridBlockItem;
import com.refinedmods.refinedstorage.common.storage.portablegrid.PortableGridType;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reads whether a Portable Grid item is the creative one.
 *
 * <p>The Inventory Interface has to charge a Portable Grid for the transfers it makes, the same
 * way Refined Storage charges it for the ones you make by hand. Refined Storage answers "creative
 * or not" three different ways and none of them can be asked from outside:
 * {@code PortableGridBlockItem.isCreative} is private, {@code createEnergyStorageInternal} — the
 * one that substitutes {@code CreativeEnergyStorage} — is private, and the public static
 * {@code createEnergyStorage(stack)} deliberately does not, because its callers are the renderer
 * and the disk writer, which want the stored number rather than the effective one.
 * {@code getRenderInfo} does answer it, but reaches the client-only storage repository on the way,
 * so it cannot be called on a server.
 *
 * <p>Which leaves the field. An {@link Accessor} rather than a copy of the naming convention:
 * "the id starts with {@code creative_}" would be right today for all four grids in the allowlist
 * and is a string comparison standing in for a fact the item already knows.
 */
@Mixin(PortableGridBlockItem.class)
public interface PortableGridBlockItemAccessor {
    @Accessor("type")
    PortableGridType rstweaks$getType();
}
