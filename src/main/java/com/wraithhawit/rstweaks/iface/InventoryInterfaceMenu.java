package com.wraithhawit.rstweaks.iface;

import java.util.function.UnaryOperator;

import com.refinedmods.refinedstorage.common.api.RefinedStorageApi;
import com.refinedmods.refinedstorage.common.api.support.resource.ResourceContainer;
import com.refinedmods.refinedstorage.common.api.support.slotreference.SlotReference;
import com.refinedmods.refinedstorage.common.support.containermenu.AbstractResourceContainerMenu;
import com.refinedmods.refinedstorage.common.support.containermenu.ClientProperty;
import com.refinedmods.refinedstorage.common.support.containermenu.PropertyType;
import com.refinedmods.refinedstorage.common.support.containermenu.PropertyTypes;
import com.refinedmods.refinedstorage.common.support.containermenu.ResourceSlot;
import com.refinedmods.refinedstorage.common.support.containermenu.ResourceSlotType;
import com.refinedmods.refinedstorage.common.support.containermenu.ServerProperty;
import com.refinedmods.refinedstorage.common.support.resource.ResourceContainerImpl;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import org.jetbrains.annotations.Nullable;

/**
 * The Inventory Interface configuration screen's menu.
 *
 * <p>Almost none of this is ours. Extending {@link AbstractResourceContainerMenu} means Refined
 * Storage's {@code ResourceSlotChangePacket}, {@code ResourceSlotAmountChangePacket} and
 * {@code PropertyChangePacket} already serve it: every one of those handlers dispatches on the
 * base class rather than on a menu type, so editing a filter slot, setting its amount, and
 * toggling a side button all arrive here without this mod registering a packet of its own. The two
 * filter buttons reuse Refined Storage's property types outright, so its widgets can drive them.
 *
 * <p>The two that are ours are the toggles, and they need ids in our namespace because the
 * property lookup on the way back in matches by id across whatever menu is open.
 *
 * <p>Everything the server writes goes straight onto the grid stack. There is no block entity to
 * be the owner of this configuration and no saved data of our own: the stack in the player's hand
 * is the storage, which is what makes the setting travel with the item when it is handed to
 * somebody else.
 */
public class InventoryInterfaceMenu extends AbstractResourceContainerMenu {
    public static final PropertyType<Boolean> INSERT =
        PropertyTypes.createBooleanProperty(InventoryInterfaceContent.id("auto_insert"));
    public static final PropertyType<Boolean> EXPORT =
        PropertyTypes.createBooleanProperty(InventoryInterfaceContent.id("auto_export"));

    private static final Component FILTER_HELP =
        Component.translatable("gui.rstweaks.inventory_interface.filter_help");

    private static final int FILTER_SLOT_X = 8;
    private static final int FILTER_SLOT_Y = 20;
    private static final int PLAYER_INVENTORY_Y = 55;

    /**
     * The live stack in the player's inventory on the server, {@code null} on the client. Live
     * matters: it is the same object the inventory holds, so {@code set} on it is the save.
     */
    @Nullable
    private final ItemStack gridStack;

    /**
     * The client's copy, because there is no stack to read on that side and the screen has to draw
     * the per-slot markers from something.
     *
     * <p>Updated locally the moment a marker is clicked, then the same change is sent. That is how
     * Refined Storage's own {@code ClientProperty} behaves — set locally, send, let the server be
     * authoritative — and it is what keeps a marker from lagging a round trip behind the click that
     * changed it. The two cannot meaningfully diverge: the server applies the identical edit, and a
     * rejected one only happens when the screen is not open, in which case there is nothing to draw.
     */
    private InventoryInterfaceState clientState = InventoryInterfaceState.EMPTY;

    private final ResourceContainer filter;

    public InventoryInterfaceMenu(final int syncId,
                                  final Inventory playerInventory,
                                  final InventoryInterfaceData data) {
        super(InventoryInterfaceContent.MENU.get(), syncId);
        this.gridStack = null;
        this.clientState = data.state();
        this.disabledSlot = data.slotReference().orElse(null);
        this.filter = ResourceContainerImpl.createForFilter(
            RefinedStorageApi.INSTANCE.getItemResourceFactory(), data.filter());
        registerProperty(new ClientProperty<>(INSERT, data.state().insert()));
        registerProperty(new ClientProperty<>(EXPORT, data.state().export()));
        registerProperty(new ClientProperty<>(PropertyTypes.FILTER_MODE, data.state().filterMode()));
        registerProperty(new ClientProperty<>(PropertyTypes.FUZZY_MODE, data.state().fuzzyMode()));
        addSlots(playerInventory.player);
    }

