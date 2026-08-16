package com.wraithhawit.rstweaks.planner;

import com.refinedmods.refinedstorage.api.autocrafting.PatternRepository;
import com.refinedmods.refinedstorage.api.autocrafting.preview.Preview;
import com.refinedmods.refinedstorage.api.autocrafting.task.TaskPlan;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.api.storage.root.RootStorage;
import com.wraithhawit.rstweaks.Config;
import com.wraithhawit.rstweaks.RSTweaks;
import com.wraithhawit.rstweaks.Stats;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

/**
 * Entry point for the linear-programming crafting planner.
 *
 * <p>Refined Storage's {@code CraftingTree} is depth-first with an {@code activePatterns}
 * guard, so it cannot represent a resource that is both consumed and produced by the
 * same subgraph. That is exactly what container recycling is — bucket → milk_bucket →
 * cake → bucket — which is why asking for 1000 cakes plans 3000 buckets. Expressing
 * the same problem as equations makes the cycle net out and the answer becomes 3.
 *
 * <p><b>This planner is allowed to decline.</b> It only takes over subgraphs that
 * actually contain a byproduct or a cycle, and it returns {@code null} on anything it
 * is not certain about: too many patterns, solver budget exhausted, no integer
 * solution, or a solution the simulator could not execute. Every {@code null} means
 * stock Refined Storage plans the craft exactly as it does today, so the worst case
 * is the behaviour you already have.
 */
public final class LpCraftingPlanner {
    private LpCraftingPlanner() {
    }

    /**
     * @return a plan, or {@code null} to let stock Refined Storage handle it.
     *     Deliberately nullable rather than {@code Optional}: "decline" and "planned
     *     nothing" are different outcomes and must not be confused at the call site.
     */
    /**
     * The outcome of a planning attempt.
     *
     * @param impossible whether the solver <em>proved</em> the request unsatisfiable, as opposed to
     *     declining for a reason that leaves stock Refined Storage free to try. Only the integer
     *     program's own infeasibility counts. A repair loop giving up, or a subgraph outgrowing the
     *     pattern cap, says nothing about whether the craft is possible — those must still fall
     *     back, or we would report "missing resources" for crafts that are merely hard to model.
     * @param graph the subgraph that was solved, kept only when the request was proved impossible
     *     so {@link #shortfall} can say what it was short of without rebuilding it. Working out
     *     the shortfall costs a second solve, so it stays the caller's decision: the task path
     *     never asks, and the request screen only asks once.
     */
    public record Attempt(@Nullable TaskPlan plan,
                          boolean impossible,
                          @Nullable CraftingGraph.Graph graph) {
        static final Attempt DECLINED = new Attempt(null, false, null);
    }

    @Nullable
    public static TaskPlan tryPlan(final PatternRepository repository,
                                   final RootStorage rootStorage,
                                   final ResourceKey target,
                                   final long amount) {
        return attempt(repository, rootStorage, target, amount).plan();
    }

    public static Attempt attempt(final PatternRepository repository,
                                  final RootStorage rootStorage,
                                  final ResourceKey target,
                                  final long amount) {
        if (!Config.lpPlanner || amount <= 0L) {
            return Attempt.DECLINED;
        }
        if (!Config.keepRecycledResourcesInTask) {
            // Every plan this produces assumes a byproduct of the root pattern comes back
            // to the task. Without that redirect it goes straight to the network and the
            // second iteration deadlocks, so planning at all would be unsafe.
            return Attempt.DECLINED;
        }
        try {
            final CraftingGraph.Graph graph = CraftingGraph.build(
                repository, rootStorage, target,
                Config.intOrDefault(Config.MAX_PLANNER_PATTERNS, 4096));

            if (!graph.needsLpPlanner()) {
                // Acyclic and byproduct-free: stock RS already gets this right, and
                // leaving it alone keeps the blast radius to the cases that need us.
                //
                // Logged because "declined" and "not installed" look identical from
                // in-game, and the reason is the only way to tell a correct decline
                // from a subgraph that outgrew the pattern cap.
                explain(target, graph.truncated()
                    ? "pattern subgraph exceeded maxPlannerPatterns (" + graph.patterns().size()
                        + "); raise it if this craft should be solved"
                    : "no byproducts and no cycle -- stock RS already plans this correctly");
                ++Stats.lpPlannerDeclined;
                return Attempt.DECLINED;
            }

            final PlanMatrix.Outcome outcome = PlanMatrix.solve(
                graph,
                target,
                amount,
                Config.intOrDefault(Config.MAX_SOLVER_NODES, 5000),
                MAX_PIVOTS,
                MAX_BRANCH_DEPTH,
                MAX_SEED_REPAIRS,
                Config.intOrDefault(Config.MAX_SIMULATION_PASSES, 20_000)
            );
            if (outcome.plan() == null) {
                // Include the graph shape: knowing whether this was 3 patterns or 300,
                // and how many resource classes, is usually enough to tell a modelling
                // mistake from a genuinely impossible request.
                explain(target, outcome.failure()
                    + " [patterns=" + graph.patterns().size()
                    + ", classes=" + graph.classes().size() + "]");
                ++Stats.lpPlannerDeclined;
                // Infeasibility of the integer program is a proof, not a shrug: no assignment of
                // pattern counts satisfies the request, whatever stock RS would go on to try. The
                // other failures are this planner giving up, and must stay silent so RS can.
                final boolean impossible = outcome.failure().startsWith(PROVEN_INFEASIBLE);
                return new Attempt(null, impossible, impossible ? graph : null);
            }
            ++Stats.lpPlannerUsed;
            // The whole plan, not just its size. When a craft goes on to stall, the
            // question is always "what did it decide to do", and a count cannot answer
            // it. This fires once per accepted craft, so it is not a hot path.
            RSTweaks.LOGGER.info("[rstweaks] LP plan for {}x {}: {}", amount, target,
                describe(outcome.plan()));
            return new Attempt(outcome.plan(), false, null);
        } catch (final RuntimeException | StackOverflowError e) {
            // Never let a planner bug break crafting. Declining is always safe.
            ++Stats.lpPlannerDeclined;
            RSTweaks.LOGGER.warn("[rstweaks] LP planner failed for {}x {}, falling back to stock",
                amount, target, e);
            return Attempt.DECLINED;
        }
    }

