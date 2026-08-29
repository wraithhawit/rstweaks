package com.wraithhawit.rstweaks.test;

import com.refinedmods.refinedstorage.api.core.Action;
import com.refinedmods.refinedstorage.api.network.Network;
import com.refinedmods.refinedstorage.api.network.storage.StorageNetworkComponent;
import com.refinedmods.refinedstorage.api.storage.Actor;
import com.refinedmods.refinedstorage.common.content.Blocks;
import com.refinedmods.refinedstorage.common.grid.CraftingGridBlockEntity;
import com.refinedmods.refinedstorage.common.support.resource.FluidResource;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import com.wraithhawit.rstweaks.Config;
import com.wraithhawit.rstweaks.RSTweaks;
import com.wraithhawit.rstweaks.Stats;
import com.wraithhawit.rstweaks.storage.GridNetworkAccess;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.block.entity.BlockEntity;

import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The external storage <em>provider</em> layer, against a real chest on a real network.
 *
 * <p>Written because a mixin-liveness audit found this layer had no coverage at all.
 * {@link ExtractionSelfTest} covers {@code ItemHandlerExtractableStorage}, which is one level
 * below; the three provider mixins -- {@code CompositeExternalStorageProviderMixin} and the two
 * that declare what an item or fluid handler can serve -- were never loaded by any test, because
 * nothing ever built an External Storage block. The composite one is a {@code @Redirect} on both
 * {@code extract} and {@code insert}, and this is the same layer where a stale index quietly
 * destroyed an in-progress extraction in every build up to 0.2.55. Untested was not a good place
 * for it to be.
 *
 * <p>A plain vanilla chest is enough to exercise the skip. Refined Storage builds the composite
 * over <em>every</em> registered provider factory regardless of what the target block actually
 * offers, so a chest yields an item provider and a fluid provider side by side -- precisely the
 * case the optimization exists for.
 *
 * <p>The assertion that matters is {@code skippingProvidersChangesNothingButTheCallCount}:
 * turning the optimization off must produce the identical result. An optimization that changes
 * an answer is not an optimization.
 */
@GameTestHolder(RSTweaks.MODID)
@PrefixGameTestTemplate(false)
public final class ExternalStorageProviderGameTest {
    private static final BlockPos CONTROLLER = new BlockPos(0, 1, 0);
    private static final BlockPos GRID = new BlockPos(1, 1, 0);
    private static final BlockPos EXTERNAL = new BlockPos(2, 1, 0);
    private static final BlockPos CHEST = new BlockPos(3, 1, 0);

    /** The external storage scans on its own schedule, so nothing here is true immediately. */
    private static final int SETTLE_TICKS = 30;

    private static final int STOCKED = 64;

    private static final Actor ACTOR = () -> "rstweaks-external-test";

    private ExternalStorageProviderGameTest() {
    }

    /**
     * The chest contents reach the network through the composite, and extracting takes them out
     * of the chest for real. If the redirect ever skipped a provider that could serve, the iron
     * would simply not be there.
     */
    @GameTest(template = "network", timeoutTicks = 400)
    public static void externalStorageServesItemsThroughTheComposite(final GameTestHelper helper) {
        build(helper, () -> {
            final StorageNetworkComponent storage = storage(helper);
            expect("iron visible on the network", storage.get(iron()), STOCKED);

            final long extracted = storage.extract(iron(), 10L, Action.EXECUTE, ACTOR);
            expect("iron extracted", extracted, 10L);
            expect("iron left on the network", storage.get(iron()), STOCKED - 10L);
            expect("iron left in the chest", countInChest(helper), STOCKED - 10L);

            helper.succeed();
        });
    }

    /** Inserting goes back into the chest, through the same redirect. */
    @GameTest(template = "network", timeoutTicks = 400)
    public static void externalStorageAcceptsItemsThroughTheComposite(final GameTestHelper helper) {
        build(helper, () -> {
            final StorageNetworkComponent storage = storage(helper);
            final long inserted = storage.insert(iron(), 5L, Action.EXECUTE, ACTOR);

            expect("iron inserted", inserted, 5L);
            expect("iron now in the chest", countInChest(helper), STOCKED + 5L);

            helper.succeed();
        });
    }

