package com.wraithhawit.rstweaks.sink;

import javax.annotation.Nullable;

/**
 * Checks {@link SinkRejectionCache}'s answers against the real thing, so the assumption it rests
 * on can be disproved rather than argued about.
 *
 * <h2>Why this exists</h2>
 *
 * The cache keys on sink identity alone, but {@code calculateIterationInputs(SIMULATE)} is
 * recomputed on every attempt. If those inputs can differ between two attempts in the same burst,
 * a refusal recorded for one set of resources is being reused for another. The argument that they
 * cannot -- nothing extracts from the task's internal storage without an acceptance, and an
 * acceptance empties the cache -- is only an argument, and it reasons about every sink
 * implementation from the outside. No headless suite can settle it, because the question is about
 * what real sinks do at runtime.
 *
 * <p>So this asks them. With verification on, a cache hit <b>still performs the real probe</b> and
 * compares, and <b>the live answer is the one returned</b>. That makes verification mode correct
 * by construction: it cannot cause the delayed craft it is looking for, because the cache's answer
 * is never acted on while it runs.
 *
 * <p><b>It is therefore slower than having no cache at all</b> -- every hit pays for the probe it
 * was meant to avoid, plus the comparison. It is a diagnostic, not a setting to leave on, and a
 * profile taken with it enabled measures nothing useful about performance.
 *
 * <h2>Reading the result</h2>
 *
 * {@code agreements} rising with {@code mismatches} at zero is the assumption holding under real
 * load. {@code agreements} at zero means the cache never hit and the run proved nothing -- the
 * machines were probably never busy enough to make the task sweep them twice.
 *
 * <p>A mismatch names the sink, the two answers, and the resources in play, because the useful
 * question is not that one happened but whether the inputs had changed underneath it. The first
 * few are logged in full and then it goes quiet, on the same reasoning as everything else here:
 * the tenth copy of a line teaches nothing the first did not.
 *
 * <p>Free of Refined Storage and Minecraft types, like {@link SinkRejectionCache} and for the same
 * reason. The caller renders what it has; this decides and counts.
 */
public final class SinkCacheVerifier {
    /** How many disagreements are described in full before only the count is kept. */
    public static volatile int reportLimit = 10;

    private static long agreements;
    private static long mismatches;
    private static long reported;

    private SinkCacheVerifier() {
    }

    /**
     * Compares a cached answer with the live one.
     *
     * @return a line to log, or {@code null} when they agree or enough have been reported
     */
    @Nullable
    public static synchronized String note(final Object cached,
                                           final Object live,
                                           final String sink,
                                           final String pattern,
                                           final String resources,
                                           final long burst,
                                           final String thread) {
        if (cached == live || cached.equals(live)) {
            ++agreements;
            return null;
        }
        ++mismatches;
        if (reported >= reportLimit) {
            return null;
        }
        ++reported;
        return String.format(
            "sink cache MISMATCH #%d: cached %s but live %s -- sink %s, burst %d, thread %s, "
                + "pattern %s, resources %s",
            mismatches, cached, live, sink, burst, thread, pattern, resources);
    }

    public static synchronized long agreements() {
        return agreements;
    }

    public static synchronized long mismatches() {
        return mismatches;
    }

    /** Test seam. */
    public static synchronized void reset() {
        agreements = 0L;
        mismatches = 0L;
        reported = 0L;
    }
}
