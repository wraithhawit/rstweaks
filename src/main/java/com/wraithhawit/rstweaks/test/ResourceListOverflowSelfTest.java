package com.wraithhawit.rstweaks.test;

import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.api.resource.list.MutableResourceList;
import com.refinedmods.refinedstorage.api.resource.list.MutableResourceListImpl;
import com.refinedmods.refinedstorage.api.storage.composite.CompositeStorageImpl;
import com.wraithhawit.rstweaks.ResourceListOverflow;
import com.wraithhawit.rstweaks.Stats;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Proves the overflow clamp fires, and that it is the only thing it changes.
 *
 * <p>Has to run in a game. Nothing transforms Refined Storage in a bare JVM, so a headless run
 * of this would exercise stock {@code Entry.increment} and report a pass while proving the
 * opposite of what it claims — see {@link RSTweaksGameTests}.
 *
 * <p>The first scenario is the reported crash, reduced. LavaSurf, 2026-08-18: the server died in
 * {@code ResourceAmount.validate} with "Amount must be larger than 0", reached from
 * {@code getAll()} both through the Step Requester's crafting calculation and through opening a
 * grid, and it survived a relog. On stock Refined Storage that scenario throws here exactly as it
 * did there; the rest exist so that fixing it cannot quietly cost anything else.
 */
public final class ResourceListOverflowSelfTest {
    private ResourceListOverflowSelfTest() {
    }

    /** A synthetic resource. RS's {@link ResourceKey} is an empty marker interface. */
    private record TestResource(String id) implements ResourceKey {
        @Override
        public String toString() {
            return id;
        }
    }

    public static CraftingPlanSelfTest.Result run() {
        final List<String> failures = new ArrayList<>();
        int scenarios = 0;

        ResourceListOverflow.forgetReported();

        ++scenarios;
        overflowingTotalDoesNotCrashTheList(failures);
        ++scenarios;
        overflowIsCounted(failures);
        ++scenarios;
        exactMaximumIsNotClamped(failures);
        ++scenarios;
        ordinaryAdditionIsUntouched(failures);
        ++scenarios;
        saturatedEntryStillExtracts(failures);
        ++scenarios;
        nonPositiveAdditionStillRejected(failures);
        ++scenarios;
        compositeCacheSurvivesTwoHugeSources(failures);

        return new CraftingPlanSelfTest.Result(scenarios, failures);
    }

    /**
     * The crash itself: a total pushed past {@code Long.MAX_VALUE}, then read back. Stock RS
     * wraps to {@code Long.MIN_VALUE} and {@code copyState} throws while building the list.
     */
    private static void overflowingTotalDoesNotCrashTheList(final List<String> failures) {
        final MutableResourceList list = MutableResourceListImpl.create();
        final ResourceKey resource = new TestResource("overflow");
        list.add(resource, Long.MAX_VALUE);
        list.add(resource, 1L);

        if (list.get(resource) <= 0L) {
            failures.add("total went non-positive after overflow: " + list.get(resource)
                + " -- the clamp did not fire, so the mixin is not applied");
            return;
        }
        try {
            final Collection<ResourceAmount> state = list.copyState();
            if (state.size() != 1) {
                failures.add("expected one entry after overflow, got " + state.size());
                return;
            }
            final ResourceAmount only = state.iterator().next();
            if (only.amount() != Long.MAX_VALUE) {
                failures.add("expected the total to saturate at Long.MAX_VALUE, got "
                    + only.amount());
            }
        } catch (final RuntimeException e) {
            failures.add("copyState still threw after an overflow: " + e);
        }
    }

    /** The clamp is silent everywhere except the log and this counter, so pin the counter. */
    private static void overflowIsCounted(final List<String> failures) {
        final long before = Stats.resourceAmountOverflowsClamped;

        final MutableResourceList list = MutableResourceListImpl.create();
        final ResourceKey resource = new TestResource("counted");
        list.add(resource, Long.MAX_VALUE);
        list.add(resource, 1000L);

        if (Stats.resourceAmountOverflowsClamped != before + 1L) {
            failures.add("expected exactly one clamp to be counted, counter went from "
                + before + " to " + Stats.resourceAmountOverflowsClamped);
        }
    }

