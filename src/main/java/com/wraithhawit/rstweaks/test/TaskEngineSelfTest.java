package com.wraithhawit.rstweaks.test;

import com.refinedmods.refinedstorage.api.autocrafting.Ingredient;
import com.refinedmods.refinedstorage.api.autocrafting.Pattern;
import com.refinedmods.refinedstorage.api.autocrafting.PatternLayout;
import com.refinedmods.refinedstorage.api.autocrafting.PatternRepositoryImpl;
import com.refinedmods.refinedstorage.api.autocrafting.calculation.CancellationToken;
import com.refinedmods.refinedstorage.api.autocrafting.calculation.CraftingCalculatorImpl;
import com.refinedmods.refinedstorage.api.autocrafting.task.ExternalPatternSinkProvider;
import com.refinedmods.refinedstorage.api.autocrafting.task.StepBehavior;
import com.refinedmods.refinedstorage.api.autocrafting.task.Task;
import com.refinedmods.refinedstorage.api.autocrafting.task.TaskImpl;
import com.refinedmods.refinedstorage.api.autocrafting.task.TaskListener;
import com.refinedmods.refinedstorage.api.autocrafting.task.TaskPlan;
import com.refinedmods.refinedstorage.api.autocrafting.task.TaskPlanCraftingCalculatorListener;
import com.refinedmods.refinedstorage.api.autocrafting.task.TaskState;
import com.refinedmods.refinedstorage.api.core.Action;
import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.api.storage.Actor;
import com.refinedmods.refinedstorage.api.storage.StorageImpl;
import com.refinedmods.refinedstorage.api.storage.root.RootStorageImpl;
import com.wraithhawit.rstweaks.Config;
import com.wraithhawit.rstweaks.planner.Durability;
import com.wraithhawit.rstweaks.planner.LpCraftingPlanner;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Consumer;

import javax.annotation.Nullable;

/**
 * Runs a crafting task to completion through Refined Storage's real task engine, and
 * audits what the network is left holding.
 *
 * <p><b>This one cannot be run headlessly, and that is the point.</b> Everything it
 * exercises lives in mixins — {@code TaskImplMixin}, {@code AbstractTaskPatternMixin},
 * {@code InternalTaskPatternMixin} — so a plain JVM runs the unmodified Refined Storage
 * and proves nothing about our code. It is a gametest so that Mixin has actually applied
 * the transformations before the assertions run. See issue #2.
 *
 * <p>What it is for is item destruction. {@code TaskContainer.step} wraps each task step
 * in {@code catch (Exception)}, and its response to one is to mark the task
 * <em>completed</em>, notify the listeners — which fires the "crafting finished" toast —
 * and drop the task with its internal storage still inside it. Everything the task had
 * extracted is gone, and the player is told it worked. That is not a hypothetical: a
 * {@code @Unique} field whose inline initializer never reached the instance threw an NPE
 * on the first statement of every step from 0.2.57 to 0.2.63, and killed 26 out of 26
 * crafts in one session of a real world.
 *
 * <p>So the loop below reproduces {@code TaskContainer}'s catch rather than letting the
 * exception escape: a throw has to be reported as the item-loss event it really is, not
 * as a stack trace from a test harness. And completion alone is never accepted as a pass
 * — the ledger in {@link #audit} is what separates "the craft worked" from "the craft
 * said it worked".
 */
public final class TaskEngineSelfTest {
    private static final Actor ACTOR = () -> "rstweaks-task-engine-test";

    /** No external patterns in these fixtures, so nothing is ever routed to a machine. */
    private static final ExternalPatternSinkProvider NO_SINKS = layout -> List.of();

    /**
     * A task that cannot finish would otherwise spin here forever. Generous enough that
     * no legitimate fixture reaches it: the largest below is 100 iterations, and
     * {@link StepBehavior#DEFAULT} retires at least one per step.
     */
    private static final int MAX_STEPS = 4096;

    private TaskEngineSelfTest() {
    }

