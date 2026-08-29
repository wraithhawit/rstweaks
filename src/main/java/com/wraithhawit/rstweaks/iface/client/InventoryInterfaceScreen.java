package com.wraithhawit.rstweaks.iface.client;

import com.refinedmods.refinedstorage.common.storage.FilterModeSideButtonWidget;
import com.refinedmods.refinedstorage.common.support.AbstractBaseScreen;
import com.refinedmods.refinedstorage.common.support.AbstractFilterScreen;
import com.refinedmods.refinedstorage.common.support.containermenu.PropertyTypes;
import com.refinedmods.refinedstorage.common.support.containermenu.ResourceSlot;
import com.refinedmods.refinedstorage.common.support.widget.FuzzyModeSideButtonWidget;

import com.wraithhawit.rstweaks.RSTweaks;
import com.wraithhawit.rstweaks.iface.ConfigureSlotPacket;
import com.wraithhawit.rstweaks.iface.InventoryInterfaceMenu;
import com.wraithhawit.rstweaks.iface.InventoryInterfaceState;
import com.wraithhawit.rstweaks.iface.SlotMode;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

/**
 * The Inventory Interface configuration screen.
 *
 * <p>Drawn on Refined Storage's {@code generic_filter.png}, which is a one-row filter over a player
 * inventory — 176x137, filter slots at y=20, inventory at y=55. Those numbers are not a choice; the
 * slots in {@link InventoryInterfaceMenu} are placed to land on the holes in that texture. It is
 * also why the filter is nine slots and one row: a second row would need a background nobody has
 * drawn, and the whole reason this issue sat open upstream for years is that it was labelled
 * {@code art-necessary}.
 *
 * <h2>Two kinds of per-slot control</h2>
 *
 * <p><b>Filter slots carry a mode marker</b> in the bottom-right corner — the same corner and the
 * same 10x10 geometry Refined Storage uses for {@code renderExportingIndicators}, so it looks like
 * something the Exporter would do. Clicking the marker cycles both → insert → export → off.
 *
 * <p>Getting a click there took a seam rather than a new gesture: a filter slot's clicks are all
 * spoken for already (plain click opens the amount screen, shift-click clears it, click-with-an-item
 * sets it). {@link #canInteractWithResourceSlot} is Refined Storage's own hook for exactly this — it
 * returns false over the marker, so that click falls past its handler and reaches ours, and every
 * click anywhere else in the slot behaves as it always did.
 *
 * <p><b>Player inventory slots carry a tick</b>, but only while the "choose slots" side button is
 * on. Making that a mode rather than a modifier is deliberate: these are real inventory slots you
 * need for dragging items into filters, and quietly changing what a plain click does to your own
 * inventory is how a screen eats somebody's stack. With the mode on, excluded slots are washed red
 * and a click toggles; with it off, the screen behaves exactly as before.
 */
public class InventoryInterfaceScreen extends AbstractBaseScreen<InventoryInterfaceMenu> {
    private static final ResourceLocation AUTO_INSERT_ON = sideButton("auto_insert/yes");
    private static final ResourceLocation AUTO_INSERT_OFF = sideButton("auto_insert/no");
    private static final ResourceLocation AUTO_EXPORT_ON = sideButton("auto_export/yes");
    private static final ResourceLocation AUTO_EXPORT_OFF = sideButton("auto_export/no");
    private static final ResourceLocation CHOOSE_SLOTS_ON = sideButton("choose_slots/yes");
    private static final ResourceLocation CHOOSE_SLOTS_OFF = sideButton("choose_slots/no");

    private static final ResourceLocation[] MODE_MARKERS = {
        marker("off"), marker("insert"), marker("export"), marker("both"),
    };

    /** Refined Storage's own indicator geometry, so the marker sits where an Exporter's would. */
    private static final int MARKER_SIZE = 10;
    private static final int MARKER_X = 7 + 18 - MARKER_SIZE + 1;
    private static final int MARKER_Y = 19 + 18 - MARKER_SIZE + 1;

    /** Excluded slots are washed rather than badged: it reads at a glance and needs no sprite. */
    private static final int EXCLUDED_WASH = 0x80D02020;

    private boolean choosingSlots;

    public InventoryInterfaceScreen(final InventoryInterfaceMenu menu,
                                    final Inventory playerInventory,
                                    final Component title) {
        super(menu, playerInventory, title);
        inventoryLabelY = 42;
        imageWidth = 176;
        imageHeight = 137;
    }

    @Override
    protected ResourceLocation getTexture() {
        return AbstractFilterScreen.TEXTURE;
    }

