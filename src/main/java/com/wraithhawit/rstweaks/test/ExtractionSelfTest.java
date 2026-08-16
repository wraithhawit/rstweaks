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
 * <p>{@code CapabilityCache} is an interface of default methods, so a real
 * {@link ItemHandlerExtractableStorage} can be built over a plain
 * {@link ItemStackHandler}. This exercises the actual mixed-in code, not a
 * reimplementation of it.
 */
public final class ExtractionSelfTest {
    private static final Actor ACTOR = () -> "rstweaks-extraction-test";

    private ExtractionSelfTest() {
    }

    public record Result(int scenarios, List<String> failures) {
        public boolean passed() {
            return this.failures.isEmpty();
        }
    }

    private record Scenario(String name, int slots, long request, Action action, Item wanted) {
    }

    public static Result run() {
        final List<String> failures = new ArrayList<>();
        final List<Scenario> scenarios = scenarios();
        final boolean original = Config.externalStorageSlotIndex;
        try {
            for (final Scenario s : scenarios) {
                try {
                    compare(s, failures);
                } catch (final RuntimeException e) {
                    failures.add(s.name() + ": threw " + e);
                }
            }
        } finally {
            Config.externalStorageSlotIndex = original;
        }
        return new Result(scenarios.size(), failures);
    }

    private static void compare(final Scenario s, final List<String> failures) {
        Config.externalStorageSlotIndex = true;
        final ItemStackHandler indexed = populate(s.slots(), s.name());
        final long indexedResult = extract(indexed, s);

        Config.externalStorageSlotIndex = false;
        final ItemStackHandler plain = populate(s.slots(), s.name());
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
        final CapabilityCache cache = new CapabilityCache() {
            @Override
            public Optional<IItemHandler> getItemHandler() {
                return Optional.of(handler);
            }
        };
        final ItemHandlerExtractableStorage storage = new ItemHandlerExtractableStorage(cache);
        return storage.extract(
            ItemResource.ofItemStack(new ItemStack(s.wanted())), s.request(), s.action(), ACTOR);
    }

    /** Deterministic contents, so both runs start from an identical inventory. */
    private static ItemStackHandler populate(final int slots, final String seed) {
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
        }
        return out;
    }
}
