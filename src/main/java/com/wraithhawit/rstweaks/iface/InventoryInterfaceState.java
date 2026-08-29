package com.wraithhawit.rstweaks.iface;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.api.resource.filter.FilterMode;
import com.refinedmods.refinedstorage.common.api.support.resource.ResourceContainer;
import com.refinedmods.refinedstorage.common.support.FilterModeSettings;
import com.refinedmods.refinedstorage.common.support.resource.ResourceCodecs;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * Everything an Inventory Interface remembers, stored on the grid's own {@link net.minecraft.world.item.ItemStack}.
 *
 * <p>This rides on <em>Refined Storage's</em> items — a Wireless Grid, a Portable Grid, and the
 * wireless grids the addons add. A data component can be attached to any stack by anyone, so
 * carrying our configuration on somebody else's item needs no fork, no mixin and no cooperation
 * from the mod that registered it. The component type is ours ({@code rstweaks:inventory_interface}),
 * so uninstalling this mod leaves the grid itself untouched; the unknown component is dropped and
 * the item still works.
 *
 * <p><b>The filter is one list and the amount means one thing:</b> how many of that resource to
 * keep on you.
 *
 * <ul>
 *   <li>{@code insert} with {@link FilterMode#ALLOW} files away everything on the list
 *       <em>beyond</em> its amount.
 *   <li>{@code insert} with {@link FilterMode#BLOCK} files away everything <em>not</em> on the
 *       list, and leaves listed resources alone entirely.
 *   <li>{@code export} tops listed resources back up to their amount. It reads the list, not the
 *       mode, because topping up is a whitelist by nature — which is what makes BLOCK + export
 *       coherent rather than contradictory: "these are mine, do not put them away, and keep me
 *       stocked with them".
 * </ul>
 *
 * <p>Nine slots because that is the width of Refined Storage's {@code generic_filter.png}, which
 * this feature's screen is drawn on rather than on a texture of our own.
 */
public record InventoryInterfaceState(
    boolean insert,
    boolean export,
    FilterMode filterMode,
    boolean fuzzyMode,
    List<Optional<ResourceAmount>> filter
) {
    /** Fixed: the screen is Refined Storage's one-row filter background. */
    public static final int FILTER_SLOTS = 9;

    public static final InventoryInterfaceState EMPTY =
        new InventoryInterfaceState(false, false, FilterMode.ALLOW, false, emptyFilter());

    public static final Codec<InventoryInterfaceState> CODEC = RecordCodecBuilder.create(
        instance -> instance.group(
            Codec.BOOL.optionalFieldOf("insert", false).forGetter(InventoryInterfaceState::insert),
            Codec.BOOL.optionalFieldOf("export", false).forGetter(InventoryInterfaceState::export),
            Codec.INT
                .xmap(FilterModeSettings::getFilterMode, FilterModeSettings::getFilterMode)
                .optionalFieldOf("filter_mode", FilterMode.ALLOW)
                .forGetter(InventoryInterfaceState::filterMode),
            Codec.BOOL.optionalFieldOf("fuzzy_mode", false).forGetter(InventoryInterfaceState::fuzzyMode),
            ResourceCodecs.AMOUNT_OPTIONAL_CODEC.listOf()
                .optionalFieldOf("filter", emptyFilter())
                .forGetter(InventoryInterfaceState::filter)
        ).apply(instance, InventoryInterfaceState::new)
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, InventoryInterfaceState> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.BOOL, InventoryInterfaceState::insert,
            ByteBufCodecs.BOOL, InventoryInterfaceState::export,
            ByteBufCodecs.INT.map(FilterModeSettings::getFilterMode, FilterModeSettings::getFilterMode),
            InventoryInterfaceState::filterMode,
            ByteBufCodecs.BOOL, InventoryInterfaceState::fuzzyMode,
            ByteBufCodecs.collection(ArrayList::new, ResourceCodecs.AMOUNT_STREAM_OPTIONAL_CODEC),
            InventoryInterfaceState::filter,
            InventoryInterfaceState::new
        );

    public InventoryInterfaceState {
        filter = normalize(filter);
    }

    /**
     * A shorter or longer list than the screen has slots is not a state this mod ever writes, but
     * it is a state it can be handed: a config change, a hand-edited NBT, or a future version that
     * moved to two rows. Padding rather than throwing keeps a stack that has been through a
     * different build usable instead of turning it into a crash on tooltip render.
     */
    private static List<Optional<ResourceAmount>> normalize(final List<Optional<ResourceAmount>> given) {
        if (given.size() == FILTER_SLOTS) {
            return List.copyOf(given);
        }
        final List<Optional<ResourceAmount>> padded = new ArrayList<>(FILTER_SLOTS);
        for (int i = 0; i < FILTER_SLOTS; ++i) {
            padded.add(i < given.size() ? given.get(i) : Optional.empty());
        }
        return Collections.unmodifiableList(padded);
    }

    private static List<Optional<ResourceAmount>> emptyFilter() {
        return Collections.nCopies(FILTER_SLOTS, Optional.empty());
    }

    /** True when this configuration would do something on a tick. */
    public boolean isActive() {
        return insert || export;
    }

    public InventoryInterfaceState withInsert(final boolean newInsert) {
        return new InventoryInterfaceState(newInsert, export, filterMode, fuzzyMode, filter);
    }

    public InventoryInterfaceState withExport(final boolean newExport) {
        return new InventoryInterfaceState(insert, newExport, filterMode, fuzzyMode, filter);
    }

    public InventoryInterfaceState withFilterMode(final FilterMode newFilterMode) {
        return new InventoryInterfaceState(insert, export, newFilterMode, fuzzyMode, filter);
    }

    public InventoryInterfaceState withFuzzyMode(final boolean newFuzzyMode) {
        return new InventoryInterfaceState(insert, export, filterMode, newFuzzyMode, filter);
    }

    public InventoryInterfaceState withFilter(final ResourceContainer container) {
        final List<Optional<ResourceAmount>> resources = new ArrayList<>(container.size());
        for (int i = 0; i < container.size(); ++i) {
            resources.add(Optional.ofNullable(container.get(i)));
        }
        return new InventoryInterfaceState(insert, export, filterMode, fuzzyMode, resources);
    }
}
