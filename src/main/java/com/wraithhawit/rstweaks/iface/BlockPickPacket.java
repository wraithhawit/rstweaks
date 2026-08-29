package com.wraithhawit.rstweaks.iface;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * "I pressed pick block at this face of this block, and I do not have it."
 *
 * <p>The one packet this mod has. It carries <b>where the player was looking</b>, not what they
 * want — the server derives the item from its own copy of the block. That is the difference
 * between a request the server can check and a request it has to take on trust: a client that sent
 * an item stack could ask for a stack of anything, with any components on it, and the only thing
 * standing between that and the network would be whether the network happened to contain it. A
 * block position can be checked against what is actually there and against whether the player can
 * reach it, and both checks are made.
 *
 * <p>The face is sent because {@code getCloneItemStack} takes a {@link net.minecraft.world.phys.HitResult}.
 * Almost no block reads it, and the ones that do are the reason it is not simply invented
 * server-side.
 */
public record BlockPickPacket(BlockPos pos, Direction face) implements CustomPacketPayload {
    public static final Type<BlockPickPacket> TYPE =
        new Type<>(InventoryInterfaceContent.id("block_pick"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BlockPickPacket> STREAM_CODEC =
        StreamCodec.composite(
            BlockPos.STREAM_CODEC, BlockPickPacket::pos,
            Direction.STREAM_CODEC, BlockPickPacket::face,
            BlockPickPacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
