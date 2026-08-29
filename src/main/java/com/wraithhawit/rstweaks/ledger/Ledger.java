package com.wraithhawit.rstweaks.ledger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The books: what was there, what was made, what was used, and therefore what must be there now.
 *
 * <pre>   initial + produced - consumed == final</pre>
 *
 * <p>That one property is the test suite. Every niche autocrafting failure this mod has chased is
 * a conservation failure wearing a costume — a catalyst asking for 712 million, a crystal that
 * does 64 jobs forever, a thousand cakes planning three thousand buckets — so a violation upward
 * is a duplication glitch and downward is items destroyed, and one property covers all of them
 * plus the ones nobody has thought of yet. Run it over every plan, every schedule and every
 * execution.
 *
 * <p>Balances are kept in <b>column units</b> (see {@link Pools}), so a tool's balance is crafts
 * remaining rather than items held. {@link #fromStock} does that conversion once at the boundary;
 * mixing the two units anywhere else is how a planner promises crafts from a tool that is not
 * coming back.
 *
 * <p>Not thread-safe, and deliberately so: a ledger belongs to one plan, one schedule or one
 * execution, and sharing one across threads would be a modelling mistake rather than a
 * synchronisation problem.
 */
public final class Ledger {
    private final Pools pools;
    private final Map<Integer, Long> initial;
    private final Map<Integer, Long> consumed = new LinkedHashMap<>();
    private final Map<Integer, Long> produced = new LinkedHashMap<>();

    public Ledger(final Pools pools, final Map<Integer, Long> initialByColumn) {
        this.pools = pools;
        this.initial = Map.copyOf(initialByColumn);
    }

    /** An empty ledger — everything starts at zero, which is what a pure plan check wants. */
    public static Ledger empty(final Pools pools) {
        return new Ledger(pools, Map.of());
    }

    /**
     * A ledger opened against real stock, counted per resource.
     *
     * <p>The conversion is the whole point: five crystals at various wear levels are not five of
     * anything the planner can use, they are the sum of what each has left.
     */
    public static Ledger fromStock(final Pools pools, final Map<Integer, Long> stockByResource) {
        return new Ledger(pools, toColumns(pools, stockByResource));
    }

    /** Folds a per-resource count into per-column units, the same way stock is opened. */
    public static Map<Integer, Long> toColumns(final Pools pools, final Map<Integer, Long> byResource) {
        final Map<Integer, Long> out = new LinkedHashMap<>();
        byResource.forEach((resource, count) -> out.merge(pools.columnOf(resource),
            Math.multiplyExact(count, pools.unitsOf(resource)), Math::addExact));
        return out;
    }

    /**
     * Runs a recipe {@code times} over, in one entry.
     *
     * <p>A batch and the same iterations run one at a time must leave identical books — that
     * equivalence is the only thing standing between an optimisation and a dupe bug, and
     * {@link #totalsMatch} exists to assert it.
     */
    public void apply(final Transform transform, final long times) {
        if (times < 0L) {
            throw new IllegalArgumentException("cannot run " + transform.label() + " " + times + " times");
        }
        if (times == 0L) {
            return;
        }
        transform.consumed(this.pools).forEach((column, amount) ->
            record(this.consumed, column, Math.multiplyExact(amount, times)));
        transform.produced(this.pools).forEach((column, amount) ->
            record(this.produced, column, Math.multiplyExact(amount, times)));
    }

    /** Items leaving the books for a reason no recipe explains — an extraction the task keeps. */
    public void consume(final int column, final long amount) {
        record(this.consumed, column, amount);
    }

    /** Items arriving from outside the recipes — an insertion, or the seed a plan starts with. */
    public void produce(final int column, final long amount) {
        record(this.produced, column, amount);
    }

    public long balance(final int column) {
        return this.initial.getOrDefault(column, 0L)
            + this.produced.getOrDefault(column, 0L)
            - this.consumed.getOrDefault(column, 0L);
    }

    /** Every column the books have touched, with what should be there now. */
    public Map<Integer, Long> balances() {
        final Map<Integer, Long> out = new LinkedHashMap<>();
        for (final int column : columns()) {
            out.put(column, balance(column));
        }
        return out;
    }

    public Map<Integer, Long> consumedTotals() {
        return Map.copyOf(this.consumed);
    }

    public Map<Integer, Long> producedTotals() {
        return Map.copyOf(this.produced);
    }

    /**
     * Columns the books drove below zero: something was spent that was never there.
     *
     * <p>A plan whose ledger is conserved can still be unrunnable — equations describe a steady
     * state, and nothing in them says you own a bucket. This is that gap, named per column, and it
     * is what phase 03 grows a seed against.
     */
    public Map<Integer, Long> deficits() {
        final Map<Integer, Long> out = new LinkedHashMap<>();
        balances().forEach((column, amount) -> {
            if (amount < 0L) {
                out.put(column, -amount);
            }
        });
        return out;
    }

    /**
     * The property, checked against what the world actually holds afterwards.
     *
     * @param observedFinalByColumn real stock after the fact, already folded into column units
     * @return one violation per column that disagrees; empty when the books balance
     */
    public List<Violation> reconcile(final Map<Integer, Long> observedFinalByColumn) {
        final Set<Integer> all = new LinkedHashSet<>(columns());
        all.addAll(observedFinalByColumn.keySet());
        final List<Violation> violations = new ArrayList<>();
        for (final int column : all) {
            final long expected = balance(column);
            final long observed = observedFinalByColumn.getOrDefault(column, 0L);
            if (expected != observed) {
                violations.add(new Violation(column, expected, observed));
            }
        }
        return violations;
    }

    /**
     * Whether two ledgers did the same work — not merely ended in the same place.
     *
     * <p>Balances alone would pass a run that destroyed a stack and duplicated it back, so this
     * compares the totals on both sides of the identity.
     */
    public boolean totalsMatch(final Ledger other) {
        return this.consumed.equals(other.consumed) && this.produced.equals(other.produced);
    }

    private Set<Integer> columns() {
        final Set<Integer> all = new LinkedHashSet<>(this.initial.keySet());
        all.addAll(this.produced.keySet());
        all.addAll(this.consumed.keySet());
        return all;
    }

    private static void record(final Map<Integer, Long> into, final int column, final long amount) {
        if (amount < 0L) {
            throw new IllegalArgumentException("a negative entry belongs on the other side of the books");
        }
        if (amount == 0L) {
            return;
        }
        into.merge(column, amount, Math::addExact);
    }

    /**
     * One column where the books and the world disagree.
     *
     * <p>The direction is the diagnosis: more than expected is a duplication glitch, less is items
     * destroyed. Both have shipped in this mod before, and the second is the one players never
     * forgive.
     */
    public record Violation(int column, long expected, long observed) {
        public long delta() {
            return this.observed - this.expected;
        }

        public boolean duplicated() {
            return delta() > 0L;
        }

        public String describe(final ResourceIndex index) {
            return index.label(this.column) + ": expected " + this.expected + ", found " + this.observed
                + " (" + (duplicated() ? "+" + delta() + " duplicated" : delta() + " destroyed") + ")";
        }
    }
}
