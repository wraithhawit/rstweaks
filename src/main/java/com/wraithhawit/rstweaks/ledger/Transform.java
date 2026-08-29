package com.wraithhawit.rstweaks.ledger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One iteration of a recipe, as slots with fates plus whatever it genuinely produces.
 *
 * <p>{@code outputs} is production out of nothing — the recipe's result, and any byproduct that
 * could not be attributed to an input slot. Everything an ingredient hands back lives on the slot
 * instead, because that is the link Refined Storage's three flat lists threw away.
 *
 * <p>The three views below are all subtraction over the same data, and every downstream phase
 * uses one of them: the solver wants {@link #net}, the ledger wants {@link #consumed} and
 * {@link #produced} separately so it can report a violation's direction, and nothing needs a
 * special case for catalysts, tools or containers.
 */
public record Transform(String label, List<Slot> slots, List<Quantity> outputs) {
    public Transform {
        slots = List.copyOf(slots);
        outputs = List.copyOf(outputs);
    }

    public static Transform of(final String label, final List<Slot> slots, final List<Quantity> outputs) {
        return new Transform(label, slots, outputs);
    }

    /** What one iteration takes, in column units. A catalyst appears here and in {@link #produced}. */
    public Map<Integer, Long> consumed(final Pools pools) {
        final Map<Integer, Long> out = new LinkedHashMap<>();
        for (final Slot slot : this.slots) {
            add(out, pools.columnOf(slot.resource()), units(pools, slot.resource(), slot.amount()));
        }
        return out;
    }

    /** What one iteration gives back, in column units: slot fates first, then real outputs. */
    public Map<Integer, Long> produced(final Pools pools) {
        final Map<Integer, Long> out = new LinkedHashMap<>();
        for (final Slot slot : this.slots) {
            if (slot.returnsSomething()) {
                add(out, pools.columnOf(slot.becomes()), units(pools, slot.becomes(), slot.amount()));
            }
        }
        for (final Quantity output : this.outputs) {
            add(out, pools.columnOf(output.resource()), units(pools, output.resource(), output.amount()));
        }
        return out;
    }

    /**
     * Produced minus consumed, with zeroes dropped — the coefficients a solver wants.
     *
     * <p>This is where the model earns its keep. A catalyst nets to zero and vanishes from the
     * column entirely, so it costs the program nothing and needs no code; a worn tool nets its
     * real wear step because the pool converted both sides to uses first; a container nets the
     * fluid it gave up and keeps its shell. One subtraction, four cases.
     */
    public Map<Integer, Long> net(final Pools pools) {
        final Map<Integer, Long> out = new LinkedHashMap<>(consumed(pools));
        out.replaceAll((column, amount) -> -amount);
        produced(pools).forEach((column, amount) -> add(out, column, amount));
        out.values().removeIf(amount -> amount == 0L);
        return out;
    }

    /** The unpooled view, for callers with no tools or fluids in play. */
    public Map<Integer, Long> net() {
        return net(Pools.NONE);
    }

    private static long units(final Pools pools, final int resource, final long count) {
        return Math.multiplyExact(count, pools.unitsOf(resource));
    }

    private static void add(final Map<Integer, Long> into, final int column, final long amount) {
        into.merge(column, amount, Math::addExact);
    }
}
