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

    /**
     * Craft attempts that SUCCEEDED but cost more than stepRequesterSlowCalculationMs,
     * and were backed off anyway. These are the ones the failure-only backoff cannot see:
     * measured at 34.8% of the server thread while failures were 45 in 100 seconds.
     */
    public static long stepRequesterSlowCalculations;

    /** Sided-input pattern lookups served by the allocation-free path. */
    public static long sidedInputLookups;

    /**
     * {@code ensureTask} calls answered from the not-craftable cache. Each one
     * avoids a full crafting calculation plus a binary search that runs another
     * full calculation per probe.
     */
    public static long uncraftableChecksSkipped;

    /**
     * Craftable-amount searches given the caller's timeout instead of CancellationToken.NONE.
     *
     * <p>Counts opportunities, not rescues: a search that would have finished quickly is unaffected.
     * It rises whenever the path is taken at all, which is what makes it useful for confirming the
     * redirect is live.
     */
    public static long craftableSearchesBounded;

    /**
     * What the network keeps trying, and failing, to autocraft.
     *
     * <p>Not a counter, because a counter cannot answer the question this is for. A single Exporter
     * with an autocrafting upgrade, asking for something that cannot be made, will run a full
     * recursive crafting calculation every time the uncraftable cache lets it retry -- and on a
     * network with many patterns one of those calculations can take seconds. It is the single most
     * expensive thing that can quietly be wrong with a base, and it is close to impossible to find by
     * hand: nothing in game says which exporter is asking, or for what.
     *
     * <p>So the resource names go here, most recently refused first. Finding the exporter that wants
     * one of these ends the problem outright, which no amount of making the calculation faster can.
     */
    public static final java.util.LinkedHashMap<String, Long> uncraftableResources =
        new java.util.LinkedHashMap<>() {
            @Override
            protected boolean removeEldestEntry(final java.util.Map.Entry<String, Long> eldest) {
                // A handful is all anyone can act on, and this is read by a chat command.
                return this.size() > 8;
            }
        };

    /** Records a resource the network has just decided it cannot craft. */
    public static synchronized void recordUncraftable(final String name) {
        uncraftableResources.remove(name);
        uncraftableResources.put(name, uncraftableResources.getOrDefault(name, 0L) + 1L);
    }

    /**
     * Requests refused because a craft for that resource was already running.
     *
     * <p>The positive-answer twin of {@link #uncraftableChecksSkipped}: that one avoids
     * recalculating a craft that cannot happen, this one avoids starting a second task
     * for a craft that is already happening. Each suppression saves a full crafting
     * calculation and a task that would then be stepped every tick until it finished.
     */
    public static long duplicateRequestsSuppressed;

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
     * Full rescans of a Sophisticated barrel skipped because it had already refused that
     * exact item this tick. Counted because upstream's own identity-keyed cache records
     * nothing on this path, so any non-zero value here is a scan that used to happen.
     */
    public static long failedInsertScansAvoided;
    /**
     * Drawer denylist checks answered from the item cache rather than a tag probe. Expected
     * to be very large on a network that autocrafts into drawers: two per insert attempt,
     * one attempt per slot.
     */
    public static long drawerDenylistLookupsAvoided;
    /** Crafts the LP planner produced a plan for, rather than declining to stock RS. */
    public static long lpPlannerUsed;

    /**
     * Craft requests the LP planner declined, falling back to stock RS. Expected to be
     * large: every acyclic, byproduct-free craft is declined by design.
     */
    public static long lpPlannerDeclined;

    /**
     * Cached resource totals clamped at {@code Long.MAX_VALUE} instead of being allowed to
     * wrap negative and crash the next read of the storage list.
     *
     * <p>Deliberately not in the ChatReporter rotation. Every other counter here is a saving
     * and reads as good news; this one means a network is reporting an impossible amount, and
     * folding it into "optimizations firing" would bury the one number that says something is
     * wrong. It is logged with the offending resource instead, once per resource.
     */
    public static long resourceAmountOverflowsClamped;

    private Stats() {
    }
}
