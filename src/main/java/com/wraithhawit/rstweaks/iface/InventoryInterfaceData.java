package com.wraithhawit.rstweaks.iface;

import java.util.Optional;

import com.refinedmods.refinedstorage.api.resource.filter.FilterMode;
import com.refinedmods.refinedstorage.common.api.support.slotreference.SlotReference;
import com.refinedmods.refinedstorage.common.api.support.slotreference.SlotReferenceFactory;
import com.refinedmods.refinedstorage.common.support.FilterModeSettings;
import com.refinedmods.refinedstorage.common.support.resource.ResourceContainerData;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * What the client is told when the configuration screen opens.
 *
 * <p>The four settings are also data slots, so the server would push them a tick after the screen
 * appeared anyway. They are sent here as well because that tick is visible: without them every
 * side button paints its "off" sprite on the first frame and then flips, which reads as the screen
 * resetting the configuration you came to look at.
 *
 * <p>The slot reference travels through Refined Storage's own {@link SlotReferenceFactory} codec,
 * which is why the reference this feature uses has to be Refined Storage's {@code
 * InventorySlotReference} rather than one of ours — the codec dispatches on a registry, and only
 * types in that registry survive the trip. The client needs it to grey out the slot the grid
 * itself is sitting in, so that the item you are configuring cannot be picked up out from under
 * the screen that is configuring it.
 */
public record InventoryInterfaceData(
    Optional<SlotReference> slotReference,
    boolean insert,
    boolean export,
    FilterMode filterMode,
    boolean fuzzyMode,
    ResourceContainerData filter
) {
    public static final StreamCodec<RegistryFriendlyByteBuf, InventoryInterfaceData> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.optional(SlotReferenceFactory.STREAM_CODEC), InventoryInterfaceData::slotReference,
            ByteBufCodecs.BOOL, InventoryInterfaceData::insert,
            ByteBufCodecs.BOOL, InventoryInterfaceData::export,
            ByteBufCodecs.INT.map(FilterModeSettings::getFilterMode, FilterModeSettings::getFilterMode),
            InventoryInterfaceData::filterMode,
            ByteBufCodecs.BOOL, InventoryInterfaceData::fuzzyMode,
            ResourceContainerData.STREAM_CODEC, InventoryInterfaceData::filter,
            InventoryInterfaceData::new
        );
}
