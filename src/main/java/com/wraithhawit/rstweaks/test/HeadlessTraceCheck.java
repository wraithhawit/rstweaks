package com.wraithhawit.rstweaks.test;

import com.wraithhawit.rstweaks.pattern.CalculationTrace;

import java.util.ArrayList;
import java.util.List;

/**
 * Self-tests for {@link CalculationTrace}. Run with {@code ./gradlew traceCheck}.
 *
 * <p>Exists mostly to pin the threshold behaviour. The whole reason the breakdown can be left on
 * in production is that it collects nothing until a calculation is already pathological, and
 * that is precisely the kind of guard that is easy to break later without noticing -- the
 * feature keeps working, it just quietly starts costing a map write per node on every craft.
 */
public final class HeadlessTraceCheck {
    /** One id for filler, so filler never looks like a duplicate-pattern resource. */
    private static final java.util.UUID FILLER_PATTERN = java.util.UUID.randomUUID();

    private static final List<String> FAILURES = new ArrayList<>();
    private static int checks;

    private HeadlessTraceCheck() {
    }

    public static void main(final String[] args) {
        cheapCalculationCollectsNothing();
        detailStartsAtTheThreshold();
        supplierIsNotCalledBelowTheThreshold();
        breakdownRanksByNodeCount();
        exhaustedCountsAreReported();
        beginResetsEverything();
        branchingFactorIsReported();
        percentagesAreRelativeToWhatWasObserved();

        System.out.printf("scenarios: %d%n", checks);
        if (FAILURES.isEmpty()) {
            System.out.println("PASS");
            return;
        }
        FAILURES.forEach(f -> System.out.println("FAIL  " + f));
        System.out.printf("%d of %d scenarios failed%n", FAILURES.size(), checks);
        System.exit(1);
    }

    private static void cheapCalculationCollectsNothing() {
        CalculationTrace.begin("minecraft:redstone", 64);
        for (int i = 0; i < 500; i++) {
            CalculationTrace.noteNode(() -> "minecraft:redstone", java.util.UUID.randomUUID());
        }
        expect("a cheap calculation counts nodes", CalculationTrace.nodes() == 500);
        expect("but collects no breakdown", !CalculationTrace.isDetailed());
        final List<String> lines = CalculationTrace.describe(3, true);
        expect("and says so rather than printing an empty table",
            lines.size() == 2 && lines.get(1).contains("no breakdown"));
    }

    private static void detailStartsAtTheThreshold() {
        CalculationTrace.begin("minecraft:redstone", 1);
        for (long i = 0; i < CalculationTrace.detailThreshold - 1; i++) {
            CalculationTrace.noteNode(() -> "a", java.util.UUID.randomUUID());
        }
        expect("still off one node short", !CalculationTrace.isDetailed());
        CalculationTrace.noteNode(() -> "a", java.util.UUID.randomUUID());
        expect("on at the threshold", CalculationTrace.isDetailed());
    }

    /**
     * The supplier exists so a resource name is never formatted on a cheap calculation. If this
     * regresses, every craft pays an ItemResource toString per tree node.
     */
    private static void supplierIsNotCalledBelowTheThreshold() {
        CalculationTrace.begin("minecraft:redstone", 1);
        final long[] calls = {0};
        for (int i = 0; i < 1000; i++) {
            CalculationTrace.noteNode(() -> {
                calls[0]++;
                return "a";
            }, java.util.UUID.randomUUID());
        }
        expect("the name supplier is never invoked below the threshold", calls[0] == 0);
    }

    private static void breakdownRanksByNodeCount() {
        CalculationTrace.begin("target", 1);
        pushPast();
        for (int i = 0; i < 50_000; i++) {
            CalculationTrace.noteNode(() -> "alltheores:tin_ingot", java.util.UUID.randomUUID());
        }
        for (int i = 0; i < 10; i++) {
            CalculationTrace.noteNode(() -> "minecraft:stick", java.util.UUID.randomUUID());
        }
        final List<String> lines = CalculationTrace.describe(5000, false);
        expect("the header names the root resource", lines.get(0).contains("target"));
        expect("the header reports the node count", lines.get(0).contains("tree nodes"));
        expect("the biggest consumer is listed first", lines.get(1).contains("tin_ingot"));
    }