    /**
     * The whole point, stated as a property: the skip must change the number of calls and
     * nothing else.
     *
     * <p>Extracting a FLUID is the faithful case, and the first draft of this test got it wrong by
     * extracting an item. { CompositeExternalStorageProvider.extract} returns on the first
     * provider that yields anything, so an item extraction from a chest short-circuits at the item
     * provider and never reaches the fluid one -- there is nothing to skip, and the test failed
     * against a mixin that was working. A fluid request against a chest is the shape actually
     * measured in the wild: Refined Types' Network Energizer pulling power every tick made every
     * item inventory on the network answer a question it could not possibly answer.
     *
     * <p>So: the item provider must be skipped, and the answer must be identical either way.
     */
    @GameTest(template = "network", timeoutTicks = 400)
    public static void skippingProvidersChangesNothingButTheCallCount(final GameTestHelper helper) {
        build(helper, () -> {
            final StorageNetworkComponent storage = storage(helper);

            final long skipsBefore = Stats.mismatchedProviderCallsAvoided;
            final long withOptimization = extractWater(storage, true);
            final long skipped = Stats.mismatchedProviderCallsAvoided - skipsBefore;

            final long noSkipsBefore = Stats.mismatchedProviderCallsAvoided;
            final long withoutOptimization = extractWater(storage, false);
            final long skippedWhenOff = Stats.mismatchedProviderCallsAvoided - noSkipsBefore;

            // A chest holds no water either way. The point is that both paths agree.
            expect("water extracted with the skip", withOptimization, 0L);
            expect("water extracted without the skip", withoutOptimization, 0L);

            // Proves the redirect ran and that a provider declared itself typed. Without this the
            // two equal answers above would also be satisfied by the mixin never applying at all,
            // which is exactly the failure this test exists to catch.
            if (skipped <= 0L) {
                throw new GameTestAssertionFailure(
                    "the item provider was never skipped for a fluid request, so either "
                        + "CompositeExternalStorageProviderMixin did not apply or no provider "
                        + "implements TypedExternalStorageProvider");
            }
            expect("skips while the optimization is off", skippedWhenOff, 0L);

            helper.succeed();
        });
    }

    // ------------------------------------------------------------------ harness

    private static long extractWater(final StorageNetworkComponent storage, final boolean skip) {
        final boolean original = Config.skipMismatchedStorageTypes;
        Config.skipMismatchedStorageTypes = skip;
        try {
            return storage.extract(new FluidResource(Fluids.WATER), 1000L, Action.EXECUTE, ACTOR);
        } finally {
            Config.skipMismatchedStorageTypes = original;
        }
    }

    private static ItemResource iron() {
        return new ItemResource(Items.IRON_INGOT);
    }

    private static void build(final GameTestHelper helper, final Runnable body) {
        helper.setBlock(CONTROLLER, Blocks.INSTANCE.getCreativeController().getDefault());
        helper.setBlock(GRID, Blocks.INSTANCE.getCraftingGrid().getDefault());
        // Facing the chest. RS reads whatever block the external storage points at, so the
        // direction here is load-bearing rather than cosmetic.
        helper.setBlock(EXTERNAL, Blocks.INSTANCE.getExternalStorage().getDefault()
            .rotated(Direction.EAST));
        helper.setBlock(CHEST, net.minecraft.world.level.block.Blocks.CHEST);

        chest(helper).setItem(0, new ItemStack(Items.IRON_INGOT, STOCKED));

        helper.runAfterDelay(SETTLE_TICKS, body);
    }

    private static Container chest(final GameTestHelper helper) {
        final BlockEntity blockEntity = helper.getBlockEntity(CHEST);
        if (blockEntity instanceof Container container) {
            return container;
        }
        throw new GameTestAssertionFailure("no chest to back the external storage with");
    }

    private static long countInChest(final GameTestHelper helper) {
        final Container container = chest(helper);
        long total = 0L;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            final ItemStack stack = container.getItem(slot);
            if (stack.is(Items.IRON_INGOT)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static StorageNetworkComponent storage(final GameTestHelper helper) {
        final BlockEntity blockEntity = helper.getBlockEntity(GRID);
        if (!(blockEntity instanceof CraftingGridBlockEntity grid)) {
            throw new GameTestAssertionFailure("no Crafting Grid block entity to reach the "
                + "network through; the block did not place");
        }
        if (!(grid instanceof GridNetworkAccess access)) {
            throw new GameTestAssertionFailure("CraftingGridBlockEntityMixin did not apply");
        }
        final Network network = access.rstweaks$network();
        if (network == null) {
            throw new GameTestAssertionFailure(
                "the grid never joined a network within " + SETTLE_TICKS + " ticks");
        }
        return network.getComponent(StorageNetworkComponent.class);
    }

    private static void expect(
            final String what,
            final long actual,
            final long expected) {
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
