package com.wraithhawit.rstweaks.test;

import com.refinedmods.refinedstorage.api.autocrafting.Ingredient;
import com.refinedmods.refinedstorage.api.autocrafting.Pattern;
import com.refinedmods.refinedstorage.api.autocrafting.PatternLayout;
import com.refinedmods.refinedstorage.api.autocrafting.PatternRepositoryImpl;
import com.refinedmods.refinedstorage.api.autocrafting.calculation.CancellationToken;
import com.refinedmods.refinedstorage.api.autocrafting.calculation.CraftingCalculatorImpl;
import com.refinedmods.refinedstorage.api.autocrafting.task.TaskPlan;
import com.refinedmods.refinedstorage.api.autocrafting.task.TaskPlanCraftingCalculatorListener;
import com.refinedmods.refinedstorage.api.core.Action;
import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.api.storage.Actor;
import com.refinedmods.refinedstorage.api.storage.StorageImpl;
import com.refinedmods.refinedstorage.api.storage.root.RootStorageImpl;
import com.wraithhawit.rstweaks.Config;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Differential test for the copy-on-write pattern-plan optimization.
 *
 * <p>Rather than asserting hand-computed expected values — which would only ever
 * test the recipes the author thought of — this runs the same crafting calculation
 * twice, once with {@code lazyPatternPlanCopy} enabled and once with it disabled,
 * and asserts the resulting {@link TaskPlan}s are identical. That tests the actual
 * invariant we care about: <b>the optimization must not change the result</b>.
 *
 * <p>The scenarios are chosen to hit the shapes where sharing a mutable map would
 * go wrong — a shared intermediate reached down two branches, a diamond where the
 * same sub-recipe is required twice, one resource consumed at several ingredient
 * indices, and partial availability forcing the calculator to backtrack. A naive
 * sharing bug shows up as an ingredient amount that is doubled, halved, or missing.
 *
 * <p>Needs no world, no network and no blocks: everything below is plain records
 * from the RS API, so this can run from a command, a gametest, or anywhere else the
 * mod is loaded with its mixins applied.
 */
public final class CraftingPlanSelfTest {
    private static final Actor ACTOR = () -> "rstweaks-selftest";

    private CraftingPlanSelfTest() {
    }

    /** A synthetic resource. RS's {@link ResourceKey} is an empty marker interface. */
    private record TestResource(String id) implements ResourceKey {
        @Override
        public String toString() {
            return this.id;
        }
    }

    public record Result(int scenarios, List<String> failures) {
        public boolean passed() {
            return this.failures.isEmpty();
        }
    }

    /**
     * @param expectPlan whether this scenario should yield a plan at all. Asserted so
     *                   that a broken fixture fails loudly instead of comparing two
     *                   empty results and reporting a vacuous pass — which is exactly
     *                   what happened the first time these fixtures were written.
     */
    private record Scenario(String name,
                            Consumer<PatternRepositoryImpl> patterns,
                            Map<String, Long> stored,
                            String request,
                            long amount,
                            boolean expectPlan) {
    }

    public static Result run() {
        final List<String> failures = new ArrayList<>();
        final List<Scenario> scenarios = scenarios();
        // Restore whatever the server was actually configured with, so running the
        // test never silently leaves the optimization in the wrong state.
        final boolean original = Config.lazyPatternPlanCopy;
        try {
            for (final Scenario scenario : scenarios) {
                try {
                    final Optional<TaskPlan> optimized = calculate(scenario, true);
                    final Optional<TaskPlan> reference = calculate(scenario, false);
                    if (optimized.isPresent() != scenario.expectPlan()) {
                        failures.add(scenario.name() + ": fixture is broken -- expected "
                            + (scenario.expectPlan() ? "a plan" : "no plan")
                            + " but got the opposite. Comparing two empty results would"
                            + " report a pass without testing anything.");
                        continue;
                    }
                    compare(scenario.name(), optimized, reference, failures);
                } catch (final RuntimeException e) {
                    failures.add(scenario.name() + ": threw " + e);
                }
            }
        } finally {
            Config.lazyPatternPlanCopy = original;
        }
        return new Result(scenarios.size(), failures);
    }

