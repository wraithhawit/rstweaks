package com.wraithhawit.rstweaks;

/**
 * Counters proving the optimizations are actually firing.
 *
 * <p>Plain {@code long}s rather than atomics on purpose: every one of these is
 * written from block-entity tick code and read from server tick / player-join
 * handlers, all of which run on the server thread. These sit on the exact hot
 * paths we are optimizing, so the counters must not cost more than the work they
 * are measuring — an atomic increment on the sided-input lookup would be a
 * measurable tax on a path that runs thousands of times per tick.
 *
 * <p>They are therefore diagnostics, not accounting. Do not use them for anything
 * that needs to be exact.
 */
public final class Stats {
    /**
     * Slot scans skipped because the slot was backing off. Each one is a full
     * recursive Refined Storage crafting calculation that did not happen.
     */
    public static long stepRequesterScansSkipped;

    /** Craft attempts that genuinely failed and triggered a backoff. */
    public static long stepRequesterFailures;

    /** Sided-input pattern lookups served by the allocation-free path. */
    public static long sidedInputLookups;

    /**
     * {@code ensureTask} calls answered from the not-craftable cache. Each one
     * avoids a full crafting calculation plus a binary search that runs another
     * full calculation per probe.
     */
    public static long uncraftableChecksSkipped;

    /**
     * Ingredient-map deep copies avoided by sharing during crafting-plan snapshots.
     * This one climbs extremely fast — it counts per ingredient index per plan copy,
     * and a single deep crafting calculation produces many thousands.
     */
    public static long patternPlanCopiesAvoided;

    /**
     * Drawer Controller connectivity checks answered from the cached handler set
     * instead of a linear scan. Each one replaces an O(drawers) list walk.
     */
    public static long drawerMembershipChecks;

    /**
     * Redundant {@code getSlots()} calls avoided while scanning external inventories.
     * RS put the call in a loop condition, so it ran once per slot examined.
     */
    public static long slotCountLookupsAvoided;

    /** Extractions answered from the slot index without a full inventory scan. */
    public static long externalIndexHits;

    /**
     * Extractions where the index could not satisfy the request and the full scan ran.
     * A persistently high ratio against {@link #externalIndexHits} means the index is
     * thrashing and is costing more than it saves.
     */
    public static long externalIndexFallbacks;

    /** Full scans done to build the index. */
    public static long externalIndexRebuilds;

    /**
     * Extractions answered as "the network has none of this" without walking a single storage.
     * Expected to be the largest counter here on a busy network — most extractions are for
     * something that particular network does not hold.
     */
    public static long emptyExtractsAvoided;

    /** Craft requests planned by the LP solver instead of RS's recursive tree. */
    /**
     * Provider calls skipped because the provider could not possibly serve the resource
     * type. Counted because the win here is entirely in call volume — the number is the
     * evidence, since no single call is slow enough to see.
     */
    public static long mismatchedProviderCallsAvoided;
    /**
     * Patterns recognised as a pure container/fluid swap and made internal. Counted
     * because the whole feature is invisible when it works -- the craft simply runs.
     */
    public static long fluidSwapPatterns;
    public static long lpPlannerUsed;

    /**
     * Craft requests the LP planner declined, falling back to stock RS. Expected to be
     * large: every acyclic, byproduct-free craft is declined by design.
     */
    public static long lpPlannerDeclined;

    private Stats() {
    }
}