    /** Which planner produced the plan, since the executor has to cope with both. */
    private enum Planner {
        /** Refined Storage's own calculator, i.e. what runs with {@code lpPlanner} off. */
        STOCK,
        /** Ours. The cycle and durability fixtures have no stock plan at all. */
        LP
    }

    private record Scenario(String name,
                            Planner planner,
                            @Nullable Durability durability,
                            Consumer<PatternRepositoryImpl> patterns,
                            Map<String, Long> stored,
                            String request,
                            long amount) {
    }

    public static CraftingPlanSelfTest.Result run() {
        final List<String> failures = new ArrayList<>();
        final List<Scenario> scenarios = scenarios();
        final boolean originalPlanner = Config.lpPlanner;
        final boolean originalRecycle = Config.keepRecycledResourcesInTask;
        final boolean originalDurability = Config.durabilityAwarePlanning;
        try {
            Config.keepRecycledResourcesInTask = true;
            for (final Scenario scenario : scenarios) {
                Config.lpPlanner = scenario.planner() == Planner.LP;
                Config.durabilityAwarePlanning = scenario.durability() != null;
                Durability.Holder.set(scenario.durability() == null
                    ? Durability.NONE : scenario.durability());
                try {
                    check(scenario, failures);
                } catch (final RuntimeException | StackOverflowError e) {
                    failures.add(scenario.name() + ": threw " + e);
                }
            }
        } finally {
            Config.lpPlanner = originalPlanner;
            Config.keepRecycledResourcesInTask = originalRecycle;
            Config.durabilityAwarePlanning = originalDurability;
            Durability.Holder.set(Durability.NONE);
        }
        return new CraftingPlanSelfTest.Result(scenarios.size(), failures);
    }

    private static void check(final Scenario scenario, final List<String> failures) {
        final PatternRepositoryImpl patterns = new PatternRepositoryImpl();
        scenario.patterns().accept(patterns);

        // Order matters: RootStorageImpl snapshots a source when it is added, so the
        // stock has to be in the source before the source is in the root.
        final StorageImpl source = new StorageImpl();
        scenario.stored().forEach((id, amount) ->
            source.insert(res(id), amount, Action.EXECUTE, ACTOR));
        final RootStorageImpl storage = new RootStorageImpl();
        storage.addSource(source);

        final Map<ResourceKey, Long> before = contents(storage);

        final TaskPlan plan = plan(scenario, patterns, storage);
        if (plan == null) {
            failures.add(scenario.name() + ": the " + scenario.planner()
                + " planner produced no plan, so nothing was executed. A scenario that "
                + "silently stops planning passes every assertion below without running "
                + "a single task step.");
            return;
        }

        final String problem = execute(plan, storage);
        if (problem != null) {
            failures.add(scenario.name() + ": " + problem);
            return;
        }
        audit(scenario, plan, before, contents(storage), failures);
    }

    @Nullable
    private static TaskPlan plan(final Scenario scenario,
                                 final PatternRepositoryImpl patterns,
                                 final RootStorageImpl storage) {
        if (scenario.planner() == Planner.LP) {
            return LpCraftingPlanner.attempt(
                patterns, storage, res(scenario.request()), scenario.amount()).plan();
        }
        // The stock path, reached with lpPlanner off. Our executor mixins sit on the
        // classes Refined Storage uses for every craft, so an ordinary plan going
        // through them unharmed is worth asserting in its own right.
        final Optional<TaskPlan> stock = TaskPlanCraftingCalculatorListener.calculatePlan(
            new CraftingCalculatorImpl(patterns, storage),
            res(scenario.request()),
            scenario.amount(),
            CancellationToken.NONE);
        return stock.orElse(null);
    }

