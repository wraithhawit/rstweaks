package com.wraithhawit.rstweaks.test;

import com.refinedmods.refinedstorage.api.core.Action;
import com.refinedmods.refinedstorage.api.network.Network;
import com.refinedmods.refinedstorage.api.network.storage.StorageNetworkComponent;
import com.refinedmods.refinedstorage.api.storage.Actor;
import com.refinedmods.refinedstorage.common.content.Blocks;
import com.refinedmods.refinedstorage.common.grid.CraftingGridBlockEntity;
import com.refinedmods.refinedstorage.common.storage.FluidStorageVariant;
import com.refinedmods.refinedstorage.common.storage.ItemStorageVariant;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import com.wraithhawit.rstweaks.Config;
import com.wraithhawit.rstweaks.RSTweaks;
import com.wraithhawit.rstweaks.storage.FluidContainers;
import com.wraithhawit.rstweaks.storage.GridNetworkAccess;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;

import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The Crafting Grid refill, against a real network built out of real blocks.
 *
 * <p>Craft a cake and the three milk buckets come back empty; this feature leaves them
 * full and charges the network instead. There are two ways to pay and one way to decline,
 * and all three have to be checked together, because two of the three failures this
 * feature has had were <em>silent</em>: it did the right thing when it ran and simply
 * stopped running. In 0.2.79 it was gated on a pattern that only existed because our own
 * fluid substitution registered it; parking that feature switched this one off and
 * nothing said so for a version.
 *
 * <p>That is why this test builds the network rather than faking it. A stub network would
 * keep passing through exactly the kind of change that killed it — the gates that failed
 * were about reaching the network, not about the arithmetic once it was reached. So a
 * creative controller, a storage block and a Crafting Grid go in the world, and the
 * network is whatever {@code CraftingGridBlockEntityMixin} hands back.
 *
 * <p>The slot is taken out of a real menu for the same reason. It is package-private, and
 * getting an instance any other way would mean building one that is not the one the game
 * uses. See {@link CraftingGridResultSlotAccess}.
 */
@GameTestHolder(RSTweaks.MODID)
@PrefixGameTestTemplate(false)
public final class CraftingGridRefillGameTest {
    private static final BlockPos CONTROLLER = new BlockPos(0, 1, 0);
    private static final BlockPos ITEM_STORAGE = new BlockPos(1, 1, 0);
    private static final BlockPos FLUID_STORAGE = new BlockPos(2, 1, 0);
    private static final BlockPos GRID = new BlockPos(3, 1, 0);

    /** Network nodes join on their first ticks, so nothing below is true immediately. */
    private static final int SETTLE_TICKS = 10;

    private static final Actor ACTOR = () -> "rstweaks-grid-test";

    private CraftingGridRefillGameTest() {
    }

    /**
     * A full container in storage is traded for the empty one the craft handed back.
     * Nothing is created: the network gives up a water bucket and takes an empty one.
     */
    @GameTest(template = "network", timeoutTicks = 400)
    public static void paysWithAStoredContainer(final GameTestHelper helper) {
        run(helper, (storage, contents) ->
            storage.insert(filled(), 1L, Action.EXECUTE, ACTOR), (helper2, storage, before) -> {
                expectSlotStillHolds(helper2, storage, Items.WATER_BUCKET);
                expect(helper2, "stored water buckets", storage.get(filled()), 0L);
                expect(helper2, "empty buckets handed back", storage.get(emptied()), 1L);
                expect(helper2, "fluid spent", before - fluidStored(storage), 0L);
            });
    }

    /**
     * No full container to trade, but enough fluid: the bucket the player is holding is
     * filled out of the tank instead.
     */
    @GameTest(template = "network", timeoutTicks = 400)
    public static void paysWithFluidWhenNoContainerIsStored(final GameTestHelper helper) {
        run(helper, (storage, contents) ->
            storage.insert(contents.fluid(), contents.amount(), Action.EXECUTE, ACTOR),
            (helper2, storage, before) -> {
                expectSlotStillHolds(helper2, storage, Items.WATER_BUCKET);
                expect(helper2, "fluid spent", before - fluidStored(storage), bucketAmount());
                expect(helper2, "empty buckets handed back", storage.get(emptied()), 0L);
            });
    }

