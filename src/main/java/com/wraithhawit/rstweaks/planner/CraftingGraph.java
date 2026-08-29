package com.wraithhawit.rstweaks.planner;

import com.refinedmods.refinedstorage.api.autocrafting.Ingredient;
import com.refinedmods.refinedstorage.api.autocrafting.Pattern;
import com.refinedmods.refinedstorage.api.autocrafting.PatternRepository;
import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.api.storage.root.RootStorage;
import com.wraithhawit.rstweaks.ledger.ResourceIndex;
import com.wraithhawit.rstweaks.ledger.Transform;
import com.wraithhawit.rstweaks.ledger.rs.ClassPools;
import com.wraithhawit.rstweaks.ledger.rs.PatternTransforms;
import com.wraithhawit.rstweaks.ledger.rs.Remainder;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

/**
 * Extracts the crafting subgraph reachable from a target and reduces it to the
 * numbers the solver needs.
 *
 * <p>Two jobs beyond plain reachability:
 *
 * <p><b>Equivalence classes.</b> An {@link Ingredient} accepts a list of alternative
 * inputs, so a resource is not a single column unless we decide which alternative is
 * used — which the solver cannot express linearly without a variable per choice.
 * Following Nodrance's rule, two resources are merged only when <em>no ingredient
 * anywhere distinguishes them</em>: they are accepted by exactly the same set of
 * ingredient slots. Merging on "appears together somewhere" would be wrong — oak and
 * birch might be interchangeable in one recipe and not in another, and substituting
 * would produce a plan that cannot execute.
 *
 * <p><b>Targeting.</b> Reports whether the subgraph contains byproducts or a cycle.
 * Only those cases need the LP planner; everything else stays on stock Refined
 * Storage, which keeps the blast radius small.
 */
public final class CraftingGraph {
    private CraftingGraph() {
    }

    /**
     * One resource class: the merged resources, what the network holds of each, and
     * the pooled total. Per-resource amounts are kept so the emitted plan can name a
     * concrete resource the network actually has, rather than guessing a member.
     */
    public record ResourceClass(int index,
                                List<ResourceKey> members,
                                Map<ResourceKey, Long> availableByResource,
                                long available,
                                boolean tool) {
        /**
         * A tool class counts <em>uses</em>, not items: {@code available} is how many
         * crafts the stored tools have left between them, while
         * {@code availableByResource} still counts real items so the emitted plan can
         * name something the network actually holds. Anywhere those two units meet has
         * to convert; see {@code PlanMatrix.initialRequirements}.
         */
        public boolean tool() {
            return this.tool;
        }
    }

    /** A pattern's per-iteration effect, in class indices. */
    public record PatternEffect(int patternIndex,
                                Pattern pattern,
                                Map<Integer, Long> consumedByClass,
                                Map<Integer, Long> producedByClass,
                                List<IngredientSlot> slots) {
    }

    /** One ingredient slot, retained so the emitted plan can name concrete resources. */
    public record IngredientSlot(int ingredientIndex, int classIndex, long amountPerIteration) {
    }

    public record Graph(List<Pattern> patterns,
                        List<PatternEffect> effects,
                        List<ResourceClass> classes,
                        Map<ResourceKey, Integer> classOf,
                        int targetClass,
                        boolean hasByproducts,
                        boolean hasCycle,
                        boolean truncated) {
        /** Whether this subgraph is one the LP planner should take over. */
        public boolean needsLpPlanner() {
            return !this.truncated && (this.hasByproducts || this.hasCycle);
        }

        /**
         * Roughly how many base items one of {@code resource} expands into.
         *
         * <p>This is the number that decides whether Refined Storage's own calculator can finish.
         * {@code CraftingTree.calculateIngredient} is:
         *
         * <pre>{@code   long remaining = ingredientState.amount() * this.amount.iterations();
         *   while (remaining > 0L) { ... } }</pre>
         *
         * <p>— a loop that runs <b>once per expanded item</b>. Three hundred and twenty
         * nine-times-compressed blocks is 9<sup>9</sup> each, so that loop counts to about
         * 1.2×10<sup>11</sup> and the request dies on its five-second timeout, on the server
         * thread. The solver is O(patterns) and does not care what the amount is, which is the
         * whole argument for handing it these.
         *
         * <p>Saturating rather than exact: past {@link #EXPANSION_CAP} the only question anyone is
         * asking is "far too many", and an exact answer would overflow long before it was useful.
         */
        public long expansionOf(final ResourceKey resource) {
            return expansion(resource, new HashMap<>(), new HashSet<>());
        }

        private long expansion(final ResourceKey resource,
                               final Map<ResourceKey, Long> memo,
                               final Set<ResourceKey> visiting) {
            final Long cached = memo.get(resource);
            if (cached != null) {
                return cached;
            }
            if (!visiting.add(resource)) {
                // A cycle, which this estimate cannot describe. Those already reach the planner
                // on their own, so answering "one" here costs nothing.
                return 1L;
            }
            long result = 1L;
            final Pattern producer = producerOf(resource);
            if (producer != null) {
                long total = 0L;
                for (final Ingredient ingredient : producer.layout().ingredients()) {
                    final long each = expansion(ingredient.inputs().getFirst(), memo, visiting);
                    total = saturatingAdd(total, saturatingMultiply(ingredient.amount(), each));
                }
                final long perCraft = outputAmount(producer, resource);
                result = perCraft <= 0L ? total : Math.max(1L, total / perCraft);
            }
            visiting.remove(resource);
            memo.put(resource, result);
            return result;
        }

        @Nullable
        private Pattern producerOf(final ResourceKey resource) {
            for (final Pattern pattern : this.patterns) {
                for (final ResourceAmount output : pattern.layout().outputs()) {
                    if (output.resource().equals(resource)) {
                        return pattern;
                    }
                }
            }
            return null;
        }

        private static long outputAmount(final Pattern pattern, final ResourceKey resource) {
            for (final ResourceAmount output : pattern.layout().outputs()) {
                if (output.resource().equals(resource)) {
                    return output.amount();
                }
            }
            return 0L;
        }
    }