    /**
     * The boundary. Landing exactly on {@code Long.MAX_VALUE} is a representable total and must
     * go through stock RS untouched — an off-by-one here would clamp a legal sum and count a
     * phantom overflow every time a network happened to hit it.
     */
    private static void exactMaximumIsNotClamped(final List<String> failures) {
        final long before = Stats.resourceAmountOverflowsClamped;

        final MutableResourceList list = MutableResourceListImpl.create();
        final ResourceKey resource = new TestResource("exact");
        list.add(resource, Long.MAX_VALUE - 10L);
        list.add(resource, 10L);

        if (list.get(resource) != Long.MAX_VALUE) {
            failures.add("expected exactly Long.MAX_VALUE, got " + list.get(resource));
        }
        if (Stats.resourceAmountOverflowsClamped != before) {
            failures.add("an exact Long.MAX_VALUE total was counted as an overflow");
        }
    }

    /** The path every insert on every network takes. It must be arithmetic and nothing else. */
    private static void ordinaryAdditionIsUntouched(final List<String> failures) {
        final long before = Stats.resourceAmountOverflowsClamped;

        final MutableResourceList list = MutableResourceListImpl.create();
        final ResourceKey resource = new TestResource("ordinary");
        list.add(resource, 5L);
        list.add(resource, 7L);

        if (list.get(resource) != 12L) {
            failures.add("expected 12 after 5 + 7, got " + list.get(resource));
        }
        if (Stats.resourceAmountOverflowsClamped != before) {
            failures.add("an ordinary addition was counted as an overflow");
        }
    }

    /**
     * A saturated entry has to stay a working entry. Clamping would be a poor trade if the
     * resource then became impossible to take out — that is the failure mode being avoided.
     */
    private static void saturatedEntryStillExtracts(final List<String> failures) {
        final MutableResourceList list = MutableResourceListImpl.create();
        final ResourceKey resource = new TestResource("saturated");
        list.add(resource, Long.MAX_VALUE);
        list.add(resource, 500L);

        list.remove(resource, 100L);
        if (list.get(resource) != Long.MAX_VALUE - 100L) {
            failures.add("expected Long.MAX_VALUE - 100 after removing from a saturated entry,"
                + " got " + list.get(resource));
        }
        if (!list.contains(resource)) {
            failures.add("a saturated entry vanished from the list after a partial removal");
        }
    }

    /**
     * The clamp must not have swallowed Refined Storage's own validation. A zero or negative
     * addition is a caller bug and still has to be raised, not absorbed into a clamp.
     */
    private static void nonPositiveAdditionStillRejected(final List<String> failures) {
        final MutableResourceList list = MutableResourceListImpl.create();
        final ResourceKey resource = new TestResource("rejected");
        list.add(resource, 10L);
        try {
            list.add(resource, 0L);
            failures.add("adding zero was accepted; RS's own validation has been lost");
        } catch (final IllegalArgumentException expected) {
            // Correct.
        }
        try {
            list.add(resource, -5L);
            failures.add("adding a negative amount was accepted; validation has been lost");
        } catch (final IllegalArgumentException expected) {
            // Correct.
        }
    }

    /**
     * The shape the report actually arrived in: not one caller adding twice, but a composite
     * summing several storages into one shared key as the network is built. This is
     * {@code addContentOfSourceToList} reduced to the two public methods it is made of, and
     * {@code getAll} is the call that crashed on the reporter's world.
     */
    private static void compositeCacheSurvivesTwoHugeSources(final List<String> failures) {
        final CompositeStorageImpl composite =
            new CompositeStorageImpl(MutableResourceListImpl.create());
        final ResourceKey resource = new TestResource("energy");

        // Two storages, each reporting a long-typed amount near the top of the range under the
        // same key -- what a shared singleton resource does with more than one External Storage.
        composite.addToCache(resource, Long.MAX_VALUE / 2L + 1L);
        composite.addToCache(resource, Long.MAX_VALUE / 2L + 1L);

        try {
            final Collection<ResourceAmount> all = composite.getAll();
            if (all.size() != 1) {
                failures.add("expected one cached resource, got " + all.size());
                return;
            }
            if (all.iterator().next().amount() != Long.MAX_VALUE) {
                failures.add("expected the composite cache to saturate, got "
                    + all.iterator().next().amount());
            }
        } catch (final RuntimeException e) {
            failures.add("CompositeStorageImpl.getAll threw after two large sources: " + e);
        }
    }
}
