package com.wraithhawit.rstweaks.iface;

import java.util.List;
import java.util.Optional;

import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.common.api.RefinedStorageApi;
import com.refinedmods.refinedstorage.common.api.support.resource.ResourceContainer;
import com.refinedmods.refinedstorage.common.api.support.slotreference.SlotReference;
import com.refinedmods.refinedstorage.common.support.resource.ResourceContainerData;

import com.wraithhawit.rstweaks.Config;

import net.minecraft.ChatFormatting;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * How the configuration screen is reached: sneak, then right-click in the air.
 *
 * <p>That gesture is free on every supported grid, and free without stepping on anything:
 *
 * <ul>
 *   <li>Right-click a <em>block</em> while sneaking is already taken — a wireless grid binds
 *       itself to a network, a portable grid places itself. Both of those are
 *       {@code RightClickBlock}, a different event, so this listener never sees them.
 *   <li>Right-click in the air <em>without</em> sneaking opens the grid, which is what it should
 *       keep doing.
 *   <li>Right-click in the air <em>with</em> sneaking currently does the same thing as without on
 *       every one of these items. Nobody presses it on purpose.
 * </ul>
 *
 * <p>Doing it as a NeoForge event rather than a mixin is what makes the compatibility claim in the
 * issue true. {@code AbstractNetworkEnergyItem.use} would be the obvious mixin target and it would
 * have covered Refined Storage's own grids, but an addon is free to override {@code use} — Quartz
 * Arsenal and Universal Grid both do — and a mixin on the superclass would then silently not run
 * for exactly the two mods this was meant to support. The event fires ahead of {@code Item.use}
 * for every item there is, so there is nothing to override.
 */
public final class InventoryInterfaceOpener {
    private static final Component TITLE =
        Component.translatable("gui.rstweaks.inventory_interface");
    private static final Component TOOLTIP_HINT =
        Component.translatable("item.rstweaks.inventory_interface.hint");
    private static final Component TOOLTIP_INSERT =
        Component.translatable("item.rstweaks.inventory_interface.auto_insert");
    private static final Component TOOLTIP_EXPORT =
        Component.translatable("item.rstweaks.inventory_interface.auto_export");

    private InventoryInterfaceOpener() {
    }

    @SubscribeEvent
    public static void onRightClickItem(final PlayerInteractEvent.RightClickItem event) {
        if (!Config.inventoryInterface) {
            return;
        }
        final Player player = event.getEntity();
        if (!player.isCrouching() || !SupportedGrids.isSupported(event.getItemStack())) {
            return;
        }
        // Cancelled on both sides, so the client does not also run the item's own use() and open
        // the grid screen for a frame before the server's menu replaces it.
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        if (player instanceof ServerPlayer serverPlayer) {
            open(serverPlayer, event.getHand());
        }
    }

    /**
     * Says on the item that the feature exists, and what it is currently set to.
     *
     * <p>A gesture nobody can see is a feature nobody finds. The two state lines only appear once
     * something is switched on, so an untouched grid gains one grey line rather than three.
     */
    @SubscribeEvent
    public static void onTooltip(final ItemTooltipEvent event) {
        if (!Config.inventoryInterface || !SupportedGrids.isSupported(event.getItemStack())) {
            return;
        }
        final List<Component> lines = event.getToolTip();
        lines.add(TOOLTIP_HINT.copy().withStyle(ChatFormatting.DARK_GRAY));
        final InventoryInterfaceState state =
            event.getItemStack().get(InventoryInterfaceContent.STATE.get());
        if (state == null) {
            return;
        }
        if (state.insert()) {
            lines.add(TOOLTIP_INSERT.copy().withStyle(ChatFormatting.GRAY));
        }
        if (state.export()) {
            lines.add(TOOLTIP_EXPORT.copy().withStyle(ChatFormatting.GRAY));
        }
    }

    private static void open(final ServerPlayer player, final InteractionHand hand) {
        final SlotReference slotReference =
            RefinedStorageApi.INSTANCE.createInventorySlotReference(player, hand);
        final ItemStack stack = slotReference.resolve(player).orElse(null);
        if (stack == null) {
            return;
        }
        final InventoryInterfaceState state =
            stack.getOrDefault(InventoryInterfaceContent.STATE.get(), InventoryInterfaceState.EMPTY);
        final ResourceContainer filter = InventoryInterfaceMenu.createFilterContainer();
        final List<Optional<ResourceAmount>> configured = state.filter();
        for (int i = 0; i < filter.size() && i < configured.size(); ++i) {
            final int index = i;
            configured.get(i).ifPresent(resource -> filter.set(index, resource));
        }
        // Seeded first, then listened to: setting the listener before the seed would write the
        // configuration back nine times on open, each time from a container that is not yet
        // finished being filled in.
        filter.setListener(() -> stack.set(
            InventoryInterfaceContent.STATE.get(),
            stack.getOrDefault(InventoryInterfaceContent.STATE.get(), InventoryInterfaceState.EMPTY)
                .withFilter(filter)));
        final InventoryInterfaceData data = new InventoryInterfaceData(
            Optional.of(slotReference),
            state.insert(),
            state.export(),
            state.filterMode(),
            state.fuzzyMode(),
            ResourceContainerData.of(filter));
        player.openMenu(new Provider(stack, filter, slotReference),
            (RegistryFriendlyByteBuf buf) -> InventoryInterfaceData.STREAM_CODEC.encode(buf, data));
    }

    private record Provider(ItemStack stack, ResourceContainer filter, SlotReference slotReference)
        implements MenuProvider {

        @Override
        public Component getDisplayName() {
            return TITLE;
        }

        @Override
        public AbstractContainerMenu createMenu(final int syncId,
                                                final Inventory inventory,
                                                final Player player) {
            return new InventoryInterfaceMenu(syncId, player, stack, filter, slotReference);
        }
    }
}