    /** Past this the answer is "far too many", and an exact one would overflow. */
    public static final long EXPANSION_CAP = 1_000_000_000_000_000L;

    public static long saturatingMultiply(final long a, final long b) {
        if (a == 0L || b == 0L) {
            return 0L;
        }
        if (a > EXPANSION_CAP / b) {
            return EXPANSION_CAP;
        }
        return Math.min(EXPANSION_CAP, a * b);
    }

    private static long saturatingAdd(final long a, final long b) {
        return Math.min(EXPANSION_CAP, a + b);
    }

    public static Graph build(final PatternRepository repository,
                              final RootStorage rootStorage,
                              final ResourceKey target,
                              final int maxPatterns) {
        final List<Pattern> patterns = new ArrayList<>();
        final Set<Pattern> seen = new HashSet<>();
        final Set<ResourceKey> resources = new LinkedHashSet<>();
        resources.add(target);

        boolean truncated = false;
        boolean hasByproducts = false;

        final Deque<ResourceKey> queue = new ArrayDeque<>();
        queue.add(target);
        final Set<ResourceKey> expanded = new HashSet<>();

        while (!queue.isEmpty()) {
            final ResourceKey resource = queue.poll();
            if (!expanded.add(resource)) {
                continue;
            }
            final Collection<Pattern> producers = repository.getByOutput(resource);
            for (final Pattern pattern : producers) {
                if (!seen.add(pattern)) {
                    continue;
                }
                if (patterns.size() >= maxPatterns) {
                    truncated = true;
                    break;
                }
                patterns.add(pattern);
                if (!pattern.layout().byproducts().isEmpty()) {
                    hasByproducts = true;
                }
                for (final ResourceAmount output : pattern.layout().outputs()) {
                    if (resources.add(output.resource())) {
                        queue.add(output.resource());
                    }
                }
                for (final ResourceAmount byproduct : pattern.layout().byproducts()) {
                    if (resources.add(byproduct.resource())) {
                        queue.add(byproduct.resource());
                    }
                }
                for (final Ingredient ingredient : pattern.layout().ingredients()) {
                    for (final ResourceKey input : ingredient.inputs()) {
                        if (resources.add(input)) {
                            queue.add(input);
                        }
                    }
                }
            }
            if (truncated) {
                break;
            }
        }

        // Gated, not merely configurable: a wear-aware plan requisitions one crystal for
        // sixty-four crafts, which only runs if the executor also substitutes wear levels
        // and recomputes what the recipe hands back. Until that half exists, planning
        // this way would produce a task that stalls after one iteration.
        final Durability durability = com.wraithhawit.rstweaks.Config.durabilityAwarePlanning
            ? Durability.Holder.get()
            : Durability.NONE;
        addWornVariantsFromStorage(resources, rootStorage, durability);
        final DurabilityClasses.Result merged = DurabilityClasses.merge(
            buildClasses(patterns, resources), patterns, durability);
        final Map<ResourceKey, Integer> classOf = merged.classOf();
        final List<ResourceClass> classes = materialiseClasses(
            classOf, rootStorage, merged.toolClasses(), durability);
        final List<PatternEffect> effects = buildEffectsViaLedger(
            patterns, classOf, merged.toolClasses(), durability);
        final boolean hasCycle = detectCycle(effects);

        return new Graph(
            patterns,
            effects,
            classes,
            classOf,
            classOf.getOrDefault(target, -1),
            hasByproducts,
            hasCycle,
            truncated
        );
    }

