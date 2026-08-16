package com.wraithhawit.rstweaks.storage;

import com.refinedmods.refinedstorage.api.autocrafting.Ingredient;
import com.refinedmods.refinedstorage.api.autocrafting.Pattern;
import com.refinedmods.refinedstorage.api.autocrafting.task.TaskPlan;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;

import java.util.HashSet;
import java.util.Set;

/**
 * Everything the patterns in one task consume, so a pattern can tell whether something it
 * produces is still wanted by a sibling.
 *
 * <p>Refined Storage's executor decides where a product goes with one test — is this the
 * root pattern? — and the root's outputs leave for the network immediately. That is right
 * until a cycle runs through two <em>different</em> patterns: a water bucket becomes an
 * empty bucket and 1000mB of water, and the empty bucket is what the <em>other</em>
 * pattern needs to make the next water bucket. The bucket leaves, the second iteration
 * has nothing, and the craft jams after exactly one.
 *
 * <p>A pattern cannot see its siblings — but the plan can. The set is computed once when
 * the task is built and handed to each pattern as it is constructed, which is the only
 * moment both are in scope.
 *
 * <p>Handover uses a static field rather than a parameter because the patterns are built
 * inside {@code TaskImpl}'s constructor, where no signature we control is involved. It is
 * written and cleared within that constructor on the same thread, and every reader
 * defaults to the empty set, so a missed handover costs the optimization and never
 * corrupts a task.
 */
public final class TaskConsumption {
    private static final ThreadLocal<Set<ResourceKey>> BUILDING = new ThreadLocal<>();

    private TaskConsumption() {
    }

    /** Every resource any pattern in this plan takes as an ingredient. */
    public static Set<ResourceKey> of(final TaskPlan plan) {
        final Set<ResourceKey> consumed = new HashSet<>();
        for (final Pattern pattern : plan.patterns().keySet()) {
            for (final Ingredient ingredient : pattern.layout().ingredients()) {
                consumed.addAll(ingredient.inputs());
            }
        }
        return consumed;
    }

    /** Called around {@code TaskImpl}'s constructor while its patterns are built. */
    public static void beginBuilding(final Set<ResourceKey> consumed) {
        BUILDING.set(consumed);
    }

    public static void endBuilding() {
        BUILDING.remove();
    }

    /** What the task under construction consumes, or empty when nothing is in flight. */
    public static Set<ResourceKey> current() {
        final Set<ResourceKey> consumed = BUILDING.get();
        return consumed == null ? Set.of() : consumed;
    }
}
