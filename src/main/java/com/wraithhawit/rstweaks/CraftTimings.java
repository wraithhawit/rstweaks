package com.wraithhawit.rstweaks;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * How long each craft actually took, start to finish.
 *
 * <h2>Why this exists</h2>
 *
 * <p><b>Every performance number in this mod's history is a share of the server thread, and a share
 * cannot measure these optimizations.</b> The multiblock crafter has no time budget, so it expands
 * to fill the tick: make a step cheaper and Refined Storage simply runs more steps, leaving
 * {@code tickNode} pinned at ~95% no matter what. "The durability path went 40.15% to 26.87%"
 * describes a redistribution, not a saving.
 *
 * <p>Wall-clock time for a known craft has none of that problem. Ask for a million insanium twice,
 * on two builds, and the difference is the answer — no profile, no interpretation, no arguing about
 * denominators.
 *
 * <p>Kept as a short history rather than a running mean: craft durations vary by orders of magnitude
 * depending on what is being made, so an average across different recipes is a number with no
 * meaning. The comparison that works is the same craft against itself.
 */
public final class CraftTimings {
    /**
     * One finished craft.
     *
     * @param resource what was requested, already shortened for reading
     * @param amount   how many were asked for
     * @param millis   wall-clock time from the task being built to it completing
     */
    public record Finished(String resource, long amount, long millis) {
        /** Items per second, the figure that is actually comparable between builds. */
        public double perSecond() {
            return millis <= 0L ? 0.0 : amount * 1000.0 / millis;
        }
    }

    /** Enough to compare a few runs, few enough to print in chat without scrolling. */
    private static final int KEPT = 8;

    private static final Deque<Finished> HISTORY = new ArrayDeque<>();

    private CraftTimings() {
    }

    /** Records a completed craft and logs it. Called from the task engine, on the server thread. */
    public static synchronized void record(final String resource, final long amount,
                                           final long millis) {
        final Finished finished = new Finished(resource, amount, millis);
        HISTORY.addFirst(finished);
        while (HISTORY.size() > KEPT) {
            HISTORY.removeLast();
        }
        RSTweaks.LOGGER.info("[rstweaks] craft finished: {} x {} in {} ({}/s)",
            String.format("%,d", amount), resource, describe(millis),
            String.format("%,.0f", finished.perSecond()));
    }

    /** Most recent first. */
    public static synchronized List<Finished> recent() {
        return new ArrayList<>(HISTORY);
    }

    /**
     * A duration a person can read at a glance.
     *
     * <p>Milliseconds for anything under a second, because a fast craft is where the interesting
     * differences are; minutes and seconds above that, because "251,394ms" is not a number anybody
     * compares correctly under time pressure.
     */
    public static String describe(final long millis) {
        if (millis < 1000L) {
            return millis + "ms";
        }
        if (millis < 60_000L) {
            return String.format("%.1fs", millis / 1000.0);
        }
        return String.format("%dm %02ds", millis / 60_000L, millis % 60_000L / 1000L);
    }

    /**
     * Shortens a resource for reading: {@code ItemResource[item=minecraft:cake, components={}]}
     * becomes {@code minecraft:cake}.
     *
     * <p>Falls back to the whole string rather than guessing, so an unfamiliar resource type is
     * still identifiable rather than being silently truncated to nothing.
     */
    public static String shorten(final String resource) {
        final int start = resource.indexOf("item=");
        if (start < 0) {
            return resource;
        }
        final int from = start + "item=".length();
        final int comma = resource.indexOf(',', from);
        final int end = comma < 0 ? resource.indexOf(']', from) : comma;
        return end < 0 ? resource.substring(from) : resource.substring(from, end);
    }
}