    /**
     * Merges resources that no ingredient distinguishes, by giving each resource a
     * signature of the ingredient slots that accept it and grouping identical
     * signatures. A resource accepted by no ingredient (a pure output, like the
     * target) gets its own class via its unique identity in the signature map.
     */
    /**
     * Pulls the tool you actually own into the graph.
     *
     * <p>Everything else here is discovered by walking patterns, which is enough because
     * a pattern names the resources it touches. A worn tool is the exception: the pattern
     * was encoded with {@code crystal@0} and hands back {@code crystal@1}, so those are
     * the only two wear levels the graph would ever hear about — and the crystal in your
     * network is at damage 95. Without this, a partly worn tool is invisible and the
     * planner reports that you have none.
     *
     * <p>Enumerating storage is not cheap on a large network, so it happens only when the
     * pattern graph contains a durable item at all, which is rare.
     */
    private static void addWornVariantsFromStorage(final Set<ResourceKey> resources,
                                                   final RootStorage rootStorage,
                                                   final Durability durability) {
        final List<ResourceKey> durable = new ArrayList<>();
        for (final ResourceKey resource : resources) {
            if (durability.isDurable(resource)) {
                durable.add(resource);
            }
        }
        if (durable.isEmpty()) {
            return;
        }
        final List<ResourceKey> found = new ArrayList<>();
        for (final ResourceAmount stored : rootStorage.getAll()) {
            for (final ResourceKey known : durable) {
                if (durability.sameTool(known, stored.resource())) {
                    found.add(stored.resource());
                    break;
                }
            }
        }
        resources.addAll(found);
    }

    private static Map<ResourceKey, Integer> buildClasses(final List<Pattern> patterns,
                                                          final Set<ResourceKey> resources) {
        final Map<ResourceKey, Set<String>> signatures = new HashMap<>();
        for (final ResourceKey resource : resources) {
            signatures.put(resource, new LinkedHashSet<>());
        }
        for (int p = 0; p < patterns.size(); p++) {
            final List<Ingredient> ingredients = patterns.get(p).layout().ingredients();
            for (int i = 0; i < ingredients.size(); i++) {
                final String slot = p + ":" + i;
                for (final ResourceKey input : ingredients.get(i).inputs()) {
                    final Set<String> signature = signatures.get(input);
                    if (signature != null) {
                        signature.add(slot);
                    }
                }
            }
        }

        final Map<String, Integer> byKey = new LinkedHashMap<>();
        final Map<ResourceKey, Integer> classOf = new LinkedHashMap<>();
        for (final ResourceKey resource : resources) {
            final Set<String> signature = signatures.get(resource);
            // An empty signature means nothing consumes it; those must stay distinct,
            // so key on identity rather than collapsing every terminal output together.
            final String key = signature.isEmpty()
                ? "solo:" + System.identityHashCode(resource) + ":" + resource
                : "sig:" + signature;
            classOf.put(resource, byKey.computeIfAbsent(key, k -> byKey.size()));
        }
        return classOf;
    }

    private static List<ResourceClass> materialiseClasses(final Map<ResourceKey, Integer> classOf,
                                                          final RootStorage rootStorage,
                                                          final Set<Integer> toolClasses,
                                                          final Durability durability) {
        final Map<Integer, List<ResourceKey>> members = new LinkedHashMap<>();
        classOf.forEach((resource, index) ->
            members.computeIfAbsent(index, k -> new ArrayList<>()).add(resource));

        final List<ResourceClass> classes = new ArrayList<>(members.size());
        for (int i = 0; i < members.size(); i++) {
            final List<ResourceKey> group = members.getOrDefault(i, List.of());
            final boolean tool = toolClasses.contains(i);
            final Map<ResourceKey, Long> perResource = new LinkedHashMap<>();
            long available = 0L;
            for (final ResourceKey resource : group) {
                final long held = rootStorage.get(resource);
                if (held > 0L) {
                    perResource.put(resource, held);
                }
                // A tool class is denominated in crafts remaining, so three crystals at
                // ninety damage out of a hundred are thirty uses, not three of anything.
                available += tool
                    ? DurabilityClasses.usesOf(resource, held, durability)
                    : held;
            }
            classes.add(new ResourceClass(i, group, perResource, available, tool));
        }
        return classes;
    }