    private static Optional<TaskPlan> calculate(final Scenario scenario, final boolean optimized) {
        Config.lazyPatternPlanCopy = optimized;

        final PatternRepositoryImpl patterns = new PatternRepositoryImpl();
        scenario.patterns().accept(patterns);

        // Order matters: RootStorageImpl snapshots a source's contents when it is
        // added, and later inserts into that source do not propagate. Adding first
        // and inserting after leaves the root storage empty, which makes every
        // calculation return no plan.
        final StorageImpl source = new StorageImpl();
        scenario.stored().forEach((id, amount) ->
            source.insert(res(id), amount, Action.EXECUTE, ACTOR));
        final RootStorageImpl storage = new RootStorageImpl();
        storage.addSource(source);

        return TaskPlanCraftingCalculatorListener.calculatePlan(
            new CraftingCalculatorImpl(patterns, storage),
            res(scenario.request()),
            scenario.amount(),
            CancellationToken.NONE
        );
    }

    private static void compare(final String name,
                                final Optional<TaskPlan> optimized,
                                final Optional<TaskPlan> reference,
                                final List<String> failures) {
        if (optimized.isPresent() != reference.isPresent()) {
            failures.add(name + ": plan presence differs (optimized="
                + optimized.isPresent() + ", reference=" + reference.isPresent() + ")");
            return;
        }
        if (optimized.isEmpty()) {
            return;
        }
        final TaskPlan a = optimized.get();
        final TaskPlan b = reference.get();

        if (!a.resource().equals(b.resource()) || a.amount() != b.amount()) {
            failures.add(name + ": root differs -- " + a.resource() + " x" + a.amount()
                + " vs " + b.resource() + " x" + b.amount());
        }
        if (!a.rootPattern().equals(b.rootPattern())) {
            failures.add(name + ": root pattern differs");
        }
        if (!a.patterns().keySet().equals(b.patterns().keySet())) {
            failures.add(name + ": pattern set differs (" + a.patterns().size()
                + " vs " + b.patterns().size() + ")");
            return;
        }
        for (final Pattern pattern : a.patterns().keySet()) {
            final TaskPlan.PatternPlan pa = a.getPattern(pattern);
            final TaskPlan.PatternPlan pb = b.getPattern(pattern);
            final String out = pattern.layout().outputs().getFirst().resource().toString();
            if (pa.iterations() != pb.iterations()) {
                failures.add(name + ": iterations differ for " + out
                    + " -- " + pa.iterations() + " vs " + pb.iterations());
            }
            if (pa.root() != pb.root()) {
                failures.add(name + ": root flag differs for " + out);
            }
            // The heart of it: a sharing bug shows up here as a doubled, halved or
            // missing ingredient amount.
            if (!ingredientsOf(pa).equals(ingredientsOf(pb))) {
                failures.add(name + ": INGREDIENTS DIFFER for " + out
                    + "\n      optimized=" + ingredientsOf(pa)
                    + "\n      reference=" + ingredientsOf(pb));
            }
        }
        final String reqA = normalize(a.initialRequirements());
        final String reqB = normalize(b.initialRequirements());
        if (!reqA.equals(reqB)) {
            failures.add(name + ": initial requirements differ -- " + reqA + " vs " + reqB);
        }
    }

