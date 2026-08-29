package com.wraithhawit.rstweaks.iface.client;

import com.refinedmods.refinedstorage.common.storage.FilterModeSideButtonWidget;
import com.refinedmods.refinedstorage.common.support.AbstractBaseScreen;
import com.refinedmods.refinedstorage.common.support.AbstractFilterScreen;
import com.refinedmods.refinedstorage.common.support.containermenu.PropertyTypes;
import com.refinedmods.refinedstorage.common.support.widget.FuzzyModeSideButtonWidget;

import com.wraithhawit.rstweaks.RSTweaks;
import com.wraithhawit.rstweaks.iface.InventoryInterfaceMenu;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

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
 * <p>Four side buttons, and only two of the sprites are ours. The filter mode and fuzzy mode
 * buttons are Refined Storage's own widgets driven by Refined Storage's own property types, so
 * they look and behave exactly as they do on an Exporter — which is the point, since a player who
 * has configured an Exporter has already learned this screen.
 */
public class InventoryInterfaceScreen extends AbstractBaseScreen<InventoryInterfaceMenu> {
    private static final ResourceLocation AUTO_INSERT_ON = sprite("auto_insert/yes");
    private static final ResourceLocation AUTO_INSERT_OFF = sprite("auto_insert/no");
    private static final ResourceLocation AUTO_EXPORT_ON = sprite("auto_export/yes");
    private static final ResourceLocation AUTO_EXPORT_OFF = sprite("auto_export/no");

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
    }

    private static ResourceLocation sprite(final String path) {
        return ResourceLocation.fromNamespaceAndPath(RSTweaks.MODID, "widget/side_button/" + path);
    }
}