    private static void exhaustedCountsAreReported() {
        CalculationTrace.begin("target", 1);
        pushPast();
        for (int i = 0; i < 20_000; i++) {
            CalculationTrace.noteNode(() -> "alltheores:raw_tin", java.util.UUID.randomUUID());
        }
        for (int i = 0; i < 7; i++) {
            CalculationTrace.noteExhausted(() -> "alltheores:raw_tin");
        }
        final boolean found = CalculationTrace.describe(5000, false).stream()
            .anyMatch(line -> line.contains("raw_tin") && line.contains("ran out 7 times"));
        expect("a resource that ran out says how often", found);
    }

    private static void beginResetsEverything() {
        CalculationTrace.begin("first", 1);
        pushPast();
        expect("detail is on after a big calculation", CalculationTrace.isDetailed());
        CalculationTrace.begin("second", 2);
        expect("begin clears the node count", CalculationTrace.nodes() == 0);
        expect("begin clears the detail flag", !CalculationTrace.isDetailed());
        expect("begin clears the breakdown",
            CalculationTrace.describe(1, true).get(1).contains("no breakdown"));
    }


    /**
     * The flaw the first real traces exposed. Counts only cover nodes after the threshold, but
     * percentages were computed against the TOTAL node count -- so a calculation that just
     * crossed the line reported its dominant resource as "4%" when it was 99% of everything
     * actually seen. The header now says how much the breakdown covers.
     */
    private static void percentagesAreRelativeToWhatWasObserved() {
        CalculationTrace.begin("target", 1);
        pushPast();
        for (int i = 0; i < 1000; i++) {
            CalculationTrace.noteNode(() -> "dominant", java.util.UUID.randomUUID());
        }
        final long observed = CalculationTrace.observed();
        expect("observed is the post-threshold window, not the total",
            observed < CalculationTrace.nodes());
        final List<String> lines = CalculationTrace.describe(500, false);
        expect("the header says the breakdown is partial",
            lines.get(0).contains("breakdown covers the last"));
        final String dominant = lines.stream()
            .filter(line -> line.contains("dominant")).findFirst().orElse("");
        // 1000 of ~1001 observed is 100%, not 1000 of 21001 which would round to 5%.
        expect("the percentage is against observed nodes", dominant.contains("(100%)"));
    }


    /**
     * The measurement that decides whether duplicate patterns are worth deduplicating: a
     * resource reached through many distinct patterns is paying for a failing subtree per
     * pattern, because Pattern identity is a per-item UUID and two copies of one recipe are two
     * alternatives to the calculator.
     */
    private static void branchingFactorIsReported() {
        CalculationTrace.begin("target", 1);
        pushPast();
        final java.util.UUID one = java.util.UUID.randomUUID();
        for (int i = 0; i < 500; i++) {
            // One resource reached through a single pattern: nothing to report.
            CalculationTrace.noteNode(() -> "single", one);
        }
        for (int i = 0; i < 5000; i++) {
            // The duplicate-pattern shape: same resource, many distinct pattern ids.
            CalculationTrace.noteNode(() -> "many", java.util.UUID.randomUUID());
        }
        final List<String> lines = CalculationTrace.describe(900, false);
        final String many = lines.stream()
            .filter(line -> line.contains("making many")).findFirst().orElse("");
        final String single = lines.stream()
            .filter(line -> line.contains("making single")).findFirst().orElse("");
        expect("a resource with many patterns says so", many.contains("patterns"));
        expect("and reports the cap when it is hit", many.contains(">="));
        expect("a resource with one pattern stays quiet", !single.contains("patterns"));
    }

    /** Drives the trace past the detail threshold with filler nodes. */
    private static void pushPast() {
        for (long i = 0; i <= CalculationTrace.detailThreshold; i++) {
            CalculationTrace.noteNode(() -> "filler", FILLER_PATTERN);
        }
    }

    private static void expect(final String what, final boolean condition) {
        ++checks;
        if (!condition) {
            FAILURES.add(what);
        }
    }
}