    /** Stable string form so ordering differences never masquerade as failures. */
    private static Map<Integer, Map<String, Long>> ingredientsOf(final TaskPlan.PatternPlan plan) {
        final Map<Integer, Map<String, Long>> out = new LinkedHashMap<>();
        plan.ingredients().entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> {
                final Map<String, Long> inner = new LinkedHashMap<>();
                entry.getValue().entrySet().stream()
                    .sorted(Comparator.comparing(e -> e.getKey().toString()))
                    .forEach(e -> inner.put(e.getKey().toString(), e.getValue()));
                out.put(entry.getKey(), inner);
            });
        return out;
    }

    private static String normalize(final Iterable<ResourceAmount> requirements) {
        final List<String> parts = new ArrayList<>();
        requirements.forEach(r -> parts.add(r.resource() + "x" + r.amount()));
        parts.sort(Comparator.naturalOrder());
        return parts.toString();
    }

    // ---------------------------------------------------------------- fixtures

    private static ResourceKey res(final String id) {
        return new TestResource(id);
    }

    /** Deterministic ids, so both runs build byte-identical pattern graphs. */
    private static Pattern recipe(final String output, final long outputAmount,
                                  final List<Ingredient> ingredients) {
        return new Pattern(
            UUID.nameUUIDFromBytes(("rstweaks:" + output).getBytes(StandardCharsets.UTF_8)),
            PatternLayout.internal(
                ingredients,
                List.of(new ResourceAmount(res(output), outputAmount)),
                List.of()
            )
        );
    }

    private static Ingredient ing(final long amount, final String... inputs) {
        final List<ResourceKey> keys = new ArrayList<>(inputs.length);
        for (final String input : inputs) {
            keys.add(res(input));
        }
        return new Ingredient(amount, keys);
    }

    private static List<Scenario> scenarios() {
        final List<Scenario> out = new ArrayList<>();

        out.add(new Scenario("linear chain, depth 5", repo -> {
            repo.add(recipe("e", 1, List.of(ing(1, "d"))), 0);
            repo.add(recipe("d", 1, List.of(ing(1, "c"))), 0);
            repo.add(recipe("c", 1, List.of(ing(1, "b"))), 0);
            repo.add(recipe("b", 1, List.of(ing(1, "a"))), 0);
        }, Map.of("a", 64L), "e", 8L, true));

        out.add(new Scenario("shared intermediate down two branches", repo -> {
            repo.add(recipe("top", 1, List.of(ing(1, "left"), ing(1, "right"))), 0);
            repo.add(recipe("left", 1, List.of(ing(2, "shared"))), 0);
            repo.add(recipe("right", 1, List.of(ing(3, "shared"))), 0);
            repo.add(recipe("shared", 1, List.of(ing(1, "base"))), 0);
        }, Map.of("base", 256L), "top", 6L, true));

        out.add(new Scenario("diamond, same sub-recipe twice", repo -> {
            repo.add(recipe("apex", 1, List.of(ing(1, "mid1"), ing(1, "mid2"))), 0);
            repo.add(recipe("mid1", 1, List.of(ing(1, "bottom"))), 0);
            repo.add(recipe("mid2", 1, List.of(ing(1, "bottom"))), 0);
            repo.add(recipe("bottom", 4, List.of(ing(1, "ore"))), 0);
        }, Map.of("ore", 128L), "apex", 7L, true));

        out.add(new Scenario("one resource at several ingredient indices", repo -> {
            repo.add(recipe("multi", 1,
                List.of(ing(1, "common"), ing(2, "common"), ing(3, "common"))), 0);
            repo.add(recipe("common", 2, List.of(ing(1, "raw"))), 0);
        }, Map.of("raw", 512L), "multi", 5L, true));

        out.add(new Scenario("wide recipe, six distinct indices", repo -> {
            repo.add(recipe("wide", 1, List.of(
                ing(1, "i1"), ing(1, "i2"), ing(1, "i3"),
                ing(1, "i4"), ing(1, "i5"), ing(1, "i6"))), 0);
            for (int i = 1; i <= 6; i++) {
                repo.add(recipe("i" + i, 1, List.of(ing(1, "stock"))), 0);
            }
        }, Map.of("stock", 512L), "wide", 9L, true));

        out.add(new Scenario("partial availability forces backtracking", repo -> {
            repo.add(recipe("assembly", 1, List.of(ing(2, "partA"), ing(2, "partB"))), 0);
            repo.add(recipe("partA", 1, List.of(ing(1, "metal"))), 0);
            repo.add(recipe("partB", 1, List.of(ing(1, "metal"))), 0);
            repo.add(recipe("metal", 3, List.of(ing(1, "raw"))), 0);
        }, Map.of("raw", 64L, "metal", 5L, "partA", 3L), "assembly", 11L, true));

        out.add(new Scenario("large batch, deep tree", repo -> {
            repo.add(recipe("l4", 1, List.of(ing(4, "l3"))), 0);
            repo.add(recipe("l3", 1, List.of(ing(4, "l2"))), 0);
            repo.add(recipe("l2", 1, List.of(ing(4, "l1"))), 0);
            repo.add(recipe("l1", 1, List.of(ing(4, "l0"))), 0);
        }, Map.of("l0", 100_000L), "l4", 64L, true));

        out.add(new Scenario("alternative inputs on one ingredient", repo -> {
            repo.add(recipe("alloy", 1, List.of(ing(1, "tin", "zinc"), ing(1, "copper"))), 0);
            repo.add(recipe("copper", 2, List.of(ing(1, "copperOre"))), 0);
        }, Map.of("zinc", 32L, "copperOre", 32L), "alloy", 4L, true));

        out.add(new Scenario("unsatisfiable request", repo -> {
            repo.add(recipe("impossible", 1, List.of(ing(1, "missing"))), 0);
        }, Map.of("somethingElse", 64L), "impossible", 1L, false));

        out.add(new Scenario("byproduct-free chain with uneven outputs", repo -> {
            repo.add(recipe("gear", 1, List.of(ing(5, "plate"))), 0);
            repo.add(recipe("plate", 3, List.of(ing(2, "ingot"))), 0);
            repo.add(recipe("ingot", 7, List.of(ing(1, "dust"))), 0);
        }, Map.of("dust", 4096L), "gear", 13L, true));

        return out;
    }
}
