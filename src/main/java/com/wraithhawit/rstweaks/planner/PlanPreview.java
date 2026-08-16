package com.wraithhawit.rstweaks.planner;

import com.refinedmods.refinedstorage.api.autocrafting.Ingredient;
import com.refinedmods.refinedstorage.api.autocrafting.Pattern;
import com.refinedmods.refinedstorage.api.autocrafting.PatternLayout;
import com.refinedmods.refinedstorage.api.autocrafting.preview.Preview;
import com.refinedmods.refinedstorage.api.autocrafting.preview.PreviewItem;
import com.refinedmods.refinedstorage.api.autocrafting.preview.PreviewType;
import com.refinedmods.refinedstorage.api.autocrafting.task.TaskPlan;
import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders an LP {@link TaskPlan} as the flat {@link Preview} the grid shows.
 *
 * <p>Refined Storage computes the request screen through an entirely separate path
 * from the task itself — {@code PreviewCraftingCalculatorListener.calculatePreview}
 * rather than {@code TaskPlanCraftingCalculatorListener.calculatePlan}. Hooking only
 * the latter produced a task that crafted 4 buckets while the screen still announced
 * 3000, which is indistinguishable from the feature not working. Worse, a preview
 * reporting thousands of missing containers can stop the request being made at all.
 *
 * <p>So the preview is derived from the same plan the task will run: crafted amounts
 * come from what the plan's patterns produce, and the drawn-from-storage amounts
 * come from its initial requirements. Nothing is reported missing, because a plan
 * only exists once the simulator has proven it executable.
 */
public final class PlanPreview {
    private PlanPreview() {
    }

    public static Preview from(final TaskPlan plan) {
        final Map<ResourceKey, long[]> rows = new LinkedHashMap<>();

        // [0] = available (drawn from the network), [1] = toCraft (made by this plan)
        for (final ResourceAmount requirement : plan.initialRequirements()) {
            rows.computeIfAbsent(requirement.resource(), k -> new long[2])[0] += requirement.amount();
        }
        for (final Map.Entry<Pattern, TaskPlan.PatternPlan> entry : plan.patterns().entrySet()) {
            final long iterations = entry.getValue().iterations();
            for (final ResourceAmount output : entry.getKey().layout().outputs()) {
                rows.computeIfAbsent(output.resource(), k -> new long[2])[1] +=
                    Math.multiplyExact(output.amount(), iterations);
            }
            // Byproducts are deliberately NOT counted as crafted. A recycled container is
            // handed back and reused, not manufactured: one bucket going round 128 times
            // is one bucket, and "to craft 128" reads as though 128 will exist. The
            // bucket still appears in the preview, under available, from the initial
            // requirements above — which is the true statement, and the only number that
            // changes if you have more or fewer of them.
            //
            // This also matches what the column means everywhere else in Refined Storage:
            // "to craft" counts crafting steps, and no step produces these.
        }

        // A container can also cycle as an OUTPUT rather than a byproduct: a fluid
        // substitution pattern turns a water bucket into an empty bucket and 1000mB of
        // water, and the empty bucket is an output. Sixty-four iterations then announce
        // "to craft: 64 buckets" for one bucket going round sixty-four times — the same
        // untruth the byproduct rule above was written to stop, arriving by another door.
        //
        // The test is that the plan asked the network for some, and produces exactly as
        // many as it consumes. Requisitioned means it came from your storage rather than
        // from crafting; net zero means the plan neither creates nor destroys any. Both
        // together mean it is being handed round, and the honest number is the one under
        // available. An intermediate the plan genuinely manufactures — a water bucket,
        // say — is not requisitioned, so it keeps its count.
        zeroOutRecycled(plan, rows);

        // The row for the thing you asked for says what you will get, not how many times
        // a pattern fires. Netherite templates are 1 template -> 2 templates, so 192
        // iterations produce 384 and consume 192; announcing 384 for a request of 192 is
        // true about the machine and false about the outcome. Set last, so it wins over
        // the recycling rule above — a self-duplicating target is requisitioned and does
        // net out, but you are still owed the amount you asked for.
        rows.computeIfAbsent(plan.resource(), k -> new long[2])[1] = plan.amount();

        final List<PreviewItem> items = new ArrayList<>(rows.size());
        rows.forEach((resource, counts) ->
            items.add(new PreviewItem(resource, counts[0], 0L, counts[1])));
        return new Preview(PreviewType.SUCCESS, items, List.of());
    }

    /** Clears the crafted count for anything the plan merely passes round. */
    private static void zeroOutRecycled(final TaskPlan plan,
                                        final Map<ResourceKey, long[]> rows) {
        final Map<ResourceKey, Long> produced = new LinkedHashMap<>();
        final Map<ResourceKey, Long> consumed = new LinkedHashMap<>();
        for (final Map.Entry<Pattern, TaskPlan.PatternPlan> entry : plan.patterns().entrySet()) {
            final long iterations = entry.getValue().iterations();
            final PatternLayout layout = entry.getKey().layout();
            for (final ResourceAmount output : layout.outputs()) {
                produced.merge(output.resource(),
                    Math.multiplyExact(output.amount(), iterations), Long::sum);
            }
            for (final ResourceAmount byproduct : layout.byproducts()) {
                produced.merge(byproduct.resource(),
                    Math.multiplyExact(byproduct.amount(), iterations), Long::sum);
            }
            for (final Ingredient ingredient : layout.ingredients()) {
                // The first alternative is the one the plan will draw on; the others are
                // substitutes it did not choose.
                consumed.merge(ingredient.inputs().getFirst(),
                    Math.multiplyExact(ingredient.amount(), iterations), Long::sum);
            }
        }
        for (final ResourceAmount requirement : plan.initialRequirements()) {
            final ResourceKey resource = requirement.resource();
            final long made = produced.getOrDefault(resource, 0L);
            if (made > 0L && made == consumed.getOrDefault(resource, 0L)) {
                final long[] row = rows.get(resource);
                if (row != null) {
                    row[1] = 0L;
                }
            }
        }
    }
}