    /**
     * The same per-iteration effects, derived from the ledger model instead of by hand.
     *
     * <p>{@link #buildEffects} below reads a pattern's three flat lists and applies four separate
     * rules to them: an ingredient is consumption, an output is production, a byproduct is
     * production <em>unless</em> its class is a tool, and a tool ingredient costs a wear step read
     * off the pattern. Here there is one rule — a slot has a fate — and all four fall out of
     * subtraction. See {@link Slot}.
     *
     * <p><b>Gross for ordinary columns, net for pooled ones</b>, and the split is not arbitrary.
     * The gross figures are what working capital needs: a catalyst is consumed and produced in
     * equal measure, which nets to nothing but still means you must <em>own</em> one before the
     * craft can run, and that is how it reaches {@code initialRequirements}. Net is what a pool
     * needs: a tool column is denominated in crafts remaining, so a half-worn crystal can still run
     * the recipe, and charging the gross thousand would say it cannot. The hand-written version
     * below encodes exactly this distinction by skipping the tool byproduct.
     */
    static List<PatternEffect> buildEffectsViaLedger(final List<Pattern> patterns,
                                                     final Map<ResourceKey, Integer> classOf,
                                                     final Set<Integer> toolClasses,
                                                     final Durability durability) {
        final ResourceIndex index = new ResourceIndex();
        final ClassPools pools = ClassPools.build(index, classOf, toolClasses, durability);
        final Remainder remainder = Remainder.Holder.get();

        final List<PatternEffect> effects = new ArrayList<>(patterns.size());
        for (int p = 0; p < patterns.size(); p++) {
            final Pattern pattern = patterns.get(p);
            final Transform transform =
                PatternTransforms.build(pattern, index, durability, remainder).transform();

            final Map<Integer, Long> grossConsumed = transform.consumed(pools);
            final Map<Integer, Long> grossProduced = transform.produced(pools);
            final Map<Integer, Long> net = transform.net(pools);

            final Map<Integer, Long> consumed = new LinkedHashMap<>();
            final Map<Integer, Long> produced = new LinkedHashMap<>();
            final Set<Integer> columns = new LinkedHashSet<>(grossConsumed.keySet());
            columns.addAll(grossProduced.keySet());
            for (final int column : columns) {
                final int cls = pools.classOfColumn(column);
                if (cls < 0) {
                    // A resource the graph never classified: outside the subgraph, and outside
                    // the program. The hand-written version drops these the same way.
                    continue;
                }
                if (pools.isToolColumn(column)) {
                    final long remaining = net.getOrDefault(column, 0L);
                    if (remaining < 0L) {
                        consumed.merge(cls, -remaining, Math::addExact);
                    } else if (remaining > 0L) {
                        produced.merge(cls, remaining, Math::addExact);
                    }
                    continue;
                }
                final long takes = grossConsumed.getOrDefault(column, 0L);
                final long gives = grossProduced.getOrDefault(column, 0L);
                if (takes > 0L) {
                    consumed.merge(cls, takes, Math::addExact);
                }
                if (gives > 0L) {
                    produced.merge(cls, gives, Math::addExact);
                }
            }

            effects.add(new PatternEffect(p, pattern, consumed, produced,
                slotsOf(pattern, classOf)));
        }
        return effects;
    }

    /** The emitted plan still names concrete resources per ingredient slot; unchanged by the model. */
    private static List<IngredientSlot> slotsOf(final Pattern pattern,
                                                final Map<ResourceKey, Integer> classOf) {
        final List<IngredientSlot> slots = new ArrayList<>();
        final List<Ingredient> ingredients = pattern.layout().ingredients();
        for (int i = 0; i < ingredients.size(); i++) {
            final Ingredient ingredient = ingredients.get(i);
            final Integer cls = classOf.get(ingredient.inputs().getFirst());
            if (cls != null) {
                slots.add(new IngredientSlot(i, cls, ingredient.amount()));
            }
        }
        return slots;
    }

    /**
     * A cycle here means some resource class is both consumed and produced within the
     * subgraph reachable from itself — exactly the bucket case, and exactly what a
     * depth-first tree planner cannot represent.
     */
    private static boolean detectCycle(final List<PatternEffect> effects) {
        // Edge class -> class when one pattern consumes the first and produces the second.
        final Map<Integer, Set<Integer>> edges = new HashMap<>();
        for (final PatternEffect effect : effects) {
            for (final Integer from : effect.consumedByClass().keySet()) {
                edges.computeIfAbsent(from, k -> new HashSet<>()).addAll(effect.producedByClass().keySet());
            }
        }
        final Set<Integer> visiting = new HashSet<>();
        final Set<Integer> done = new HashSet<>();
        for (final Integer node : edges.keySet()) {
            if (hasCycleFrom(node, edges, visiting, done)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasCycleFrom(final Integer node,
                                        final Map<Integer, Set<Integer>> edges,
                                        final Set<Integer> visiting,
                                        final Set<Integer> done) {
        if (done.contains(node)) {
            return false;
        }
        if (!visiting.add(node)) {
            return true;
        }
        for (final Integer next : edges.getOrDefault(node, Set.of())) {
            if (hasCycleFrom(next, edges, visiting, done)) {
                return true;
            }
        }
        visiting.remove(node);
        done.add(node);
        return false;
    }
}
