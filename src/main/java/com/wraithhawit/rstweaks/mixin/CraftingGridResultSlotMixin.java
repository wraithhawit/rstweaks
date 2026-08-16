package com.wraithhawit.rstweaks.mixin;

import com.refinedmods.refinedstorage.api.core.Action;
import com.refinedmods.refinedstorage.api.network.Network;
import com.refinedmods.refinedstorage.api.network.storage.StorageNetworkComponent;
import com.refinedmods.refinedstorage.api.storage.Actor;
import com.refinedmods.refinedstorage.common.api.storage.PlayerActor;
import com.refinedmods.refinedstorage.common.grid.CraftingGrid;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import com.wraithhawit.rstweaks.Config;
import com.wraithhawit.rstweaks.RSTweaks;
import com.wraithhawit.rstweaks.storage.FluidContainers;
import com.wraithhawit.rstweaks.storage.GridNetworkAccess;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Refills a container in the crafting grid instead of leaving it empty.
 *
 * <p>Crafting a cake takes three milk buckets and hands back three empty ones, so the next cake
 * needs three more milk buckets that the network does not have — even when it is holding plenty of
 * milk. Refined Storage already refills an ordinary ingredient from the network after a craft
 * ({@code useIngredient} pulls a replacement); this is the same courtesy for the ingredient that
 * comes back as a container.
 *
 * <p><b>Nothing is created.</b> The bucket stays where it is and the network pays for it, one of
 * two ways: a full container already in storage is traded for the empty one, and only if there is
 * none does the fluid get spent filling the bucket the player has. Stock first, then the tank —
 * the ready-made bucket is what the network was holding for exactly this, and turning fluid into
 * containers while full ones sit in a drawer is the wrong way round.
 *
 * <p><b>No patterns required, since 0.2.80.</b> This used to demand that the network hold a pattern
 * producing the filled container, as a proxy for "the player meant this fluid to be convertible".
 * That proxy only ever worked because our own fluid substitution registered such a pattern, and
 * with that feature parked the check could never pass — the feature was dead rather than cautious.
 *
 * <p>What limits it now is simply whether the network can pay. If there is no full container in
 * storage and not enough fluid, nothing happens and the empty container is handed back exactly as
 * Refined Storage intended. That is a better guard than the pattern was: it asks whether the
 * network <em>can</em>, rather than whether the player once encoded something unrelated.
 *
 * <p>The test on the items themselves is unchanged and is the strict part — one container, one
 * remainder, and the remainder must be precisely that container emptied. A recipe with a bespoke
 * remainder is not this and is left alone.
 *
 * <p>Targeted by string because the slot class is package-private.
 */
@Mixin(targets = "com.refinedmods.refinedstorage.common.grid.CraftingGridResultSlot")
public abstract class CraftingGridResultSlotMixin {
    @Shadow
    @Final
    private CraftingGrid craftingGrid;

    /**
     * At the head of the remaining-item path, before Refined Storage schedules the swap of the
     * ingredient for its empty container. Cancelling leaves the full container in the slot, which
     * is the whole feature: the craft has already taken what it needed, and the fluid to make the
     * container whole again comes out of storage.
     */
    @Inject(method = "useIngredientWithRemainingItem", at = @At("HEAD"), cancellable = true)
    private void rstweaks$refillFromNetwork(final Player player,
                                            final int slot,
                                            final ItemStack remainingItem,
                                            final CallbackInfo ci) {
        if (!Config.refillContainersInCraftingGrid) {
            return;
        }
        try {
            if (rstweaks$refill(player, slot, remainingItem)) {
                ci.cancel();
            }
        } catch (final RuntimeException | LinkageError e) {
            // Leaving the empty container behind is the old behaviour and always correct, so any
            // surprise here costs a convenience rather than a craft.
            RSTweaks.LOGGER.warn("[rstweaks] could not refill a container in the crafting grid", e);
        }
    }

    @Unique
    private boolean rstweaks$refill(final Player player,
                                    final int slot,
                                    final ItemStack remainingItem) {
        if (remainingItem.getCount() != 1) {
            return false;
        }
        final ItemStack inSlot = this.craftingGrid.getCraftingMatrix().getItem(slot);
        // One at a time. A stack of filled containers would need one extraction each, and the
        // amount to pay for is only unambiguous when a single one is being handed back.
        if (inSlot.isEmpty() || inSlot.getCount() != 1) {
            return false;
        }
        final ItemResource filled = ItemResource.ofItemStack(inSlot);
        final FluidContainers.Contents contents = FluidContainers.of(filled);
        if (contents == null) {
            return false;
        }
        // The remainder has to be this container emptied, and nothing else. A recipe with a
        // bespoke remainder is not a swap and must be left alone.
        if (!contents.emptied().equals(ItemResource.ofItemStack(remainingItem))) {
            return false;
        }
        if (!(this.craftingGrid instanceof GridNetworkAccess access)) {
            return false;
        }
        final Network network = access.rstweaks$network();
        if (network == null) {
            return false;
        }
        final StorageNetworkComponent storage = network.getComponent(StorageNetworkComponent.class);
        final Actor actor = new PlayerActor(player);
        final ItemResource emptied = ItemResource.ofItemStack(remainingItem);
        // A milk bucket already in the system is spent before any milk is. Both routes leave the
        // player with the same full bucket in the same slot, so the only difference is which of
        // the two the network gives up -- and the ready-made one is the one it is holding for
        // exactly this.
        return rstweaks$payWithStoredContainer(storage, actor, filled, emptied)
            || rstweaks$payWithFluid(storage, actor, contents);
    }

    /**
     * Trades a full container from storage for the empty one the craft handed back.
     *
     * <p>The slot is left alone, so what really happens is a swap of ownership: the network gives
     * up one full container and takes the empty one. Refined Storage does the same thing for an
     * ordinary ingredient in {@code useIngredient}; it simply never had a path for the ones that
     * come back as containers.
     */
    @Unique
    private static boolean rstweaks$payWithStoredContainer(final StorageNetworkComponent storage,
                                                           final Actor actor,
                                                           final ItemResource filled,
                                                           final ItemResource emptied) {
        if (storage.extract(filled, 1L, Action.SIMULATE, actor) < 1L) {
            return false;
        }
        // The empty one has to have somewhere to go. Taking the full container without being able
        // to hand back the empty would destroy it, so both halves are simulated before either
        // runs.
        if (storage.insert(emptied, 1L, Action.SIMULATE, actor) < 1L) {
            return false;
        }
        storage.extract(filled, 1L, Action.EXECUTE, actor);
        storage.insert(emptied, 1L, Action.EXECUTE, actor);
        return true;
    }

    /** Fills the container the player already has, out of the fluid in storage. */
    @Unique
    private static boolean rstweaks$payWithFluid(final StorageNetworkComponent storage,
                                                 final Actor actor,
                                                 final FluidContainers.Contents contents) {
        // Simulated first: a partial extraction would take the fluid and still leave the player
        // with an empty bucket, which is worse than not helping at all.
        if (storage.extract(contents.fluid(), contents.amount(), Action.SIMULATE, actor)
            < contents.amount()) {
            return false;
        }
        storage.extract(contents.fluid(), contents.amount(), Action.EXECUTE, actor);
        return true;
    }
}
