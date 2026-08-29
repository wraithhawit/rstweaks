package com.wraithhawit.rstweaks.ledger.rs;

import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.wraithhawit.rstweaks.ledger.Pools;
import com.wraithhawit.rstweaks.ledger.ResourceIndex;
import com.wraithhawit.rstweaks.planner.Durability;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * A {@link Pools} over the planner's own resource classes.
 *
 * <p>{@code CraftingGraph} already merges resources two ways: fuzzy alternatives that no recipe
 * distinguishes become one class, and every wear level of a tool becomes one class counted in uses.
 * Those are the same idea {@link Pools} states in two methods — which column a resource pays into,
 * and what one of it is worth there — so the graph's classes and the ledger's columns are the same
 * structure seen twice.
 *
 * <p>This is the bridge between them: a column is the id of the class's representative resource, so
 * the ledger can do its arithmetic in its own terms and the answer maps straight back to a class
 * index the matrix can use. The representative is the lowest id in the class, which makes the
 * mapping independent of the order patterns happened to arrive in.
 */
public final class ClassPools implements Pools {
    private final Map<Integer, Integer> columnOf;
    private final Map<Integer, Long> unitsOf;
    private final Map<Integer, Integer> classOfColumn;
    private final Set<Integer> toolColumns;

    private ClassPools(final Map<Integer, Integer> columnOf,
                       final Map<Integer, Long> unitsOf,
                       final Map<Integer, Integer> classOfColumn,
                       final Set<Integer> toolColumns) {
        this.columnOf = Map.copyOf(columnOf);
        this.unitsOf = Map.copyOf(unitsOf);
        this.classOfColumn = Map.copyOf(classOfColumn);
        this.toolColumns = Set.copyOf(toolColumns);
    }

    public static ClassPools build(final ResourceIndex index,
                                   final Map<ResourceKey, Integer> classOf,
                                   final Set<Integer> toolClasses,
                                   final Durability durability) {
        // Lowest id per class, so the same graph always yields the same columns.
        final Map<Integer, Integer> representative = new LinkedHashMap<>();
        final Map<ResourceKey, Integer> ids = new LinkedHashMap<>();
        classOf.forEach((resource, cls) -> {
            final int id = index.idOf(resource);
            ids.put(resource, id);
            representative.merge(cls, id, Math::min);
        });

        final Map<Integer, Integer> columnOf = new LinkedHashMap<>();
        final Map<Integer, Long> unitsOf = new LinkedHashMap<>();
        final Map<Integer, Integer> classOfColumn = new LinkedHashMap<>();
        final Set<Integer> toolColumns = new java.util.LinkedHashSet<>();
        classOf.forEach((resource, cls) -> {
            final int id = ids.get(resource);
            final int column = representative.get(cls);
            columnOf.put(id, column);
            classOfColumn.put(column, cls);
            if (toolClasses.contains(cls)) {
                toolColumns.add(column);
                // A tool is worth what it has left, so the pool is crafts remaining rather than
                // items held, and the wear step falls out of subtraction rather than being read
                // off the pattern by hand.
                unitsOf.put(id, (long) Math.max(0, durability.usesLeft(resource)));
            }
        });
        return new ClassPools(columnOf, unitsOf, classOfColumn, toolColumns);
    }

    @Override
    public int columnOf(final int resource) {
        return this.columnOf.getOrDefault(resource, resource);
    }

    @Override
    public long unitsOf(final int resource) {
        return this.unitsOf.getOrDefault(resource, 1L);
    }

    /** The class index this column stands for, or {@code -1} for a resource the graph never saw. */
    public int classOfColumn(final int column) {
        return this.classOfColumn.getOrDefault(column, -1);
    }

    /** Whether this column is counted in uses rather than items. */
    public boolean isToolColumn(final int column) {
        return this.toolColumns.contains(column);
    }
}
