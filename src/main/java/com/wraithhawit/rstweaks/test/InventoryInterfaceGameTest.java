package com.wraithhawit.rstweaks.test;

import java.util.List;
import java.util.Optional;

import com.refinedmods.refinedstorage.api.core.Action;
import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.api.resource.filter.FilterMode;
import com.refinedmods.refinedstorage.api.storage.Actor;
import com.refinedmods.refinedstorage.api.storage.Storage;
import com.refinedmods.refinedstorage.common.api.RefinedStorageApi;
import com.refinedmods.refinedstorage.common.content.Items;
import com.refinedmods.refinedstorage.common.storage.DiskInventory;
import com.refinedmods.refinedstorage.common.storage.ItemStorageVariant;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import com.refinedmods.refinedstorage.common.util.ContainerUtil;

import com.wraithhawit.rstweaks.Config;
import com.wraithhawit.rstweaks.RSTweaks;
import com.wraithhawit.rstweaks.iface.InventoryInterfaceContent;
import com.wraithhawit.rstweaks.iface.InventoryInterfaceState;

import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The Inventory Interface, driven by the real player tick against a real storage.
 *
 * <p>A <b>Portable Grid</b> rather than a wireless one, on purpose. Both go through
 * {@code InventoryInterfaceTicker} and differ only in how the storage on the other end is
 * resolved, and the portable one is the half that can be stood up honestly in a gametest: its
 * storage is a disk in its own item data, with no network to join, no Wireless Transmitter to be
 * in range of, and no binding. A wireless test would spend all its code on scaffolding and then
 * assert the same four things about the same shared code.
 *
 * <p>It is also the half with more of our own code behind it — the disk is read back out of
 * block-entity data by hand, and whether the grid is creative is answered by
 * {@code PortableGridBlockItemAccessor}, a mixin that nothing else loads. A creative Portable Grid
 * that read as flat would make every one of these tests fail with an empty storage and no
 * explanation, which is exactly the failure that accessor exists to prevent.
 *
 * <p>Nothing here calls into the feature directly. Each test sets up an inventory, puts a real
 * {@link PlayerTickEvent.Post} on the real event bus, and then looks at what happened — so a
 * listener that was never registered fails these rather than passing them.
 *
 * <p>Posting rather than waiting, because a gametest's mock player is placed but never ticked: the
 * server reports zero players for the whole run, and the first version of this file waited for a
 * tick that never came. A test that waits for something that cannot happen does not fail loudly;
 * it reports that the feature did nothing, which is indistinguishable from the feature being
 * broken. What is given up by posting is only whether Minecraft fires this event for real players,
 * which is NeoForge's guarantee rather than ours.
 */
@GameTestHolder(RSTweaks.MODID)
@PrefixGameTestTemplate(false)
public final class InventoryInterfaceGameTest {
    /** More than one, so a rule that only holds on the first pass is caught. */
    private static final int PASSES = 3;

    /** Out of the hotbar, so the default "leave the hotbar alone" rule is not what is under test. */
    private static final int CARGO_SLOT = 9;
    private static final int SECOND_CARGO_SLOT = 11;
    private static final int GRID_SLOT = 10;
    private static final int OTHER_GRID_SLOT = 12;

    private static final Actor ACTOR = () -> "rstweaks-inventory-interface-test";

    private InventoryInterfaceGameTest() {
    }

    /**
     * ALLOW keeps the filter's amount and files away the rest. This is the whole feature in one
     * assertion: the amount on a filter slot is "how many to keep on you", not "how many to move".
     */
    @GameTest(template = "empty", timeoutTicks = 400)
    public static void autoInsertKeepsTheFilterAmountAndFilesAwayTheSurplus(final GameTestHelper helper) {
        run(helper, (player, grid) -> {
            player.getInventory().setItem(CARGO_SLOT, new ItemStack(net.minecraft.world.item.Items.IRON_INGOT, 64));
            configure(grid, state -> new InventoryInterfaceState(
                true, false, FilterMode.ALLOW, false, filter(iron(), 16L)));
        }, (helper2, player, grid) -> {
            expect("iron kept on the player", countCarried(player, iron()), 16L);
            expect("iron filed away", countStored(helper2, grid, iron()), 48L);
        });
    }

    /**
     * BLOCK is the other way round: the list is what you keep, and everything else goes. The
     * amount is deliberately not consulted here — a listed resource is yours whatever it says,
     * which is what makes "do not put these away, and keep me stocked with them" expressible.
     */
    @GameTest(template = "empty", timeoutTicks = 400)
    public static void blockModeProtectsTheListAndFilesEverythingElse(final GameTestHelper helper) {
        run(helper, (player, grid) -> {
            player.getInventory().setItem(CARGO_SLOT, new ItemStack(net.minecraft.world.item.Items.IRON_INGOT, 64));
            player.getInventory().setItem(SECOND_CARGO_SLOT, new ItemStack(net.minecraft.world.item.Items.GOLD_INGOT, 32));
            configure(grid, state -> new InventoryInterfaceState(
                true, false, FilterMode.BLOCK, false, filter(iron(), 16L)));
        }, (helper2, player, grid) -> {
            expect("listed iron untouched", countCarried(player, iron()), 64L);
            expect("iron not in storage", countStored(helper2, grid, iron()), 0L);
            expect("unlisted gold taken", countCarried(player, gold()), 0L);
            expect("unlisted gold filed away", countStored(helper2, grid, gold()), 32L);
        });
    }

