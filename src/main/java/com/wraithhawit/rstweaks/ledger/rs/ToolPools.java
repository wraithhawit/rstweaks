package com.wraithhawit.rstweaks.ledger.rs;

import com.refinedmods.refinedstorage.api.autocrafting.Ingredient;
import com.refinedmods.refinedstorage.api.autocrafting.Pattern;
import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.wraithhawit.rstweaks.ledger.Pools;
import com.wraithhawit.rstweaks.ledger.ResourceIndex;
import com.wraithhawit.rstweaks.planner.Durability;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A {@link Pools} built from {@link Durability}: every wear level of one tool becomes one column
 * counted in uses.
 *
 * <p>The arithmetic then falls out of {@link com.wraithhawit.rstweaks.ledger.Transform#net}. A
 * slot of {@code crystal@0} becoming {@code crystal@1} consumes 1000 units and returns 999, so it
 * costs one use; a recipe that burns five points costs five; crafting a fresh crystal supplies a
 * whole tool's worth. None of that is written down anywhere as durability logic — it is
 * subtraction over {@link #unitsOf}.
 *
 * <h2>Why a pool has to be earned</h2>
 *
 * <p>Uses in a pool are fungible, and destruction is not. Ten crystals with a hundred uses each
 * are a thousand uses, but they cannot satisfy a recipe that eats one whole crystal — and a
 * planner that thinks they can promises a craft that deadlocks on the first attempt. So a tool
 * family becomes a pool only when <b>every</b> pattern that consumes it also hands the same tool
 * back, one for one, more worn than it went in. Anything else stays an ordinary item, which is no
 * worse than the model Refined Storage ships.
 *
 * <p>This mirrors {@code DurabilityClasses.isWearAndReturn}, deliberately: the ledger model is not
 * yet wired into the planner, so for now the two carry the same rule. When the planner moves onto
 * this package, that one goes away rather than drifting.
 */
public final class ToolPools implements Pools {
    private final Map<Integer, Integer> columnOf;
    private final Map<Integer, Long> unitsOf;

    private ToolPools(final Map<Integer, Integer> columnOf, final Map<Integer, Long> unitsOf) {
        this.columnOf = Map.copyOf(columnOf);
        this.unitsOf = Map.copyOf(unitsOf);
    }

    /**
     * @param index      the ids to speak in; every durable resource involved is minted here
     * @param patterns   every pattern in the subgraph — the wear-and-return rule needs all of them
     * @param alsoInStock resources the network holds that no pattern mentions, so a worn tool
     *                    sitting in a drawer still counts toward the pool
     */
    public static Pools build(final ResourceIndex index,
                              final List<Pattern> patterns,
                              final Collection<ResourceKey> alsoInStock,
                              final Durability durability) {
        final Set<ResourceKey> all = new LinkedHashSet<>(alsoInStock);
        for (final Pattern pattern : patterns) {
            for (final Ingredient ingredient : pattern.layout().ingredients()) {
                all.addAll(ingredient.inputs());
            }
            for (final ResourceAmount output : pattern.layout().outputs()) {
                all.add(output.resource());
            }
            for (final ResourceAmount byproduct : pattern.layout().byproducts()) {
                all.add(byproduct.resource());
            }
        }

        final List<Set<ResourceKey>> families = groupByTool(all, durability);
        families.removeIf(family -> !isWearAndReturn(family, patterns, durability));
        if (families.isEmpty()) {
            return Pools.NONE;
        }

        final Map<Integer, Integer> columnOf = new LinkedHashMap<>();
        final Map<Integer, Long> unitsOf = new LinkedHashMap<>();
        for (final Set<ResourceKey> family : families) {
            // The lowest id is the representative, so the same graph always produces the same
            // columns whatever order the patterns arrived in.
            final Map<Integer, Long> members = new LinkedHashMap<>();
            int representative = Integer.MAX_VALUE;
            for (final ResourceKey member : family) {
                final int id = index.idOf(member);
                members.put(id, (long) Math.max(0, durability.usesLeft(member)));
                representative = Math.min(representative, id);
            }
            for (final Map.Entry<Integer, Long> member : members.entrySet()) {
                columnOf.put(member.getKey(), representative);
                unitsOf.put(member.getKey(), member.getValue());
            }
        }
        return new ToolPools(columnOf, unitsOf);
    }

    @Override
    public int columnOf(final int resource) {
        return this.columnOf.getOrDefault(resource, resource);
    }

    @Override
    public long unitsOf(final int resource) {
        return this.unitsOf.getOrDefault(resource, 1L);
    }

    /** Durable resources gathered into families of the same tool at different wear levels. */
    private static List<Set<ResourceKey>> groupByTool(final Collection<ResourceKey> resources,
                                                      final Durability durability) {
        final List<Set<ResourceKey>> families = new ArrayList<>();
        for (final ResourceKey resource : resources) {
            if (!durability.isDurable(resource)) {
                continue;
            }
            Set<ResourceKey> found = null;
            for (final Set<ResourceKey> family : families) {
                if (durability.sameTool(family.iterator().next(), resource)) {
                    found = family;
                    break;
                }
            }
            if (found == null) {
                found = new LinkedHashSet<>();
                families.add(found);
            }
            found.add(resource);
        }
        families.removeIf(family -> family.size() < 2);
        return families;
    }

    /**
     * Whether every pattern touching this family wears the tool and gives it back.
     *
     * <p>A pattern that consumes the tool without returning it destroys it; one returning more
     * than it took would be a duplication glitch; one returning it no more worn than it went in
     * would make the tool immortal in the ledger. Any of the three and the family is not a pool.
     */
    private static boolean isWearAndReturn(final Set<ResourceKey> family,
                                           final List<Pattern> patterns,
                                           final Durability durability) {
        boolean wornBySomething = false;
        for (final Pattern pattern : patterns) {
            long consumed = 0L;
            ResourceKey consumedKey = null;
            for (final Ingredient ingredient : pattern.layout().ingredients()) {
                for (final ResourceKey input : ingredient.inputs()) {
                    if (family.contains(input)) {
                        consumed += ingredient.amount();
                        if (consumedKey == null) {
                            consumedKey = input;
                        }
                        break;
                    }
                }
            }
            if (consumed == 0L) {
                continue;
            }
            long returned = 0L;
            ResourceKey returnedKey = null;
            for (final ResourceAmount byproduct : pattern.layout().byproducts()) {
                if (family.contains(byproduct.resource())) {
                    returned += byproduct.amount();
                    if (returnedKey == null) {
                        returnedKey = byproduct.resource();
                    }
                }
            }
            if (returned != consumed || returnedKey == null) {
                return false;
            }
            if (durability.usesLeft(consumedKey) - durability.usesLeft(returnedKey) < 1) {
                return false;
            }
            wornBySomething = true;
        }
        return wornBySomething;
    }
}
