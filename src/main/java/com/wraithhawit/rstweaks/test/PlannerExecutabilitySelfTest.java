package com.wraithhawit.rstweaks.test;

import com.refinedmods.refinedstorage.api.autocrafting.Ingredient;
import com.refinedmods.refinedstorage.api.autocrafting.Pattern;
import com.refinedmods.refinedstorage.api.autocrafting.PatternLayout;
import com.refinedmods.refinedstorage.api.autocrafting.PatternRepositoryImpl;
import com.refinedmods.refinedstorage.api.autocrafting.preview.Preview;
import com.refinedmods.refinedstorage.api.autocrafting.preview.PreviewItem;
import com.refinedmods.refinedstorage.api.autocrafting.preview.PreviewType;
import com.refinedmods.refinedstorage.api.autocrafting.task.TaskPlan;
import com.refinedmods.refinedstorage.api.core.Action;
import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.api.storage.Actor;
import com.refinedmods.refinedstorage.api.storage.StorageImpl;
import com.refinedmods.refinedstorage.api.storage.root.RootStorageImpl;
import com.wraithhawit.rstweaks.Config;
import com.wraithhawit.rstweaks.planner.Durability;
import com.wraithhawit.rstweaks.planner.LpCraftingPlanner;
import com.wraithhawit.rstweaks.planner.PlanPreview;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import javax.annotation.Nullable;

/**
 * Checks that a plan the LP planner emits can actually be <em>run</em>.
 *
 * <p>Solving and executing are different claims, and the planner shipped for weeks
 * satisfying only the first. Refined Storage steps patterns against the task's internal
 * storage, which is filled once from {@link TaskPlan#initialRequirements()} and never
 * topped up. So a plan is only executable if, starting from nothing but its own stated
 * requirements, every iteration it schedules can retire.
 *
 * <p>Rice slimeballs are the case that proved it necessary. A water bucket is consumed
 * and the bucket handed back, so the bucket's net consumption across the plan is zero
 * and it was left out of the requirements entirely — mathematically correct and
 * completely unrunnable, because the first iteration has no bucket to fill and nothing
 * else will ever produce one. The task sat in EXTRACTING_INITIAL_RESOURCES forever,
 * which in-game is indistinguishable from a mod that has crashed.
 *
 * <p>The replay below deliberately does <em>not</em> reuse the planner's own simulator
 * or its resource-class abstraction. It walks the emitted {@link TaskPlan} against the
 * real {@link PatternLayout}s, so a mistake in how resources are grouped into classes
 * shows up here instead of being confirmed by its own author.
 */
public final class PlannerExecutabilitySelfTest {
    private static final Actor ACTOR = () -> "rstweaks-selftest";

    private PlannerExecutabilitySelfTest() {
    }

    private record TestResource(String id) implements ResourceKey {
        @Override
        public String toString() {
            return id;
        }
    }

    /**
     * @param expectPlan whether the planner should engage at all. Asserted in both
     *     directions: a scenario that silently stopped producing a plan would otherwise
     *     "pass" by never being executed, which is the failure mode that already bit the
     *     crafting-plan fixtures once.
     */
    private record Scenario(String name,
                            Consumer<PatternRepositoryImpl> patterns,
                            Map<String, Long> stored,
                            String request,
                            long amount,
                            boolean expectPlan) {
        /** The same fixture, but the planner is expected to refuse it. */
        Scenario withoutPlan() {
            return new Scenario(name + " (recycling disabled)",
                patterns, stored, request, amount, false);
        }
    }

    public static CraftingPlanSelfTest.Result run() {
        final List<String> failures = new ArrayList<>();
        final List<Scenario> scenarios = scenarios();
        final boolean originalPlanner = Config.lpPlanner;
        final boolean originalRecycle = Config.keepRecycledResourcesInTask;
        try {
            // The planner is off by default, so the test has to switch it on to have
            // anything to test. Both flags are restored in the finally below.
            Config.lpPlanner = true;
            Config.keepRecycledResourcesInTask = true;
            for (final Scenario scenario : scenarios) {
                try {
                    check(scenario, failures);
                } catch (final RuntimeException | StackOverflowError e) {
                    failures.add(scenario.name() + ": threw " + e);
                }
            }

            // Two things about the byproduct redirect, and the second is the one that
            // matters. First: with it off the planner must not plan at all, because every
            // plan it makes assumes the root pattern's byproducts come back to the task.
            Config.keepRecycledResourcesInTask = false;
            try {
                check(scenarios.getFirst().withoutPlan(), failures);
            } catch (final RuntimeException | StackOverflowError e) {
                failures.add("recycling disabled: threw " + e);
            }

            // Second: prove the redirect is load-bearing rather than the test agreeing
            // with itself. Plan with recycling on, replay with it off, and require a
            // deadlock. If this passes, either the mixin is unnecessary or the replay is
            // not modelling how Refined Storage routes root byproducts — and the second
            // is what let "one slimeball works, eighteen deadlock" reach the game.
            // One per redirect, since they cover different halves of the problem: the
            // container case needs byproducts held, the template case needs outputs held.
            for (final String name : List.of("recycled container, one in stock",
                                             "self-duplicating pattern, one to seed")) {
                try {
                    final Scenario cycle = scenarios.stream()
                        .filter(s -> s.name().equals(name))
                        .findFirst()
                        .orElseThrow();
                    Config.keepRecycledResourcesInTask = true;
                    final TaskPlan plan = buildPlan(cycle);
                    Config.keepRecycledResourcesInTask = false;
                    if (plan == null) {
                        failures.add("redirect guard [" + name + "]: no plan to test with");
                    } else if (replay(plan, cycle.request(), cycle.amount()) == null) {
                        failures.add("redirect guard [" + name + "]: a plan built for "
                            + "recycled resources ran anyway without the redirect. Either "
                            + "keepRecycledResourcesInTask is not doing anything, or this "
                            + "replay does not model what the root pattern sends to the "
                            + "network.");
                    }
                } catch (final RuntimeException | StackOverflowError e) {
                    failures.add("redirect guard [" + name + "]: threw " + e);
                }
            }
            Config.keepRecycledResourcesInTask = true;
            durabilityChecks(failures);
            shortfallChecks(failures);
        } finally {
            Config.lpPlanner = originalPlanner;
            Config.keepRecycledResourcesInTask = originalRecycle;
            Durability.Holder.set(Durability.NONE);
        }
        return new CraftingPlanSelfTest.Result(
            scenarios.size() + 3 + DURABILITY_SCENARIOS + SHORTFALL_SCENARIOS, failures);
    }