    /**
     * "Keep 16" means sixteen in the whole inventory, not sixteen outside the hotbar.
     *
     * <p>Auto-insert will not take from the hotbar by default, but what is sitting there still
     * counts against the budget. Otherwise the two halves of the feature disagree about the same
     * number: insert would leave sixteen in the bag on top of ten in the hotbar, and export — which
     * counts everything — would see twenty-six and consider the job done at an amount nobody asked
     * for.
     */
    @GameTest(template = "empty", timeoutTicks = 400)
    public static void theKeepBudgetCountsTheHotbarItWillNotTakeFrom(final GameTestHelper helper) {
        run(helper, (player, grid) -> {
            player.getInventory().selected = 0;
            player.getInventory().setItem(1, new ItemStack(net.minecraft.world.item.Items.IRON_INGOT, 10));
            player.getInventory().setItem(CARGO_SLOT, new ItemStack(net.minecraft.world.item.Items.IRON_INGOT, 64));
            configure(grid, state -> new InventoryInterfaceState(
                true, false, FilterMode.ALLOW, false, filter(iron(), 16L)));
        }, (helper2, player, grid) -> {
            expect("iron kept across the whole inventory", countCarried(player, iron()), 16L);
            expect("hotbar iron left where it was",
                player.getInventory().getItem(1).getCount(), 10L);
            expect("the rest filed away", countStored(helper2, grid, iron()), 58L);
        });
    }

    /** Export tops the player back up to the filter's amount, and stops there. */
    @GameTest(template = "empty", timeoutTicks = 400)
    public static void autoExportTopsUpToTheFilterAmount(final GameTestHelper helper) {
        run(helper, (player, grid) -> {
            store(helper, grid, iron(), 64L);
            configure(grid, state -> new InventoryInterfaceState(
                false, true, FilterMode.ALLOW, false, filter(iron(), 40L)));
        }, (helper2, player, grid) -> {
            expect("iron handed to the player", countCarried(player, iron()), 40L);
            expect("iron left in storage", countStored(helper2, grid, iron()), 24L);
        });
    }

    /**
     * The safety rails, with the hotbar rule switched off so they are the only thing left holding.
     *
     * <p>An empty BLOCK filter means "file away everything", which is the most destructive setting
     * this feature has. What survives it is the whole list of things auto-insert refuses to take:
     * the item in your hand, the grid doing the filing, and any other grid you are carrying.
     */
    @GameTest(template = "empty", timeoutTicks = 400)
    public static void autoInsertRefusesTheHeldItemAndAnyGrid(final GameTestHelper helper) {
        final boolean originalHotbar = Config.inventoryInterfaceInsertFromHotbar;
        Config.inventoryInterfaceInsertFromHotbar = true;
        try {
            run(helper, (player, grid) -> {
                player.getInventory().selected = 0;
                player.getInventory().setItem(0, new ItemStack(net.minecraft.world.item.Items.DIAMOND, 5));
                player.getInventory().setItem(CARGO_SLOT, new ItemStack(net.minecraft.world.item.Items.COBBLESTONE, 16));
                player.getInventory().setItem(OTHER_GRID_SLOT, new ItemStack(Items.INSTANCE.getWirelessGrid()));
                configure(grid, state -> new InventoryInterfaceState(
                    true, false, FilterMode.BLOCK, false, InventoryInterfaceState.EMPTY.filter()));
            }, (helper2, player, grid) -> {
                expect("unprotected cobblestone filed away",
                    countStored(helper2, grid, new ItemResource(net.minecraft.world.item.Items.COBBLESTONE)), 16L);
                expect("held diamonds untouched",
                    countCarried(player, new ItemResource(net.minecraft.world.item.Items.DIAMOND)), 5L);
                if (!player.getInventory().getItem(GRID_SLOT).is(Items.INSTANCE.getCreativePortableGrid())) {
                    throw new GameTestAssertionFailure("the grid filed itself away");
                }
                if (!player.getInventory().getItem(OTHER_GRID_SLOT).is(Items.INSTANCE.getWirelessGrid())) {
                    throw new GameTestAssertionFailure("a second grid was filed away, which is a walk home");
                }
            });
        } finally {
            Config.inventoryInterfaceInsertFromHotbar = originalHotbar;
        }
    }

    // ------------------------------------------------------------------ harness

    @FunctionalInterface
    private interface Setup {
        void apply(ServerPlayer player, ItemStack grid);
    }

    @FunctionalInterface
    private interface Check {
        void verify(GameTestHelper helper, ServerPlayer player, ItemStack grid);
    }

