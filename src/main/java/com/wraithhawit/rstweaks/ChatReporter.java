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
                          long sidedLookups,
                          long uncraftable,
                          long indexHits,
                          long indexFallbacks,
                          long indexRebuilds,
                          long slotCounts,
                          long drawerChecks,
                          long wrongTypeProbes,
                          long fluidSwaps,
                          long lpPlanned,
                          long planCopies,
                          long emptyExtracts) {

        static final Counts ZERO = new Counts(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

        static Counts now() {
            return new Counts(
                Stats.stepRequesterScansSkipped,
                Stats.stepRequesterFailures,
                Stats.sidedInputLookups,
                Stats.uncraftableChecksSkipped,
                Stats.externalIndexHits,
                Stats.externalIndexFallbacks,
                Stats.externalIndexRebuilds,
                Stats.slotCountLookupsAvoided,
                Stats.drawerMembershipChecks,
                Stats.mismatchedProviderCallsAvoided,
                Stats.fluidSwapPatterns,
                Stats.lpPlannerUsed,
                Stats.patternPlanCopiesAvoided,
                Stats.emptyExtractsAvoided);
        }

        Counts since(final Counts earlier) {
            return new Counts(
                stepScans - earlier.stepScans,
                stepFailures - earlier.stepFailures,
                sidedLookups - earlier.sidedLookups,
                uncraftable - earlier.uncraftable,
                indexHits - earlier.indexHits,
                indexFallbacks - earlier.indexFallbacks,
                indexRebuilds - earlier.indexRebuilds,
                slotCounts - earlier.slotCounts,
                drawerChecks - earlier.drawerChecks,
                wrongTypeProbes - earlier.wrongTypeProbes,
                fluidSwaps - earlier.fluidSwaps,
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
        // Version first: every test result needs to be attributable to a build, and chat
        // is the one place it is visible without opening a log.
        player.sendSystemMessage(Component.empty()
            .append(PREFIX)
            .append(Component.literal("v" + RSTweaks.version + " ").withStyle(ChatFormatting.AQUA))
            .append(Component.literal("active: ").withStyle(ChatFormatting.GRAY))
            .append(Component.literal(RSTweaks.activeFeatures()).withStyle(ChatFormatting.WHITE)));

        final Component summary = buildSummary(Counts.now(), "so far this session");
        if (summary != null) {
            player.sendSystemMessage(summary);
        }
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
        final Component summary = buildSummary(now.since(lastReported), "since last report");
        lastReported = now;
        if (summary == null) {
            return;
        }
        final MinecraftServer server = event.getServer();
        server.getPlayerList().broadcastSystemMessage(summary, false);
    }

    /** The counters as chat text, or {@code null} when every one of them was zero. */
    static Component buildSummary(final Counts delta, final String suffix) {
        final List<String> parts = new ArrayList<>(9);
        if (delta.stepScans() > 0) {
            parts.add(String.format("%,d crafting calculations skipped", delta.stepScans()));
        }
        if (delta.uncraftable() > 0) {
            parts.add(String.format("%,d uncraftable rechecks avoided", delta.uncraftable()));
        }
        if (delta.stepFailures() > 0) {
            parts.add(String.format("%,d failed attempts backed off", delta.stepFailures()));
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
        if (delta.fluidSwaps() > 0) {
            parts.add(String.format("%,d fluid substitution patterns", delta.fluidSwaps()));
        }
        if (delta.lpPlanned() > 0) {
            parts.add(String.format("%,d crafts planned by solver", delta.lpPlanned()));
        }
        if (delta.planCopies() > 0) {
            parts.add(String.format("%,d plan copies avoided", delta.planCopies()));
        }
        if (delta.emptyExtracts() > 0) {
            parts.add(String.format("%,d storage walks avoided", delta.emptyExtracts()));
        }
        if (parts.isEmpty()) {
            return null;
        }
        return Component.empty()
            .append(PREFIX)
            .append(Component.literal(String.join(", ", parts)).withStyle(ChatFormatting.GREEN))
            .append(Component.literal(" " + suffix + ".").withStyle(ChatFormatting.GRAY));
    }

    /** Session totals on demand, for {@code /rstweaks stats}. */
    public static Component sessionTotals() {
        final Component summary = buildSummary(Counts.now(), "so far this session");
        return summary != null ? summary : Component.empty()
            .append(PREFIX)
            .append(Component.literal("no optimization has fired yet this session.")
                .withStyle(ChatFormatting.GRAY));
    }
}
