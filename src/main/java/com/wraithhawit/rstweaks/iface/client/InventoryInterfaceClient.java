package com.wraithhawit.rstweaks.iface.client;

import com.wraithhawit.rstweaks.RSTweaks;
import com.wraithhawit.rstweaks.iface.InventoryInterfaceContent;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

/**
 * Binds the configuration menu to its screen.
 *
 * <p>A class of its own, annotated for {@link Dist#CLIENT}, rather than a branch inside the mod
 * constructor. A dedicated server never loads this class, so it never loads
 * {@link InventoryInterfaceScreen} and never touches the client-only Minecraft types that screen
 * extends. Referencing a screen from shared registration code is the specific mistake that takes a
 * dedicated server down at startup with a {@code NoClassDefFoundError} that names none of this.
 */
@EventBusSubscriber(modid = RSTweaks.MODID, value = Dist.CLIENT)
public final class InventoryInterfaceClient {
    private InventoryInterfaceClient() {
    }

    @SubscribeEvent
    public static void onRegisterMenuScreens(final RegisterMenuScreensEvent event) {
        event.register(InventoryInterfaceContent.MENU.get(), InventoryInterfaceScreen::new);
    }
}
