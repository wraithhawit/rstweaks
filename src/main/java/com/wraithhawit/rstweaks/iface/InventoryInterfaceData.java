package com.wraithhawit.rstweaks.iface;

import java.util.Optional;

import com.refinedmods.refinedstorage.common.api.support.slotreference.SlotReference;
import com.refinedmods.refinedstorage.common.api.support.slotreference.SlotReferenceFactory;
import com.refinedmods.refinedstorage.common.support.resource.ResourceContainerData;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * What the client is told when the configuration screen opens.
 *
 * <p>The whole state, not a field at a time. Four of its settings are also data slots and the
 * server would push those a tick later anyway, but that tick is visible: without them every side
 * button paints its "off" sprite on the first frame and then flips, which reads as the screen
 * resetting the configuration you came to look at. The per-slot modes and the inventory mask are
 * not data slots at all — a data slot is an {@code int} and the mask is thirty-six bits — so for
 * those this is the only delivery.
 *
 * <p>The filter container travels separately because it is not part of the state on the wire: it is
 * a live {@code ResourceContainer} on the server whose changes flow through Refined Storage's own
 * slot packets, and {@link ResourceContainerData} is the shape those expect.
 *
 * <p>The slot reference travels through Refined Storage's own {@link SlotReferenceFactory} codec,
 * which is why the reference this feature uses has to be one Refined Storage knows — the codec
 * dispatches on a registry, and only types in that registry survive the trip. The client needs it
 * to grey out the slot the grid itself is sitting in, so the item being configured cannot be picked
 * up out from under the screen configuring it.
 */
public record InventoryInterfaceData(
    Optional<SlotReference> slotReference,
    InventoryInterfaceState state,
    ResourceContainerData filter
) {
    public static final StreamCodec<RegistryFriendlyByteBuf, InventoryInterfaceData> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.optional(SlotReferenceFactory.STREAM_CODEC), InventoryInterfaceData::slotReference,
            InventoryInterfaceState.STREAM_CODEC, InventoryInterfaceData::state,
            ResourceContainerData.STREAM_CODEC, InventoryInterfaceData::filter,
            InventoryInterfaceData::new
        );
}