    private static LpCraftingPlanner.Attempt attempt(final Scenario scenario) {
        final PatternRepositoryImpl patterns = new PatternRepositoryImpl();
        scenario.patterns().accept(patterns);

        // Insert before adding the source: RootStorageImpl snapshots contents at
        // addSource time, and later inserts do not propagate.
        final StorageImpl source = new StorageImpl();
        scenario.stored().forEach((id, amount) ->
            source.insert(res(id), amount, Action.EXECUTE, ACTOR));
        final RootStorageImpl storage = new RootStorageImpl();
        storage.addSource(source);

        return LpCraftingPlanner.attempt(
            patterns, storage, res(scenario.request()), scenario.amount());
    }

    @Nullable
    private static TaskPlan buildPlan(final Scenario scenario) {
        return attempt(scenario).plan();
    }

    private static void check(final Scenario scenario, final List<String> failures) {
        check(scenario, failures, null);
    }

    /**
     * @param extra optional assertion on the emitted plan, returning a complaint or
     *     {@code null}. Needed because "a plan exists and runs" is not the same as "the
     *     plan is any good" — a durability graph will happily be solved by requisitioning
     *     a fresh crystal per craft, which runs perfectly and is the behaviour we are
     *     trying to get rid of.
     */
    private static void check(final Scenario scenario,
                              final List<String> failures,
                              @Nullable final java.util.function.Function<TaskPlan, String> extra) {
        final TaskPlan plan = buildPlan(scenario);
        if (plan != null && extra != null) {
            final String complaint = extra.apply(plan);
            if (complaint != null) {
                failures.add(scenario.name() + ": " + complaint);
            }
        }
        if ((plan != null) != scenario.expectPlan()) {
            failures.add(scenario.name() + ": expected the planner to "
                + (scenario.expectPlan() ? "produce a plan but it declined"
                    : "decline but it produced a plan"));
            return;
        }
        if (plan == null) {
            return;
        }
        final String problem = replay(plan, scenario.request(), scenario.amount());
        if (problem != null) {
            failures.add(scenario.name() + ": " + problem
                + "\n      initial requirements = " + describe(plan.initialRequirements()));
        }
        final String previewProblem = checkPreview(plan);
        if (previewProblem != null) {
            failures.add(scenario.name() + ": preview " + previewProblem);
        }
    }

    /**
     * The request screen must not claim to craft something no pattern makes.
     *
     * <p>It used to count byproducts as crafted, so recycling one bucket through 128
     * iterations was announced as "to craft: 128 buckets" — a number that is not wrong
     * about the arithmetic and is completely wrong about what will happen.
     */
    @Nullable
    private static String checkPreview(final TaskPlan plan) {
        // The requested row must say what the player gets. A self-duplicating pattern
        // fires twice as many outputs as it nets, and reporting 384 for a request of 192
        // is true about the machine and false about the outcome.
        for (final PreviewItem item : PlanPreview.from(plan).items()) {
            if (item.resource().equals(plan.resource()) && item.toCraft() != plan.amount()) {
                return "says it will craft " + item.toCraft() + " " + item.resource()
                    + " but " + plan.amount() + " were requested";
            }
        }

        // Nothing the plan merely passes round may be announced as crafted. A container
        // cycling sixty-four times is one container, whether it comes back as a byproduct
        // or as an output — the water setup hits the second case, where the empty bucket
        // is an output of the fluid substitution pattern.
        final Map<ResourceKey, Long> made = new HashMap<>();
        final Map<ResourceKey, Long> used = new HashMap<>();
        plan.patterns().forEach((pattern, patternPlan) -> {
            final long iterations = patternPlan.iterations();
            pattern.layout().outputs().forEach(o ->
                made.merge(o.resource(), o.amount() * iterations, Long::sum));
            pattern.layout().byproducts().forEach(b ->
                made.merge(b.resource(), b.amount() * iterations, Long::sum));
            pattern.layout().ingredients().forEach(i ->
                used.merge(i.inputs().getFirst(), i.amount() * iterations, Long::sum));
        });
        for (final ResourceAmount requirement : plan.initialRequirements()) {
            final ResourceKey resource = requirement.resource();
            if (resource.equals(plan.resource())) {
                continue;
            }
            final long produced = made.getOrDefault(resource, 0L);
            if (produced <= 0L || produced != used.getOrDefault(resource, 0L)) {
                continue;
            }
            for (final PreviewItem item : PlanPreview.from(plan).items()) {
                if (item.resource().equals(resource) && item.toCraft() > 0L) {
                    return "says it will craft " + item.toCraft() + " " + resource
                        + ", but the plan requisitions " + requirement.amount()
                        + " and cycles them: " + produced + " produced, "
                        + used.getOrDefault(resource, 0L) + " consumed";
                }
            }
        }

        final Map<ResourceKey, Long> producible = new HashMap<>();
        plan.patterns().forEach((pattern, patternPlan) ->
            pattern.layout().outputs().forEach(output ->
                producible.merge(output.resource(),
                    output.amount() * patternPlan.iterations(), Long::sum)));

        for (final PreviewItem item : PlanPreview.from(plan).items()) {
            if (item.toCraft() <= 0L) {
                continue;
            }
            final long canMake = producible.getOrDefault(item.resource(), 0L);
            if (canMake < item.toCraft()) {
                return "claims to craft " + item.toCraft() + " " + item.resource()
                    + " but the plan's patterns output " + canMake;
            }
        }
        return null;
    }

