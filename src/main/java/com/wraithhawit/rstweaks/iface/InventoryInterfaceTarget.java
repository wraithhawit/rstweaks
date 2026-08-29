package com.wraithhawit.rstweaks.iface;

import java.util.Optional;

import com.refinedmods.refinedstorage.api.core.Action;
import com.refinedmods.refinedstorage.api.network.Network;
import com.refinedmods.refinedstorage.api.network.energy.EnergyNetworkComponent;
import com.refinedmods.refinedstorage.api.network.energy.EnergyStorage;
import com.refinedmods.refinedstorage.api.network.storage.StorageNetworkComponent;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.api.storage.Actor;
import com.refinedmods.refinedstorage.api.storage.Storage;
import com.refinedmods.refinedstorage.common.Platform;
import com.refinedmods.refinedstorage.common.api.RefinedStorageApi;
import com.refinedmods.refinedstorage.common.api.security.SecurityHelper;
import com.refinedmods.refinedstorage.common.api.support.network.item.NetworkItemContext;
import com.refinedmods.refinedstorage.common.api.support.slotreference.SlotReference;
import com.refinedmods.refinedstorage.common.security.BuiltinPermission;
import com.refinedmods.refinedstorage.common.storage.DiskInventory;
import com.refinedmods.refinedstorage.common.storage.portablegrid.PortableGridBlockItem;
import com.refinedmods.refinedstorage.common.storage.portablegrid.PortableGridType;
import com.refinedmods.refinedstorage.common.support.energy.CreativeEnergyStorage;
import com.refinedmods.refinedstorage.common.util.ContainerUtil;

import com.wraithhawit.rstweaks.mixin.PortableGridBlockItemAccessor;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;


/**
 * What an Inventory Interface actually moves items into and out of, and what that costs.
 *
 * <p>The two kinds of grid this runs on do not share a storage, an energy source, or a notion of
 * permission, and neither is reachable through the {@code Grid} interface without opening a menu —
 * {@code Grid.createOperations} wants a screen's worth of context and hands back operations
 * phrased in terms of a cursor. So each is resolved here through the narrowest public Refined
 * Storage API that answers the question:
 *
 * <ul>
 *   <li>A <b>wireless</b> grid resolves its network through
 *       {@code NetworkItemHelper.createContext}, which is the same call {@code WirelessGridItem.use}
 *       makes. Range, binding, dimension and the item's own energy are therefore all decided by
 *       Refined Storage rather than re-implemented here: an out-of-range grid resolves to nothing
 *       and this target is simply absent.
 *   <li>A <b>portable</b> grid has no network. Its storage is the disk in its own block-entity
 *       data, reached with {@link DiskInventory#resolve(int)} exactly as
 *       {@code PortableGridBlockItem.use} reaches it.
 * </ul>
 *
 * <p>Energy is drained per successful operation at Refined Storage's own configured rates, through
 * Refined Storage's own drain path, so a grid that runs dry doing this runs dry at the same rate
 * as one you emptied by hand. A transfer is never made free: if the fee cannot be paid the
 * operation does not happen.
 *
 * <p>Security is only a question for the wireless grids, since a portable grid's disk is in your
 * pocket. {@link BuiltinPermission#INSERT} and {@link BuiltinPermission#EXTRACT} are checked
 * against the network the same way Refined Storage checks them for a grid you opened — a player
 * who may not take from a network may not have this take from it either.
 */
abstract class InventoryInterfaceTarget {
    /**
     * Resolves the storage behind a grid stack, or empty when it cannot serve right now — no
     * network in range, no disk, no energy. Absent is the ordinary case rather than an error: a
     * wireless grid spends most of its life out of range of its transmitter.
     */
    static Optional<InventoryInterfaceTarget> of(final ServerPlayer player,
                                                 final ItemStack stack,
                                                 final SlotReference slotReference) {
        if (stack.getItem() instanceof PortableGridBlockItem) {
            return portable(player, stack);
        }
        return wireless(player, stack, slotReference);
    }

    /**
     * The first grid the player is carrying that can actually serve, in inventory order.
     *
     * <p>For block pick, which — unlike the ticker — is not asking a particular configured grid to
     * do something, but asking whether the player has <em>any</em> way to reach a network at all.
     * First-that-resolves rather than a preference order: a player carrying two grids has them
     * bound to networks that both work or one that does not, and picking between two working ones
     * is a distinction without a difference.
     */
    static Optional<InventoryInterfaceTarget> firstCarried(final ServerPlayer player) {
        for (final SlotReference slotReference : SupportedGrids.carriedBy(player)) {
            final ItemStack stack = slotReference.resolve(player).orElse(null);
            if (stack == null) {
                continue;
            }
            final Optional<InventoryInterfaceTarget> target = of(player, stack, slotReference);
            if (target.isPresent()) {
                return target;
            }
        }
        return Optional.empty();
    }

    abstract boolean mayInsert(ServerPlayer player);

    abstract boolean mayExtract(ServerPlayer player);

    /** Returns how much was actually stored, after charging for it. */
    abstract long insert(ResourceKey resource, long amount, Actor actor);

    /** Returns how much was actually taken, after charging for it. */
    abstract long extract(ResourceKey resource, long amount, Actor actor);