    /**
     * Builds a player carrying a Portable Grid with a working disk, applies the setup, waits for
     * the real tick to do its work, and checks.
     *
     * <p>The interval is forced to one tick for the duration. At the shipped default of twenty a
     * test would either wait long enough to be slow or land in the gap and read as a feature that
     * does nothing — and "does nothing" is the exact failure these tests are meant to detect.
     */
    private static void run(final GameTestHelper helper, final Setup setup, final Check check) {
        final int originalInterval = Config.inventoryInterfaceIntervalTicks;
        final boolean originalEnabled = Config.inventoryInterface;
        // An interval of one means every posted event is a pass, whatever the player's tick count
        // and entity id happen to be. At the shipped default of twenty, the stagger would decide
        // whether a test passed.
        Config.inventoryInterfaceIntervalTicks = 1;
        Config.inventoryInterface = true;
        try {
            final ServerPlayer player = (ServerPlayer) helper.makeMockServerPlayerInLevel();
            player.getInventory().clearContent();

            final ItemStack grid = portableGrid(helper, player);
            player.getInventory().setItem(GRID_SLOT, grid);
            setup.apply(player, grid);

            for (int pass = 0; pass < PASSES; ++pass) {
                NeoForge.EVENT_BUS.post(new PlayerTickEvent.Post(player));
            }

            check.verify(helper, player, player.getInventory().getItem(GRID_SLOT));
            helper.succeed();
        } finally {
            Config.inventoryInterfaceIntervalTicks = originalInterval;
            Config.inventoryInterface = originalEnabled;
        }
    }

    /**
     * A creative Portable Grid holding a creative disk.
     *
     * <p>The disk is ticked once in the player's inventory first, because that is what gives it a
     * storage: Refined Storage assigns one in {@code AbstractStorageContainerItem.inventoryTick},
     * so a disk that has never been carried resolves to nothing. Then it is written into the
     * grid's block-entity data under {@code "inv"}, which is the same shape
     * {@code AbstractPortableGridBlockEntity.writeDiskInventory} produces and the one the feature
     * reads back.
     */
    private static ItemStack portableGrid(final GameTestHelper helper, final Player player) {
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

    private static void configure(final ItemStack grid,
                                  final java.util.function.UnaryOperator<InventoryInterfaceState> change) {
        grid.set(InventoryInterfaceContent.STATE.get(), change.apply(InventoryInterfaceState.EMPTY));
    }

    private static List<Optional<ResourceAmount>> filter(final ItemResource resource, final long amount) {
        final List<Optional<ResourceAmount>> entries =
            new java.util.ArrayList<>(InventoryInterfaceState.EMPTY.filter());
        entries.set(0, Optional.of(new ResourceAmount(resource, amount)));
        return entries;
    }

    private static ItemResource iron() {
        return new ItemResource(net.minecraft.world.item.Items.IRON_INGOT);
    }

    private static ItemResource gold() {
        return new ItemResource(net.minecraft.world.item.Items.GOLD_INGOT);
    }

    private static void store(final GameTestHelper helper,
                              final ItemStack grid,
                              final ItemResource resource,
                              final long amount) {
        final long inserted = storage(helper, grid).insert(resource, amount, Action.EXECUTE, ACTOR);
        if (inserted != amount) {
            throw new GameTestAssertionFailure(
                "could not seed the disk: inserted " + inserted + " of " + amount);
        }
    }

    private static long countCarried(final Player player, final ItemResource resource) {
        long total = 0L;
        for (int slot = 0; slot < player.getInventory().items.size(); ++slot) {
            final ItemStack stack = player.getInventory().getItem(slot);
            if (!stack.isEmpty() && ItemResource.ofItemStack(stack).equals(resource)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static long countStored(final GameTestHelper helper,
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

    /** Reads the grid's disk the same way the feature does, so the test can see what it did. */
    private static Storage storage(final GameTestHelper helper, final ItemStack grid) {
        final ServerLevel level = helper.getLevel();
        final CustomData blockEntityData = grid.get(DataComponents.BLOCK_ENTITY_DATA);
        if (blockEntityData == null) {
            throw new GameTestAssertionFailure("the Portable Grid lost its block entity data");
        }
        final DiskInventory diskInventory = new DiskInventory((inventory, slot) -> {
        }, 1);
        ContainerUtil.read(blockEntityData.copyTag().getCompound("inv"), diskInventory, level.registryAccess());
        diskInventory.setStorageRepository(RefinedStorageApi.INSTANCE.getStorageRepository(level));
        return diskInventory.resolve(0).orElseThrow(() -> new GameTestAssertionFailure(
            "the Portable Grid's disk resolved to no storage; Refined Storage never assigned it one"));
    }

    private static void expect(final String what, final long actual, final long expected) {
        if (actual != expected) {
            throw new GameTestAssertionFailure(what + ": expected " + expected + ", got " + actual);
        }
    }

    /** A failure the gametest framework will report as one. */
    private static final class GameTestAssertionFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        GameTestAssertionFailure(final String message) {
            super(message);
        }
    }
}
