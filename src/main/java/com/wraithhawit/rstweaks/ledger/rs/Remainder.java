package com.wraithhawit.rstweaks.ledger.rs;

import com.refinedmods.refinedstorage.api.resource.ResourceKey;

import javax.annotation.Nullable;

/**
 * What an ingredient leaves behind in the slot it was used from — a bucket after the milk, a
 * bottle after the honey.
 *
 * <p>Minecraft already knows this: {@code ItemStack.getCraftingRemainingItem()} is the game's own
 * answer, and it is the last of the three fates a slot can have that cannot be read off the
 * pattern alone. It arrives through an interface for the same reason {@code Durability} does — the
 * ledger has to be testable in a plain JVM, so only the implementation touches the game.
 *
 * <p>The default answers "nothing", which is exactly the model Refined Storage has today: a
 * container is an opaque item that goes in and does not come back. That is why a thousand cakes
 * plan three thousand buckets.
 */
public interface Remainder {
    /** No containers at all; the behaviour before this existed. */
    Remainder NONE = input -> null;

    /**
     * What this resource turns into when used in a recipe, or {@code null} if it is consumed.
     *
     * <p>Must be a <em>fate</em>, not a hint: returning something the recipe does not actually
     * hand back would make the ledger credit an item that never arrives, which is the destroyed
     * half of a conservation failure. When in doubt, return null and let the slot be consumed.
     */
    @Nullable
    ResourceKey remainderOf(ResourceKey input);

    /**
     * Installed once at mod construction, and swapped by the headless tests for a fake with no
     * Minecraft behind it. Volatile because the planner runs on Refined Storage's own autocrafting
     * threads as well as the server thread.
     */
    final class Holder {
        private static volatile Remainder current = NONE;

        private Holder() {
        }

        public static Remainder get() {
            return current;
        }

        public static void set(final Remainder remainder) {
            current = remainder == null ? NONE : remainder;
        }
    }
}
