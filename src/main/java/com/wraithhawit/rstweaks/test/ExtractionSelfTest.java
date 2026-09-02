package com.wraithhawit.rstweaks.test;

import com.refinedmods.refinedstorage.api.core.Action;
import com.refinedmods.refinedstorage.api.storage.Actor;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import com.refinedmods.refinedstorage.neoforge.storage.CapabilityCache;
import com.refinedmods.refinedstorage.neoforge.storage.ItemHandlerExtractableStorage;
import com.wraithhawit.rstweaks.Config;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.function.Consumer;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;

/**
 * Differential test for the external-inventory slot index.
 *
 * <p>Runs the same extraction twice against identical inventories — once with
 * {@code externalStorageSlotIndex} on, once off — and asserts both the returned
 * amount and the resulting inventory contents match exactly. Testing the invariant
 * ("the index must not change the answer") rather than hand-computed expectations
 * means it covers cases nobody thought to enumerate.
 *
 * <p>The failure mode it exists to catch is <b>over-reporting under SIMULATE</b>. The
 * indexed pass consults some slots and then may fall back to the full scan; if the
 * scan re-examines a slot the index already counted, the total is inflated and
 * Refined Storage believes it can extract more than exists. Nothing crashes — the
 * craft simply stalls later, far from the cause.
 *
 * <p><b>Under-reporting is the other half, and it took until 0.22.1 to cover.</b> The
 * scenarios were all built from slots holding at most one stack, and on those the two
 * paths cannot disagree — a green differential that proved only that the test could not
 * tell them apart. The drawer scenarios put more than a stack in one slot, which is where
 * {@code extractSlot} does something the indexed pass has to match rather than skip.
 *
 * <p>{@code CapabilityCache} is an interface of default methods, so a real
 * {@link ItemHandlerExtractableStorage} can be built over a plain
 * {@link ItemStackHandler}. This exercises the actual mixed-in code, not a
 * reimplementation of it.
 */
public final class ExtractionSelfTest {
    private static final Actor ACTOR = () -> "rstweaks-extraction-test";

    /**
     * The slot the drawer scenarios overfill, and what goes in it.
     *
     * <p>A plain {@link ItemStackHandler} reproduces the part of a drawer that matters here
     * exactly: {@code extractItem} hands back at most {@code getMaxStackSize()} per call however
     * much is asked for, so a slot holding 16,900 answers a request for {@code MAX_VALUE} with
     * 64. No drawer mod is needed to test it, and none is a dependency.
     */
    private static final int OVERSTACKED_SLOT = 7;

    private static final Item OVERSTACKED_ITEM = Items.IRON_INGOT;

    private ExtractionSelfTest() {
    }

    public record Result(int scenarios, List<String> failures) {
        public boolean passed() {
            return this.failures.isEmpty();
        }
    }

    /**
     * @param overstack how many of {@link #OVERSTACKED_ITEM} to put in one slot, drawer-style,
     *                  or zero for an ordinary inventory
     */
    private record Scenario(String name, int slots, long request, Action action, Item wanted,
                            int overstack) {
        Scenario(final String name, final int slots, final long request, final Action action,
                 final Item wanted) {
            this(name, slots, request, action, wanted, 0);
        }
    }

    public static Result run() {
        final List<String> failures = new ArrayList<>();
        final List<Scenario> scenarios = scenarios();
        final List<StaleScenario> stale = staleScenarios();
        final boolean original = Config.externalStorageSlotIndex;
        try {
            for (final Scenario s : scenarios) {
                try {
                    compare(s, failures);
                } catch (final RuntimeException e) {
                    failures.add(s.name() + ": threw " + e);
                }
            }
            for (final StaleScenario s : stale) {
                try {
                    compareStale(s, failures);
                } catch (final RuntimeException e) {
                    failures.add(s.name() + ": threw " + e);
                }
            }
        } finally {
            Config.externalStorageSlotIndex = original;
        }
        return new Result(scenarios.size() + stale.size(), failures);
    }

    private static void compare(final Scenario s, final List<String> failures) {
        Config.externalStorageSlotIndex = true;
        final ItemStackHandler indexed = populate(s.slots(), s.name(), s.overstack());
        final long indexedResult = extract(indexed, s);

        Config.externalStorageSlotIndex = false;
        final ItemStackHandler plain = populate(s.slots(), s.name(), s.overstack());
        final long plainResult = extract(plain, s);

        if (indexedResult != plainResult) {
            failures.add(s.name() + ": returned " + indexedResult
                + " with index, " + plainResult + " without"
                + (indexedResult > plainResult ? "  <-- OVER-REPORTING" : ""));
        }
        final String a = contents(indexed);
        final String b = contents(plain);
        if (!a.equals(b)) {
            failures.add(s.name() + ": inventory differs after extraction\n      indexed=" + a
                + "\n      plain  =" + b);
        }
    }