    /**
     * What an impossible request is short of, as a preview the request screen can show, or
     * {@code null} to leave the caller with its own fallback.
     *
     * <p>Costs a second solve, so it is deliberately not part of {@link #attempt}: the task path
     * has no use for it, and only the request screen ever asks.
     */
    @Nullable
    public static Preview shortfall(final Attempt attempt,
                                    final ResourceKey target,
                                    final long amount) {
        if (!attempt.impossible() || attempt.graph() == null) {
            return null;
        }
        try {
            return PlanShortfall.diagnose(
                attempt.graph(),
                target,
                amount,
                Config.intOrDefault(Config.MAX_SOLVER_NODES, 5000),
                MAX_PIVOTS,
                MAX_BRANCH_DEPTH
            );
        } catch (final RuntimeException | StackOverflowError e) {
            // A worse message is not worth a broken request screen.
            RSTweaks.LOGGER.warn("[rstweaks] could not explain why {}x {} is impossible",
                amount, target, e);
            return null;
        }
    }

    /**
     * Prefix on every failure the solver can prove. Matched as a string because the reason is
     * already assembled for the log, and a parallel enum would be one more thing to keep in step
     * with it — see {@code PlanMatrix} for where these are produced.
     */
    private static final String PROVEN_INFEASIBLE = "no integer solution";

    /**
     * Renders an accepted plan as one line: what each pattern runs, and what the network
     * must supply up front. Everything a stalled craft is judged against — if the plan
     * asks for an initial resource nothing can provide, the task sits in
     * EXTRACTING_INITIAL_RESOURCES forever and looks exactly like a freeze.
     */
    private static String describe(final TaskPlan plan) {
        final StringBuilder sb = new StringBuilder(128);
        sb.append(plan.patterns().size()).append(" patterns [");
        boolean first = true;
        for (final Map.Entry<?, TaskPlan.PatternPlan> e : plan.patterns().entrySet()) {
            if (!first) {
                sb.append(", ");
            }
            first = false;
            sb.append(e.getValue().iterations()).append('x');
            if (e.getValue().root()) {
                sb.append(" ROOT");
            }
            sb.append(' ').append(shortName(e.getKey()));
        }
        sb.append("], initial requirements ").append(plan.initialRequirements());
        return sb.toString();
    }

    /** Patterns print their whole layout, which is unreadable in a log line. */
    private static String shortName(final Object pattern) {
        final String s = String.valueOf(pattern);
        final int cut = s.indexOf(", layout=");
        return cut < 0 ? s : s.substring(0, cut) + "]";
    }

    /**
     * Logs why a request was declined, rate-limited per resource so a repeatedly
     * failing exporter cannot flood the log. Kept at INFO rather than DEBUG because
     * this is the only signal distinguishing "correctly declined" from "silently
     * never engaged", and that distinction is what you need when the numbers in the
     * grid have not changed.
     */
    private static void explain(final ResourceKey target, final String reason) {
        final long now = System.currentTimeMillis();
        final Long last = LAST_EXPLAINED.get(target);
        if (last != null && now - last < EXPLAIN_INTERVAL_MS) {
            return;
        }
        if (LAST_EXPLAINED.size() > 256) {
            LAST_EXPLAINED.clear();
        }
        LAST_EXPLAINED.put(target, now);
        RSTweaks.LOGGER.info("[rstweaks] LP planner declined {}: {}", target, reason);
    }

    private static final Map<ResourceKey, Long> LAST_EXPLAINED = new ConcurrentHashMap<>();
    private static final long EXPLAIN_INTERVAL_MS = 30_000L;

    /**
     * Pivot cap per LP. Bland's rule cannot cycle, so this only bounds pathological
     * degeneracy; crafting tableaux settle in far fewer.
     */
    private static final int MAX_PIVOTS = 2000;

    /** Branch depth cap. Crafting relaxations are nearly integral, so this is generous. */
    private static final int MAX_BRANCH_DEPTH = 64;

    /**
     * How many times the seed-repair loop may re-solve. Each round adds one class the
     * simulation stalled on; needing more than a handful means the graph is not
     * something we should be planning.
     */
    private static final int MAX_SEED_REPAIRS = 8;
}