    /**
     * Runs the plan the way Refined Storage will, and returns what went wrong, or
     * {@code null} if it completed.
     *
     * <p>Greedy to a fixed point, exactly like {@code InternalTaskPattern.step}: retire
     * any iteration whose inputs are present, repeat until none can. If iterations remain
     * when nothing more can run, the real executor would stall in the same place.
     */
    @Nullable
    private static String replay(final TaskPlan plan, final String request, final long amount) {
        final Map<ResourceKey, Long> pool = new HashMap<>();
        plan.initialRequirements().forEach(r -> pool.merge(r.resource(), r.amount(), Long::sum));
        // What has left the task for the network, which the task can never draw on again.
        final Map<ResourceKey, Long> delivered = new HashMap<>();
        final long extractedTarget = pool.getOrDefault(res(request), 0L);

        final Map<Pattern, Long> remaining = new HashMap<>();
        final Map<Pattern, Boolean> isRoot = new HashMap<>();
        // What the whole task consumes. The executor holds anything a sibling still
        // needs, so the replay has to know the same thing or it will reject plans that
        // actually run -- and accept ones that jam.
        final java.util.Set<ResourceKey> taskConsumes = new java.util.HashSet<>();
        plan.patterns().keySet().forEach(p2 ->
            p2.layout().ingredients().forEach(i -> taskConsumes.addAll(i.inputs())));
        plan.patterns().forEach((pattern, patternPlan) -> {
            remaining.put(pattern, patternPlan.iterations());
            isRoot.put(pattern, patternPlan.root());
        });

        long outstanding = remaining.values().stream().mapToLong(Long::longValue).sum();
        // One pass can retire at least one iteration, so this bounds the loop without
        // capping how much work a legitimate plan may do.
        long passes = 0;
        final long maxPasses = outstanding + 8L;
        while (outstanding > 0L) {
            if (++passes > maxPasses) {
                return "replay made no progress within " + maxPasses + " passes";
            }
            boolean progressed = false;
            for (final Map.Entry<Pattern, Long> entry : remaining.entrySet()) {
                if (entry.getValue() <= 0L) {
                    continue;
                }
                if (!runOnce(entry.getKey(), pool, delivered, isRoot.get(entry.getKey()),
                              taskConsumes)) {
                    continue;
                }
                entry.setValue(entry.getValue() - 1L);
                outstanding--;
                progressed = true;
            }
            if (!progressed) {
                final List<String> stuck = new ArrayList<>();
                remaining.forEach((pattern, left) -> {
                    if (left > 0L) {
                        stuck.add(pattern.layout().outputs().getFirst().resource()
                            + " (" + left + " iterations left)");
                    }
                });
                return "DEADLOCK -- the plan cannot run from its own initial requirements."
                    + " Stalled on " + stuck + ", pool = " + pool;
            }
        }
        final String leak = checkDurabilityConservation(plan, pool, delivered);
        if (leak != null) {
            return leak;
        }

        // Whatever is still held when the task finishes is handed back to the network,
        // so the player's gain is everything delivered plus the leftovers, less the
        // amount the task took from them to start with.
        final long net = delivered.getOrDefault(res(request), 0L)
            + pool.getOrDefault(res(request), 0L)
            - extractedTarget;
        if (net < amount) {
            return "ran to completion but the network gained " + net + " " + request
                + ", not the " + amount + " requested";
        }
        return null;
    }

    /**
     * A craft may not create durability out of nothing.
     *
     * <p>This is the check that stands between the feature and a duplication glitch.
     * Refined Storage bakes the byproduct into the pattern at encode time, so a pattern
     * made with a fresh crystal hands back a crystal at damage 1 on <em>every</em>
     * iteration — sixty-four crafts from one crystal, which ends at damage 1 and never
     * breaks. That version replays perfectly and satisfies every other assertion here.
     *
     * <p>So the ledger is checked instead: uses at the end must equal uses at the start,
     * plus whatever crafting fresh tools added, minus one for each iteration that used a
     * tool. Any surplus means a tool was repaired.
     */
    @Nullable
    private static String checkDurabilityConservation(final TaskPlan plan,
                                                      final Map<ResourceKey, Long> pool,
                                                      final Map<ResourceKey, Long> delivered) {
        final Durability durability = Durability.Holder.get();
        long started = 0L;
        for (final ResourceAmount requirement : plan.initialRequirements()) {
            started += uses(durability, requirement.resource(), requirement.amount());
        }
        if (started == 0L) {
            return null;
        }

        long crafted = 0L;
        long spent = 0L;
        for (final Map.Entry<Pattern, TaskPlan.PatternPlan> entry : plan.patterns().entrySet()) {
            final PatternLayout layout = entry.getKey().layout();
            final long iterations = entry.getValue().iterations();
            for (final ResourceAmount output : layout.outputs()) {
                if (durability.isDurable(output.resource())) {
                    crafted += Math.multiplyExact(
                        uses(durability, output.resource(), output.amount()), iterations);
                }
            }
            for (final Ingredient ingredient : layout.ingredients()) {
                if (durability.isDurable(ingredient.inputs().getFirst())) {
                    spent += Math.multiplyExact(ingredient.amount(), iterations);
                    break;
                }
            }
        }

        long ended = 0L;
        for (final Map.Entry<ResourceKey, Long> held : pool.entrySet()) {
            ended += uses(durability, held.getKey(), held.getValue());
        }
        for (final Map.Entry<ResourceKey, Long> held : delivered.entrySet()) {
            ended += uses(durability, held.getKey(), held.getValue());
        }

        final long expected = started + crafted - spent;
        return ended == expected ? null
            : "durability was not conserved: started with " + started + " uses, crafted "
                + crafted + ", spent " + spent + ", ended with " + ended
                + " (expected " + expected + ")";
    }

    private static long uses(final Durability durability,
                             final ResourceKey resource,
                             final long count) {
        return durability.isDurable(resource)
            ? Math.multiplyExact(count, (long) durability.usesLeft(resource))
            : 0L;
    }

