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
    List<Optional<ResourceAmount>> filter,
    List<SlotMode> slotModes,
    long insertSlots
) {
    /** Fixed: the screen is Refined Storage's one-row filter background. */
    public static final int FILTER_SLOTS = 9;

    /** The player inventory slots auto-insert can see: the hotbar and the three main rows. */
    public static final int INVENTORY_SLOTS = 36;

    /**
     * Every inventory slot allowed, which is what an untouched configuration means.
     *
     * <p>Not "everything except the hotbar", even though that is the shipped behaviour: the hotbar
     * is excluded by the {@code inventoryInterfaceInsertFromHotbar} config, which is a server-level
     * policy about whether this feature may touch hotbars at all. This mask is the player's own
     * choice of which slots within what the server allows. Two switches, but they answer different
     * questions and only one of them is in the screen.
     */
    public static final long ALL_INVENTORY_SLOTS = (1L << INVENTORY_SLOTS) - 1L;

    public static final InventoryInterfaceState EMPTY = new InventoryInterfaceState(
        false, false, FilterMode.ALLOW, false, emptyFilter(), defaultSlotModes(), ALL_INVENTORY_SLOTS);

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
                .forGetter(InventoryInterfaceState::filter),
            Codec.INT.xmap(SlotMode::byId, SlotMode::getId).listOf()
                .optionalFieldOf("slot_modes", defaultSlotModes())
                .forGetter(InventoryInterfaceState::slotModes),
            Codec.LONG.optionalFieldOf("insert_slots", ALL_INVENTORY_SLOTS)
                .forGetter(InventoryInterfaceState::insertSlots)
        ).apply(instance, InventoryInterfaceState::new)
    );

    private static final StreamCodec<RegistryFriendlyByteBuf, List<Optional<ResourceAmount>>>
        FILTER_STREAM_CODEC =
        ByteBufCodecs.collection(ArrayList::new, ResourceCodecs.AMOUNT_STREAM_OPTIONAL_CODEC)
            .map(List::copyOf, ArrayList::new);

    /**
     * Written out by hand rather than with {@code StreamCodec.composite}, which stops at six
     * components and this has seven. The slot modes are a fixed-length run of bytes rather than a
     * length-prefixed collection because the constructor has already padded them to
     * {@link #FILTER_SLOTS}, so the length is not information.
     */
    public static final StreamCodec<RegistryFriendlyByteBuf, InventoryInterfaceState> STREAM_CODEC =
        StreamCodec.of((buf, state) -> {
            buf.writeBoolean(state.insert());
            buf.writeBoolean(state.export());
            buf.writeByte(FilterModeSettings.getFilterMode(state.filterMode()));
            buf.writeBoolean(state.fuzzyMode());
            FILTER_STREAM_CODEC.encode(buf, state.filter());
            for (final SlotMode mode : state.slotModes()) {
                buf.writeByte(mode.getId());
            }
            buf.writeLong(state.insertSlots());
        }, buf -> {
            final boolean insert = buf.readBoolean();
            final boolean export = buf.readBoolean();
            final FilterMode filterMode = FilterModeSettings.getFilterMode(buf.readByte());
            final boolean fuzzyMode = buf.readBoolean();
            final List<Optional<ResourceAmount>> filter = FILTER_STREAM_CODEC.decode(buf);
            final List<SlotMode> slotModes = new ArrayList<>(FILTER_SLOTS);
            for (int i = 0; i < FILTER_SLOTS; ++i) {
                slotModes.add(SlotMode.byId(buf.readByte()));
            }
            return new InventoryInterfaceState(
                insert, export, filterMode, fuzzyMode, filter, slotModes, buf.readLong());
        });

    public InventoryInterfaceState {
        filter = normalize(filter);
        slotModes = normalizeModes(slotModes);
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

    /** Same padding rule as the filter, for the same reason. */
    private static List<SlotMode> normalizeModes(final List<SlotMode> given) {
        if (given.size() == FILTER_SLOTS) {
            return List.copyOf(given);
        }
        final List<SlotMode> padded = new ArrayList<>(FILTER_SLOTS);
        for (int i = 0; i < FILTER_SLOTS; ++i) {
            padded.add(i < given.size() ? given.get(i) : SlotMode.BOTH);
        }
        return Collections.unmodifiableList(padded);
    }

    private static List<Optional<ResourceAmount>> emptyFilter() {
        return Collections.nCopies(FILTER_SLOTS, Optional.empty());
    }

    private static List<SlotMode> defaultSlotModes() {
        return Collections.nCopies(FILTER_SLOTS, SlotMode.BOTH);
    }

    /**
     * A configuration with every filter slot in {@link SlotMode#BOTH} and every inventory slot
     * allowed, which is what the per-slot controls default to. The shorthand the tests and any
     * caller that does not care about them should use, so adding a field again does not touch
     * every construction site.
     */
    public static InventoryInterfaceState of(final boolean insert,
                                             final boolean export,
                                             final FilterMode filterMode,
                                             final boolean fuzzyMode,
                                             final List<Optional<ResourceAmount>> filter) {
        return new InventoryInterfaceState(
            insert, export, filterMode, fuzzyMode, filter, defaultSlotModes(), ALL_INVENTORY_SLOTS);
    }

    /** True when this configuration would do something on a tick. */
    public boolean isActive() {
        return insert || export;
    }

    public SlotMode slotMode(final int index) {
        return index >= 0 && index < slotModes.size() ? slotModes.get(index) : SlotMode.BOTH;
    }

    /** Whether auto-insert is allowed to take from one player inventory slot. */
    public boolean insertSlotEnabled(final int slot) {
        return slot < 0 || slot >= INVENTORY_SLOTS || (insertSlots & (1L << slot)) != 0L;
    }

    public InventoryInterfaceState withSlotMode(final int index, final SlotMode mode) {
        if (index < 0 || index >= FILTER_SLOTS) {
            return this;
        }
        final List<SlotMode> updated = new ArrayList<>(slotModes);
        updated.set(index, mode);
        return new InventoryInterfaceState(
            insert, export, filterMode, fuzzyMode, filter, updated, insertSlots);
    }

    public InventoryInterfaceState withInsertSlot(final int slot, final boolean enabled) {
        if (slot < 0 || slot >= INVENTORY_SLOTS) {
            return this;
        }
        final long bit = 1L << slot;
        final long updated = enabled ? insertSlots | bit : insertSlots & ~bit;
        return new InventoryInterfaceState(
            insert, export, filterMode, fuzzyMode, filter, slotModes, updated);
    }

    public InventoryInterfaceState withInsert(final boolean newInsert) {
        return new InventoryInterfaceState(newInsert, export, filterMode, fuzzyMode, filter, slotModes, insertSlots);
    }

    public InventoryInterfaceState withExport(final boolean newExport) {
        return new InventoryInterfaceState(insert, newExport, filterMode, fuzzyMode, filter, slotModes, insertSlots);
    }

    public InventoryInterfaceState withFilterMode(final FilterMode newFilterMode) {
        return new InventoryInterfaceState(insert, export, newFilterMode, fuzzyMode, filter, slotModes, insertSlots);
    }

    public InventoryInterfaceState withFuzzyMode(final boolean newFuzzyMode) {
        return new InventoryInterfaceState(insert, export, filterMode, newFuzzyMode, filter, slotModes, insertSlots);
    }

    public InventoryInterfaceState withFilter(final ResourceContainer container) {
        final List<Optional<ResourceAmount>> resources = new ArrayList<>(container.size());
        for (int i = 0; i < container.size(); ++i) {
            resources.add(Optional.ofNullable(container.get(i)));
        }
        return new InventoryInterfaceState(insert, export, filterMode, fuzzyMode, resources, slotModes, insertSlots);
    }
}