    @Override
    protected void init() {
        super.init();
        addSideButton(new ToggleSideButtonWidget(
            getMenu().getProperty(InventoryInterfaceMenu.INSERT),
            Component.translatable("gui.rstweaks.inventory_interface.auto_insert"),
            AUTO_INSERT_ON,
            AUTO_INSERT_OFF,
            Component.translatable("gui.rstweaks.inventory_interface.auto_insert.help")));
        addSideButton(new ToggleSideButtonWidget(
            getMenu().getProperty(InventoryInterfaceMenu.EXPORT),
            Component.translatable("gui.rstweaks.inventory_interface.auto_export"),
            AUTO_EXPORT_ON,
            AUTO_EXPORT_OFF,
            Component.translatable("gui.rstweaks.inventory_interface.auto_export.help")));
        addSideButton(new FilterModeSideButtonWidget(
            getMenu().getProperty(PropertyTypes.FILTER_MODE),
            Component.translatable("gui.rstweaks.inventory_interface.filter_mode.allow_help"),
            Component.translatable("gui.rstweaks.inventory_interface.filter_mode.block_help")));
        addSideButton(new FuzzyModeSideButtonWidget(
            getMenu().getProperty(PropertyTypes.FUZZY_MODE),
            () -> FuzzyModeSideButtonWidget.Type.GENERIC));
        addSideButton(new ChooseSlotsSideButtonWidget(
            () -> choosingSlots, () -> choosingSlots = !choosingSlots));
    }

    // ---------------------------------------------------------------- filter slot mode markers

    @Override
    protected void renderBg(final GuiGraphics graphics, final float delta, final int mouseX, final int mouseY) {
        super.renderBg(graphics, delta, mouseX, mouseY);
        for (int i = 0; i < InventoryInterfaceState.FILTER_SLOTS; ++i) {
            final SlotMode mode = getMenu().slotMode(i);
            graphics.blitSprite(MODE_MARKERS[mode.getId()],
                leftPos + MARKER_X + i * 18, topPos + MARKER_Y, MARKER_SIZE, MARKER_SIZE);
        }
    }

    /**
     * Hands the marker's ten pixels back to us. Everything else in the slot stays Refined Storage's.
     */
    @Override
    protected boolean canInteractWithResourceSlot(final ResourceSlot resourceSlot,
                                                  final double mouseX,
                                                  final double mouseY) {
        return markerIndexAt(mouseX, mouseY) < 0;
    }

    @Override
    public boolean mouseClicked(final double mouseX, final double mouseY, final int button) {
        final int marker = markerIndexAt(mouseX, mouseY);
        if (marker >= 0) {
            getMenu().sendSlotConfiguration(
                ConfigureSlotPacket.filterSlotMode(marker, getMenu().slotMode(marker).next()));
            return true;
        }
        if (choosingSlots && hoveredSlot != null && toggleInsertSlot(hoveredSlot)) {
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private int markerIndexAt(final double mouseX, final double mouseY) {
        final int y = topPos + MARKER_Y;
        if (mouseY < y || mouseY >= y + MARKER_SIZE) {
            return -1;
        }
        for (int i = 0; i < InventoryInterfaceState.FILTER_SLOTS; ++i) {
            final int x = leftPos + MARKER_X + i * 18;
            if (mouseX >= x && mouseX < x + MARKER_SIZE) {
                return i;
            }
        }
        return -1;
    }

    // ---------------------------------------------------------------- inventory slot ticks

    @Override
    protected void renderSlot(final GuiGraphics graphics, final Slot slot) {
        super.renderSlot(graphics, slot);
        if (!choosingSlots) {
            return;
        }
        final int index = inventorySlotIndex(slot);
        if (index >= 0 && !getMenu().insertSlotEnabled(index)) {
            graphics.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, EXCLUDED_WASH);
        }
    }

    private boolean toggleInsertSlot(final Slot slot) {
        final int index = inventorySlotIndex(slot);
        if (index < 0) {
            return false;
        }
        getMenu().sendSlotConfiguration(
            ConfigureSlotPacket.insertSlot(index, !getMenu().insertSlotEnabled(index)));
        return true;
    }

    /**
     * The player inventory index behind a menu slot, or -1 for a filter slot.
     *
     * <p>By container identity rather than by slot number, because the filter slots are added first
     * and their indices would otherwise collide with the inventory's.
     */
    private int inventorySlotIndex(final Slot slot) {
        if (!(slot.container instanceof Inventory)) {
            return -1;
        }
        final int index = slot.getContainerSlot();
        return index >= 0 && index < InventoryInterfaceState.INVENTORY_SLOTS ? index : -1;
    }

    private static ResourceLocation sideButton(final String path) {
        return ResourceLocation.fromNamespaceAndPath(RSTweaks.MODID, "widget/side_button/" + path);
    }

    private static ResourceLocation marker(final String path) {
        return ResourceLocation.fromNamespaceAndPath(RSTweaks.MODID, "widget/slot_mode/" + path);
    }
}