    /**
     * One iteration of a pattern, if the pool can pay for it.
     *
     * <p>The {@code root} handling is the whole reason this replay is worth having.
     * {@code InternalTaskPattern.returnOutput} sends the root pattern's outputs
     * <em>and byproducts</em> to the network rather than the task's internal storage,
     * and the task never draws from the network again once running. So for the root
     * pattern, anything produced leaves the pool — unless {@code keepRecycledResourcesInTask}
     * redirects the byproducts back, which is exactly what the mixin does. Modelling
     * this faithfully is what turns "one slimeball works, eighteen deadlock" from an
     * in-game surprise into a test failure.
     */
    private static boolean runOnce(final Pattern pattern,
                                   final Map<ResourceKey, Long> pool,
                                   final Map<ResourceKey, Long> delivered,
                                   final boolean root,
                                   final java.util.Set<ResourceKey> taskConsumes) {
        final PatternLayout layout = pattern.layout();
        final Map<ResourceKey, Long> spend = new HashMap<>();
        // Wear is resolved before the ordinary ingredients, because the tool the pattern
        // was encoded with is almost never the one in the pool — that mismatch is the
        // entire bug. Whatever wear level is actually present is consumed, and the
        // byproduct becomes that tool one use further along, exactly as the executor
        // mixin does it. Modelling anything else here would confirm plans that deadlock.
        final Durability durability = Durability.Holder.get();
        final Map<ResourceKey, ResourceKey> wear = new HashMap<>();
        for (final Ingredient ingredient : layout.ingredients()) {
            final ResourceKey encoded = ingredient.inputs().getFirst();
            if (!durability.isDurable(encoded)) {
                continue;
            }
            ResourceKey found = null;
            for (final Map.Entry<ResourceKey, Long> held : pool.entrySet()) {
                if (held.getValue() > 0L && durability.sameTool(encoded, held.getKey())) {
                    found = held.getKey();
                    break;
                }
            }
            if (found == null) {
                return false;
            }
            wear.put(encoded, found);
        }

        for (final Ingredient ingredient : layout.ingredients()) {
            long needed = ingredient.amount();
            final ResourceKey substitute = wear.get(ingredient.inputs().getFirst());
            if (substitute != null) {
                final long have = pool.getOrDefault(substitute, 0L)
                    - spend.getOrDefault(substitute, 0L);
                if (have < needed) {
                    return false;
                }
                spend.merge(substitute, needed, Long::sum);
                continue;
            }
            // An ingredient accepts any of several resources; take whatever is there,
            // which is what Refined Storage does.
            for (final ResourceKey input : ingredient.inputs()) {
                if (needed <= 0L) {
                    break;
                }
                final long have = pool.getOrDefault(input, 0L) - spend.getOrDefault(input, 0L);
                final long take = Math.min(needed, have);
                if (take > 0L) {
                    spend.merge(input, take, Long::sum);
                    needed -= take;
                }
            }
            if (needed > 0L) {
                return false;
            }
        }
        spend.forEach((resource, taken) -> pool.merge(resource, -taken, Long::sum));
        if (!root) {
            layout.outputs().forEach(o -> pool.merge(o.resource(), o.amount(), Long::sum));
            layout.byproducts().forEach(b -> pool.merge(b.resource(), b.amount(), Long::sum));
            return true;
        }
        // The root pattern is where things leave the task, and the two rules differ.
        //
        // Outputs are what the player asked for, so they go to the network as they are
        // made and the craft visibly fills up — unless this same pattern consumes them,
        // which is template duplication (1 template + materials -> 2 templates). There
        // the output has to stay or iteration two has nothing to work with.
        for (final ResourceAmount output : layout.outputs()) {
            if (Config.keepRecycledResourcesInTask
                && (consumes(layout, output.resource())
                    || taskConsumes.contains(output.resource()))) {
                pool.merge(output.resource(), output.amount(), Long::sum);
            } else {
                delivered.merge(output.resource(), output.amount(), Long::sum);
            }
        }
        // Byproducts are never the requested item, so holding all of them until the task
        // finishes costs nothing and covers a container recycled through a *different*
        // pattern, which no per-pattern test could see.
        for (final ResourceAmount byproduct : layout.byproducts()) {
            final ResourceAmount actual = worn(durability, wear, byproduct);
            if (actual == null) {
                // The tool broke on this use. Nothing comes back, which is what makes a
                // durability plan finite instead of a perpetual motion machine.
                continue;
            }
            if (Config.keepRecycledResourcesInTask) {
                pool.merge(actual.resource(), actual.amount(), Long::sum);
            } else {
                delivered.merge(actual.resource(), actual.amount(), Long::sum);
            }
        }
        return true;
    }

    /**
     * The byproduct as it will actually come back: if the pattern was encoded consuming
     * {@code crystal@0} and returning {@code crystal@1}, but {@code crystal@7} was the one
     * in the pool, what returns is {@code crystal@8}.
     *
     * <p>This is the {@code getRemainingKey} idea from AE2's {@code IPatternDetails.IInput},
     * where the thing handed back is a function of the item actually used rather than a
     * constant baked in when the pattern was encoded.
     */
    @Nullable
    private static ResourceAmount worn(final Durability durability,
                                       final Map<ResourceKey, ResourceKey> wear,
                                       final ResourceAmount byproduct) {
        if (wear.isEmpty() || !durability.isDurable(byproduct.resource())) {
            return byproduct;
        }
        for (final ResourceKey consumed : wear.values()) {
            if (!durability.sameTool(consumed, byproduct.resource())) {
                continue;
            }
            final ResourceKey after = durability.afterUses(consumed, 1);
            return after == null ? null : new ResourceAmount(after, byproduct.amount());
        }
        return byproduct;
    }

    /** Whether the pattern takes this resource as one of its own ingredients. */
    private static boolean consumes(final PatternLayout layout, final ResourceKey resource) {
        for (final Ingredient ingredient : layout.ingredients()) {
            if (ingredient.inputs().contains(resource)) {
                return true;
            }
        }
        return false;
    }

    private static String describe(final Iterable<ResourceAmount> requirements) {
        final List<String> parts = new ArrayList<>();
        requirements.forEach(r -> parts.add(r.resource() + "x" + r.amount()));
        parts.sort(String::compareTo);
        return parts.toString();
    }

    // -------------------------------------------------------------- durability

    private static final int DURABILITY_SCENARIOS = 6;

