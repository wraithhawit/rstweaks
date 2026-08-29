package com.wraithhawit.rstweaks.iface;

import com.wraithhawit.rstweaks.RSTweaks;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * One per-slot edit made in the configuration screen.
 *
 * <p>Two edits, one packet, because they are the same shape and the same trust question: an index
 * and a small value, applied to whatever configuration the sender currently has open. Refined
 * Storage's own property mechanism would have carried these for free, but a property is an
 * {@code int} and the inventory mask is thirty-six bits, so half of it would not fit and the other
 * half would be two properties pretending to be one.
 *
 * <p>Nothing here is trusted beyond the index range. The packet cannot name a grid, a player or a
 * slot outside the screen: it is applied to {@code player.containerMenu} and only when that is one
 * of ours, which is the same check Refined Storage's own slot packets make.
 */
public record ConfigureSlotPacket(Kind kind, int index, int value) implements CustomPacketPayload {
    public static final Type<ConfigureSlotPacket> TYPE =
        new Type<>(InventoryInterfaceContent.id("configure_slot"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ConfigureSlotPacket> STREAM_CODEC =
        StreamCodec.of((buf, packet) -> {
            buf.writeByte(packet.kind().ordinal());
            buf.writeByte(packet.index());
            buf.writeByte(packet.value());
        }, buf -> {
            final int kind = buf.readByte();
            return new ConfigureSlotPacket(
                kind >= 0 && kind < Kind.VALUES.length ? Kind.VALUES[kind] : Kind.FILTER_SLOT_MODE,
                buf.readByte(),
                buf.readByte());
        });

    public enum Kind {
        /** {@code index} is a filter slot, {@code value} a {@link SlotMode} id. */
        FILTER_SLOT_MODE,
        /** {@code index} is a player inventory slot, {@code value} 0 or 1. */
        INSERT_SLOT;

        static final Kind[] VALUES = values();
    }

    public static void handle(final ConfigureSlotPacket packet, final IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        context.enqueueWork(() -> {
            if (player.containerMenu instanceof InventoryInterfaceMenu menu) {
                menu.configureSlot(packet.kind(), packet.index(), packet.value());
            } else {
                RSTweaks.LOGGER.debug("[rstweaks] inventory interface: slot edit with no screen open");
            }
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Built by the screen; the factories live here so both ends read the same shape. */
    public static ConfigureSlotPacket filterSlotMode(final int index, final SlotMode mode) {
        return new ConfigureSlotPacket(Kind.FILTER_SLOT_MODE, index, mode.getId());
    }

    public static ConfigureSlotPacket insertSlot(final int slot, final boolean enabled) {
        return new ConfigureSlotPacket(Kind.INSERT_SLOT, slot, enabled ? 1 : 0);
    }
}