    private static long extract(final IItemHandler handler, final Scenario s) {
        return storageOver(handler).extract(
            ItemResource.ofItemStack(new ItemStack(s.wanted())), s.request(), s.action(), ACTOR);
    }

    /**
     * The real Refined Storage storage over a plain handler.
     *
     * <p>{@code CapabilityCache} is an interface of default methods, so this is the
     * genuine class with our mixin on it rather than a stand-in. Held onto by the stale
     * scenarios below, because the slot index is per-storage state: a fresh instance per
     * extraction is a fresh index, which is exactly the case that never goes stale.
     */
    private static ItemHandlerExtractableStorage storageOver(final IItemHandler handler) {
        return new ItemHandlerExtractableStorage(new CapabilityCache() {
            @Override
            public Optional<IItemHandler> getItemHandler() {
                return Optional.of(handler);
            }
        });
    }

    // ------------------------------------------------------------- stale index

    /**
     * An inventory that changes behind Refined Storage's back between two extractions on
     * the same storage.
     *
     * @param disturb what happens to the inventory after the index has been built. It
     *     writes to the handler directly, which is what a hopper, a pipe or a player
     *     does — none of them tell the storage anything.
     */
    private record StaleScenario(String name,
                                 int slots,
                                 Item wanted,
                                 long firstRequest,
                                 long secondRequest,
                                 Action action,
                                 Consumer<ItemStackHandler> disturb) {
    }

    /**
     * Two extractions on one storage, with the inventory rearranged in between.
     *
     * <p>This is the path that destroyed items up to 0.2.55. The indexed pass walks the
     * slots it believes hold the resource, extracting as it goes; when it reaches an entry
     * that no longer matches it gives up and hands the request to Refined Storage's own
     * scan — and it used to hand it over <em>without saying what it had already taken</em>.
     * The items were out of the inventory and absent from the returned total, which is
     * deletion. Nothing about a fresh index can reach that exit, so
     * {@link #compare} never has.
     *
     * <p>Asserted as a differential against the same sequence with the index off: same
     * amount returned, same inventory left behind. An expectation written by hand would
     * only cover the disturbance its author imagined.
     */
    /**
     * What one run of the sequence reported, and what physically moved while it did.
     *
     * @param heldBeforeSecond how many were in the inventory once the disturbance had
     *     been applied — the ceiling on what the second extraction may honestly claim.
     */
    private record StaleRun(long first,
                            long firstRemoved,
                            long second,
                            long secondRemoved,
                            long heldBeforeSecond,
                            String contents) {
    }

    private static void compareStale(final StaleScenario s, final List<String> failures) {
        Config.externalStorageSlotIndex = true;
        final ItemStackHandler indexed = populate(s.slots(), s.name());
        final StaleRun withIndex = runStale(indexed, s);

        Config.externalStorageSlotIndex = false;
        final ItemStackHandler plain = populate(s.slots(), s.name());
        final StaleRun withoutIndex = runStale(plain, s);

        // The invariant that matters, and it is not a comparison at all: whatever leaves
        // the inventory has to be exactly what Refined Storage is told left it. Every item
        // 0.2.55 destroyed went out through this gap -- taken from a slot, then omitted
        // from the total when the indexed pass gave up partway and handed over to the
        // scan.
        if (withIndex.firstRemoved() != withIndex.first()) {
            failures.add(s.name() + ": the first extraction took " + withIndex.firstRemoved()
                + " " + s.wanted() + " out of the inventory but reported " + withIndex.first()
                + (withIndex.firstRemoved() > withIndex.first()
                    ? "  <-- ITEMS DESTROYED" : "  <-- ITEMS CREATED"));
        }
        if (s.action() == Action.EXECUTE) {
            if (withIndex.secondRemoved() != withIndex.second()) {
                failures.add(s.name() + ": took " + withIndex.secondRemoved() + " "
                    + s.wanted() + " out of the inventory but reported " + withIndex.second()
                    + (withIndex.secondRemoved() > withIndex.second()
                        ? "  <-- ITEMS DESTROYED" : "  <-- ITEMS CREATED"));
            }
        } else {
            // A simulation must move nothing and must never promise more than is there.
            // Over-reporting under SIMULATE is the quiet failure: nothing breaks now, and
            // the craft stalls later somewhere with no connection to this code.
            if (withIndex.secondRemoved() != 0L) {
                failures.add(s.name() + ": a SIMULATE extraction removed "
                    + withIndex.secondRemoved() + " " + s.wanted());
            }
            if (withIndex.second() > withIndex.heldBeforeSecond()) {
                failures.add(s.name() + ": promised " + withIndex.second() + " " + s.wanted()
                    + " when the inventory holds " + withIndex.heldBeforeSecond()
                    + "  <-- OVER-REPORTING");
            }
        }

        // And the differential, which covers everything nobody thought to assert: with the
        // index off, the same sequence against the same inventory must answer the same.
        if (withIndex.first() != withoutIndex.first()
            || withIndex.second() != withoutIndex.second()) {
            failures.add(s.name() + ": returned " + withIndex.first() + "+" + withIndex.second()
                + " with index, " + withoutIndex.first() + "+" + withoutIndex.second()
                + " without");
        }
        if (!withIndex.contents().equals(withoutIndex.contents())) {
            failures.add(s.name() + ": inventory differs after extraction\n      indexed="
                + withIndex.contents() + "\n      plain  =" + withoutIndex.contents());
        }
    }