    /**
     * Tools that wear out, which Refined Storage cannot express at all today.
     *
     * <p>A Mystical Agriculture infusion crystal is consumed by the recipe and handed
     * back one point more damaged, and RS records that as a byproduct — but a crystal at
     * damage 0 and the same crystal at damage 1 are different resources, so a pattern
     * encoded with a fresh one can only ever consume a fresh one. The first craft works
     * and the second has nothing to use.
     *
     * <p>The model here follows Applied Energistics: a worn tool is a <b>supply of
     * uses</b>, not a chain of a hundred distinct resources. One crystal with 30 hits
     * left is 30 crafts. That keeps the program at one extra resource class rather than
     * a hundred extra patterns — 0.57 ms against 63 ms, measured.
     */
    private static void durabilityChecks(final List<String> failures) {
        // 100 uses, like the crystal this was reported for. The requisition is the whole
        // point: without asserting it, a plan that demands 64 crystals also runs
        // perfectly and the scenario passes while testing nothing.
        run(failures, new FakeDurability("crystal", 100),
            "durable tool, one crystal covers the whole run",
            repo -> repo.add(wearPattern(0), 0),
            Map.of(FakeDurability.worn("crystal", 0), 1L, "material", 4096L),
            "product", 64L, true,
            plan -> requisition(plan, "crystal@", 1L));

        // Already worn, and enough left. Must reach for the worn one it already has
        // rather than declaring it has no crystal.
        run(failures, new FakeDurability("crystal", 100),
            "partly worn tool with enough uses left",
            repo -> repo.add(wearPattern(0), 0),
            Map.of(FakeDurability.worn("crystal", 95), 1L, "material", 4096L),
            "product", 3L, true,
            plan -> requisition(plan, "crystal@95", 1L));

        // The case actually asked for: one crystal is not enough, so craft replacements
        // as the old ones break — and only then.
        run(failures, new FakeDurability("crystal", 10),
            "crystal runs out mid-run, replacements crafted",
            repo -> {
                repo.add(wearPattern(0), 0);
                repo.add(pattern("newcrystal", List.of(ing(8, "gem")),
                    List.of(new ResourceAmount(res(FakeDurability.worn("crystal", 0)), 1L)),
                    List.of()), 0);
            },
            Map.of(FakeDurability.worn("crystal", 0), 1L, "material", 4096L, "gem", 4096L),
            "product", 25L, true,
            // 25 crafts at 10 uses each, with 10 uses already in stock, needs 2 more
            // crystals. Without this the scenario passes by crafting 24 of them, which
            // runs perfectly and is exactly the waste the feature exists to remove.
            plan -> {
                final long crystals = plan.patterns().entrySet().stream()
                    .filter(e -> e.getKey().layout().outputs().stream()
                        .anyMatch(o -> String.valueOf(o.resource()).startsWith("crystal@")))
                    .mapToLong(e -> e.getValue().iterations())
                    .sum();
                return crystals > 2
                    ? "crafted " + crystals + " crystals; 2 should cover 25 uses"
                    : null;
            });

        // Prove the ageing half is load-bearing, not just agreeing with itself. This
        // durability model substitutes wear levels but never actually wears anything
        // down, which is precisely Refined Storage's behaviour without the byproduct
        // rewrite: the pattern hands back the crystal it was encoded with, every time.
        // The craft runs, every other assertion passes, and one crystal does sixty-four
        // jobs forever. Conservation is what catches it.
        run(failures, new NeverWears("crystal", 100),
            "ageing disabled: a repaired crystal must be caught",
            repo -> repo.add(wearPattern(0), 0),
            Map.of(FakeDurability.worn("crystal", 0), 1L, "material", 4096L),
            "product", 64L, true,
            plan -> null,
            true);

        // Not every recipe costs one point of durability. Asked by Nodrance, and worth
        // asking: the wear step has to come from the pattern, because assuming 1 makes a
        // tool that burns 5 per craft last five times too long — the planner under-orders
        // replacements and the executor ages it too slowly, which is a duplication bug
        // dressed up as a working feature.
        run(failures, new FakeDurability("crystal", 100),
            "recipe that burns five durability per craft",
            repo -> {
                repo.add(wearPattern(0, 5), 0);
                repo.add(pattern("newcrystal", List.of(ing(8, "gem")),
                    List.of(new ResourceAmount(res(FakeDurability.worn("crystal", 0)), 1L)),
                    List.of()), 0);
            },
            Map.of(FakeDurability.worn("crystal", 0), 1L, "material", 4096L, "gem", 4096L),
            "product", 40L, true,
            // 40 crafts x 5 = 200 durability; one crystal is 100, and one is already in
            // stock, so exactly one replacement.
            plan -> {
                final long crystals = plan.patterns().entrySet().stream()
                    .filter(e -> e.getKey().layout().outputs().stream()
                        .anyMatch(o -> String.valueOf(o.resource()).startsWith("crystal@")))
                    .mapToLong(e -> e.getValue().iterations())
                    .sum();
                return crystals == 1 ? null
                    : "crafted " + crystals + " crystals; 40 crafts at 5 durability needs 1";
            });

        // No crystal and no way to make one: decline rather than plan a craft that
        // cannot take its first step.
        run(failures, new FakeDurability("crystal", 100),
            "no tool and no recipe for one",
            repo -> repo.add(wearPattern(0), 0),
            Map.of("material", 4096L),
            "product", 8L, false);
    }

    /** Complains unless the plan asks the network for exactly this many matching items. */
    @Nullable
    private static String requisition(final TaskPlan plan, final String prefix, final long expected) {
        long total = 0L;
        final List<String> named = new ArrayList<>();
        for (final ResourceAmount requirement : plan.initialRequirements()) {
            if (String.valueOf(requirement.resource()).startsWith(prefix)) {
                total += requirement.amount();
                named.add(requirement.resource() + "x" + requirement.amount());
            }
        }
        return total == expected ? null
            : "requisitions " + named + " matching '" + prefix + "', expected " + expected;
    }

    /** The infusion-crystal shape: consume the tool, hand it back one point more worn. */
    private static Pattern wearPattern(final int damage) {
        return wearPattern(damage, 1);
    }