    /**
     * The network can pay neither way, so Refined Storage's own behaviour has to stand:
     * the empty bucket goes back in the slot and nothing is charged.
     *
     * <p>This is the assertion that catches the feature dying quietly. The other two
     * would keep passing if the refill started declining <em>everything</em> — only by
     * pinning down what "declined" looks like can the pair of them mean anything.
     */
    @GameTest(template = "network", timeoutTicks = 400)
    public static void leavesTheEmptyContainerWhenTheNetworkCannotPay(final GameTestHelper helper) {
        run(helper, (storage, contents) -> {
            // Deliberately just short. Nothing is a weaker fixture than not quite enough:
            // a refill that failed to check the amount would still pass with an empty
            // network, because there is nothing there to take.
            storage.insert(contents.fluid(), contents.amount() - 1L, Action.EXECUTE, ACTOR);
        }, (helper2, storage, before) -> {
            expectSlotStillHolds(helper2, storage, Items.BUCKET);
            expect(helper2, "fluid spent", before - fluidStored(storage), 0L);
            expect(helper2, "empty buckets handed back", storage.get(emptied()), 0L);
        });
    }

    // ------------------------------------------------------------------ harness

    @FunctionalInterface
    private interface Stock {
        void fill(StorageNetworkComponent storage, FluidContainers.Contents contents);
    }

    @FunctionalInterface
    private interface Check {
        /** @param fluidBefore what the network held in fluid before the refill ran. */
        void verify(GameTestHelper helper, StorageNetworkComponent storage, long fluidBefore);
    }

    private static void run(final GameTestHelper helper, final Stock stock, final Check check) {
        helper.setBlock(CONTROLLER, Blocks.INSTANCE.getCreativeController().getDefault());
        helper.setBlock(ITEM_STORAGE, Blocks.INSTANCE.getItemStorageBlock(ItemStorageVariant.ONE_K));
        helper.setBlock(FLUID_STORAGE,
            Blocks.INSTANCE.getFluidStorageBlock(FluidStorageVariant.SIXTY_FOUR_B));
        helper.setBlock(GRID, Blocks.INSTANCE.getCraftingGrid().getDefault());

        helper.runAfterDelay(SETTLE_TICKS, () -> {
            final boolean original = Config.refillContainersInCraftingGrid;
            Config.refillContainersInCraftingGrid = true;
            try {
                doRun(helper, stock, check);
            } finally {
                Config.refillContainersInCraftingGrid = original;
            }
        });
    }

    private static void doRun(final GameTestHelper helper, final Stock stock, final Check check) {
        final CraftingGridBlockEntity grid = grid(helper);
        final StorageNetworkComponent storage = storage(helper, grid);
        final FluidContainers.Contents contents = contents();

        stock.fill(storage, contents);

        // The craft has already happened by the time this path runs: the ingredient is
        // still in the matrix and Refined Storage is about to swap it for the empty
        // container it produced.
        grid.getCraftingMatrix().setItem(0, new ItemStack(Items.WATER_BUCKET));

        final long fluidBefore = fluidStored(storage);
        // A real ServerPlayer rather than the lighter mock: the menu is built server-side
        // for a player with an inventory, and PlayerActor names one too.
        final Player player = helper.makeMockServerPlayerInLevel();
        resultSlot(helper, grid, player)
            .rstweaks$useIngredientWithRemainingItem(player, 0, new ItemStack(Items.BUCKET));

        check.verify(helper, storage, fluidBefore);
        helper.succeed();
    }

    private static CraftingGridBlockEntity grid(final GameTestHelper helper) {
        final BlockEntity blockEntity = helper.getBlockEntity(GRID);
        if (blockEntity instanceof CraftingGridBlockEntity grid) {
            return grid;
        }
        throw new GameTestAssertionFailure("no Crafting Grid block entity; the block did "
            + "not place, or Refined Storage's registration has changed");
    }