    private static Optional<InventoryInterfaceTarget> wireless(final ServerPlayer player,
                                                               final ItemStack stack,
                                                               final SlotReference slotReference) {
        final NetworkItemContext context = RefinedStorageApi.INSTANCE
            .getNetworkItemHelper()
            .createContext(stack, player, slotReference);
        if (!context.isActive()) {
            return Optional.empty();
        }
        return context.resolveNetwork()
            .filter(InventoryInterfaceTarget::hasEnergy)
            .map(network -> new WirelessTarget(context, network));
    }

    private static boolean hasEnergy(final Network network) {
        return !RefinedStorageApi.INSTANCE.isEnergyRequired()
            || network.getComponent(EnergyNetworkComponent.class).getStored() > 0L;
    }

    private static Optional<InventoryInterfaceTarget> portable(final ServerPlayer player,
                                                               final ItemStack stack) {
        final CustomData blockEntityData = stack.get(DataComponents.BLOCK_ENTITY_DATA);
        if (blockEntityData == null) {
            return Optional.empty();
        }
        final CompoundTag tag = blockEntityData.copyTag();
        if (!tag.contains("inv")) {
            return Optional.empty();
        }
        final DiskInventory diskInventory = new DiskInventory((inventory, slot) -> {
        }, 1);
        ContainerUtil.read(tag.getCompound("inv"), diskInventory, player.registryAccess());
        diskInventory.setStorageRepository(RefinedStorageApi.INSTANCE.getStorageRepository(player.serverLevel()));
        return diskInventory.resolve(0).map(storage -> new PortableTarget(storage, energyStorageOf(stack)));
    }

    /**
     * Mirrors {@code PortableGridBlockItem.createEnergyStorageInternal}, which is private: the
     * creative variant runs on {@link CreativeEnergyStorage} rather than on the number written
     * into the stack, which for a creative grid is zero and would otherwise read as flat.
     */
    private static EnergyStorage energyStorageOf(final ItemStack stack) {
        final boolean creative = stack.getItem() instanceof PortableGridBlockItemAccessor accessor
            && accessor.rstweaks$getType() == PortableGridType.CREATIVE;
        return creative ? CreativeEnergyStorage.INSTANCE : PortableGridBlockItem.createEnergyStorage(stack);
    }

    private static final class WirelessTarget extends InventoryInterfaceTarget {
        private final NetworkItemContext context;
        private final Network network;
        private final Storage storage;

        private WirelessTarget(final NetworkItemContext context, final Network network) {
            this.context = context;
            this.network = network;
            this.storage = network.getComponent(StorageNetworkComponent.class);
        }

        @Override
        boolean mayInsert(final ServerPlayer player) {
            return SecurityHelper.isAllowed(player, BuiltinPermission.INSERT, network);
        }

        @Override
        boolean mayExtract(final ServerPlayer player) {
            return SecurityHelper.isAllowed(player, BuiltinPermission.EXTRACT, network);
        }

        @Override
        long insert(final ResourceKey resource, final long amount, final Actor actor) {
            final long inserted = storage.insert(resource, amount, Action.EXECUTE, actor);
            if (inserted > 0L) {
                context.drainEnergy(Platform.INSTANCE.getConfig().getWirelessGrid().getInsertEnergyUsage());
            }
            return inserted;
        }

        @Override
        long extract(final ResourceKey resource, final long amount, final Actor actor) {
            final long extracted = storage.extract(resource, amount, Action.EXECUTE, actor);
            if (extracted > 0L) {
                context.drainEnergy(Platform.INSTANCE.getConfig().getWirelessGrid().getExtractEnergyUsage());
            }
            return extracted;
        }
    }

    private static final class PortableTarget extends InventoryInterfaceTarget {
        private final Storage storage;
        private final EnergyStorage energyStorage;

        private PortableTarget(final Storage storage, final EnergyStorage energyStorage) {
            this.storage = storage;
            this.energyStorage = energyStorage;
        }

        @Override
        boolean mayInsert(final ServerPlayer player) {
            return true;
        }

        @Override
        boolean mayExtract(final ServerPlayer player) {
            return true;
        }

        @Override
        long insert(final ResourceKey resource, final long amount, final Actor actor) {
            if (!charge(Platform.INSTANCE.getConfig().getPortableGrid().getInsertEnergyUsage())) {
                return 0L;
            }
            return storage.insert(resource, amount, Action.EXECUTE, actor);
        }

        @Override
        long extract(final ResourceKey resource, final long amount, final Actor actor) {
            if (!charge(Platform.INSTANCE.getConfig().getPortableGrid().getExtractEnergyUsage())) {
                return 0L;
            }
            return storage.extract(resource, amount, Action.EXECUTE, actor);
        }

        /**
         * Charged before the transfer rather than after it, unlike the wireless path.
         *
         * <p>The wireless one can drain afterwards because {@code NetworkItemContext.drainEnergy}
         * takes what it can and a network that comes up short simply goes inactive on the next
         * resolve. A portable grid's energy is a fixed buffer in the stack, and a fee it cannot
         * pay has to stop the transfer rather than be waived — so the fee is taken first and the
         * transfer is abandoned if it bounces. Nothing has moved at that point.
         */
        private boolean charge(final long usage) {
            if (usage <= 0L || !RefinedStorageApi.INSTANCE.isEnergyRequired()) {
                return true;
            }
            return energyStorage.extract(usage, Action.EXECUTE) >= usage;
        }
    }
}