    /** Extract, disturb, extract again — against one storage, so the index survives. */
    private static StaleRun runStale(final ItemStackHandler handler, final StaleScenario s) {
        final ItemHandlerExtractableStorage storage = storageOver(handler);
        final ItemResource wanted = ItemResource.ofItemStack(new ItemStack(s.wanted()));

        // Always EXECUTE: the index only learns where things are by being used, and a
        // simulated first pass leaves nothing for the second one to find stale.
        final long beforeFirst = count(handler, s.wanted());
        final long first = storage.extract(wanted, s.firstRequest(), Action.EXECUTE, ACTOR);
        final long afterFirst = count(handler, s.wanted());

        s.disturb().accept(handler);

        final long beforeSecond = count(handler, s.wanted());
        final long second = storage.extract(wanted, s.secondRequest(), s.action(), ACTOR);
        final long afterSecond = count(handler, s.wanted());

        return new StaleRun(first, beforeFirst - afterFirst,
            second, beforeSecond - afterSecond, beforeSecond, contents(handler));
    }

    private static long count(final IItemHandler handler, final Item item) {
        long total = 0L;
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            final ItemStack stack = handler.getStackInSlot(slot);
            if (stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /**
     * The n-th slot holding this item, counting from zero, or -1.
     *
     * <p><b>Which one is disturbed decides what the scenario tests, and getting that
     * wrong makes it test nothing.</b> The indexed pass walks its candidates in slot
     * order, extracting as it goes, and only carries a running total across the
     * stale-entry exit if it had already taken something. Disturbing the <em>first</em>
     * candidate means the exit is reached with zero extracted, where reporting the total
     * and reporting nothing are the same number — the 0.2.55 bug was reinstated to check
     * this suite and every scenario still passed until the disturbance moved later down
     * the list.
     */
    private static int nthSlotWith(final ItemStackHandler handler, final Item item, final int n) {
        int seen = 0;
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            if (handler.getStackInSlot(slot).is(item) && seen++ == n) {
                return slot;
            }
        }
        return -1;
    }

    private static List<StaleScenario> staleScenarios() {
        final List<StaleScenario> out = new ArrayList<>();
        for (final Action action : Action.values()) {
            final String tag = action == Action.SIMULATE ? "simulate" : "execute";

            // The stale entry is met partway through, after earlier candidates have
            // already given up items. Under EXECUTE that is exactly 0.2.55: the early
            // slots are drained, the fallback scan reports only what it finds, and the
            // difference is gone. Confirmed to fail with that bug put back.
            out.add(new StaleScenario("later indexed slot emptied behind us, " + tag,
                200, Items.IRON_INGOT, 5L, 2000L, action, handler -> {
                    final int slot = nthSlotWith(handler, Items.IRON_INGOT, 2);
                    if (slot >= 0) {
                        handler.setStackInSlot(slot, ItemStack.EMPTY);
                    }
                }));

            // Same exit, reached with a slot that still has something in it -- the
            // verification read is what notices, and the candidate list is not shortened
            // by it. Also confirmed to fail with the 0.2.55 bug put back.
            out.add(new StaleScenario("later indexed slot now holds something else, " + tag,
                200, Items.IRON_INGOT, 5L, 2000L, action, handler -> {
                    final int slot = nthSlotWith(handler, Items.IRON_INGOT, 2);
                    if (slot >= 0) {
                        handler.setStackInSlot(slot, new ItemStack(Items.NETHERITE_INGOT, 7));
                    }
                }));

            // The first candidate is the stale one, so the exit is taken with nothing
            // extracted yet. Kept because it is a different arithmetic case, not because
            // it can catch the loss -- see nthSlotWith.
            out.add(new StaleScenario("first indexed slot emptied behind us, " + tag,
                200, Items.IRON_INGOT, 5L, 2000L, action, handler -> {
                    final int slot = nthSlotWith(handler, Items.IRON_INGOT, 0);
                    if (slot >= 0) {
                        handler.setStackInSlot(slot, ItemStack.EMPTY);
                    }
                }));

            // Everything the index knew about is gone. The whole request has to come from
            // the fallback scan, which must not double-count the slots already visited.
            out.add(new StaleScenario("every indexed slot cleared, " + tag,
                200, Items.REDSTONE, 5L, 5000L, action, handler -> {
                    for (int slot = 0; slot < handler.getSlots(); slot++) {
                        if (handler.getStackInSlot(slot).is(Items.REDSTONE)) {
                            handler.setStackInSlot(slot, ItemStack.EMPTY);
                        }
                    }
                }));

            // More arrives in a slot the index has never heard of. The index is allowed to
            // be late here -- but only late: what it does report must still be honest, and
            // the fallback scan has to find the rest.
            out.add(new StaleScenario("more appears in an unindexed slot, " + tag,
                200, Items.GOLD_INGOT, 5L, 9999L, action, handler -> {
                    for (int slot = handler.getSlots() - 1; slot >= 0; slot--) {
                        if (handler.getStackInSlot(slot).isEmpty()) {
                            handler.setStackInSlot(slot, new ItemStack(Items.GOLD_INGOT, 64));
                            return;
                        }
                    }
                }));
        }
        return out;
    }

    /** Deterministic contents, so both runs start from an identical inventory. */
    private static ItemStackHandler populate(final int slots, final String seed) {
        return populate(slots, seed, 0);
    }

    private static ItemStackHandler populate(final int slots, final String seed,
                                             final int overstack) {
        final ItemStackHandler handler = new ItemStackHandler(slots);
        final Random rng = new Random(seed.hashCode());
        final Item[] palette = {Items.IRON_INGOT, Items.GOLD_INGOT, Items.COPPER_INGOT,
            Items.REDSTONE, Items.DIAMOND};
        for (int slot = 0; slot < slots; slot++) {
            if (rng.nextInt(4) == 0) {
                continue;
            }
            final Item item = palette[rng.nextInt(palette.length)];
            handler.setStackInSlot(slot, new ItemStack(item, 1 + rng.nextInt(64)));
        }
        if (overstack > 0) {
            handler.setStackInSlot(OVERSTACKED_SLOT, new ItemStack(OVERSTACKED_ITEM, overstack));
        }
        return handler;
    }

    private static String contents(final IItemHandler handler) {
        final StringBuilder sb = new StringBuilder();
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            final ItemStack stack = handler.getStackInSlot(slot);
            if (!stack.isEmpty()) {
                sb.append(slot).append(':').append(stack.getItem()).append('x')
                    .append(stack.getCount()).append(' ');
            }
        }
        return sb.toString();
    }