    /**
     * Steps the task the way the network does, and returns what went wrong.
     *
     * <p>The {@code catch} is not defensive coding — it is a faithful copy of
     * {@code TaskContainer.step}, which really does swallow any {@link Exception}, mark
     * the task completed and drop it. Letting the exception propagate instead would
     * report a harness failure; reporting it here names the actual consequence.
     */
    @Nullable
    private static String execute(final TaskPlan plan, final RootStorageImpl storage) {
        final Task task = new TaskImpl(plan, ACTOR, false);
        // How the network wires a running task in: TaskContainer.attach registers it as a
        // listener on the storage so it can intercept inserts meant for it.
        storage.addListener(task);
        try {
            for (int steps = 0; steps < MAX_STEPS; steps++) {
                if (task.getState() == TaskState.COMPLETED) {
                    return null;
                }
                final boolean changed;
                try {
                    changed = task.step(storage, NO_SINKS, StepBehavior.DEFAULT, TaskListener.EMPTY);
                } catch (final Exception e) {
                    return "step " + steps + " threw " + e + ". Refined Storage catches this,"
                        + " marks the task COMPLETED, fires the finished toast and drops the"
                        + " task with its internal storage inside it -- everything it had"
                        + " extracted is destroyed.";
                }
                // step() reports whether anything changed, not whether the task is done.
                // Nothing outside the task can feed these fixtures -- no machines, no
                // players -- so a step that changes nothing will change nothing next tick
                // either. That is the rice-slimeball deadlock, and in game it looks like a
                // craft frozen at 0% forever.
                if (!changed) {
                    return "made no progress and is stuck in " + task.getState()
                        + " after " + steps + " steps. Nothing else can feed this task, so"
                        + " this is a craft that never finishes.";
                }
            }
            return "did not finish within " + MAX_STEPS + " steps; stuck in "
                + task.getState();
        } finally {
            storage.removeListener(task);
        }
    }

    /**
     * The ledger. Every resource the plan touches has one arithmetically correct final
     * amount: what was there, plus everything the patterns output, minus everything they
     * consumed. Anything else is items created or destroyed.
     *
     * <p>Durable tools are exempted from the exact check and audited separately, because
     * which <em>wear level</em> comes back is decided at execution time by the tool that
     * was actually in the internal storage — that substitution is the whole feature. What
     * must hold for them is the count of uses, not the identity of the resource.
     */
    private static void audit(final Scenario scenario,
                              final TaskPlan plan,
                              final Map<ResourceKey, Long> before,
                              final Map<ResourceKey, Long> after,
                              final List<String> failures) {
        final Durability durability = Durability.Holder.get();
        final Map<ResourceKey, Long> made = new HashMap<>();
        final Map<ResourceKey, Long> used = new HashMap<>();
        plan.patterns().forEach((pattern, patternPlan) -> {
            final long iterations = patternPlan.iterations();
            final PatternLayout layout = pattern.layout();
            layout.outputs().forEach(o ->
                made.merge(o.resource(), o.amount() * iterations, Long::sum));
            layout.byproducts().forEach(b ->
                made.merge(b.resource(), b.amount() * iterations, Long::sum));
            layout.ingredients().forEach(i ->
                used.merge(i.inputs().getFirst(), i.amount() * iterations, Long::sum));
        });

        // The requested item first, because "completed and delivered nothing" is the
        // symptom this whole class exists for and it deserves its own sentence.
        final ResourceKey target = res(scenario.request());
        final long gained = after.getOrDefault(target, 0L) - before.getOrDefault(target, 0L);
        if (gained != scenario.amount()) {
            failures.add(scenario.name() + ": the task completed but the network gained "
                + gained + " " + scenario.request() + ", not the " + scenario.amount()
                + " requested");
        }

        final List<ResourceKey> everything = new ArrayList<>(before.keySet());
        after.keySet().forEach(key -> {
            if (!everything.contains(key)) {
                everything.add(key);
            }
        });
        made.keySet().forEach(key -> {
            if (!everything.contains(key)) {
                everything.add(key);
            }
        });
        for (final ResourceKey resource : everything) {
            if (durability.isDurable(resource)) {
                continue;
            }
            final long expected = before.getOrDefault(resource, 0L)
                + made.getOrDefault(resource, 0L)
                - used.getOrDefault(resource, 0L);
            final long actual = after.getOrDefault(resource, 0L);
            if (actual != expected) {
                failures.add(scenario.name() + ": " + resource + " ended at " + actual
                    + ", not the " + expected + " the plan accounts for ("
                    + before.getOrDefault(resource, 0L) + " stored + "
                    + made.getOrDefault(resource, 0L) + " made - "
                    + used.getOrDefault(resource, 0L) + " used)"
                    + (actual < expected ? "  <-- ITEMS DESTROYED" : "  <-- ITEMS CREATED"));
            }
        }

        auditDurability(scenario, plan, before, after, durability, failures);
    }

