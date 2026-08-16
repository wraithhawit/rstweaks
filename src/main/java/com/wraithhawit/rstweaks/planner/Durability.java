package com.wraithhawit.rstweaks.planner;

import com.refinedmods.refinedstorage.api.resource.ResourceKey;

import javax.annotation.Nullable;

/**
 * What the planner needs to know about tools that wear out, expressed so that the
 * planner itself never touches Minecraft.
 *
 * <p>The whole {@code planner} package is deliberately free of Minecraft types — that is
 * what lets {@code HeadlessPlannerCheck} run the solver in a plain JVM in two seconds
 * instead of a restart and a manual craft. Durability is the first thing the planner has
 * needed that only the game can answer, so it arrives through this interface rather than
 * as an {@code ItemStack} import.
 *
 * <p>The model follows Applied Energistics rather than Refined Storage. AE2's
 * {@code IPatternDetails.IInput} has:
 *
 * <pre>{@code   AEKey getRemainingKey(AEKey actuallyConsumed); }</pre>
 *
 * <p>— the thing handed back is a <em>function of the item actually used</em>, evaluated
 * per craft. Refined Storage instead bakes the byproduct into the pattern when it is
 * encoded, from the crystal that happened to be in the grid at the time, so a pattern
 * made with a fresh crystal can only ever consume a fresh crystal. One craft works and
 * the second deadlocks.
 *
 * <p>Given that, a worn tool is not a chain of a hundred distinct resources. It is a
 * <b>supply of uses</b>: a crystal with 30 hits left is 30 crafts, and a freshly made one
 * is {@code maxUses} crafts. That keeps the linear program at one extra resource class
 * and no extra patterns — measured at 0.57 ms versus 63 ms for the
 * one-pattern-per-damage-step alternative.
 */
public interface Durability {
    /** Ignores durability entirely; the behaviour before this existed. */
    Durability NONE = new Durability() {
        @Override
        public boolean isDurable(final ResourceKey resource) {
            return false;
        }

        @Override
        public int maxUses(final ResourceKey resource) {
            return 0;
        }

        @Override
        public int usesLeft(final ResourceKey resource) {
            return 0;
        }

        @Nullable
        @Override
        public ResourceKey afterUses(final ResourceKey resource, final int uses) {
            return null;
        }

        @Override
        public boolean sameTool(final ResourceKey a, final ResourceKey b) {
            return false;
        }
    };

    /** Whether this resource is an item that wears out rather than being consumed. */
    boolean isDurable(ResourceKey resource);

    /** Crafts a pristine one of these survives. Zero when not durable. */
    int maxUses(ResourceKey resource);

    /** Crafts this particular one has left, given the damage it already carries. */
    int usesLeft(ResourceKey resource);

    /**
     * The same tool after being used this many more times, or {@code null} if it breaks.
     *
     * <p>This is the {@code getRemainingKey} equivalent, and the reason the executor can
     * hand back a correctly worn tool instead of the one the pattern was encoded with.
     */
    @Nullable
    ResourceKey afterUses(ResourceKey resource, int uses);

    /** Whether two resources are the same tool differing only in how worn they are. */
    boolean sameTool(ResourceKey a, ResourceKey b);

    /**
     * Installed once at mod construction, and swapped by the headless tests for a fake
     * with no Minecraft behind it. Volatile because the planner runs on Refined Storage's
     * own autocrafting threads as well as the server thread.
     */
    final class Holder {
        private static volatile Durability current = NONE;

        private Holder() {
        }

        public static Durability get() {
            return current;
        }

        public static void set(final Durability durability) {
            current = durability == null ? NONE : durability;
        }
    }
}