    private static StorageNetworkComponent storage(final GameTestHelper helper,
                                                   final CraftingGridBlockEntity grid) {
        if (!(grid instanceof GridNetworkAccess access)) {
            throw new GameTestAssertionFailure("CraftingGridBlockEntityMixin did not apply, "
                + "so the result slot can never find a network to charge");
        }
        final Network network = access.rstweaks$network();
        if (network == null) {
            throw new GameTestAssertionFailure("the Crafting Grid never joined a network "
                + "within " + SETTLE_TICKS + " ticks");
        }
        return network.getComponent(StorageNetworkComponent.class);
    }

    /**
     * The result slot out of a real menu.
     *
     * <p>Scanning for the interface rather than the class name: the interface is only on
     * this slot because our mixin put it there, so finding it proves the mixin applied,
     * and failing to find it says so in one sentence instead of a
     * {@code ClassCastException} somewhere else.
     */
    private static CraftingGridResultSlotAccess resultSlot(final GameTestHelper helper,
                                                           final CraftingGridBlockEntity grid,
                                                           final Player player) {
        final AbstractContainerMenu menu = grid.createMenu(0, player.getInventory(), player);
        try {
            for (final Slot slot : menu.slots) {
                if (slot instanceof CraftingGridResultSlotAccess access) {
                    return access;
                }
            }
        } finally {
            // Closed immediately, and this is not tidiness. An open grid menu registers
            // as a watcher on the network, so the first storage change pushes a
            // grid_update packet at the player -- and a gametest's mock player has no
            // connection that will take one. NeoForge throws, our mixin's own guard
            // catches it and declines the refill, and the test reports the feature broken
            // when the only thing broken was the audience. The slot keeps its reference
            // to the block entity, which is all it needs.
            menu.removed(player);
        }
        throw new GameTestAssertionFailure("no result slot in the Crafting Grid menu carries "
            + "CraftingGridResultSlotAccess, so CraftingGridResultSlotTestMixin -- and "
            + "therefore very likely the refill mixin beside it -- did not apply");
    }

    // ------------------------------------------------------------- expectations

    private static void expectSlotStillHolds(final GameTestHelper helper,
                                             final StorageNetworkComponent storage,
                                             final net.minecraft.world.item.Item expected) {
        final ItemStack inSlot = grid(helper).getCraftingMatrix().getItem(0);
        if (!inSlot.is(expected)) {
            throw new GameTestAssertionFailure("the matrix slot holds " + inSlot
                + ", expected " + expected);
        }
    }

    private static void expect(final GameTestHelper helper,
                               final String what,
                               final long actual,
                               final long expected) {
        if (actual != expected) {
            throw new GameTestAssertionFailure(what + " was " + actual + ", expected " + expected);
        }
    }

    private static ItemResource filled() {
        return ItemResource.ofItemStack(new ItemStack(Items.WATER_BUCKET));
    }

    private static ItemResource emptied() {
        return ItemResource.ofItemStack(new ItemStack(Items.BUCKET));
    }

    /**
     * What a water bucket holds, asked of the bucket rather than assumed.
     *
     * <p>The same call the feature makes, so the fixture cannot disagree with it about
     * which fluid resource or how much — a mismatch there would look like the refill
     * failing when it was the test stocking the wrong thing.
     */
    private static FluidContainers.Contents contents() {
        final FluidContainers.Contents contents = FluidContainers.of(filled());
        if (contents == null) {
            throw new GameTestAssertionFailure("a water bucket does not report any fluid; "
                + "FluidContainers can no longer read a vanilla container");
        }
        return contents;
    }

    private static long bucketAmount() {
        return contents().amount();
    }

    private static long fluidStored(final StorageNetworkComponent storage) {
        return storage.get(contents().fluid());
    }

    /** A failure the gametest framework will report as one. */
    private static final class GameTestAssertionFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        GameTestAssertionFailure(final String message) {
            super(message);
        }
    }
}
