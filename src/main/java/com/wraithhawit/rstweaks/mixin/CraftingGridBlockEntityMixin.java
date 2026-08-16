package com.wraithhawit.rstweaks.mixin;

import com.refinedmods.refinedstorage.api.network.Network;
import com.refinedmods.refinedstorage.common.grid.CraftingGridBlockEntity;
import com.wraithhawit.rstweaks.storage.GridNetworkAccess;

import java.util.Optional;

import javax.annotation.Nullable;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Hands out the Crafting Grid's network, so the result slot can pay for a refilled container.
 *
 * <p>Shadowing rather than re-deriving it: the block entity already knows how to find its network,
 * and {@code getNetwork()} is declared here, which is the one place a shadow is legal.
 *
 * @see GridNetworkAccess
 */
@Mixin(CraftingGridBlockEntity.class)
public abstract class CraftingGridBlockEntityMixin implements GridNetworkAccess {
    @Shadow
    private Optional<Network> getNetwork() {
        throw new AssertionError("shadow");
    }

    @Override
    @Nullable
    public Network rstweaks$network() {
        return getNetwork().orElse(null);
    }
}
