package com.wraithhawit.rstweaks.planner;

import com.refinedmods.refinedstorage.api.autocrafting.Ingredient;
import com.refinedmods.refinedstorage.api.autocrafting.Pattern;
import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Collapses every wear level of a tool into one resource class, so the program counts
 * <b>uses</b> instead of items.
 *
 * <p>A Mystical Agriculture infusion crystal is consumed by its recipe and handed back
 * one point more damaged. Refined Storage records the damaged one as a byproduct, but
 * {@code crystal@0} and {@code crystal@1} are different resources, so the pattern can
 * only ever consume the exact wear level it was encoded with: the first craft works and
 * the second has nothing to use.
 *
 * <p>Modelled as a chain — one pattern per damage step — the program grows by a variable
 * and a row per step, and solve time goes cubic: 0.57 ms at one step, 63 ms at a hundred,
 * 304 ms at two hundred, measured. Modelled as a supply of uses it costs one class and no
 * extra patterns, and it says the thing the player actually wants to know: this crystal
 * covers thirty crafts, make another when it runs out.
 *
 * <p><b>Only wear-and-return counts.</b> A tool merges into a uses class only when every
 * pattern that consumes it also hands the same tool back, one for one. A recipe that
 * genuinely destroys a tool is consuming an item, not a use, and stays as it was — a
 * distinction worth keeping, because getting it wrong would let the planner promise
 * crafts from a tool that is not coming back.
 */
final class DurabilityClasses {
    private DurabilityClasses() {
    }

    /**
     * @param classOf     resource to class index, reindexed to stay contiguous
     * @param toolClasses class indices now measured in uses rather than items
     */
    record Result(Map<ResourceKey, Integer> classOf, Set<Integer> toolClasses) {
    }

    static Result merge(final Map<ResourceKey, Integer> classOf,
                        final List<Pattern> patterns,
                        final Durability durability) {
        final List<Set<ResourceKey>> groups = groupByTool(classOf.keySet(), durability);
        groups.removeIf(group -> !isWearAndReturn(group, patterns, durability));
        if (groups.isEmpty()) {
            return new Result(classOf, Set.of());
        }

        // Point every wear level at its group's representative, then renumber so the
        // class indices stay contiguous — the classes list is addressed by position.
        final Map<ResourceKey, Integer> grouped = new LinkedHashMap<>(classOf);
        final Map<ResourceKey, Integer> groupOf = new LinkedHashMap<>();
        for (int g = 0; g < groups.size(); g++) {
            for (final ResourceKey member : groups.get(g)) {
                groupOf.put(member, g);
            }
        }

        final Map<String, Integer> renumbered = new LinkedHashMap<>();
        final Map<ResourceKey, Integer> out = new LinkedHashMap<>();
        final Set<Integer> toolClasses = new LinkedHashSet<>();
        for (final Map.Entry<ResourceKey, Integer> entry : grouped.entrySet()) {
            final Integer group = groupOf.get(entry.getKey());
            final String key = group != null ? "tool:" + group : "cls:" + entry.getValue();
            final int index = renumbered.computeIfAbsent(key, k -> renumbered.size());
            out.put(entry.getKey(), index);
            if (group != null) {
                toolClasses.add(index);
            }
        }
        return new Result(out, toolClasses);
    }

    private static List<Set<ResourceKey>> groupByTool(final Set<ResourceKey> resources,
                                                      final Durability durability) {
        final List<Set<ResourceKey>> groups = new ArrayList<>();
        for (final ResourceKey resource : resources) {
            if (!durability.isDurable(resource)) {
                continue;
            }
            Set<ResourceKey> found = null;
            for (final Set<ResourceKey> group : groups) {
                if (durability.sameTool(group.iterator().next(), resource)) {
                    found = group;
                    break;
                }
            }
            if (found == null) {
                found = new LinkedHashSet<>();
                groups.add(found);
            }
            found.add(resource);
        }
        // A single wear level that no pattern wears down is just an item; merging it
        // would change nothing and only widen the blast radius.
        groups.removeIf(group -> group.size() < 2);
        return groups;
    }

    /**
     * Whether every pattern touching this tool wears it and gives it back one for one.
     *
     * <p>A pattern that consumes the tool without returning it destroys it, and a pattern
     * returning more than it took would be a duplication glitch; either way the uses
     * model does not describe what happens, so the whole group stays as items.
     */
    private static boolean isWearAndReturn(final Set<ResourceKey> group,
                                           final List<Pattern> patterns,
                                           final Durability durability) {
        boolean wornBySomething = false;
        for (final Pattern pattern : patterns) {
            long consumed = 0L;
            for (final Ingredient ingredient : pattern.layout().ingredients()) {
                for (final ResourceKey input : ingredient.inputs()) {
                    if (group.contains(input)) {
                        consumed += ingredient.amount();
                        break;
                    }
                }
            }
            if (consumed == 0L) {
                continue;
            }
            long returned = 0L;
            for (final ResourceAmount byproduct : pattern.layout().byproducts()) {
                if (group.contains(byproduct.resource())) {
                    returned += byproduct.amount();
                }
            }
            if (returned != consumed) {
                return false;
            }
            // A recipe that costs no durability, or hands back a *less* worn tool, is not
            // wear-and-return and must not be modelled as a supply of uses — treating it
            // that way would make the tool immortal in the ledger.
            if (wearStep(pattern, group, durability) < 1L) {
                return false;
            }
            wornBySomething = true;
        }
        return wornBySomething;
    }

    /**
     * How much durability one iteration of this pattern costs, read off the pattern
     * rather than assumed.
     *
     * <p>Assuming one is wrong and quietly so: a recipe that burns five per craft would
     * have its tool last five times too long, the planner would under-order replacements,
     * and the executor would age the tool too slowly — a duplication bug wearing the
     * costume of a working feature. The pattern already carries the answer, in the gap
     * between the tool it consumes and the one it hands back.
     */
    static long wearStep(final Pattern pattern,
                         final Set<ResourceKey> group,
                         final Durability durability) {
        ResourceKey consumed = null;
        for (final Ingredient ingredient : pattern.layout().ingredients()) {
            for (final ResourceKey input : ingredient.inputs()) {
                if (group.contains(input)) {
                    consumed = input;
                    break;
                }
            }
            if (consumed != null) {
                break;
            }
        }
        ResourceKey returned = null;
        for (final ResourceAmount byproduct : pattern.layout().byproducts()) {
            if (group.contains(byproduct.resource())) {
                returned = byproduct.resource();
                break;
            }
        }
        if (consumed == null || returned == null) {
            return 0L;
        }
        return (long) durability.usesLeft(consumed) - durability.usesLeft(returned);
    }

    /** The wear step for a class index, for callers that only have {@code classOf}. */
    static long wearStep(final Pattern pattern,
                         final Map<ResourceKey, Integer> classOf,
                         final int cls,
                         final Durability durability) {
        final Set<ResourceKey> group = new LinkedHashSet<>();
        classOf.forEach((resource, index) -> {
            if (index == cls) {
                group.add(resource);
            }
        });
        return Math.max(1L, wearStep(pattern, group, durability));
    }

    /**
     * How many uses a stack of this resource represents. A pristine tool is
     * {@code maxUses}; a worn one is whatever it has left.
     */
    static long usesOf(final ResourceKey resource, final long count, final Durability durability) {
        return Math.multiplyExact(count, Math.max(0, durability.usesLeft(resource)));
    }
}
