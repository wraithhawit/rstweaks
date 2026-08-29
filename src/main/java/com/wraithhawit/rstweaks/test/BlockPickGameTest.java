package com.wraithhawit.rstweaks.test;

import com.refinedmods.refinedstorage.common.support.resource.ItemResource;

import com.wraithhawit.rstweaks.Config;
import com.wraithhawit.rstweaks.RSTweaks;
import com.wraithhawit.rstweaks.iface.BlockPick;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Block pick, against a real block and a real storage.
 *
 * <p>{@link BlockPick#pick} rather than the packet, because the packet is plumbing and this is the
 * behaviour. What the packet carries — a position and a face — is what these tests pass in, so the
 * server-side derivation of "what item is that block" is exercised exactly as it is in play.
 *
 * <p>Three of these four are about refusing. The one that matters most is
 * {@link #pickRefusesRatherThanOverwriteAFullInventory}: vanilla's creative equivalent,
 * {@code Inventory.setPickedItem}, will overwrite the selected hotbar slot when it has nowhere to
 * put its contents, which costs nothing in creative and would destroy something you mined here.
 */
@GameTestHolder(RSTweaks.MODID)
@PrefixGameTestTemplate(false)
public final class BlockPickGameTest {
    private static final BlockPos TARGET = new BlockPos(1, 1, 1);
    private static final int GRID_SLOT = 10;
    private static final int STOCKED = 100;

    private BlockPickGameTest() {
    }

    /** The ordinary case: aim at a block you do not have, get a stack of it in your hand. */
    @GameTest(template = "empty", timeoutTicks = 400)
    public static void pickTakesAStackOutOfTheNetwork(final GameTestHelper helper) {
        run(helper, (player, grid) -> {
            PortableGridFixture.store(helper, grid, stone(), STOCKED);

            final boolean picked = pick(helper, player);

            expect("the pick happened", picked, true);
            expect("a full stack in hand", player.getInventory().getSelected().getCount(), 64L);
            if (!player.getInventory().getSelected().is(Items.STONE)) {
                throw new PortableGridFixture.FixtureFailure(
                    "held " + player.getInventory().getSelected() + " rather than stone");
            }
            expect("taken out of storage",
                PortableGridFixture.countStored(helper, grid, stone()), STOCKED - 64L);
        });
    }

    /** Nothing in the network means nothing happens — not an empty stack in your hand. */
    @GameTest(template = "empty", timeoutTicks = 400)
    public static void pickDoesNothingWhenTheNetworkHasNone(final GameTestHelper helper) {
        run(helper, (player, grid) -> {
            final boolean picked = pick(helper, player);

            expect("the pick was refused", picked, false);
            if (!player.getInventory().getSelected().isEmpty()) {
                throw new PortableGridFixture.FixtureFailure(
                    "something appeared in hand: " + player.getInventory().getSelected());
            }
        });
    }

    /**
     * A full inventory refuses the pick rather than overwriting the held stack.
     *
     * <p>Every slot occupied, so there is no empty hotbar slot and nowhere to move the held item
     * to. Vanilla's creative path would write over the selected slot here. Nothing may be extracted
     * either: an item that leaves a disk and reaches no inventory has been destroyed.
     */
    @GameTest(template = "empty", timeoutTicks = 400)
    public static void pickRefusesRatherThanOverwriteAFullInventory(final GameTestHelper helper) {
        run(helper, (player, grid) -> {
            PortableGridFixture.store(helper, grid, stone(), STOCKED);
            final Inventory inventory = player.getInventory();
            for (int slot = 0; slot < inventory.items.size(); ++slot) {
                if (slot != GRID_SLOT) {
                    inventory.setItem(slot, new ItemStack(Items.DIRT, 64));
                }
            }
            inventory.selected = 0;

            final boolean picked = pick(helper, player);

            expect("the pick was refused", picked, false);
            expect("the held stack survived", inventory.getItem(0).getCount(), 64L);
            if (!inventory.getItem(0).is(Items.DIRT)) {
                throw new PortableGridFixture.FixtureFailure("the held dirt was overwritten");
            }
            expect("nothing left the network",
                PortableGridFixture.countStored(helper, grid, stone()), (long) STOCKED);
        });
    }

    /**
     * A block the player cannot reach is refused.
     *
     * <p>The packet says where, and the server checks that "where" against Minecraft's own
     * {@code canInteractWithBlock} — the same question that decides whether the block could be
     * broken. Without it, the position in the packet is a request for any block in the world.
     */
    @GameTest(template = "empty", timeoutTicks = 400)
    public static void pickIsRefusedOutOfReach(final GameTestHelper helper) {
        run(helper, (player, grid) -> {
            PortableGridFixture.store(helper, grid, stone(), STOCKED);
            final BlockPos target = helper.absolutePos(TARGET);
            player.moveTo(target.getX() + 0.5, target.getY() + 40.0, target.getZ() + 0.5);

            final boolean picked = BlockPick.pick(player, target, Direction.UP);

            expect("the pick was refused", picked, false);
            expect("nothing left the network",
                PortableGridFixture.countStored(helper, grid, stone()), (long) STOCKED);
        });
    }

    // ------------------------------------------------------------------ harness

    @FunctionalInterface
    private interface Body {
        void run(ServerPlayer player, ItemStack grid);
    }

    private static void run(final GameTestHelper helper, final Body body) {
        final boolean original = Config.blockPick;
        Config.blockPick = true;
        try {
            helper.setBlock(TARGET, Blocks.STONE);

            final ServerPlayer player = (ServerPlayer) helper.makeMockServerPlayerInLevel();
            player.getInventory().clearContent();
            final ItemStack grid = PortableGridFixture.portableGrid(helper, player);
            player.getInventory().setItem(GRID_SLOT, grid);

            body.run(player, player.getInventory().getItem(GRID_SLOT));
            helper.succeed();
        } finally {
            Config.blockPick = original;
        }
    }

    /** Stands the player on the block and picks it, which is what the key press amounts to. */
    private static boolean pick(final GameTestHelper helper, final ServerPlayer player) {
        final BlockPos target = helper.absolutePos(TARGET);
        player.moveTo(target.getX() + 0.5, target.getY() + 1.0, target.getZ() + 0.5);
        return BlockPick.pick(player, target, Direction.UP);
    }

    private static ItemResource stone() {
        return new ItemResource(Items.STONE);
    }

    private static void expect(final String what, final long actual, final long expected) {
        if (actual != expected) {
            throw new PortableGridFixture.FixtureFailure(
                what + ": expected " + expected + ", got " + actual);
        }
    }

    private static void expect(final String what, final boolean actual, final boolean expected) {
        if (actual != expected) {
            throw new PortableGridFixture.FixtureFailure(
                what + ": expected " + expected + ", got " + actual);
        }
    }
}
