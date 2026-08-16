package com.wraithhawit.rstweaks.test;

import com.refinedmods.refinedstorage.api.autocrafting.Ingredient;
import com.refinedmods.refinedstorage.api.autocrafting.Pattern;
import com.refinedmods.refinedstorage.api.autocrafting.PatternLayout;
import com.refinedmods.refinedstorage.api.autocrafting.PatternRepositoryImpl;
import com.refinedmods.refinedstorage.api.autocrafting.task.TaskPlan;
import com.refinedmods.refinedstorage.api.core.Action;
import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.api.storage.Actor;
import com.refinedmods.refinedstorage.api.storage.StorageImpl;
import com.refinedmods.refinedstorage.api.storage.root.RootStorageImpl;
import com.wraithhawit.rstweaks.Config;
import com.wraithhawit.rstweaks.planner.LpCraftingPlanner;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * Times the LP planner on a durability chain, to answer "what would modelling tool
 * durability cost" with a number instead of an opinion.
 *
 * <p>The naive way to model a crystal that survives N crafts is one pattern per damage
 * step — {@code crystal(d) + materials -> product + crystal(d+1)} for every d. That adds
 * N variables and N resource classes to the program, and this measures what that does to
 * solve time as N grows.
 *
 * <p>Planning happens once per craft request, not per tick, so this is not TPS. It is the
 * delay before the request screen appears — and the Max button runs the planner
 * repeatedly, so it is multiplied there.
 */
public final class PlannerBenchmark {
    private PlannerBenchmark() {
    }

    private record R(String id) implements ResourceKey {
        @Override
        public String toString() {
            return id;
        }
    }

    private static ResourceKey res(final String id) {
        return new R(id);
    }

    public static void main(final String[] args) {
        Config.lpPlanner = true;
        Config.keepRecycledResourcesInTask = true;

        System.out.printf("%-10s %-10s %12s %12s%n", "steps", "requested", "plan?", "ms");
        for (final int steps : new int[] {1, 4, 16, 32, 64, 100, 200}) {
            run(steps, 64);
        }
        System.out.println();
        for (final int requested : new int[] {1, 16, 64, 256}) {
            run(100, requested);
        }
    }

    private static void run(final int steps, final int requested) {
        // Warm up so the figure is steady-state JIT, not first-call interpretation.
        for (int i = 0; i < 3; i++) {
            plan(steps, requested);
        }
        final long start = System.nanoTime();
        final TaskPlan plan = plan(steps, requested);
        final double ms = (System.nanoTime() - start) / 1_000_000.0;
        System.out.printf("%-10d %-10d %12s %12.2f%n",
            steps, requested, plan == null ? "declined" : plan.patterns().size() + " patterns", ms);
    }

    private static TaskPlan plan(final int steps, final int requested) {
        final PatternRepositoryImpl patterns = new PatternRepositoryImpl();
        // One pattern per damage step, which is the shape a synthesized durability chain
        // would produce: use the crystal, hand back a more damaged one.
        for (int d = 0; d < steps; d++) {
            patterns.add(pattern("use" + d,
                List.of(ing(1, "crystal" + d), ing(1, "material")),
                List.of(new ResourceAmount(res("product"), 1L)),
                List.of(new ResourceAmount(res("crystal" + (d + 1)), 1L))), 0);
        }
        // And the recipe that makes a fresh crystal once the old one is spent.
        patterns.add(pattern("newcrystal",
            List.of(ing(8, "gem")),
            List.of(new ResourceAmount(res("crystal0"), 1L)),
            List.of()), 0);

        final StorageImpl source = new StorageImpl();
        source.insert(res("crystal0"), 1L, Action.EXECUTE, ACTOR);
        source.insert(res("material"), 100_000L, Action.EXECUTE, ACTOR);
        source.insert(res("gem"), 100_000L, Action.EXECUTE, ACTOR);
        final RootStorageImpl storage = new RootStorageImpl();
        storage.addSource(source);

        return LpCraftingPlanner.tryPlan(patterns, storage, res("product"), requested);
    }

    private static final Actor ACTOR = () -> "rstweaks-benchmark";

    private static Pattern pattern(final String id,
                                   final List<Ingredient> ingredients,
                                   final List<ResourceAmount> outputs,
                                   final List<ResourceAmount> byproducts) {
        return new Pattern(
            UUID.nameUUIDFromBytes(("rstweaks-bench:" + id).getBytes(StandardCharsets.UTF_8)),
            PatternLayout.internal(ingredients, outputs, byproducts));
    }

    private static Ingredient ing(final long amount, final String input) {
        return new Ingredient(amount, List.of(res(input)));
    }
}
