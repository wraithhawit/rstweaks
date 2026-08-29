package com.wraithhawit.rstweaks.test;

import com.refinedmods.refinedstorage.api.autocrafting.Ingredient;
import com.refinedmods.refinedstorage.api.autocrafting.Pattern;
import com.refinedmods.refinedstorage.api.autocrafting.PatternLayout;
import com.refinedmods.refinedstorage.api.autocrafting.PatternRepositoryImpl;
import com.refinedmods.refinedstorage.api.core.Action;
import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.api.storage.Actor;
import com.refinedmods.refinedstorage.api.storage.StorageImpl;
import com.refinedmods.refinedstorage.api.storage.root.RootStorageImpl;
import com.wraithhawit.rstweaks.Config;
import com.wraithhawit.rstweaks.planner.CraftingGraph;
import com.wraithhawit.rstweaks.planner.LpCraftingPlanner;
import com.wraithhawit.rstweaks.planner.PlanMatrix;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * Can the solver do a compression chain, and how fast?
 *
 * <p>Refined Storage's depth-first calculator cannot: {@code allthecompressed:netherrack_9x} at 320
 * gave "this request took too long to calculate, and was cancelled" in game on 2026-08-29. Our LP
 * planner never saw it, because {@code CraftingGraph.needsLpPlanner} gates it to subgraphs with
 * byproducts or cycles and a compression chain has neither.
 *
 * <p>That gate is a scope decision, not a limit of the solver — but "the solver would have been
 * fine" is a claim, and this measures it instead. It builds the graph and calls
 * {@link PlanMatrix#solve} directly, which is what the gate stands in front of.
 *
 * <p>Run: {@code ./gradlew compressionProbe}.
 */
public final class CompressionProbe {
    private static final Actor ACTOR = () -> "rstweaks-probe";

    private CompressionProbe() {
    }

    private record R(String id) implements ResourceKey {
        @Override
        public String toString() {
            return this.id;
        }
    }

    public static void main(final String[] args) {
        Config.lpPlanner = true;
        Config.keepRecycledResourcesInTask = true;

        System.out.printf("%-8s %-10s %-14s %14s %10s%n",
            "tiers", "requested", "base stock", "outcome", "ms");
        // Nine tiers of nine is what "_9x" means, and 320 of them is the request that failed.
        for (final int tiers : new int[] {1, 3, 5, 7, 9}) {
            probe(tiers, 320, true);
        }
        System.out.println();
        // The realistic case: nobody has 124 billion netherrack. The tree cannot even tell you
        // that; the question is whether the solver says so, and how fast.
        for (final int tiers : new int[] {5, 7, 9}) {
            probe(tiers, 320, false);
        }
        System.out.println();
        // And the claim the whole model rests on: the amount is a right-hand side, not a
        // dimension of the problem. If that holds, these should all cost the same.
        for (final int requested : new int[] {1, 320, 1_000_000}) {
            probe(9, requested, true);
        }

        // The gate, end to end. Everything above bypasses it by calling the solver directly;
        // this asks the question the game asks, so it says whether the planner would actually
        // have taken the request that timed out.
        System.out.printf("%n%-8s %-10s %-14s %14s%n", "tiers", "requested", "expands to", "gate");
        for (final int[] shape : new int[][] {{1, 64}, {3, 64}, {5, 320}, {9, 320}, {9, 1}}) {
            gate(shape[0], shape[1]);
        }
    }

    private static void gate(final int tiers, final long requested) {
        final PatternRepositoryImpl patterns = repository(tiers);
        final RootStorageImpl storage = stock(tiers, requested, true);
        final ResourceKey target = res("t" + tiers);
        final CraftingGraph.Graph graph = CraftingGraph.build(patterns, storage, target, 4096);
        final long expansion =
            CraftingGraph.saturatingMultiply(requested, graph.expansionOf(target));
        final boolean taken = LpCraftingPlanner.tryPlan(patterns, storage, target, requested) != null;
        System.out.printf("%-8d %-10d %-14d %14s%n",
            tiers, requested, expansion, taken ? "TAKEN" : "left to RS");
    }

    private static void probe(final int tiers, final long requested, final boolean enoughStock) {
        for (int i = 0; i < 3; i++) {
            solve(tiers, requested, enoughStock);
        }
        final long start = System.nanoTime();
        final String outcome = solve(tiers, requested, enoughStock);
        final double ms = (System.nanoTime() - start) / 1_000_000.0;
        System.out.printf("%-8d %-10d %-14s %14s %10.2f%n",
            tiers, requested, enoughStock ? "plenty" : "none", outcome, ms);
    }

    private static PatternRepositoryImpl repository(final int tiers) {
        final PatternRepositoryImpl patterns = new PatternRepositoryImpl();
        for (int tier = 1; tier <= tiers; tier++) {
            patterns.add(pattern("compress" + tier,
                List.of(new Ingredient(9L, List.of(res("t" + (tier - 1))))),
                List.of(new ResourceAmount(res("t" + tier), 1L))), 0);
        }
        return patterns;
    }

    private static RootStorageImpl stock(final int tiers,
                                         final long requested,
                                         final boolean enoughStock) {
        final StorageImpl source = new StorageImpl();
        if (enoughStock) {
            // 9^tiers per item requested, which is the whole point of the shape.
            long needed = requested;
            for (int tier = 0; tier < tiers; tier++) {
                needed = Math.multiplyExact(needed, 9L);
            }
            source.insert(res("t0"), needed, Action.EXECUTE, ACTOR);
        }
        final RootStorageImpl storage = new RootStorageImpl();
        storage.addSource(source);
        return storage;
    }

    private static String solve(final int tiers, final long requested, final boolean enoughStock) {
        final PatternRepositoryImpl patterns = repository(tiers);
        final RootStorageImpl storage = stock(tiers, requested, enoughStock);

        final CraftingGraph.Graph graph = CraftingGraph.build(
            patterns, storage, res("t" + tiers), 4096);
        final PlanMatrix.Outcome outcome = PlanMatrix.solve(
            graph, res("t" + tiers), requested, 5000, 2000, 64, 8, 20_000);
        if (outcome.plan() != null) {
            return outcome.plan().patterns().size() + " patterns";
        }
        final String failure = outcome.failure();
        return failure.length() > 14 ? failure.substring(0, 14) : failure;
    }

    private static ResourceKey res(final String id) {
        return new R(id);
    }

    private static Pattern pattern(final String id,
                                   final List<Ingredient> ingredients,
                                   final List<ResourceAmount> outputs) {
        return new Pattern(
            UUID.nameUUIDFromBytes(("rstweaks-probe:" + id).getBytes(StandardCharsets.UTF_8)),
            PatternLayout.internal(ingredients, outputs, List.of()));
    }
}
