package com.wraithhawit.rstweaks.ledger;

/**
 * The second currency: families of resources that differ only by one scalar "remaining".
 *
 * <p>Item counting gives up on a worn tool. A 1000-use crystal is a thousand distinct resources to
 * Refined Storage, and a planner treating them as unrelated items either demands a thousand
 * crystals or — worse — discovers it can reuse {@code crystal@1} forever. Nodrance put the
 * question exactly: <em>"is it simulating 1000 durability as 1000 items, or 1000 recipes of 558
 * durability shovel to 557 durability shovel?"</em> Neither. The right variable is <b>uses</b>:
 * one pool, whose size is the sum of what every wear level in storage has left.
 *
 * <p>Two methods say all of it:
 *
 * <ul>
 *   <li>{@link #columnOf} — which pool this resource pays into. A pool is addressed by the id of
 *       one representative member, so pools need no id space of their own and an unpooled
 *       resource is simply its own column.</li>
 *   <li>{@link #unitsOf} — what one item of this resource is worth in that column. A pristine
 *       crystal is {@code maxUses}; a worn one is what it has left; an ordinary item is 1.</li>
 * </ul>
 *
 * <p><b>The wear step is never assumed, and never coded.</b> A slot of {@code crystal@0} becoming
 * {@code crystal@1} consumes 1000 units and produces 999, so it nets one use — by subtraction, in
 * {@link Transform#net}, with no durability logic anywhere in this package. A recipe that burns
 * five points a craft nets five for the same reason. That matters: assuming one would make such a
 * tool last five times too long, which reads as a working feature and is a duplication bug.
 *
 * <p><b>Nothing here is about damage.</b> Charge, blood, stored fluid and uses are the same shape,
 * so a mod that stores power in a crafting ingredient stops being a permanent known gap and
 * becomes one implementation of this interface.
 *
 * <p>One trap, because it has already cost a bug: a fuzzy slot accepting "any hammer" is
 * <em>not</em> a pool. A copper hammer and an iron hammer are different tools, not wear levels of
 * one, and merging them makes the planner demand the first one it saw.
 */
public interface Pools {
    /** Every resource is its own column and worth one: the model before pools existed. */
    Pools NONE = new Pools() {
        @Override
        public int columnOf(final int resource) {
            return resource;
        }

        @Override
        public long unitsOf(final int resource) {
            return 1L;
        }
    };

    /** The column this resource pays into: its pool's representative, or itself. */
    int columnOf(int resource);

    /** What one item of this resource contributes to its column. One, unless pooled. */
    long unitsOf(int resource);

    /** Whether this resource is measured in something other than items. */
    default boolean isPooled(final int resource) {
        return columnOf(resource) != resource || unitsOf(resource) != 1L;
    }
}
