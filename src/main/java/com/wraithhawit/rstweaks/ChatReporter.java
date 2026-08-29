package com.wraithhawit.rstweaks;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Reports in chat that the optimizations are live and doing something.
 *
 * <p>Deliberately reports counts of work <em>avoided</em> rather than a bare "loaded"
 * line â€” a startup message only proves the jar was read, whereas a non-zero skip count
 * proves the injections actually fired on the hot path.
 *
 * <p>A periodic summary is suppressed when nothing changed since the last one, so an idle
 * network stays silent instead of repeating zeroes. That suppression used to be decided
 * by only the four counters this class started with, while the body reported nine â€” so a
 * session that exercised the newer optimizations and none of the original four printed
 * nothing at all, and looked exactly like a mod that had failed to load. The report is
 * now built first and suppressed only if it came out empty.
 */
public final class ChatReporter {
    private static final Component PREFIX =
        Component.literal("[rstweaks] ").withStyle(ChatFormatting.DARK_AQUA);

    private static int tickCounter;
    private static Counts lastReported = Counts.ZERO;

    private ChatReporter() {
    }

    /**
     * Every counter at one instant, so a report can show what changed rather than mixing
     * per-interval deltas with running totals in the same sentence â€” which the previous
     * version did, and which made "since last report" untrue for half the line.
     */
    private record Counts(long stepScans,
                          long stepFailures,
                          long stepSlow,
                          long stepCalls,
                          long stepNanos,
                          long patternSorts,
                          long stepTimeouts,
                          long stepBudgetExpiries,
                          long sidedLookups,
                          long uncraftable,
                          long duplicateRequests,
                          long indexHits,
                          long indexFallbacks,
                          long indexRebuilds,
                          long slotCounts,
                          long drawerChecks,
                          long wrongTypeProbes,
                          long lpPlanned,
                          long planCopies,
                          long emptyExtracts) {

        static final Counts ZERO =
            new Counts(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

        static Counts now() {
            return new Counts(
                Stats.stepRequesterScansSkipped,
                Stats.stepRequesterFailures,
                Stats.stepRequesterSlowCalculations,
                Stats.stepRequesterCalculations,
                Stats.stepRequesterCalculationNanos,
                Stats.patternListsSorted,
                Stats.stepRequesterTimeouts,
                Stats.stepRequesterBudgetExpiries,
                Stats.sidedInputLookups,
                Stats.uncraftableChecksSkipped,
                Stats.duplicateRequestsSuppressed,
                Stats.externalIndexHits,
                Stats.externalIndexFallbacks,
                Stats.externalIndexRebuilds,
                Stats.slotCountLookupsAvoided,
                Stats.drawerMembershipChecks,
                Stats.mismatchedProviderCallsAvoided,
                Stats.lpPlannerUsed,
                Stats.patternPlanCopiesAvoided,
                Stats.emptyExtractsAvoided);
        }

        Counts since(final Counts earlier) {
            return new Counts(
                stepScans - earlier.stepScans,
                stepFailures - earlier.stepFailures,
                stepSlow - earlier.stepSlow,
                stepCalls - earlier.stepCalls,
                stepNanos - earlier.stepNanos,
                patternSorts - earlier.patternSorts,
                stepTimeouts - earlier.stepTimeouts,
                stepBudgetExpiries - earlier.stepBudgetExpiries,
                sidedLookups - earlier.sidedLookups,
                uncraftable - earlier.uncraftable,
                duplicateRequests - earlier.duplicateRequests,
                indexHits - earlier.indexHits,
                indexFallbacks - earlier.indexFallbacks,
                indexRebuilds - earlier.indexRebuilds,
                slotCounts - earlier.slotCounts,
                drawerChecks - earlier.drawerChecks,
                wrongTypeProbes - earlier.wrongTypeProbes,
                lpPlanned - earlier.lpPlanned,
                planCopies - earlier.planCopies,
                emptyExtracts - earlier.emptyExtracts);
        }
    }

    @SubscribeEvent
    public static void onPlayerJoin(final PlayerEvent.PlayerLoggedInEvent event) {
        if (!Config.CHAT_NOTIFICATIONS.get()) {
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        // Version and features only. Every test result needs to be attributable to a build,
        // and chat is the one place that is visible without opening a log -- but the
        // counters are not something to push at somebody who just walked in. They are one
        // command away.
        player.sendSystemMessage(Component.empty()
            .append(PREFIX)
            .append(Component.literal("v" + RSTweaks.version + " ").withStyle(ChatFormatting.AQUA))
            .append(Component.literal("active: ").withStyle(ChatFormatting.GRAY))
            .append(Component.literal(RSTweaks.activeFeatures()).withStyle(ChatFormatting.WHITE)));
    }

    @SubscribeEvent
    public static void onServerTick(final ServerTickEvent.Post event) {
        if (!Config.CHAT_NOTIFICATIONS.get()) {
            return;
        }
        final int intervalSeconds = Config.CHAT_NOTIFICATION_INTERVAL_SECONDS.getAsInt();
        if (intervalSeconds <= 0) {
            return;
        }
        // Counted in ticks rather than wall time on purpose: when the server is lagging,
        // tick-based pacing stretches the interval instead of firing a burst of reports
        // the moment it catches up.
        if (++tickCounter < intervalSeconds * 20) {
            return;
        }
        tickCounter = 0;

        final Counts now = Counts.now();
        final List<Component> summary = report(now.since(lastReported), "since last report");
        lastReported = now;
        if (summary.isEmpty()) {
            return;
        }
        final MinecraftServer server = event.getServer();
        summary.forEach(line -> server.getPlayerList().broadcastSystemMessage(line, false));
    }

    /**
     * The counters as chat lines — a header, then one stat per line, or empty when every
     * counter was zero.
     *
     * <p>One per line rather than a comma-joined sentence. Twelve figures run together
     * wrap across the chat box at whatever width the reader happens to have, and the
     * number you came to look at ends up in the middle of a paragraph. Wraith asked for
     * this on 2026-08-17 and he was right: these are read one at a time.
     */
    static List<Component> report(final Counts delta, final String suffix) {
        final List<String> parts = describe(delta);
        if (parts.isEmpty()) {
            return List.of();
        }
        final List<Component> lines = new ArrayList<>(parts.size() + 1);
        lines.add(Component.empty()
            .append(PREFIX)
            .append(Component.literal(suffix + ":").withStyle(ChatFormatting.GRAY)));
        for (final String part : parts) {
            // The count is split off and coloured on its own so the eye lands on the
            // number rather than on the sentence explaining it. Every entry above is
            // "<number> <words>", but a future one without a space must not throw in the
            // middle of a chat message -- it just goes out unsplit.
            final int space = part.indexOf(' ');
            if (space < 0) {
                lines.add(Component.literal("  " + part).withStyle(ChatFormatting.GREEN));
                continue;
            }
            lines.add(Component.empty()
                .append(Component.literal("  " + part.substring(0, space) + " ")
                    .withStyle(ChatFormatting.GREEN))
                .append(Component.literal(part.substring(space + 1))
                    .withStyle(ChatFormatting.GRAY)));
        }
        return lines;
    }

    /** Each non-zero counter as "&lt;count&gt; &lt;what it means&gt;". */
    private static List<String> describe(final Counts delta) {
        final List<String> parts = new ArrayList<>(12);
        if (delta.stepScans() > 0) {
            parts.add(String.format("%,d crafting calculations skipped", delta.stepScans()));
        }
        if (delta.uncraftable() > 0) {
            parts.add(String.format("%,d uncraftable rechecks avoided", delta.uncraftable()));
        }
        if (delta.duplicateRequests() > 0) {
            parts.add(String.format("%,d duplicate craft requests refused",
                delta.duplicateRequests()));
        }
        if (delta.stepFailures() > 0) {
            parts.add(String.format("%,d failed attempts backed off", delta.stepFailures()));
        }
        if (delta.patternSorts() > 0) {
            // Counts only the lists whose order actually changed, so this is a direct measure
            // of how often RS handed back heap-array order rather than priority order.
            parts.add(String.format("%,d pattern lists reordered", delta.patternSorts()));
        }
        if (delta.stepCalls() > 0) {
            // The line that would have prevented 0.2.113's wrong threshold. Count, mean and
            // worst case together: a mean well under the threshold with a huge count is the
            // "thousands of small calls" shape, and a large slowest with a small count is the
            // opposite. Setting the threshold needs to know which.
            final double meanMs = delta.stepNanos() / 1_000_000.0 / delta.stepCalls();
            parts.add(String.format("%,d craft calculations (%.2fms mean, %,dms total; %,dms session peak)",
                delta.stepCalls(),
                meanMs,
                Math.round(delta.stepNanos() / 1_000_000.0),
                Stats.stepRequesterSlowestMs));
        }
        if (delta.stepBudgetExpiries() > 0) {
            // Each of these is a five-second server-thread freeze that did not happen. A slot
            // climbing the ladder is being asked for something large, not something impossible.
            parts.add(String.format("%,d long calculations cut short", delta.stepBudgetExpiries()));
        }
        if (delta.stepTimeouts() > 0) {
            // The delta-able form of "is it still hitting the ceiling". Each one also means a
            // craft was reported impossible when it may only have been slow.
            parts.add(String.format("%,d hit the calculation timeout", delta.stepTimeouts()));
        }
        if (delta.stepSlow() > 0) {
            // Reported separately from failures on purpose: these SUCCEEDED. A high number
            // here with failures near zero is the signature the failure-only backoff used to
            // miss entirely, and it is the one line that says the slot is expensive rather
            // than impossible.
            parts.add(String.format("%,d slow crafts backed off", delta.stepSlow()));
        }
        if (delta.sidedLookups() > 0) {
            parts.add(String.format("%,d fast pattern lookups", delta.sidedLookups()));
        }
        if (delta.indexHits() > 0 || delta.indexFallbacks() > 0) {
            // Shown as a ratio: a fallback share that stays high means the index is
            // thrashing rather than helping, which no amount of tuning fixes.
            final long total = delta.indexHits() + delta.indexFallbacks();
            parts.add(String.format("%,d indexed extractions (%.1f%% hit, %,d rebuilds)",
                total, delta.indexHits() * 100.0 / total, delta.indexRebuilds()));
        }
        if (delta.slotCounts() > 0) {
            parts.add(String.format("%,d slot-count lookups avoided", delta.slotCounts()));
        }
        if (delta.drawerChecks() > 0) {
            parts.add(String.format("%,d drawer scans avoided", delta.drawerChecks()));
        }
        if (delta.wrongTypeProbes() > 0) {
            parts.add(String.format("%,d wrong-type storage probes avoided",
                delta.wrongTypeProbes()));
        }
        if (delta.lpPlanned() > 0) {
            parts.add(String.format("%,d crafts planned by solver", delta.lpPlanned()));
        }
        // Read straight off Stats rather than through the delta record, because the question this
        // answers is "did batching do anything at all" and a zero is the interesting answer. It
        // refuses every pattern with a byproduct, so on a crafting-tool chain it is silent by
        // design -- which is indistinguishable from being switched off unless the number is here.
        if (Config.batchedExecution) {
            parts.add(Stats.batchedSteps == 0L
                ? "batching on, nothing batched yet"
                : String.format("%,d iterations in %,d batches (%.0f wide)",
                    Stats.batchedIterations, Stats.batchedSteps,
                    (double) Stats.batchedIterations / Stats.batchedSteps));
        }
        if (delta.planCopies() > 0) {
            parts.add(String.format("%,d plan copies avoided", delta.planCopies()));
        }
        if (delta.emptyExtracts() > 0) {
            parts.add(String.format("%,d storage walks avoided", delta.emptyExtracts()));
        }
        return parts;
    }

    /** Session totals on demand, for {@code /rstweaks stats}. */
    public static List<Component> sessionTotals() {
        final List<Component> lines = report(Counts.now(), "session totals");
        if (!lines.isEmpty()) {
            return lines;
        }
        return List.of(Component.empty()
            .append(PREFIX)
            .append(Component.literal("no optimization has fired yet this session.")
                .withStyle(ChatFormatting.GRAY)));
    }
}
