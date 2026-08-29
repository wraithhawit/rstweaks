package com.wraithhawit.rstweaks.test;

import com.refinedmods.refinedstorage.api.autocrafting.Ingredient;
import com.refinedmods.refinedstorage.api.autocrafting.Pattern;
import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.api.autocrafting.task.TaskPlan;
import com.wraithhawit.rstweaks.Config;
import com.wraithhawit.rstweaks.ledger.Ledger;
import com.wraithhawit.rstweaks.ledger.Pools;
import com.wraithhawit.rstweaks.ledger.ResourceIndex;
import com.wraithhawit.rstweaks.ledger.Slot;
import com.wraithhawit.rstweaks.ledger.Transform;
import com.wraithhawit.rstweaks.ledger.rs.PatternTransforms;
import com.wraithhawit.rstweaks.ledger.rs.Remainder;
import com.wraithhawit.rstweaks.ledger.rs.ToolPools;
import com.wraithhawit.rstweaks.planner.Durability;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs the ledger model over the plans the <em>shipping</em> planner actually emits.
 *
 * <p>Every other ledger test is built from patterns written to exercise it. This one is built from
 * nothing: it takes {@link PlannerExecutabilitySelfTest}'s scenarios — the rice slimeball, the cake
 * cycle, the self-duplicating template, the fluid swap, all of them cases that were once real bugs
 * — asks the LP planner for a plan, and audits that plan three ways.
 *
 * <p><b>1. The inference must not invent or destroy anything.</b> A slot's fate is only a
 * reattribution: "consume this ingredient, produce that byproduct" said as one fact instead of two.
 * So on any column the pools do not touch, the ledger's totals must equal what you get by reading
 * the raw {@code PatternLayout} the way Refined Storage does. If {@link PatternTransforms} ever
 * credits a byproduct the pattern never listed, or drops one it did, the two disagree — and that
 * disagreement is a duplication or a destruction, in a plan the mod ships today.
 *
 * <p><b>2. The plan must be funded.</b> Refined Storage fills a task's internal storage once from
 * {@code initialRequirements()} and never tops it up, so a plan whose recipes consume more of
 * something than the requirements plus its own production can supply is unrunnable. This is the
 * rice-slimeball bug stated as arithmetic rather than as a replay.
 *
 * <p><b>3. The plan must produce what was asked for.</b>
 *
 * <p>Claims 2 and 3 are <em>aggregate</em>, and deliberately weaker than
 * {@link PlannerExecutabilitySelfTest}'s replay: a plan can be funded in total and still deadlock
 * on ordering, which is what that replay catches and this cannot. They are complementary, not
 * redundant — this one sees the whole plan at once, that one sees time.
 */
public final class LedgerParitySelfTest {
    private static final List<String> FAILURES = new ArrayList<>();
    private static int checks;
    private static int fates;

    private LedgerParitySelfTest() {
    }

    public static CraftingPlanSelfTest.Result run() {
        FAILURES.clear();
        checks = 0;
        fates = 0;

        final boolean originalPlanner = Config.lpPlanner;
        final boolean originalRecycle = Config.keepRecycledResourcesInTask;
        final Remainder originalRemainder = Remainder.Holder.get();
        try {
            Config.lpPlanner = true;
            Config.keepRecycledResourcesInTask = true;
            Remainder.Holder.set(BUCKETS);
            for (final PlannerExecutabilitySelfTest.Scenario scenario
                : PlannerExecutabilitySelfTest.scenarios()) {
                try {
                    final TaskPlan plan = PlannerExecutabilitySelfTest.buildPlan(scenario);
                    if (plan == null) {
                        // Whether the planner should have engaged at all is the other suite's
                        // question, and it asks it in both directions.
                        continue;
                    }
                    audit(scenario.name(), plan);
                } catch (final RuntimeException | StackOverflowError e) {
                    FAILURES.add(scenario.name() + ": threw " + e);
                }
            }
        } finally {
            Config.lpPlanner = originalPlanner;
            Config.keepRecycledResourcesInTask = originalRecycle;
            Remainder.Holder.set(originalRemainder);
        }

        // Without this the suite is a mirror. Every scenario passed before any inference worked at
        // all -- the ledger agreed with Refined Storage because it was behaving exactly like
        // Refined Storage -- and a green run said nothing about whether a fate is ever found. It
        // has to be possible for this suite to tell a working inference from one that does nothing.
        expect("the run inferred at least one fate", fates > 0,
            () -> "every slot in every plan came back consumed");
        return new CraftingPlanSelfTest.Result(checks, List.copyOf(FAILURES));
    }

