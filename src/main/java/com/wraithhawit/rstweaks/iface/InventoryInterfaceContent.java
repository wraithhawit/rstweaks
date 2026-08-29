package com.wraithhawit.rstweaks.iface;

import com.wraithhawit.rstweaks.RSTweaks;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.MenuType;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * The only two things this mod registers.
 *
 * <p>Both belong to us and neither touches Refined Storage's registries: the data component is
 * attached to Refined Storage's items at runtime, and the menu type exists so the configuration
 * screen can be opened as an ordinary menu. Uninstalling this mod therefore leaves a Wireless Grid
 * a Wireless Grid — the unknown component is dropped on load and the item is unchanged.
 *
 * <p>Nothing here is initialised eagerly. The codecs reach
 * {@code RefinedStorageApi.INSTANCE.getResourceTypeRegistry()}, which Refined Storage installs in
 * its own mod constructor, and the order of two mods' constructors is not something to depend on.
 * Every reference to them is inside a lambda that the registry event runs, long after every
 * constructor has finished.
 */
public final class InventoryInterfaceContent {
    private static final DeferredRegister.DataComponents COMPONENTS =
        DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, RSTweaks.MODID);

    private static final DeferredRegister<MenuType<?>> MENUS =
        DeferredRegister.create(Registries.MENU, RSTweaks.MODID);

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<InventoryInterfaceState>> STATE =
        COMPONENTS.registerComponentType("inventory_interface", builder -> builder
            .persistent(InventoryInterfaceState.CODEC)
            .networkSynchronized(InventoryInterfaceState.STREAM_CODEC));

    public static final DeferredHolder<MenuType<?>, MenuType<InventoryInterfaceMenu>> MENU =
        MENUS.register("inventory_interface", () -> IMenuTypeExtension.create(
            (syncId, playerInventory, buf) ->
                new InventoryInterfaceMenu(syncId, playerInventory, InventoryInterfaceData.STREAM_CODEC.decode(buf))));

    private InventoryInterfaceContent() {
    }

    public static void register(final IEventBus modEventBus) {
        COMPONENTS.register(modEventBus);
        MENUS.register(modEventBus);
    }

    static ResourceLocation id(final String path) {
        return ResourceLocation.fromNamespaceAndPath(RSTweaks.MODID, path);
    }
}
