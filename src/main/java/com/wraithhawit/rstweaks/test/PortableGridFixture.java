package com.wraithhawit.rstweaks.test;

import com.refinedmods.refinedstorage.api.core.Action;
import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.api.storage.Actor;
import com.refinedmods.refinedstorage.api.storage.Storage;
import com.refinedmods.refinedstorage.common.api.RefinedStorageApi;
import com.refinedmods.refinedstorage.common.content.Items;
import com.refinedmods.refinedstorage.common.storage.DiskInventory;
import com.refinedmods.refinedstorage.common.storage.ItemStorageVariant;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import com.refinedmods.refinedstorage.common.util.ContainerUtil;

import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/**
 * A working Portable Grid, for tests that need a network they can reach without building one.
 *
 * <p>Shared by {@link InventoryInterfaceGameTest} and {@link BlockPickGameTest} because both need
 * exactly this and neither is about it. A Portable Grid rather than a wireless one for the same
 * reason in both: its storage is a disk in its own item data, with no network to join, no Wireless
 * Transmitter to be in range of and no binding, so standing one up is four calls instead of a
 * structure.
 */
final class PortableGridFixture {
    static final Actor ACTOR = () -> "rstweaks-gametest";

    private PortableGridFixture() {
    }

    /**
     * A creative Portable Grid holding a creative disk.
     *
     * <p>The disk is ticked once in the player's inventory first, because that is what gives it a
     * storage: Refined Storage assigns one in {@code AbstractStorageContainerItem.inventoryTick},
     * so a disk that has never been carried resolves to nothing. It is then written into the grid's
     * block-entity data under {@code "inv"}, which is the shape
     * {@code AbstractPortableGridBlockEntity.writeDiskInventory} produces and the one the feature
     * reads back.
     *
     * <p>Creative on both counts so that neither energy nor capacity is what a test is measuring.
     * The creative grid is also the one that needs {@code PortableGridBlockItemAccessor} to be
     * working — without it the grid reads as flat and every test here fails with an empty storage.
     */
    static ItemStack portableGrid(final GameTestHelper helper, final Player player) {
        final ServerLevel level = helper.getLevel();
        final ItemStack disk = new ItemStack(Items.INSTANCE.getItemStorageDisk(ItemStorageVariant.CREATIVE));
        disk.getItem().inventoryTick(disk, level, player, 0, false);

        final DiskInventory diskInventory = new DiskInventory((inventory, slot) -> {
        }, 1);
        diskInventory.setItem(0, disk);

        final CompoundTag tag = new CompoundTag();
        tag.put("inv", ContainerUtil.write(diskInventory, level.registryAccess()));

        final ItemStack grid = new ItemStack(Items.INSTANCE.getCreativePortableGrid());
        grid.set(DataComponents.BLOCK_ENTITY_DATA, CustomData.of(tag));
        return grid;
    }

    /** Reads the grid's disk the same way the feature does, so a test can see what it did. */
    static Storage storage(final GameTestHelper helper, final ItemStack grid) {
        final ServerLevel level = helper.getLevel();
        final CustomData blockEntityData = grid.get(DataComponents.BLOCK_ENTITY_DATA);
        if (blockEntityData == null) {
            throw new FixtureFailure("the Portable Grid lost its block entity data");
        }
        final DiskInventory diskInventory = new DiskInventory((inventory, slot) -> {
        }, 1);
        ContainerUtil.read(blockEntityData.copyTag().getCompound("inv"), diskInventory, level.registryAccess());
        diskInventory.setStorageRepository(RefinedStorageApi.INSTANCE.getStorageRepository(level));
        return diskInventory.resolve(0).orElseThrow(() -> new FixtureFailure(
            "the Portable Grid's disk resolved to no storage; Refined Storage never assigned it one"));
    }

    static void store(final GameTestHelper helper,
                      final ItemStack grid,
                      final ItemResource resource,
                      final long amount) {
        final long inserted = storage(helper, grid).insert(resource, amount, Action.EXECUTE, ACTOR);
        if (inserted != amount) {
            throw new FixtureFailure("could not seed the disk: inserted " + inserted + " of " + amount);
        }
    }

    static long countStored(final GameTestHelper helper,
                            final ItemStack grid,
                            final ItemResource resource) {
        long total = 0L;
        for (final ResourceAmount stored : storage(helper, grid).getAll()) {
            if (resource.equals(stored.resource())) {
                total += stored.amount();
            }
        }
        return total;
    }

    /** A failure the gametest framework will report as one. */
    static final class FixtureFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        FixtureFailure(final String message) {
            super(message);
        }
    }
}
