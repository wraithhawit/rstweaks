package com.wraithhawit.rstweaks.iface;

import java.util.Optional;

import com.refinedmods.refinedstorage.api.storage.Actor;
import com.refinedmods.refinedstorage.common.api.storage.PlayerActor;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;

import com.wraithhawit.rstweaks.Config;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Pick block, out of the network.
 *
 * <p>[refinedmods/refinedstorage-quartz-arsenal#4]. Vanilla's pick-block in survival can only
 * reach into your own inventory: aim at a block you are not carrying, press the key, and nothing
 * happens. With a Wireless or Portable Grid on you, this makes that case reach the network instead
 * — which is what RS1's request asked for, in the words of somebody who was tired of opening a
 * grid every thirty seconds while building.
 *
 * <p>Deliberately <b>not</b> tied to the Inventory Interface's filter. That filter is a standing
 * instruction about what to do while nobody is looking; this is a key you pressed, about the block
 * you are looking at, and making it obey a filter would mean the answer to "why did that not work"
 * is a screen you have to go and read.
 *
 * <h2>What the server does not take on trust</h2>
 *
 * <p>The packet says where, not what — see {@link BlockPickPacket}. On top of that: the chunk has
 * to be loaded, the block has to still be there, and the player has to be able to reach it. The
 * reach check is Minecraft's own {@code canInteractWithBlock}, the same one that decides whether
 * you may break the block, so a client that lies about position is already caught by everything
 * else.
 *
 * <p>And then the ordinary rules: the grid must resolve (bound, in range, powered), the player must
 * hold {@code EXTRACT} on that network, and the extraction is charged for. Nothing here is a
 * shortcut around what opening the grid and clicking the item would have cost.
 */
public final class BlockPick {
    /**
     * Slack on the reach check, in blocks.
     *
     * <p>The client decided what it was looking at a few frames before this packet arrived and the
     * player has been moving since. Minecraft's own block-breaking check uses a comparable
     * allowance for the same reason; without one, picking while walking backwards fails at random.
     */
    private static final double REACH_PADDING = 1.0;

    private BlockPick() {
    }

    public static void handle(final BlockPickPacket packet, final IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        context.enqueueWork(() -> pick(player, packet.pos(), packet.face()));
    }

    /**
     * Runs one pick. Separated from the packet so it can be driven by a test without inventing an
     * {@link IPayloadContext} — the packet is plumbing, this is the behaviour.
     *
     * @return true when something was actually handed to the player
     */
    public static boolean pick(final ServerPlayer player, final BlockPos pos, final Direction face) {
        if (!Config.blockPick) {
            return false;
        }
        final ServerLevel level = player.serverLevel();
        if (!level.isLoaded(pos) || !player.canInteractWithBlock(pos, REACH_PADDING)) {
            return false;
        }
        final BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            return false;
        }
        final ItemStack wanted = state.getCloneItemStack(
            new BlockHitResult(Vec3.atCenterOf(pos), face, pos, false), level, pos, player);
        if (wanted.isEmpty()) {
            return false;
        }
        final Optional<InventoryInterfaceTarget> resolved = InventoryInterfaceTarget.firstCarried(player);
        if (resolved.isEmpty()) {
            return false;
        }
        final InventoryInterfaceTarget target = resolved.get();
        if (!target.mayExtract(player)) {
            return false;
        }
        // The destination is chosen BEFORE anything is extracted. Choosing afterwards would mean
        // discovering there is nowhere to put a stack that has already left the disk, and the only
        // moves left at that point are to put it back or to drop it -- neither of which is what
        // somebody who pressed pick block was asking for, and one of which is how items get lost.
        final int destination = destinationSlot(player.getInventory());
        if (destination < 0) {
            return false;
        }
        final ItemResource resource = ItemResource.ofItemStack(wanted);
        final Actor actor = new PlayerActor(player);
        final long extracted = target.extract(resource, wanted.getMaxStackSize(), actor);
        if (extracted <= 0L) {
            return false;
        }
        place(player, destination, resource.toItemStack(extracted));
        return true;
    }

    /**
     * Where the picked stack goes, or -1 when there is nowhere for it.
     *
     * <p>Vanilla's creative equivalent, {@code Inventory.setPickedItem}, will overwrite the
     * selected hotbar slot when it cannot find anywhere to move its contents. In creative that
     * costs nothing. Here it would destroy something the player mined, so a full inventory means
     * the pick simply does not happen.
     */
    private static int destinationSlot(final Inventory inventory) {
        if (inventory.getItem(inventory.selected).isEmpty()) {
            return inventory.selected;
        }
        for (int slot = 0; slot < Inventory.getSelectionSize(); ++slot) {
            if (inventory.getItem(slot).isEmpty()) {
                return slot;
            }
        }
        // Hotbar full. The selected slot can still be used if its contents have somewhere to go,
        // which is what makes picking feel the same as it does in creative: what you asked for
        // ends up in your hand rather than somewhere in the bag.
        final int free = inventory.getFreeSlot();
        if (free >= 0) {
            inventory.setItem(free, inventory.getItem(inventory.selected));
            inventory.setItem(inventory.selected, ItemStack.EMPTY);
            return inventory.selected;
        }
        return -1;
    }

    private static void place(final ServerPlayer player, final int slot, final ItemStack stack) {
        final Inventory inventory = player.getInventory();
        inventory.setItem(slot, stack);
        inventory.selected = slot;
        inventory.setChanged();
        // The client picked the block but the server picked the slot, so the client has to be told
        // which one. Without this the item appears in the hotbar and the player is still holding
        // whatever they were holding before, which reads as the feature putting it in the wrong
        // place.
        player.connection.send(new ClientboundSetCarriedItemPacket(slot));
    }
}