    /**
     * What vanilla's crafting remainder actually answers, and nothing more.
     *
     * <p>Deliberately not a mapping invented to make the scenarios work. A bucket comes back
     * because {@code Items.MILK_BUCKET} declares {@code craftRemainder(Items.BUCKET)}; a crucible
     * handed back by a machine declares nothing of the kind and is not in here, so the scenarios
     * that use one stay unattributed in the suite exactly as they would in game. A fixture more
     * generous than {@link com.wraithhawit.rstweaks.storage.ItemRemainder} would be testing a
     * capability this mod does not have.
     */
    private static final Remainder BUCKETS = input -> {
        final String id = String.valueOf(input);
        return id.endsWith("_bucket") && !id.equals("bucket")
            ? PlannerExecutabilitySelfTest.res("bucket")
            : null;
    };

    private static void audit(final String name, final TaskPlan plan) {
        final ResourceIndex index = new ResourceIndex();
        final Durability durability = Durability.Holder.get();
        final Remainder remainder = Remainder.Holder.get();
        final List<Pattern> patterns = new ArrayList<>(plan.patterns().keySet());
        final Pools pools = ToolPools.build(index, patterns, List.of(), durability);

        final Map<Integer, Long> stock = new LinkedHashMap<>();
        for (final ResourceAmount requirement : plan.initialRequirements()) {
            stock.merge(index.idOf(requirement.resource()), requirement.amount(), Math::addExact);
        }
        final Ledger ledger = Ledger.fromStock(pools, stock);

        // The same plan read the way Refined Storage reads it: ingredients out, outputs and
        // byproducts in, with no notion that any of them are related.
        final Map<Integer, Long> raw = new LinkedHashMap<>(Ledger.toColumns(pools, stock));

        for (final Map.Entry<Pattern, TaskPlan.PatternPlan> entry : plan.patterns().entrySet()) {
            final long iterations = entry.getValue().iterations();
            final Transform transform =
                PatternTransforms.build(entry.getKey(), index, durability, remainder).transform();
            fates += (int) transform.slots().stream().filter(Slot::returnsSomething).count();
            ledger.apply(transform, iterations);
            rawApply(entry.getKey(), iterations, index, pools, raw);
        }

        expect(name + ": the plan is funded", ledger.deficits().isEmpty(),
            () -> "spends what it never had: " + index.labelled(ledger.deficits()));

        final int target = index.idOf(plan.resource());
        expect(name + ": the plan produces what was asked for",
            ledger.balance(pools.columnOf(target)) >= plan.amount(),
            () -> "asked for " + plan.amount() + " and the books end at "
                + ledger.balance(pools.columnOf(target)));

        final Map<Integer, Long> divergent = new LinkedHashMap<>();
        ledger.balances().forEach((column, balance) -> {
            if (pools.isPooled(column)) {
                // A pooled column is counted in uses on one side and items on the other, so the
                // two are not comparable by construction. That is the point of the pool, and the
                // core suite is where its arithmetic is pinned.
                return;
            }
            final long asRs = raw.getOrDefault(column, 0L);
            if (asRs != balance) {
                divergent.put(column, balance - asRs);
            }
        });
        expect(name + ": slot fates only reattribute, they do not create or destroy",
            divergent.isEmpty(),
            () -> "the ledger and the raw layout disagree by " + index.labelled(divergent));
    }

    /** The layout read flatly, exactly as {@code CraftingState} and {@code InternalTaskPattern} do. */
    private static void rawApply(final Pattern pattern,
                                 final long iterations,
                                 final ResourceIndex index,
                                 final Pools pools,
                                 final Map<Integer, Long> balances) {
        for (final Ingredient ingredient : pattern.layout().ingredients()) {
            add(balances, pools, index.idOf(ingredient.inputs().getFirst()),
                -Math.multiplyExact(ingredient.amount(), iterations));
        }
        for (final ResourceAmount output : pattern.layout().outputs()) {
            add(balances, pools, index.idOf(output.resource()),
                Math.multiplyExact(output.amount(), iterations));
        }
        for (final ResourceAmount byproduct : pattern.layout().byproducts()) {
            add(balances, pools, index.idOf(byproduct.resource()),
                Math.multiplyExact(byproduct.amount(), iterations));
        }
    }

    private static void add(final Map<Integer, Long> balances,
                            final Pools pools,
                            final int resource,
                            final long amount) {
        balances.merge(pools.columnOf(resource),
            Math.multiplyExact(amount, pools.unitsOf(resource)), Math::addExact);
    }

    private static void expect(final String what,
                               final boolean ok,
                               final java.util.function.Supplier<String> detail) {
        checks++;
        if (!ok) {
            FAILURES.add(what + " -- " + detail.get());
        }
    }
}
