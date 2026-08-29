package com.wraithhawit.rstweaks.ledger.rs;

import com.refinedmods.refinedstorage.api.autocrafting.Ingredient;
import com.refinedmods.refinedstorage.api.autocrafting.Pattern;
import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.wraithhawit.rstweaks.ledger.Quantity;
import com.wraithhawit.rstweaks.ledger.ResourceIndex;
import com.wraithhawit.rstweaks.ledger.Slot;
import com.wraithhawit.rstweaks.ledger.Transform;
import com.wraithhawit.rstweaks.planner.Durability;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Recovers the link Refined Storage threw away: which ingredient became which byproduct.
 *
 * <p>A {@code PatternLayout} is three flat lists. The executor returns every byproduct and the
 * planner reads none of them, and neither of them knows that the damaged crystal in the byproduct
 * list <em>is</em> the crystal from the ingredient list. This class puts that back by inference,
 * because the data to state it directly does not exist.
 *
 * <p><b>This inference is the load-bearing risk of the whole ledger model.</b> Everything
 * downstream is arithmetic that cannot be wrong on its own; this is the one place a wrong guess
 * turns into a wrong plan. So it is deliberately conservative: three rules, tried in order, and
 * anything they cannot explain stays what Refined Storage already thought it was — a consumed
 * ingredient and an unrelated output. That is the current behaviour, so a failure to infer costs
 * an optimisation, never an item.
 *
 * <ol>
 *   <li><b>Same resource</b> — the byproduct is the ingredient, unchanged. A catalyst.</li>
 *   <li><b>Same tool, more worn</b> — {@link Durability#sameTool} with fewer uses left. Strictly
 *       fewer: a byproduct handed back <em>less</em> worn is not wear, and modelling it as a fate
 *       would make the tool immortal.</li>
 *   <li><b>The game's own remainder</b> — {@link Remainder}, which is
 *       {@code getCraftingRemainingItem} in disguise. A bucket after the milk.</li>
 * </ol>
 *
 * <p>Everything it could not attribute is reported in {@link Result#notes()} rather than swallowed.
 * A planner that silently declines is indistinguishable from a planner that was never installed,
 * and this project has now paid for that lesson twice.
 */
public final class PatternTransforms {
    private PatternTransforms() {
    }

    /**
     * @param transform the pattern as slots with fates
     * @param notes     what the inference could not explain, in words meant for a log a player
     *                  will be asked to read
     */
    public record Result(Transform transform, List<String> notes) {
        public Result {
            notes = List.copyOf(notes);
        }

        public boolean clean() {
            return this.notes.isEmpty();
        }
    }

    /** Uses the installed durability and remainder adapters. */
    public static Result build(final Pattern pattern, final ResourceIndex index) {
        return build(pattern, index, Durability.Holder.get(), Remainder.Holder.get());
    }

    public static Result build(final Pattern pattern,
                               final ResourceIndex index,
                               final Durability durability,
                               final Remainder remainder) {
        final List<String> notes = new ArrayList<>();
        final Map<ResourceKey, Long> unattributed = new LinkedHashMap<>();
        for (final ResourceAmount byproduct : pattern.layout().byproducts()) {
            unattributed.merge(byproduct.resource(), byproduct.amount(), Math::addExact);
        }

        final List<Slot> slots = new ArrayList<>();
        for (final Ingredient ingredient : pattern.layout().ingredients()) {
            // The first input decides the slot, matching the convention the rest of the planner
            // already uses: alternatives in one slot are interchangeable by construction.
            final ResourceKey input = ingredient.inputs().getFirst();
            final int resource = index.idOf(input);
            final long amount = ingredient.amount();
            noteFuzzyDisagreement(ingredient, unattributed, durability, remainder, notes);

            final ResourceKey fate = fateOf(input, unattributed, durability, remainder, notes);
            if (fate == null) {
                slots.add(Slot.consumed(resource, amount));
                continue;
            }
            final long available = unattributed.getOrDefault(fate, 0L);
            final long matched = Math.min(amount, available);
            slots.add(Slot.transforming(resource, matched, index.idOf(fate)));
            if (matched < amount) {
                // Half a stack came back and half did not. Splitting the slot says exactly that,
                // where rounding either way would invent or destroy the difference.
                slots.add(Slot.consumed(resource, amount - matched));
                notes.add(label(pattern) + ": " + input + " x" + amount + " returns only "
                    + matched + " as " + fate + "; the rest is treated as consumed");
            }
            if (matched == available) {
                unattributed.remove(fate);
            } else {
                unattributed.put(fate, available - matched);
            }
        }

        final List<Quantity> outputs = new ArrayList<>();
        for (final ResourceAmount output : pattern.layout().outputs()) {
            outputs.add(new Quantity(index.idOf(output.resource()), output.amount()));
        }
        // Whatever no slot claimed really is production: a recipe that hands back more than it
        // took, or a genuine second result Refined Storage happened to file as a byproduct.
        unattributed.forEach((resource, amount) ->
            outputs.add(new Quantity(index.idOf(resource), amount)));

        return new Result(Transform.of(label(pattern), slots, outputs), notes);
    }

    @Nullable
    private static ResourceKey fateOf(final ResourceKey input,
                                      final Map<ResourceKey, Long> unattributed,
                                      final Durability durability,
                                      final Remainder remainder,
                                      final List<String> notes) {
        if (unattributed.getOrDefault(input, 0L) > 0L) {
            return input;
        }
        if (durability.isDurable(input)) {
            for (final ResourceKey candidate : unattributed.keySet()) {
                if (!durability.sameTool(input, candidate)) {
                    continue;
                }
                if (durability.usesLeft(candidate) < durability.usesLeft(input)) {
                    return candidate;
                }
                notes.add(input + " comes back as " + candidate + ", which is no more worn;"
                    + " not modelled as wear, because that would make the tool immortal");
            }
        }
        final ResourceKey leftover = remainder.remainderOf(input);
        if (leftover != null && unattributed.getOrDefault(leftover, 0L) > 0L) {
            return leftover;
        }
        return null;
    }

    /**
     * A fuzzy slot whose alternatives would not all be treated the same way.
     *
     * <p>Taking the first input is safe while the alternatives are interchangeable, and this is
     * how that assumption breaks: "any bucket" where only one of them has a remainder waiting,
     * "any hammer" where the byproduct is one specific hammer. Worth a line in the log, because
     * the resulting plan will be quietly conservative rather than wrong.
     */
    private static void noteFuzzyDisagreement(final Ingredient ingredient,
                                              final Map<ResourceKey, Long> unattributed,
                                              final Durability durability,
                                              final Remainder remainder,
                                              final List<String> notes) {
        final List<ResourceKey> inputs = ingredient.inputs();
        if (inputs.size() < 2 || unattributed.isEmpty()) {
            return;
        }
        final boolean firstHasFate = fateOf(inputs.getFirst(), unattributed, durability, remainder,
            new ArrayList<>()) != null;
        for (int i = 1; i < inputs.size(); i++) {
            final boolean alsoHasFate = fateOf(inputs.get(i), unattributed, durability, remainder,
                new ArrayList<>()) != null;
            if (alsoHasFate != firstHasFate) {
                notes.add("fuzzy slot disagrees: " + inputs.getFirst() + " and " + inputs.get(i)
                    + " are alternatives but only one of them is handed back; the first decides");
                return;
            }
        }
    }

    private static String label(final Pattern pattern) {
        final List<ResourceAmount> outputs = pattern.layout().outputs();
        return outputs.isEmpty() ? String.valueOf(pattern.id()) : String.valueOf(outputs.getFirst().resource());
    }
}
