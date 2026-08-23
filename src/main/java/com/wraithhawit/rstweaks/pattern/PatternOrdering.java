package com.wraithhawit.rstweaks.pattern;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * Puts a resource's candidate patterns into a stable, intentional order.
 *
 * <h2>The bug this exists for</h2>
 *
 * Refined Storage 2.0.9 keeps each output's patterns in a {@code PriorityQueue} and reads them
 * back with:
 *
 * <pre>{@code holders.stream().map(holder -> holder.pattern).toList()}</pre>
 *
 * <p><b>{@code PriorityQueue.stream()} does not iterate in priority order.</b> Java documents
 * that its iterator and spliterator make no ordering guarantee — only the head is the true
 * minimum, and everything after it sits in raw heap-array layout, a function of the sequence
 * elements were added in and whichever sift operations happened along the way.
 *
 * <p>That matters because {@code CraftingTree.calculateChild} tries alternatives <em>in the
 * order it receives them</em>, returns on the first that succeeds, and explores each failure to
 * exhaustion while copying the whole crafting state at every node. The price of a calculation
 * is therefore set by how many wrong branches come first — so an arbitrary order is an
 * arbitrary cost, and it silently changes whenever patterns are re-added.
 *
 * <p>Measured on 2026-08-23: consolidating a network's patterns into one provider (which
 * re-adds every one of them, rebuilding every per-output heap in a new sequence) took three
 * Step Requesters from 0.199 ms/tick to 20.249 ms/tick with the same patterns, same count and
 * same recipes. Some calculations went from sub-millisecond to hitting RS's entire 5,000ms
 * timeout.
 *
 * <h2>Why the tiebreak is the pattern's UUID</h2>
 *
 * Sorting by priority alone fixes nothing in practice. {@code List.sort} is stable, so equal
 * priorities keep their encounter order — which is the heap-array order we are trying to get
 * away from — and in an ordinary network every provider sits at priority 0. A tiebreak is not
 * optional; it is the entire fix.
 *
 * <p>Refined Storage's own later builds tiebreak on an insertion counter. This does not, and
 * deliberately: <b>insertion order still reshuffles when a pattern moves between providers</b>,
 * which is exactly the event that caused the regression above. {@link UUID} is intrinsic to the
 * pattern, persisted in the pattern item's data component, and identical before and after a
 * move — so a pattern's position in the search order stops depending on the history of where it
 * has been.
 *
 * <p>The resulting order is arbitrary but <em>fixed</em>. That is the point: it makes cost
 * reproducible instead of a function of insertion history, and it makes provider priority a
 * real lever, since a provider raised above 0 now genuinely gets searched first rather than
 * merely owning the head of a queue nobody reads in order.
 */
public final class PatternOrdering {
    private PatternOrdering() {
    }

    /**
     * Highest priority first, then by UUID so equal priorities have one fixed order.
     *
     * <p>Exposed for tests. The mixin uses {@link #sorted} instead, which avoids allocating a
     * comparator per call on a path the crafting calculator hits for every ingredient of every
     * node it visits.
     */
    public static <T> Comparator<T> comparator(final ToIntFunction<T> priority,
                                               final Function<T, UUID> id) {
        return Comparator.comparingInt(priority).reversed()
            .thenComparing(id, PatternOrdering::compareIds);
    }

    /**
     * Returns the entries in search order.
     *
     * <p>Short lists are the overwhelmingly common case — most resources are made one way — and
     * a general sort on a one- or two-element list is pure overhead on a very hot path, so
     * those two sizes return early. A two-element list is ordered by one comparison.
     *
     * @return a new list; the input is never mutated, because the caller's list may be the
     *         repository's own and RS reads it elsewhere
     */
    public static <T> List<T> sorted(final List<T> entries,
                                     final ToIntFunction<T> priority,
                                     final Function<T, UUID> id) {
        final int size = entries.size();
        if (size <= 1) {
            return entries;
        }
        if (size == 2) {
            final T first = entries.get(0);
            final T second = entries.get(1);
            if (compare(first, second, priority, id) <= 0) {
                return entries;
            }
            return List.of(second, first);
        }
        final List<T> copy = new ArrayList<>(entries);
        copy.sort(comparator(priority, id));
        return copy;
    }

    private static <T> int compare(final T left,
                                   final T right,
                                   final ToIntFunction<T> priority,
                                   final Function<T, UUID> id) {
        final int byPriority = Integer.compare(priority.applyAsInt(right), priority.applyAsInt(left));
        return byPriority != 0 ? byPriority : compareIds(id.apply(left), id.apply(right));
    }

    /**
     * Orders two ids, tolerating a null.
     *
     * <p>{@code UUID.compareTo} signs both halves, so it is not the same order as comparing the
     * bits unsigned — irrelevant here, since the only requirement is that the order is total
     * and always the same, but worth stating so nobody "fixes" it into an unsigned compare and
     * reshuffles every network's search order for no reason.
     */
    private static int compareIds(final UUID left, final UUID right) {
        if (left == right) {
            return 0;
        }
        if (left == null) {
            return 1;
        }
        if (right == null) {
            return -1;
        }
        return left.compareTo(right);
    }
}