    /** @param step how much durability one craft costs, which is not always one. */
    private static Pattern wearPattern(final int damage, final int step) {
        return pattern("wear" + step,
            List.of(ing(1, FakeDurability.worn("crystal", damage)), ing(1, "material")),
            List.of(new ResourceAmount(res("product"), 1L)),
            List.of(new ResourceAmount(res(FakeDurability.worn("crystal", damage + step)), 1L)));
    }

    private static void run(final List<String> failures,
                            final Durability durability,
                            final String name,
                            final Consumer<PatternRepositoryImpl> patterns,
                            final Map<String, Long> stored,
                            final String request,
                            final long amount,
                            final boolean expectPlan) {
        run(failures, durability, name, patterns, stored, request, amount, expectPlan, null);
    }

    private static void run(final List<String> failures,
                            final Durability durability,
                            final String name,
                            final Consumer<PatternRepositoryImpl> patterns,
                            final Map<String, Long> stored,
                            final String request,
                            final long amount,
                            final boolean expectPlan,
                            @Nullable final java.util.function.Function<TaskPlan, String> extra) {
        run(failures, durability, name, patterns, stored, request, amount, expectPlan,
            extra, false);
    }

    /**
     * @param mustFail inverts the result: the scenario is only satisfied when the suite
     *     <em>rejects</em> it. Used to show an assertion has teeth rather than trusting a
     *     green run — the same reason every other fix here was verified by reinstating
     *     the bug and watching it fail.
     */
    private static void run(final List<String> failures,
                            final Durability durability,
                            final String name,
                            final Consumer<PatternRepositoryImpl> patterns,
                            final Map<String, Long> stored,
                            final String request,
                            final long amount,
                            final boolean expectPlan,
                            @Nullable final java.util.function.Function<TaskPlan, String> extra,
                            final boolean mustFail) {
        Durability.Holder.set(durability);
        Config.durabilityAwarePlanning = true;
        final List<String> collected = new ArrayList<>();
        try {
            check(new Scenario(name, patterns, stored, request, amount, expectPlan),
                collected, extra);
        } catch (final RuntimeException | StackOverflowError e) {
            collected.add(name + ": threw " + e);
        } finally {
            Config.durabilityAwarePlanning = false;
            Durability.Holder.set(Durability.NONE);
        }
        if (!mustFail) {
            failures.addAll(collected);
        } else if (collected.isEmpty()) {
            failures.add(name + ": expected this to be rejected, but it passed. Either the"
                + " durability conservation check has stopped working, or wearing the tool"
                + " down is no longer necessary.");
        }
    }

    /** Named pattern with explicit outputs and byproducts. */
    private static Pattern pattern(final String id,
                                   final List<Ingredient> ingredients,
                                   final List<ResourceAmount> outputs,
                                   final List<ResourceAmount> byproducts) {
        return new Pattern(
            UUID.nameUUIDFromBytes(("rstweaks-lp:" + id).getBytes(StandardCharsets.UTF_8)),
            PatternLayout.internal(ingredients, outputs, byproducts));
    }

    // --------------------------------------------------------------- shortfalls

    private static final int SHORTFALL_SCENARIOS = 3;

    /**
     * A craft that cannot be made must say <em>what you are short of</em>.
     *
     * <p>Ten buckets' worth of lava with four lava buckets in the network previewed
     * "missing 10000mB lava" — which names the fluid you asked for as the thing you cannot
     * find, and leaves the player looking for lava rather than for buckets. The shortfall
     * is computed by re-solving with outside supply allowed; see {@code PlanShortfall}.
     *
     * <p>Asserted on the numbers rather than on "a preview exists", because the failure
     * mode being guarded against is a preview that is present and wrong — the reverted
     * v0.2.45 built an empty one and left Start enabled on an impossible craft.
     */
    private static void shortfallChecks(final List<String> failures) {
        // The case this was reported for. The lava is the outcome, the buckets are the
        // shortage, and the two must not be confused.
        expectShortfall(failures, "lava shortfall names the buckets, not the fluid",
            PlannerExecutabilitySelfTest::fluidSwapPair, Map.of("lava_bucket", 4L),
            "lava", 10_000L,
            Map.of("lava_bucket", new long[]{4L, 6L, 0L},
                   "lava", new long[]{0L, 0L, 10_000L}));

        // The same graph the "cannot conjure fluid" scenario above proves impossible. A
        // cycle that nets to zero cannot make lava out of one bucket, and the honest
        // reading of that is four more buckets, not five thousand unfindable millibuckets.
        expectShortfall(failures, "swap cycle shortfall is the missing containers",
            PlannerExecutabilitySelfTest::fluidSwapPair, Map.of("lava_bucket", 1L),
            "lava", 5000L,
            Map.of("lava_bucket", new long[]{1L, 4L, 0L},
                   "lava", new long[]{0L, 0L, 5000L}));

        // A shortage at a leaf, behind a container cycle. The recycled bucket is neither
        // available nor missing — it goes round — so the answer has to be the dough, and
        // the essence there is enough of has to read as available rather than vanish.
        expectShortfall(failures, "shortfall found behind a recycled container",
            repo -> {
                repo.add(recipe("slimeball", 1,
                    List.of(ing(4, "dough"), ing(1, "water_bucket")),
                    List.of(out("bucket", 1))), 0);
                repo.add(recipe("water_bucket", 1,
                    List.of(ing(1, "bucket"), ing(1, "essence")), List.of()), 0);
            },
            Map.of("dough", 4L, "essence", 1024L, "bucket", 1L), "slimeball", 64L,
            Map.of("dough", new long[]{4L, 252L, 0L},
                   "essence", new long[]{64L, 0L, 0L},
                   "slimeball", new long[]{0L, 0L, 64L}));
    }

    /** One encoded fluid substitution pattern plus the mirror the swap mixin registers. */
    private static void fluidSwapPair(final PatternRepositoryImpl repo) {
        repo.add(recipe("lava", 1000,
            List.of(ing(1, "lava_bucket")), List.of(out("bucket", 1))), 0);
        repo.add(recipe("lava_bucket", 1,
            List.of(ing(1, "bucket"), ing(1000, "lava")), List.of()), 0);
    }

