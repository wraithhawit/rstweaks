package com.wraithhawit.rstweaks.ensure;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The escalating calculation budget for automation that goes through {@code ensureTask}.
 *
 * <h2>Why this exists</h2>
 *
 * {@code stepRequesterCalculationBudgetMs} bounds what a Step Requester may spend planning, and
 * 0.22.3 proved what that is worth: 36.62% of the server thread down to 1.27%. But it was written
 * as a Step Crafter fix and reaches only {@code startTask}. An Exporter or an Interface carrying an
 * autocrafting upgrade asks through {@code ensureTask} instead, and nothing bounded that at all.
 *
 * <p>Measured on the same world once the Step Requester stopped dominating:
 * {@code ExporterNetworkNode.doWork} was <b>67.25% of the server thread</b>, TPS 4.3, worst tick
 * 7,052ms. Two separate calculations sit underneath it, and the second is the worse one:
 *
 * <ul>
 *   <li>{@code ensureTask} calls {@code calculatePlan} with a bare
 *       {@code TimeoutableCancellationToken} -- RS's full 5,000ms, the same freeze the Step
 *       Requester used to take.</li>
 *   <li>when that fails, {@code ensureTaskForCraftableAmount} runs
 *       {@code binarySearchMaxAmount(calculator, resource, CancellationToken.NONE)}. <b>NONE.</b>
 *       A binary search that runs a whole crafting calculation per probe, with nothing able to
 *       stop it -- not our budget, not RS's own timeout. It measured 13.78% of the thread on its
 *       own.</li>
 * </ul>
 *
 * <p>So a failing automated request pays twice: it burns the full timeout planning, fails, and
 * then runs an uncancellable search. That is why bounding only the first would not have been
 * enough.
 *
 * <h2>Why automation may be refused when a player may not</h2>
 *
 * The argument is the one {@code StepRequesterNetworkNodeMixin} already makes and 0.22.3 already
 * tested. A cancelled calculation reports {@code MISSING_RESOURCES}, which is indistinguishable
 * from "this cannot be made", so cutting the budget globally would silently refuse crafts that
 * would have worked -- for a <em>player</em>, who is waiting on the answer. An Exporter is not
 * waiting; it asks again on a schedule regardless. Refusing it once costs a retry. Freezing the
 * world for five seconds costs everyone.
 *
 * <p>{@code startTask} is therefore untouched, which is what keeps a player's own request on the
 * full budget. All three callers of {@code ensureTask} are automation:
 * {@code MissingResourcesListeningExporterTransferStrategy}, {@code InterfaceNetworkNode} and
 * {@code AutocraftOnMissingResourcesConstructorStrategy}.
 *
 * <h2>The ladder</h2>
 *
 * Per resource, not global. A global ladder would let one pathological resource's escalation hand
 * a large budget to a cheap one, which can only lengthen a freeze. Keyed by the resource's string
 * form because this class holds no Refined Storage types, so it can be exercised in a plain JVM.
 *
 * <p>Bounded by an LRU, because a network can ask about more distinct resources than are worth
 * remembering and a plain map here would grow for as long as the world runs. Evicting a resource
 * costs it its escalation, so it starts from the bottom rung again -- the safe direction.
 */
public final class EnsureTaskBudget {
    /**
     * Resources whose ladder is remembered. Large enough that a real automation setup keeps its
     * hot resources, small enough to be bounded; eviction is a lost escalation, never a wrong one.
     */
    private static final int TRACKED = 512;

    private static final Map<String, Integer> LADDER =
        new LinkedHashMap<>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(final Map.Entry<String, Integer> eldest) {
                return size() > TRACKED;
            }
        };

    private EnsureTaskBudget() {
    }

    /** The budget this resource gets for its next calculation, in milliseconds. */
    public static synchronized int budgetFor(final String resource,
                                             final int startMs,
                                             final int maxMs) {
        final int start = Math.max(1, startMs);
        final int cap = Math.max(start, maxMs);
        final Integer escalated = LADDER.get(resource);
        if (escalated == null || escalated <= 0) {
            return start;
        }
        return Math.min(escalated, cap);
    }

    /**
     * Doubles this resource's budget, because <em>our</em> bound is what stopped it.
     *
     * <p>Only our budget expiring earns a bigger one. A calculation that failed on its own merits
     * gets nothing extra, or every impossible craft would climb to the cap and automation would be
     * back to freezing the world for as long as the ceiling allows.
     */
    public static synchronized void noteExpired(final String resource,
                                                final int startMs,
                                                final int maxMs) {
        final int start = Math.max(1, startMs);
        final int cap = Math.max(start, maxMs);
        final Integer previous = LADDER.get(resource);
        final int next = previous == null || previous <= 0
            ? Math.min(start * 2, cap)
            : Math.min(previous * 2, cap);
        LADDER.put(resource, next);
    }

    /** A calculation that finished within its budget puts the resource back on the bottom rung. */
    public static synchronized void noteSucceeded(final String resource) {
        LADDER.remove(resource);
    }

    /** Test seam: the raw escalated value, or 0 when this resource has never been cut short. */
    public static synchronized int rawBudgetOf(final String resource) {
        final Integer escalated = LADDER.get(resource);
        return escalated == null ? 0 : escalated;
    }

    /** Test seam. */
    public static synchronized int tracked() {
        return LADDER.size();
    }

    /** Test seam. */
    public static synchronized void clear() {
        LADDER.clear();
    }
}
