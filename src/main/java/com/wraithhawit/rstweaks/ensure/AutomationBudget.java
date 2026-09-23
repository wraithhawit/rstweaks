package com.wraithhawit.rstweaks.ensure;

import com.refinedmods.refinedstorage.api.autocrafting.calculation.CancellationToken;
import com.wraithhawit.rstweaks.Config;
import com.wraithhawit.rstweaks.RSTweaks;
import com.wraithhawit.rstweaks.Stats;
import com.wraithhawit.rstweaks.backoff.BudgetedCancellationToken;

import javax.annotation.Nullable;

/**
 * One {@code ensureTask} request, one deadline, one ladder move, one verdict.
 *
 * <h2>What the first attempt got wrong</h2>
 *
 * 0.22.5 originally wrapped a fresh budgeted token at each of the three calculations a failing
 * request makes. Review found three consequences of that, and they are the reason this class
 * exists rather than a helper that just wraps tokens:
 *
 * <ul>
 *   <li><b>Three deadlines, not one.</b> Each wrap granted another allowance, so a request could
 *       spend three rungs back to back -- three seconds at the shipped defaults. Bounding each
 *       calculation is not the same as bounding the request.</li>
 *   <li><b>Escalation per calculation.</b> The ladder moved on each expiry, but several expiries
 *       inside one request are correlated consequences of a single attempt and do not justify
 *       several doublings. It climbed to the ceiling far faster than intended, which means longer
 *       stalls before independent retries have shown they are needed.</li>
 *   <li><b>False negatives cached.</b> Our budget expiring makes {@code calculatePlan} return
 *       empty, which {@code ensureTask} reports as {@code MISSING_RESOURCES} -- and
 *       {@code AutocraftingNetworkComponentImplMixin} caches that into its uncraftable map. So
 *       cutting a calculation short could suppress retries of a perfectly craftable resource for
 *       {@code uncraftableRecheckTicks}. "We ran out of time" is not "it cannot be made", and
 *       caching one as the other is a correctness defect, not a tuning question.</li>
 * </ul>
 *
 * <p>Review also corrected the accounting in the other direction: the craftable-amount search
 * created a token but never recorded its expiry, so a search-only overrun moved nothing at all.
 * A request now has exactly one place where its outcome is decided, which is what makes both
 * errors impossible rather than fixed.
 *
 * <h2>How the deadline travels</h2>
 *
 * The token is created at the request's first calculation and held here for the rest of it. RS
 * passes the caller's token into {@code calculatePlan} and captures it in the lambda that reaches
 * {@code ensureTaskForCraftableAmount}, so the later calculations ask for the same token back
 * rather than making their own. It is a cooperative timeout either way: cancellation is polled, so
 * a calculation can overshoot by however long it goes without checking.
 *
 * <p>Static, and server-thread by assumption like {@code Stats} and {@code CalculationTrace}. A
 * request that throws between {@link #begin} and {@link #finish} leaves its token here; the next
 * request replaces it at its own first calculation, so the damage is bounded to that one request
 * and heals itself.
 */
public final class AutomationBudget {
    @Nullable
    private static BudgetedCancellationToken current;
    @Nullable
    private static String currentResource;
    private static boolean cutShort;

    private AutomationBudget() {
    }

    /** The ceiling for an automation calculation: our own, never above RS's, never below the start. */
    public static int cap() {
        final int start = Math.max(1, Config.intOrDefault(Config.STEP_REQUESTER_CALCULATION_BUDGET_MS, 200));
        final int rsTimeout = (int) Math.min(Integer.MAX_VALUE, Config.craftingCalculationTimeoutMs);
        final int configuredMax = Config.intOrDefault(Config.STEP_REQUESTER_MAX_BUDGET_MS, 1000);
        final int bounded = configuredMax > 0 ? Math.min(configuredMax, rsTimeout) : rsTimeout;
        return Math.max(start, bounded);
    }

    /**
     * The deadline for this request, creating it on the first calculation and reusing it after.
     *
     * <p>Keyed by resource so a token left behind by a request that threw cannot be handed to a
     * different resource: a mismatch starts fresh.
     */
    public static CancellationToken tokenFor(final String resource, final CancellationToken caller) {
        if (!Config.budgetEnsureTaskCalculations) {
            return caller;
        }
        final BudgetedCancellationToken existing = current;
        if (existing != null && resource.equals(currentResource)) {
            return existing;
        }
        return begin(resource, caller);
    }

    /**
     * The token for the craftable-amount search, which must never be a second allowance.
     *
     * <p>Returns {@code null} when there is no request in flight -- the search then keeps whatever
     * the caller was going to give it, which is what {@code boundCraftableSearch} decides.
     */
    @Nullable
    public static CancellationToken inFlight(final String resource) {
        if (!Config.budgetEnsureTaskCalculations) {
            return null;
        }
        final BudgetedCancellationToken existing = current;
        return existing != null && resource.equals(currentResource) ? existing : null;
    }

    private static BudgetedCancellationToken begin(final String resource,
                                                   final CancellationToken caller) {
        final int start = Math.max(1, Config.intOrDefault(Config.STEP_REQUESTER_CALCULATION_BUDGET_MS, 200));
        final BudgetedCancellationToken token = new BudgetedCancellationToken(
            caller, EnsureTaskBudget.budgetFor(resource, start, cap()));
        current = token;
        currentResource = resource;
        cutShort = false;
        return token;
    }

    /** Whether the request that just returned was stopped by us rather than by its own merits. */
    public static boolean wasCutShort() {
        return cutShort;
    }

    /**
     * Ends the request: one ladder move, one counter, one verdict.
     *
     * <p>Only <em>our</em> bound expiring escalates. A calculation that failed on its own merits
     * gets nothing extra, or every impossible craft would climb to the ceiling and automation would
     * be back to freezing the world for as long as that allows.
     *
     * @param succeeded whether the request produced a task, which resets the ladder
     */
    public static void finish(final boolean succeeded) {
        final BudgetedCancellationToken token = current;
        final String resource = currentResource;
        current = null;
        currentResource = null;
        if (token == null || resource == null) {
            // No calculation ran -- a HEAD short-circuit answered it. Nothing to account for.
            cutShort = false;
            return;
        }
        cutShort = token.expiredOnBudget();
        ++Stats.ensureTaskCalculations;
        if (cutShort) {
            ++Stats.ensureTaskBudgetExpiries;
            EnsureTaskBudget.noteExpired(
                resource,
                Math.max(1, Config.intOrDefault(Config.STEP_REQUESTER_CALCULATION_BUDGET_MS, 200)),
                cap());
        } else if (succeeded) {
            EnsureTaskBudget.noteSucceeded(resource);
        }
    }

    /** Timing for one phase of the request, for the log only. Moves no ladder and caches nothing. */
    public static void notePhase(final String resource, final String what, final long startedNanos) {
        final long elapsedMs = (System.nanoTime() - startedNanos) / 1_000_000L;
        if (elapsedMs > Stats.ensureTaskSlowestMs) {
            Stats.ensureTaskSlowestMs = elapsedMs;
        }
        final int slowMs = Config.intOrDefault(Config.STEP_REQUESTER_SLOW_CALCULATION_MS, 1);
        if (Config.traceSlowCalculations && slowMs > 0 && elapsedMs >= slowMs) {
            RSTweaks.LOGGER.info("[rstweaks] automation {} for {} took {}ms", what, resource,
                elapsedMs);
        }
    }

    /** Test seam. */
    public static void reset() {
        current = null;
        currentResource = null;
        cutShort = false;
    }
}