    /**
     * @param expected resource id to {available, missing, toCraft}, checked exactly. Any
     *     resource outside this map claiming a shortfall fails too: reporting something the
     *     player is not actually short of sends them hunting for the wrong item, which is
     *     the same complaint the thin message had.
     */
    private static void expectShortfall(final List<String> failures,
                                        final String name,
                                        final Consumer<PatternRepositoryImpl> patterns,
                                        final Map<String, Long> stored,
                                        final String request,
                                        final long amount,
                                        final Map<String, long[]> expected) {
        final LpCraftingPlanner.Attempt attempt;
        try {
            attempt = attempt(new Scenario(name, patterns, stored, request, amount, false));
        } catch (final RuntimeException | StackOverflowError e) {
            failures.add(name + ": threw " + e);
            return;
        }
        if (attempt.plan() != null) {
            failures.add(name + ": expected the planner to decline but it produced a plan");
            return;
        }
        if (!attempt.impossible()) {
            failures.add(name + ": the solver did not prove this impossible, so the request "
                + "screen never asks what it is short of and stock Refined Storage answers");
            return;
        }
        final Preview preview = LpCraftingPlanner.shortfall(attempt, res(request), amount);
        if (preview == null) {
            failures.add(name + ": no shortfall was worked out, so the screen falls back to "
                + "naming only the target");
            return;
        }
        if (preview.type() != PreviewType.MISSING_RESOURCES) {
            failures.add(name + ": preview type is " + preview.type() + ", so Start would be "
                + "offered on a craft already proved impossible");
        }
        final Map<String, PreviewItem> items = new HashMap<>();
        preview.items().forEach(item -> items.put(String.valueOf(item.resource()), item));
        for (final Map.Entry<String, long[]> want : expected.entrySet()) {
            final PreviewItem item = items.remove(want.getKey());
            final long[] counts = want.getValue();
            if (item == null) {
                failures.add(name + ": no row for " + want.getKey() + "; got " + describe(preview));
            } else if (item.available() != counts[0]
                || item.missing() != counts[1]
                || item.toCraft() != counts[2]) {
                failures.add(name + ": " + want.getKey() + " reads available " + item.available()
                    + ", missing " + item.missing() + ", to craft " + item.toCraft()
                    + "; expected " + counts[0] + ", " + counts[1] + ", " + counts[2]);
            }
        }
        items.forEach((id, item) -> {
            if (item.missing() > 0L) {
                failures.add(name + ": also reports " + item.missing() + " " + id
                    + " missing, which is not what the request is short of");
            }
        });
    }

    private static String describe(final Preview preview) {
        final List<String> parts = new ArrayList<>();
        preview.items().forEach(item -> parts.add(item.resource() + " available=" + item.available()
            + " missing=" + item.missing() + " toCraft=" + item.toCraft()));
        parts.sort(String::compareTo);
        return parts.toString();
    }

    // ---------------------------------------------------------------- fixtures

    static ResourceKey res(final String id) {
        return new TestResource(id);
    }

    private static Pattern recipe(final String output, final long outputAmount,
                                  final List<Ingredient> ingredients,
                                  final List<ResourceAmount> byproducts) {
        return new Pattern(
            UUID.nameUUIDFromBytes(("rstweaks-lp:" + output).getBytes(StandardCharsets.UTF_8)),
            PatternLayout.internal(
                ingredients,
                List.of(new ResourceAmount(res(output), outputAmount)),
                byproducts
            )
        );
    }

    private static Ingredient ing(final long amount, final String input) {
        return new Ingredient(amount, List.of(res(input)));
    }

    private static ResourceAmount out(final String id, final long amount) {
        return new ResourceAmount(res(id), amount);
    }

