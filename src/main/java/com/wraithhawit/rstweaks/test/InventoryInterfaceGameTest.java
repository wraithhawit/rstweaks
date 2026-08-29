package com.wraithhawit.rstweaks.test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.api.resource.filter.FilterMode;
import com.refinedmods.refinedstorage.common.api.RefinedStorageApi;
import com.refinedmods.refinedstorage.common.api.support.slotreference.SlotReference;
import com.refinedmods.refinedstorage.common.api.support.slotreference.SlotReferenceFactory;
import com.refinedmods.refinedstorage.common.api.support.slotreference.SlotReferenceProvider;
import com.refinedmods.refinedstorage.common.content.Items;
import com.refinedmods.refinedstorage.common.support.slotreference.InventorySlotReferenceFactory;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;

import com.wraithhawit.rstweaks.Config;
import com.wraithhawit.rstweaks.RSTweaks;
import com.wraithhawit.rstweaks.iface.InventoryInterfaceContent;
import com.wraithhawit.rstweaks.iface.InventoryInterfaceState;
import com.wraithhawit.rstweaks.iface.SlotMode;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import org.jetbrains.annotations.Nullable;

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
            configure(grid, state -> InventoryInterfaceState.of(
                true, false, FilterMode.ALLOW, false, filter(iron(), 16L)));
        }, (helper2, player, grid) -> {
            expect("iron kept on the player", countCarried(player, iron()), 16L);
            expect("iron filed away", PortableGridFixture.countStored(helper2, grid, iron()), 48L);
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
            configure(grid, state -> InventoryInterfaceState.of(
                true, false, FilterMode.BLOCK, false, filter(iron(), 16L)));
        }, (helper2, player, grid) -> {
            expect("listed iron untouched", countCarried(player, iron()), 64L);
            expect("iron not in storage", PortableGridFixture.countStored(helper2, grid, iron()), 0L);
            expect("unlisted gold taken", countCarried(player, gold()), 0L);
            expect("unlisted gold filed away", PortableGridFixture.countStored(helper2, grid, gold()), 32L);
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
            configure(grid, state -> InventoryInterfaceState.of(
                true, false, FilterMode.ALLOW, false, filter(iron(), 16L)));
        }, (helper2, player, grid) -> {
            expect("iron kept across the whole inventory", countCarried(player, iron()), 16L);
            expect("hotbar iron left where it was",
                player.getInventory().getItem(1).getCount(), 10L);
            expect("the rest filed away", PortableGridFixture.countStored(helper2, grid, iron()), 58L);
        });
    }

    /**
     * Per-slot modes: one entry files away, another tops up, in the same configuration.
     *
     * <p>The thing the two master switches alone cannot say. Before slot modes, both switches on
     * meant every listed resource was pinned at its amount, so "put my cobblestone away and keep me
     * stocked with torches" needed two grids. Cobblestone is INSERT, torches are EXPORT, and both
     * master switches are on.
     *
     * <p>Cobblestone keeps ONE rather than none, and that is a floor rather than a choice: Refined
     * Storages {@code ResourceAmount} refuses an amount of zero, so a listed entry always keeps at
     * least one. "File away every last one" is BLOCK mode with the resource left off the list, which
     * is what BLOCK is for.
     */
    @GameTest(template = "empty", timeoutTicks = 400)
    public static void perSlotModesSplitTheTwoDirections(final GameTestHelper helper) {
        run(helper, (player, grid) -> {
            // Iron: below its amount with stock available, so an unrestricted pass WOULD top it up.
            // The slot says insert only, so nothing may happen to it.
            PortableGridFixture.store(helper, grid, iron(), 64L);
            player.getInventory().setItem(CARGO_SLOT,
                new ItemStack(net.minecraft.world.item.Items.IRON_INGOT, 10));
            // Gold: above its amount, so an unrestricted pass WOULD file the surplus away. The slot
            // says export only, so nothing may happen to it either.
            player.getInventory().setItem(SECOND_CARGO_SLOT,
                new ItemStack(net.minecraft.world.item.Items.GOLD_INGOT, 40));
            // Torches: an ordinary BOTH entry, present so a pass that did nothing at all cannot
            // pass this test by accident.
            PortableGridFixture.store(helper, grid, torch(), 64L);

            final List<Optional<ResourceAmount>> filter =
                new java.util.ArrayList<>(InventoryInterfaceState.EMPTY.filter());
            filter.set(0, Optional.of(new ResourceAmount(iron(), 32L)));
            filter.set(1, Optional.of(new ResourceAmount(gold(), 8L)));
            filter.set(2, Optional.of(new ResourceAmount(torch(), 16L)));
            configure(grid, state -> InventoryInterfaceState
                .of(true, true, FilterMode.ALLOW, false, filter)
                .withSlotMode(0, SlotMode.INSERT)
                .withSlotMode(1, SlotMode.EXPORT));
        }, (helper2, player, grid) -> {
            expect("insert-only iron was not topped up", countCarried(player, iron()), 10L);
            expect("insert-only iron left in storage",
                PortableGridFixture.countStored(helper2, grid, iron()), 64L);
            expect("export-only gold was not filed away", countCarried(player, gold()), 40L);
            expect("export-only gold stayed out of storage",
                PortableGridFixture.countStored(helper2, grid, gold()), 0L);
            expect("the ordinary entry still ran", countCarried(player, torch()), 16L);
        });
    }

    /**
     * An excluded inventory slot is not taken from, and its contents still count towards the keep
     * budget — the same rule the hotbar follows, for the same reason.
     */
    @GameTest(template = "empty", timeoutTicks = 400)
    public static void anExcludedSlotIsLeftAloneAndStillCounts(final GameTestHelper helper) {
        run(helper, (player, grid) -> {
            // Sixty-four in the excluded slot against a keep of sixteen. The size matters: with a
            // smaller stack the keep budget would absorb it either way and the test would pass
            // whether or not the exclusion was read at all, which is how its first version was
            // wrong. At sixty-four, an unrestricted pass would file forty-eight of them.
            player.getInventory().setItem(CARGO_SLOT,
                new ItemStack(net.minecraft.world.item.Items.IRON_INGOT, 64));
            // And an included stack, to show the excluded one still spent the whole budget: with
            // sixty-four already accounted for there is nothing left to keep, so this goes entirely.
            player.getInventory().setItem(SECOND_CARGO_SLOT,
                new ItemStack(net.minecraft.world.item.Items.IRON_INGOT, 20));
            configure(grid, state -> InventoryInterfaceState
                .of(true, false, FilterMode.ALLOW, false, filter(iron(), 16L))
                .withInsertSlot(CARGO_SLOT, false));
        }, (helper2, player, grid) -> {
            expect("the excluded slot is untouched",
                player.getInventory().getItem(CARGO_SLOT).getCount(), 64L);
            expect("the included slot went entirely, its keep already spent",
                player.getInventory().getItem(SECOND_CARGO_SLOT).getCount(), 0L);
            expect("only the included stack was filed away",
                PortableGridFixture.countStored(helper2, grid, iron()), 20L);
        });
    }

    /** Export tops the player back up to the filter's amount, and stops there. */
    @GameTest(template = "empty", timeoutTicks = 400)
    public static void autoExportTopsUpToTheFilterAmount(final GameTestHelper helper) {
        run(helper, (player, grid) -> {
            PortableGridFixture.store(helper, grid, iron(), 64L);
            configure(grid, state -> InventoryInterfaceState.of(
                false, true, FilterMode.ALLOW, false, filter(iron(), 40L)));
        }, (helper2, player, grid) -> {
            expect("iron handed to the player", countCarried(player, iron()), 40L);
            expect("iron left in storage", PortableGridFixture.countStored(helper2, grid, iron()), 24L);
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
                configure(grid, state -> InventoryInterfaceState.of(
                    true, false, FilterMode.BLOCK, false, InventoryInterfaceState.EMPTY.filter()));
            }, (helper2, player, grid) -> {
                expect("unprotected cobblestone filed away",
                    PortableGridFixture.countStored(helper2, grid, new ItemResource(net.minecraft.world.item.Items.COBBLESTONE)), 16L);
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

    /**
     * A grid that is not in the inventory at all still works.
     *
     * <p>Refined Storage's Curios integration registers a slot-reference provider, so a Wireless
     * Grid worn in a Curios slot is an ordinary working grid that {@code player.getInventory()}
     * cannot see. This mod scanned the inventory and so ignored one, which is how the gap was
     * found — by somebody wearing theirs.
     *
     * <p>Curios is not in the dev run, so the grid here is exposed through a provider of our own
     * registered into the same composite. That tests the thing that was actually wrong: whether the
     * pass asks Refined Storage where the grids are, or assumes. A provider is a provider; Curios
     * has no special status in the composite.
     */
    @GameTest(template = "empty", timeoutTicks = 400)
    public static void aGridOutsideTheInventoryStillWorks(final GameTestHelper helper) {
        final int originalInterval = Config.inventoryInterfaceIntervalTicks;
        final boolean originalEnabled = Config.inventoryInterface;
        Config.inventoryInterfaceIntervalTicks = 1;
        Config.inventoryInterface = true;
        try {
            final ServerPlayer player = (ServerPlayer) helper.makeMockServerPlayerInLevel();
            player.getInventory().clearContent();

            final ItemStack grid = PortableGridFixture.portableGrid(helper, player);
            configure(grid, state -> InventoryInterfaceState.of(
                true, false, FilterMode.ALLOW, false, filter(iron(), 16L)));
            player.getInventory().setItem(CARGO_SLOT,
                new ItemStack(net.minecraft.world.item.Items.IRON_INGOT, 64));

            // Deliberately NOT put in the inventory anywhere.
            ExternalSlot.hold(grid);
            try {
                for (int pass = 0; pass < PASSES; ++pass) {
                    NeoForge.EVENT_BUS.post(new PlayerTickEvent.Post(player));
                }
            } finally {
                ExternalSlot.release();
            }

            expect("iron kept on the player", countCarried(player, iron()), 16L);
            expect("iron filed away by a grid the inventory cannot see",
                PortableGridFixture.countStored(helper, grid, iron()), 48L);
            helper.succeed();
        } finally {
            Config.inventoryInterfaceIntervalTicks = originalInterval;
            Config.inventoryInterface = originalEnabled;
        }
    }

    // ------------------------------------------------------------------ harness

    /**
     * A slot that is not an inventory slot, registered into Refined Storage's composite provider
     * the way Curios registers its own.
     *
     * <p>Registered once and inert until {@link #hold} arms it, because the composite is global and
     * lives for the session — every other test's {@code carriedBy} runs through this too, and gets
     * an empty list. {@code isDisabledSlot} answers false for every inventory index, which is the
     * honest answer for something occupying none of them and the behaviour the insert pass relies
     * on.
     */
    private static final class ExternalSlot implements SlotReferenceProvider, SlotReference {
        private static final ExternalSlot INSTANCE = new ExternalSlot();
        private static boolean registered;

        @Nullable
        private ItemStack stack;

        static void hold(final ItemStack stack) {
            if (!registered) {
                RefinedStorageApi.INSTANCE.addSlotReferenceProvider(INSTANCE);
                registered = true;
            }
            INSTANCE.stack = stack;
        }

        static void release() {
            INSTANCE.stack = null;
        }

        @Override
        public List<SlotReference> find(final Player player, final Set<Item> validItems) {
            if (stack == null || !validItems.contains(stack.getItem())) {
                return List.of();
            }
            return List.of(this);
        }

        @Override
        public Optional<ItemStack> resolve(final Player player) {
            return Optional.ofNullable(stack);
        }

        @Override
        public boolean isDisabledSlot(final int playerSlotIndex) {
            return false;
        }

        /**
         * Only used to write a reference to a client, which nothing on this path does. Refined
         * Storage's own factory rather than null so that a future caller gets a wrong answer rather
         * than a crash.
         */
        @Override
        public SlotReferenceFactory getFactory() {
            return InventorySlotReferenceFactory.INSTANCE;
        }
    }

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

            final ItemStack grid = PortableGridFixture.portableGrid(helper, player);
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

    private static ItemResource cobblestone() {
        return new ItemResource(net.minecraft.world.item.Items.COBBLESTONE);
    }

    private static ItemResource torch() {
        return new ItemResource(net.minecraft.world.item.Items.TORCH);
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
