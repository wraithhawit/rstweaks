package com.wraithhawit.rstweaks.ledger;

/**
 * One input slot and its <b>fate</b>: what goes in, and what comes back out of that same slot.
 *
 * <p>This is the primitive the whole model rests on. A Refined Storage pattern is three flat
 * lists — ingredients, outputs, byproducts — which throws away the one fact that matters:
 * <em>which ingredient became which byproduct</em>. Once that link is gone nothing downstream can
 * recover it, so the planner ignores byproducts entirely while the executor faithfully returns
 * them; the plan buys N crystals and the craft hands N crystals back.
 *
 * <p>Four cases Refined Storage models four different ways are one case here, and the net effect
 * falls out of subtraction rather than a special case each:
 *
 * <pre>
 *   consumed    4 inferium      becomes NOTHING          net -4
 *   catalyst    master crystal  becomes itself           net  0   (free, and needs no code)
 *   worn tool   crystal@0       becomes crystal@1        net -1 use, via {@link Pools}
 *   container   honey bucket    becomes bucket           net -honey, bucket kept
 * </pre>
 *
 * <p>A catalyst is not special-cased anywhere in this package. It is simply the slot whose
 * {@code becomes} equals its {@code resource}, so it cancels itself out of the arithmetic and
 * disappears from the constraint matrix.
 */
public record Slot(int resource, long amount, int becomes) {
    public Slot {
        if (resource < 0) {
            throw new IllegalArgumentException("a slot must consume a real resource");
        }
        if (amount <= 0L) {
            throw new IllegalArgumentException("a slot taking " + amount + " is not a slot");
        }
        if (becomes < 0 && becomes != ResourceIndex.NOTHING) {
            throw new IllegalArgumentException("becomes must be a resource or NOTHING");
        }
    }

    /** An ingredient that is used up: the ordinary case. */
    public static Slot consumed(final int resource, final long amount) {
        return new Slot(resource, amount, ResourceIndex.NOTHING);
    }

    /** An ingredient handed back unchanged — a catalyst, which costs nothing to run. */
    public static Slot catalyst(final int resource, final long amount) {
        return new Slot(resource, amount, resource);
    }

    /** An ingredient handed back as something else: a worn tool, an emptied container. */
    public static Slot transforming(final int resource, final long amount, final int becomes) {
        if (becomes == ResourceIndex.NOTHING) {
            throw new IllegalArgumentException("use consumed() for a slot that returns nothing");
        }
        return new Slot(resource, amount, becomes);
    }

    public boolean isCatalyst() {
        return this.becomes == this.resource;
    }

    public boolean returnsSomething() {
        return this.becomes != ResourceIndex.NOTHING;
    }
}