    private static List<Scenario> scenarios() {
        final List<Scenario> out = new ArrayList<>();

        // The bug, reduced. water_bucket consumes a bucket; slimeball hands it back.
        // Net bucket consumption is zero, so the requirements omitted it and the plan
        // deadlocked on its very first iteration.
        out.add(new Scenario("recycled container, one in stock", repo -> {
            repo.add(recipe("slimeball", 1,
                List.of(ing(4, "dough"), ing(1, "water_bucket")),
                List.of(out("bucket", 1))), 0);
            repo.add(recipe("water_bucket", 1,
                List.of(ing(1, "bucket"), ing(1, "essence")), List.of()), 0);
        }, Map.of("dough", 4096L, "essence", 1024L, "bucket", 1L), "slimeball", 64L, true));

        // Same graph with buckets to spare. The plan must still be runnable, and must
        // not quietly reserve one per iteration the way stock RS does.
        out.add(new Scenario("recycled container, buckets to spare", repo -> {
            repo.add(recipe("slimeball", 1,
                List.of(ing(4, "dough"), ing(1, "water_bucket")),
                List.of(out("bucket", 1))), 0);
            repo.add(recipe("water_bucket", 1,
                List.of(ing(1, "bucket"), ing(1, "essence")), List.of()), 0);
        }, Map.of("dough", 4096L, "essence", 1024L, "bucket", 64L), "slimeball", 64L, true));

        // The cake case from the original design note: bucket -> milk -> cake -> bucket.
        out.add(new Scenario("container recycled through two steps", repo -> {
            repo.add(recipe("cake", 1,
                List.of(ing(3, "milk_bucket"), ing(2, "sugar"), ing(1, "egg"), ing(3, "wheat")),
                List.of(out("bucket", 3))), 0);
            repo.add(recipe("milk_bucket", 1,
                List.of(ing(1, "bucket"), ing(1, "milk")), List.of()), 0);
        }, Map.of("sugar", 4096L, "egg", 4096L, "wheat", 4096L, "milk", 4096L, "bucket", 3L),
            "cake", 32L, true));

        // A byproduct that is not recycled, only surplus. The planner still engages —
        // a byproduct alone is enough — but there is nothing to bootstrap, so the
        // requirements should be plain net consumption and the replay is trivial.
        out.add(new Scenario("byproduct with no cycle", repo -> {
            repo.add(recipe("ingot", 2, List.of(ing(1, "ore")), List.of(out("slag", 1))), 0);
        }, Map.of("ore", 512L), "ingot", 64L, true));

        // A bidirectional fluid substitution pair, which is the shape ReversibleFluidSwapMixin
        // puts into the network: one encoded pattern, plus its mirror. Emptying a bucket and
        // filling one are a two-cycle by construction, so these three exist to prove the
        // planner treats that as an ordinary cycle rather than either looping on it or
        // believing it.
        out.add(new Scenario("fluid swap, emptying", repo -> {
            repo.add(recipe("lava", 1000,
                List.of(ing(1, "lava_bucket")), List.of(out("bucket", 1))), 0);
            repo.add(recipe("lava_bucket", 1,
                List.of(ing(1, "bucket"), ing(1000, "lava")), List.of()), 0);
        }, Map.of("lava_bucket", 4L), "lava", 1000L, true));

        out.add(new Scenario("fluid swap, filling", repo -> {
            repo.add(recipe("lava", 1000,
                List.of(ing(1, "lava_bucket")), List.of(out("bucket", 1))), 0);
            repo.add(recipe("lava_bucket", 1,
                List.of(ing(1, "bucket"), ing(1000, "lava")), List.of()), 0);
        }, Map.of("bucket", 4L, "lava", 4000L), "lava_bucket", 1L, true));

        // The one that matters. Net production around the loop is exactly zero, so a solver
        // that follows the cycle without accounting for it can "prove" any quantity of lava
        // is reachable from a single bucket. That is not a stalled craft, it is duplication —
        // the planner must refuse rather than return a plan it cannot honour.
        out.add(new Scenario("fluid swap cycle cannot conjure fluid", repo -> {
            repo.add(recipe("lava", 1000,
                List.of(ing(1, "lava_bucket")), List.of(out("bucket", 1))), 0);
            repo.add(recipe("lava_bucket", 1,
                List.of(ing(1, "bucket"), ing(1000, "lava")), List.of()), 0);
        }, Map.of("lava_bucket", 1L), "lava", 5000L, false));

        // Two containers in one graph: both need capital, and fixing one must not mask
        // the other. The repair loop grows whichever the replay stalls on, in turn.
        out.add(new Scenario("two independent containers", repo -> {
            repo.add(recipe("product", 1,
                List.of(ing(1, "hot_fluid"), ing(1, "filled_can")),
                List.of(out("crucible", 1), out("can", 1))), 0);
            repo.add(recipe("hot_fluid", 1,
                List.of(ing(1, "crucible"), ing(1, "rock")), List.of()), 0);
            repo.add(recipe("filled_can", 1,
                List.of(ing(1, "can"), ing(1, "juice")), List.of()), 0);
        }, Map.of("rock", 4096L, "juice", 4096L, "crucible", 1L, "can", 1L),
            "product", 40L, true));

        // The water setup, reduced. Two patterns share a container through an OUTPUT
        // rather than a byproduct: the swap pattern turns a filled bucket into an empty
        // one plus fluid, and the OTHER pattern is what needs the empty bucket back. The
        // swap is the root, so its outputs leave for the network the moment they are
        // made — and the craft jams after exactly one iteration unless the executor
        // holds anything a sibling still wants.
        // Both are OUTPUTS, with no byproducts, which is what a fluid substitution
        // pattern really looks like: PatternLayout.internal(ingredients, outputs, []).
        // Writing the bucket as a byproduct instead makes this scenario pass whether the
        // fix is present or not, because byproducts are held unconditionally — it was
        // written that way first and proved nothing.
        out.add(new Scenario("container cycled through two patterns via an output", repo -> {
            repo.add(pattern("swap",
                List.of(ing(1, "water_bucket")),
                List.of(out("water", 1000), out("bucket", 1)),
                List.of()), 0);
            repo.add(pattern("fill",
                List.of(ing(1, "bucket"), ing(1, "essence")),
                List.of(out("water_bucket", 1)),
                List.of()), 0);
        }, Map.of("bucket", 1L, "essence", 4096L), "water", 10000L, true));

        // Netherite smithing templates: the pattern consumes one and produces two, so
        // the recycled resource is the requested item itself and it is the ROOT pattern's
        // output. Reported from the game as "gave it 3, asked for 3, it made 1 and hung".
        out.add(new Scenario("self-duplicating pattern, one to seed", repo -> {
            repo.add(recipe("template", 2,
                List.of(ing(1, "template"), ing(7, "diamond"), ing(1, "netherite")),
                List.of()), 0);
        }, Map.of("template", 1L, "diamond", 4096L, "netherite", 512L), "template", 100L, true));

        // Same recipe with several to seed. The plan must not quietly reserve one per
        // iteration, and must still run.
        out.add(new Scenario("self-duplicating pattern, three to seed", repo -> {
            repo.add(recipe("template", 2,
                List.of(ing(1, "template"), ing(7, "diamond"), ing(1, "netherite")),
                List.of()), 0);
        }, Map.of("template", 3L, "diamond", 4096L, "netherite", 512L), "template", 3L, true));

        // Nothing to duplicate from. Must decline rather than plan a craft that cannot
        // take its first step.
        out.add(new Scenario("self-duplicating pattern, nothing to seed", repo -> {
            repo.add(recipe("template", 2,
                List.of(ing(1, "template"), ing(7, "diamond"), ing(1, "netherite")),
                List.of()), 0);
        }, Map.of("diamond", 4096L, "netherite", 512L), "template", 8L, false));

        // No container at all in storage. There is no working capital to be had, so the
        // planner must decline rather than emit a plan that cannot start.
        out.add(new Scenario("cycle with no container to bootstrap", repo -> {
            repo.add(recipe("slimeball", 1,
                List.of(ing(4, "dough"), ing(1, "water_bucket")),
                List.of(out("bucket", 1))), 0);
            repo.add(recipe("water_bucket", 1,
                List.of(ing(1, "bucket"), ing(1, "essence")), List.of()), 0);
        }, Map.of("dough", 4096L, "essence", 1024L), "slimeball", 64L, false));

        return out;
    }
}
