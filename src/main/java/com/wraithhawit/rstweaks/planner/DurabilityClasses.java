package com.wraithhawit.rstweaks.planner;

import com.refinedmods.refinedstorage.api.autocrafting.Ingredient;
import com.refinedmods.refinedstorage.api.autocrafting.Pattern;
import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.wraithhawit.rstweaks.ledger.rs.ToolPools;

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
        // The rule lives in ToolPools, which asks the same question of a family it builds
        // pools for. Two copies of "is this tool actually handed back" is exactly the kind of
        // duplication that drifts, and the half that drifts is the half nobody is running.
        groups.removeIf(group -> !ToolPools.isWearAndReturn(group, patterns, durability));
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
        mergeGroupsSharingAClass(groups, groupOf, classOf, durability);

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

    /**
     * Keeps tool grouping from tearing apart a class the graph had already unified.
     *
     * <p>{@code CraftingGraph.buildClasses} gives every alternative in an ingredient slot the same
     * signature, so a fuzzy slot's alternatives arrive here as <b>one</b> class — which is correct,
     * they are interchangeable. Grouping by {@code sameTool} then cuts across that, because a
     * copper hammer and an iron hammer are different tools. Splitting them left
     * {@code CraftingGraph.buildEffects} falling back to its documented assumption and counting
     * only {@code inputs().getFirst()}, so the planner demanded the first hammer in the list and
     * declared the craft impossible when the player held a different one.
     *
     * <p>The remedy is to go the other way: where a shared class spans several tool groups, merge
     * those groups into one. That is the honest model — if the slot accepts any of these tools,
     * then a use of any of them is a use, and their uses add up.
     *
     * <p><b>Only when every resource involved is durable.</b> A slot mixing a tool with an ordinary
     * item would otherwise drag that item into a class measured in uses, where {@code usesLeft}
     * reports zero for it and its stock silently stops counting. In that case the groups are left
     * alone and the tools stay as plain items, which is no worse than before this feature existed.
     */
    private static void mergeGroupsSharingAClass(final List<Set<ResourceKey>> groups,
                                                 final Map<ResourceKey, Integer> groupOf,
                                                 final Map<ResourceKey, Integer> classOf,
                                                 final Durability durability) {
        // Which tool groups each original class touches.
        final Map<Integer, Set<Integer>> groupsPerClass = new LinkedHashMap<>();
        groupOf.forEach((resource, group) -> {
            final Integer cls = classOf.get(resource);
            if (cls != null) {
                groupsPerClass.computeIfAbsent(cls, k -> new LinkedHashSet<>()).add(group);
            }
        });

        for (final Map.Entry<Integer, Set<Integer>> entry : groupsPerClass.entrySet()) {
            final int cls = entry.getKey();
            final Set<Integer> spanned = entry.getValue();
            final List<ResourceKey> members = classOf.entrySet().stream()
                .filter(e -> e.getValue() == cls)
                .map(Map.Entry::getKey)
                .toList();
            if (members.stream().anyMatch(m -> !durability.isDurable(m))) {
                continue;
            }
            // Fold every spanned group into the lowest-numbered one.
            final int target = spanned.stream().min(Integer::compareTo).orElseThrow();
            for (final int other : spanned) {
                if (other == target) {
                    continue;
                }
                groups.get(target).addAll(groups.get(other));
                for (final ResourceKey member : groups.get(other)) {
                    groupOf.put(member, target);
                }
                groups.get(other).clear();
            }
            // Then pull in the members that had no group of their own. A tool appearing at only
            // one wear level is dropped by groupByTool as "just an item", which is right in
            // isolation and wrong here: sharing a slot with a tool that IS worn makes it one of
            // the interchangeable options, and leaving it outside is precisely what split the
            // class and made the planner demand the first alternative by name.
            for (final ResourceKey member : members) {
                if (groupOf.putIfAbsent(member, target) == null) {
                    groups.get(target).add(member);
                }
            }
        }
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
     * How many uses a stack of this resource represents. A pristine tool is
     * {@code maxUses}; a worn one is whatever it has left.
     */
    static long usesOf(final ResourceKey resource, final long count, final Durability durability) {
        return Math.multiplyExact(count, Math.max(0, durability.usesLeft(resource)));
    }
}