    InventoryInterfaceMenu(final int syncId,
                           final Player player,
                           final ItemStack gridStack,
                           final ResourceContainer filter,
                           final SlotReference slotReference) {
        super(InventoryInterfaceContent.MENU.get(), syncId, player);
        this.gridStack = gridStack;
        this.disabledSlot = slotReference;
        this.filter = filter;
        registerProperty(new ServerProperty<>(INSERT, () -> state().insert(),
            value -> mutate(state -> state.withInsert(value))));
        registerProperty(new ServerProperty<>(EXPORT, () -> state().export(),
            value -> mutate(state -> state.withExport(value))));
        registerProperty(new ServerProperty<>(PropertyTypes.FILTER_MODE, () -> state().filterMode(),
            value -> mutate(state -> state.withFilterMode(value))));
        registerProperty(new ServerProperty<>(PropertyTypes.FUZZY_MODE, () -> state().fuzzyMode(),
            value -> mutate(state -> state.withFuzzyMode(value))));
        addSlots(player);
    }

    /**
     * Only item filters. The player inventory this reads from and writes to holds items, so
     * offering a fluid slot would offer a filter entry that can never match anything — which is
     * worse than not offering it, because it looks like a feature that is broken.
     */
    static ResourceContainer createFilterContainer() {
        return ResourceContainerImpl.createForFilter(
            RefinedStorageApi.INSTANCE.getItemResourceFactory(), InventoryInterfaceState.FILTER_SLOTS);
    }

    private void addSlots(final Player player) {
        for (int i = 0; i < filter.size(); ++i) {
            addSlot(new ResourceSlot(filter, i, FILTER_HELP,
                FILTER_SLOT_X + 18 * i, FILTER_SLOT_Y, ResourceSlotType.FILTER_WITH_AMOUNT));
        }
        addPlayerInventory(player.getInventory(), 8, PLAYER_INVENTORY_Y);
        transferManager.addFilterTransfer(player.getInventory());
    }

    /** The item on the server, the synced copy on the client. */
    InventoryInterfaceState state() {
        if (gridStack == null) {
            return clientState;
        }
        return gridStack.getOrDefault(InventoryInterfaceContent.STATE.get(), InventoryInterfaceState.EMPTY);
    }

    private void mutate(final UnaryOperator<InventoryInterfaceState> change) {
        if (gridStack == null) {
            clientState = change.apply(clientState);
            return;
        }
        gridStack.set(InventoryInterfaceContent.STATE.get(), change.apply(state()));
    }

    /**
     * Applies one per-slot edit. Called on the server by {@link ConfigureSlotPacket}, and on the
     * client by the screen the instant the marker is clicked so it does not lag the round trip.
     *
     * <p>The index is bounds-checked inside {@code withSlotMode} and {@code withInsertSlot}, which
     * return the state unchanged rather than throwing — this is reached from a packet, and an index
     * from the network is not a promise.
     */
    void configureSlot(final ConfigureSlotPacket.Kind kind, final int index, final int value) {
        switch (kind) {
            case FILTER_SLOT_MODE -> mutate(state -> state.withSlotMode(index, SlotMode.byId(value)));
            case INSERT_SLOT -> mutate(state -> state.withInsertSlot(index, value != 0));
        }
    }

    /** What the screen draws in a filter slot's corner. */
    public SlotMode slotMode(final int index) {
        return state().slotMode(index);
    }

    /** Whether auto-insert may take from one player inventory slot, for the screen's overlay. */
    public boolean insertSlotEnabled(final int slot) {
        return state().insertSlotEnabled(slot);
    }

    /**
     * Sends one per-slot edit and applies it locally. Client side only — {@code gridStack} is null
     * there, so {@link #mutate} writes to the synced copy rather than to an item that is not here.
     */
    public void sendSlotConfiguration(final ConfigureSlotPacket packet) {
        configureSlot(packet.kind(), packet.index(), packet.value());
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(packet);
    }


    /**
     * The grid has to still be where the menu said it was.
     *
     * <p>{@code disabledSlot} is the reference to the slot the item was opened from, and resolving
     * it is the same check Refined Storage makes for a wireless grid: drop the item, or have it
     * taken, and the screen closes rather than writing settings to a stack that is no longer
     * yours. Null player is the client copy of the menu, which has no say in this.
     */
    @Override
    public boolean stillValid(final Player playerIn) {
        if (player == null || disabledSlot == null) {
            return true;
        }
        return disabledSlot.resolve(playerIn)
            .map(stack -> stack == gridStack)
            .orElse(false);
    }
}
