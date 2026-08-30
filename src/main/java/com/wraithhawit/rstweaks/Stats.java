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

    /**
     * Every {@code startTask} a Step Requester made, and what they cost in total.
     *
     * <p>Added in 0.2.115 because 0.2.113's threshold was set from an inference rather than a
     * measurement, and the inference was wrong by two orders of magnitude. The reasoning was:
     * one {@code LP planner declined} log line per calculation, ~1/second, against 34.8% of
     * the server thread, therefore ~400ms per calculation. But that log line is written per
     * distinct resource the LP planner is offered, not per calculation, so it is not a call
     * counter at all. The real shape is thousands of calls of a few milliseconds each — spark
     * `KJdBQvnix4` shows 60,580ms inside the timed region over 120s with only ONE call above
     * 10ms, which puts the count at 6,000+ and the mean in low single-digit milliseconds.
     *
     * <p>These two make the mean and the distribution directly readable in game, so the
     * threshold can be set from data. Nanos rather than millis because a per-call figure that
     * rounds to zero sums to nothing.
     */
    public static long stepRequesterCalculations;

    /** Total nanoseconds spent in Step Requester {@code startTask} calls. */
    public static long stepRequesterCalculationNanos;

    /**
     * The single most expensive Step Requester {@code startTask} of the whole session.
     *
     * <p>A running maximum, NOT a per-report delta -- a max cannot be subtracted the way the
     * other counters can. It is labelled "session peak" in the report for that reason: 0.2.117
     * printed it beside per-window figures and produced lines like "187 craft calculations
     * (0.69ms mean, 5,000ms slowest, 129ms total)", where the peak plainly cannot fit inside
     * the total. Use {@link #stepRequesterTimeouts} for the per-window question.
     */
    public static long stepRequesterSlowestMs;

    /**
     * Calculations that burned the entire crafting budget and were cancelled.
     *
     * <p>The delta-able form of "is it still hitting the ceiling", which the session peak cannot
     * answer. A cancelled calculation reports MISSING_RESOURCES, indistinguishable from an
     * impossible one -- see {@code rstweaks-five-second-craft-timeout} -- so a non-zero count
     * here also means crafts may be being refused that would have worked.
     */
    public static long stepRequesterTimeouts;

    /**
     * Automated calculations cancelled by OUR budget rather than by RS's full timeout.
     *
     * <p>Each one is a five-second server-thread freeze that did not happen. It is also the
     * number to watch if automation seems to be retrying too much: a slot climbing the budget
     * ladder is being asked for something genuinely large, not something impossible.
     */
    public static long stepRequesterBudgetExpiries;

    /**
     * Pattern lists reordered because RS handed them back in heap-array order.
     *
     * <p>Counts only lists the sort actually changed, so a zero here with a busy network means
     * RS's order already happened to be right -- not that the mixin failed to apply.
     */
    public static long patternListsSorted;

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

    /**
     * Crafting iterations run as part of a batch rather than one at a time.
     *
     * <p>Also the only way a test can tell batched stepping apart from a no-op. Every scenario in
     * the task-engine suite passes with batching switched off — that is what it is for — so a run
     * with it switched on proves nothing unless this number moved.
     */
    public static long batchedIterations;

    /** Batches performed. {@code batchedIterations / batchedSteps} is the average batch width. */
    public static long batchedSteps;

    /**
     * SIMULATE/EXECUTE pairs the substitution probe has compared.
     *
     * <p>Refined Storage runs every crafting iteration twice — once with {@code SIMULATE} to test
     * it and once with {@code EXECUTE} to do it — so our substitution scans the task's whole
     * internal storage twice for one iteration's worth of work. Caching the first answer for the
     * second pass would halve the cost, but only if the two passes always agree, and
     * {@code calculateIterationInputs} takes the {@code Action} and may legitimately differ.
     *
     * <p>These count the answer instead of assuming it. Written only when
     * {@code substitutionProbe} is on.
     */
    public static long substitutionPairs;

    /** Pairs where EXECUTE chose exactly what SIMULATE did. */
    public static long substitutionAgreed;

    /**
     * Pairs where it did not. <b>This is the number that decides the optimization.</b> Anything
     * above zero means caching the SIMULATE answer would substitute the wrong tool.
     */
    public static long substitutionDisagreed;

    /**
     * EXECUTE passes that arrived with no SIMULATE before them.
     *
     * <p>Counted separately because it would break the pairing assumption itself rather than the
     * agreement — a different failure, and one that a bare agree/disagree ratio would hide.
     */
    public static long substitutionExecuteWithoutSimulate;

    /**
     * Internal-storage scans the EXECUTE pass skipped by reusing the SIMULATE pass's answer.
     *
     * <p>The only way to tell the reuse apart from a no-op. Every task-engine scenario passes with
     * it switched off — that is the point of the differential — so a run with it on proves nothing
     * unless this number moved.
     */
    public static long substitutionScansAvoided;

    /**
     * Why the reuse did not fire, split three ways.
     *
     * <p>0.13.0 shipped the reuse with only {@link #substitutionScansAvoided} and never surfaced
     * even that, and the profile then said it was firing about 1% of the time in a real craft while
     * the gametest showed it working. There was no way to tell which branch was responsible. These
     * make the answer one {@code /rstweaks stats} away instead of one release away.
     */
    public static long substitutionScansNotEligible;

    /** EXECUTE passes where the SIMULATE pass had remembered nothing for that resource. */
    public static long substitutionNothingRemembered;

    /** EXECUTE passes where the remembered substitute was no longer in storage in the amount needed. */
    public static long substitutionRevalidationFailed;

    /**
     * SIMULATE passes that followed another SIMULATE with no EXECUTE between them.
     *
     * <p>Which is to say: a simulate that failed, on a pattern Refined Storage immediately tried
     * again. The 0.13.1 counters put these at 3.76 per execute — roughly 73% of iterations fail
     * their simulate and never execute, so this is four fifths of all the worn-tool scanning.
     */
    public static long simulateRepeats;

    /** Repeats that reached exactly the same substitution as the simulate before them. */
    public static long simulateRepeatsAgreed;

    /**
     * Repeats that did not. <b>This decides whether a failing simulate can be cached at all.</b>
     */
    public static long simulateRepeatsDisagreed;

    /**
     * The longest unbroken run of failing simulates on one pattern.
     *
     * <p>The payoff figure. A cache that saves one rescan is not worth writing; one that saves a
     * thousand is, and the average alone would hide the difference.
     */
    public static int simulateStreakLongest;

    private Stats() {
    }
}