    /**
     * Uses in, uses out. A tool may come back at any wear level, but the total number of
     * uses the network holds afterwards is fixed: what it started with, plus whatever
     * fresh tools the plan crafted, minus one per iteration that burned one.
     *
     * <p>Both directions matter. A shortfall means a partly-worn tool was thrown away;
     * a surplus means a tool came back less worn than it went in, which is durability
     * created out of nothing — the 0.2.57 duplication bug.
     */
    private static void auditDurability(final Scenario scenario,
                                        final TaskPlan plan,
                                        final Map<ResourceKey, Long> before,
                                        final Map<ResourceKey, Long> after,
                                        final Durability durability,
                                        final List<String> failures) {
        long started = 0L;
        for (final Map.Entry<ResourceKey, Long> entry : before.entrySet()) {
            started += uses(durability, entry.getKey(), entry.getValue());
        }
        long crafted = 0L;
        long spent = 0L;
        for (final Map.Entry<Pattern, TaskPlan.PatternPlan> entry : plan.patterns().entrySet()) {
            final PatternLayout layout = entry.getKey().layout();
            final long iterations = entry.getValue().iterations();
            for (final ResourceAmount output : layout.outputs()) {
                crafted += uses(durability, output.resource(), output.amount()) * iterations;
            }
            for (final Ingredient ingredient : layout.ingredients()) {
                if (durability.isDurable(ingredient.inputs().getFirst())) {
                    spent += ingredient.amount() * iterations;
                    break;
                }
            }
        }
        if (started == 0L && crafted == 0L) {
            return;
        }
        long ended = 0L;
        for (final Map.Entry<ResourceKey, Long> entry : after.entrySet()) {
            ended += uses(durability, entry.getKey(), entry.getValue());
        }
        final long expected = started + crafted - spent;
        if (ended != expected) {
            failures.add(scenario.name() + ": durability was not conserved -- started with "
                + started + " uses, crafted " + crafted + ", spent " + spent
                + ", ended with " + ended + " (expected " + expected + "). Storage holds "
                + describe(after));
        }
    }

    private static long uses(final Durability durability,
                             final ResourceKey resource,
                             final long count) {
        return durability.isDurable(resource) ? count * durability.usesLeft(resource) : 0L;
    }

    private static Map<ResourceKey, Long> contents(final RootStorageImpl storage) {
        final Map<ResourceKey, Long> out = new HashMap<>();
        storage.getAll().forEach(amount -> out.merge(amount.resource(), amount.amount(), Long::sum));
        return out;
    }

    private static String describe(final Map<ResourceKey, Long> contents) {
        final Map<String, Long> sorted = new TreeMap<>();
        contents.forEach((resource, amount) -> sorted.put(String.valueOf(resource), amount));
        return sorted.toString();
    }

    // ---------------------------------------------------------------- fixtures

    private static ResourceKey res(final String id) {
        return PlannerExecutabilitySelfTest.res(id);
    }

    private static Pattern pattern(final String id,
                                   final List<Ingredient> ingredients,
                                   final List<ResourceAmount> outputs,
                                   final List<ResourceAmount> byproducts) {
        return new Pattern(
            UUID.nameUUIDFromBytes(("rstweaks-task:" + id).getBytes(StandardCharsets.UTF_8)),
            PatternLayout.internal(ingredients, outputs, byproducts));
    }

    private static Ingredient ing(final long amount, final String input) {
        return new Ingredient(amount, List.of(res(input)));
    }

    private static ResourceAmount out(final String id, final long amount) {
        return new ResourceAmount(res(id), amount);
    }