    private static List<Scenario> scenarios() {
        final List<Scenario> out = new ArrayList<>();
        // Above minSlotsToIndex so the indexed path actually engages.
        for (final Action action : Action.values()) {
            final String tag = action == Action.SIMULATE ? "simulate" : "execute";
            out.add(new Scenario("small request, " + tag, 200, 5L, action, Items.IRON_INGOT));
            out.add(new Scenario("spans many slots, " + tag, 200, 2000L, action, Items.IRON_INGOT));
            out.add(new Scenario("more than exists, " + tag, 200, 999999L, action, Items.DIAMOND));
            out.add(new Scenario("absent item, " + tag, 200, 64L, action, Items.NETHERITE_INGOT));
            out.add(new Scenario("below index threshold, " + tag, 8, 64L, action, Items.IRON_INGOT));
            out.add(new Scenario("large inventory, " + tag, 2000, 5000L, action, Items.REDSTONE));
            out.add(new Scenario("zero request, " + tag, 200, 0L, action, Items.GOLD_INGOT));

            // A drawer: one slot holding far more than a stack. Shipped wrong from 0.2.3 to
            // 0.22.0 -- the indexed pass called extractItem directly, so it answered 64 for a
            // drawer of 16,900, while the scan it stands in for reports what the slot holds.
            // Nothing above noticed until shift-click crafting, which budgets a whole run from
            // one simulated extraction, stopped after 22 crafts.
            //
            // The scenarios up to here could never have caught it: every slot they build holds
            // at most one stack, and with no slot over the limit both paths agree.
            out.add(new Scenario("drawer, whole inventory asked for, " + tag, 200,
                Integer.MAX_VALUE, action, OVERSTACKED_ITEM, 16900));
            out.add(new Scenario("drawer, more than one stack asked for, " + tag, 200, 5000L,
                action, OVERSTACKED_ITEM, 16900));
            out.add(new Scenario("drawer, exactly one stack asked for, " + tag, 200, 64L,
                action, OVERSTACKED_ITEM, 16900));
            out.add(new Scenario("drawer, less than one stack asked for, " + tag, 200, 5L,
                action, OVERSTACKED_ITEM, 16900));
        }
        return out;
    }
}
