package com.wraithhawit.rstweaks.test;

import com.wraithhawit.rstweaks.sink.SinkRejectionCache;

import java.util.ArrayList;
import java.util.List;

/**
 * Self-tests for {@link SinkRejectionCache}. Run with {@code ./gradlew sinkCheck}; exits non-zero
 * on the first failure.
 *
 * <p><b>What this suite is not.</b> It cannot prove the mixin applies, that the redirect lands on
 * the {@code SIMULATE} call rather than the {@code EXECUTE} one, or that sink instances really are
 * stable across calls -- no plain JVM transforms bytecode. Those were established by reading
 * Refined Storage 2.0.9's bytecode and {@code AutocraftingNetworkComponentImpl}, and only a real
 * launch can confirm them.
 *
 * <p>What it does cover is the part that decides whether a craft is placed or delayed: that an
 * acceptance is never remembered, that one empties what is remembered, and that nothing survives a
 * burst boundary. Each of those failing is silent -- the craft just happens later, or not at all --
 * which is exactly the kind of bug a cache introduces and nobody attributes to the cache.
 */
public final class HeadlessSinkCheck {
    private static final List<String> FAILURES = new ArrayList<>();
    private static int checks;

    /** Stand-ins for sinks. Identity is all the cache uses, so any object will do. */
    private static final Object SINK_A = new Object();
    private static final Object SINK_B = new Object();

    private static final Object REJECTED = "REJECTED";
    private static final Object LOCKED = "LOCKED";
    private static final Object ACCEPTED = "ACCEPTED";

    private HeadlessSinkCheck() {
    }

    public static void main(final String[] args) {
        aRefusalIsRemembered();
        anAcceptanceIsNotRemembered();
        anAcceptanceClearsWhatWasRemembered();
        aRefusalIsPerSink();
        theExactResultComesBack();
        aNewBurstForgetsEverything();
        cachesAreIndependent();
        anAllRefusedBurstDoesNotOutliveItself();

        System.out.println("sink checks: " + checks);
        if (!FAILURES.isEmpty()) {
            FAILURES.forEach(failure -> System.out.println("  FAIL " + failure));
            System.out.println("FAIL");
            System.exit(1);
        }
        System.out.println("PASS");
    }

    private static void aRefusalIsRemembered() {
        final SinkRejectionCache cache = fresh();
        expect("an unasked sink has no answer", cache.cachedRejection(SINK_A) == null);
        cache.record(SINK_A, REJECTED, false);
        expect("a refusal is remembered", cache.cachedRejection(SINK_A) == REJECTED);
    }

    /**
     * The whole safety argument. A remembered acceptance would send an iteration into a machine
     * that has since been filled by the iteration before it.
     */
    private static void anAcceptanceIsNotRemembered() {
        final SinkRejectionCache cache = fresh();
        cache.record(SINK_A, ACCEPTED, true);
        expect("an acceptance is never remembered", cache.cachedRejection(SINK_A) == null);
        expect("and nothing is stored at all", cache.size() == 0);
    }

    /** Accepting inserts resources, so every earlier answer is suspect from that moment. */
    private static void anAcceptanceClearsWhatWasRemembered() {
        final SinkRejectionCache cache = fresh();
        cache.record(SINK_A, REJECTED, false);
        cache.record(SINK_B, LOCKED, false);
        expect("two refusals held", cache.size() == 2);
        cache.record(SINK_B, ACCEPTED, true);
        expect("an acceptance drops the other sink too", cache.cachedRejection(SINK_A) == null);
        expect("and empties the cache", cache.size() == 0);
    }

    private static void aRefusalIsPerSink() {
        final SinkRejectionCache cache = fresh();
        cache.record(SINK_A, REJECTED, false);
        expect("one sink refusing does not answer for another",
            cache.cachedRejection(SINK_B) == null);
    }

    /**
     * REJECTED, SKIPPED and LOCKED are not interchangeable: {@code appendStatus} reports each
     * differently, so handing back the wrong one would mislabel a task in the UI.
     */
    private static void theExactResultComesBack() {
        final SinkRejectionCache cache = fresh();
        cache.record(SINK_A, LOCKED, false);
        expect("the same result is returned, not just 'no'",
            cache.cachedRejection(SINK_A) == LOCKED);
    }

    private static void aNewBurstForgetsEverything() {
        final SinkRejectionCache cache = fresh();
        cache.record(SINK_A, REJECTED, false);
        SinkRejectionCache.newBurst();
        expect("a new burst forgets", cache.cachedRejection(SINK_A) == null);
        expect("and reports nothing held", cache.size() == 0);
    }

    /**
     * Each {@code ExternalTaskPattern} owns one, because the answer depends on that pattern's
     * inputs. One pattern's refusal must never answer for another's.
     */
    private static void cachesAreIndependent() {
        final SinkRejectionCache one = fresh();
        final SinkRejectionCache two = new SinkRejectionCache();
        one.record(SINK_A, REJECTED, false);
        expect("a sibling pattern's cache is unaffected", two.cachedRejection(SINK_A) == null);
    }

    /**
     * The scenario a stall would look like, and the one Astra named as decisive for the cursor
     * question: every sink refuses, the burst ends, and a sink that has since become willing must
     * be asked for real rather than answered from the previous burst.
     *
     * <p>This exercises the decision, not {@code getSinkThatIsAcceptingResources} itself -- a plain
     * JVM cannot run RS's private loop. What the loop contributes was established by reading it:
     * every refusal advances {@code currentSinkIndex}, an exhausted search leaves it at
     * {@code sinks.size()}, and the next search resets it to zero, so no sink can be permanently
     * skipped while stepping continues.
     */
    private static void anAllRefusedBurstDoesNotOutliveItself() {
        final SinkRejectionCache cache = fresh();
        cache.record(SINK_A, REJECTED, false);
        cache.record(SINK_B, REJECTED, false);
        expect("the whole sweep is remembered", cache.size() == 2);

        SinkRejectionCache.newBurst();
        expect("A is asked again next burst", cache.cachedRejection(SINK_A) == null);
        expect("B is asked again next burst", cache.cachedRejection(SINK_B) == null);
    }

    /** A cache in a burst of its own, so one test cannot leave state in the next. */
    private static SinkRejectionCache fresh() {
        SinkRejectionCache.newBurst();
        return new SinkRejectionCache();
    }

    private static void expect(final String what, final boolean condition) {
        ++checks;
        if (!condition) {
            FAILURES.add(what);
        }
    }
}