    private static List<Scenario> scenarios() {
        final List<Scenario> out = new ArrayList<>();

        // The baseline nobody thinks to write: an ordinary two-step craft, planned by
        // stock Refined Storage, run through the executor our mixins are attached to.
        // Every failure this class has ever caught was on an exotic path, and the reason
        // that is alarming is that the mixins are on the path *all* crafts take.
        out.add(new Scenario("plain two-step craft", Planner.STOCK, null, repo -> {
            repo.add(pattern("plank", List.of(ing(1, "log")), List.of(out("plank", 4)),
                List.of()), 0);
            repo.add(pattern("chest", List.of(ing(8, "plank")), List.of(out("chest", 1)),
                List.of()), 0);
        }, Map.of("log", 64L), "chest", 4L));

        // A byproduct with no cycle in it. Held in the internal storage by
        // InternalTaskPatternMixin until the task finishes, so this asserts the delayed
        // hand-back really happens rather than the slag being quietly dropped.
        out.add(new Scenario("byproduct returned at the end", Planner.STOCK, null, repo ->
            repo.add(pattern("ingot", List.of(ing(1, "ore")), List.of(out("ingot", 2)),
                List.of(out("slag", 1))), 0),
            Map.of("ore", 16L), "ingot", 32L));

        // The rice slimeball shape: the bucket comes back as a byproduct of the root
        // pattern and is needed again by the next iteration. Stock RS sends it to the
        // network the moment it is made and the craft deadlocks on iteration two.
        out.add(new Scenario("recycled container", Planner.LP, null, repo -> {
            repo.add(pattern("slimeball",
                List.of(ing(4, "dough"), ing(1, "water_bucket")),
                List.of(out("slimeball", 1)), List.of(out("bucket", 1))), 0);
            repo.add(pattern("water_bucket",
                List.of(ing(1, "bucket"), ing(1, "essence")),
                List.of(out("water_bucket", 1)), List.of()), 0);
        }, Map.of("dough", 4096L, "essence", 1024L, "bucket", 1L), "slimeball", 64L));

        // Netherite templates: the recycled resource is an OUTPUT of the root pattern and
        // is also the requested item, which is the other redirect in InternalTaskPattern.
        out.add(new Scenario("self-duplicating pattern", Planner.LP, null, repo ->
            repo.add(pattern("template",
                List.of(ing(1, "template"), ing(7, "diamond"), ing(1, "netherite")),
                List.of(out("template", 2)), List.of()), 0),
            Map.of("template", 1L, "diamond", 4096L, "netherite", 512L), "template", 100L));

        // A tool that wears out. This is the exact path the 0.2.64 NPE was on -- the
        // substitution in AbstractTaskPattern.extractAll runs once per iteration here, so
        // if anything on it throws, the whole craft dies rather than losing a tweak.
        out.add(new Scenario("worn tool used across the whole craft", Planner.LP,
            new FakeDurability("crystal", 100), repo ->
            repo.add(pattern("infuse",
                List.of(ing(1, FakeDurability.worn("crystal", 0)), ing(1, "material")),
                List.of(out("product", 1)),
                List.of(out(FakeDurability.worn("crystal", 1), 1))), 0),
            Map.of(FakeDurability.worn("crystal", 0), 1L, "material", 4096L), "product", 64L));

        // Issue #10, reduced: the tool in stock cannot cover the run, so replacements are
        // crafted -- and the old one's remaining uses must be spent first. The durability
        // ledger is what proves the stranded-uses bug would be caught: burning a fresh
        // crystal while a nearly-spent one sits in storage ends with a surplus of uses.
        out.add(new Scenario("worn tool runs out and a replacement is crafted", Planner.LP,
            new FakeDurability("crystal", 10), repo -> {
                repo.add(pattern("infuse",
                    List.of(ing(1, FakeDurability.worn("crystal", 0)), ing(1, "material")),
                    List.of(out("product", 1)),
                    List.of(out(FakeDurability.worn("crystal", 1), 1))), 0);
                repo.add(pattern("newcrystal", List.of(ing(8, "gem")),
                    List.of(out(FakeDurability.worn("crystal", 0), 1)), List.of()), 0);
            },
            Map.of(FakeDurability.worn("crystal", 7), 1L, "material", 4096L, "gem", 4096L),
            "product", 25L));

        return out;
    }
}
