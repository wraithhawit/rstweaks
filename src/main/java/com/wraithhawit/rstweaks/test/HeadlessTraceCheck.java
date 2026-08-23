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
            CalculationTrace.noteNode(() -> "minecraft:redstone");
        }
        expect("a cheap calculation counts nodes", CalculationTrace.nodes() == 500);
        expect("but collects no breakdown", !CalculationTrace.isDetailed());
        final List<String> lines = CalculationTrace.describe(3, true);
        expect("and says so rather than printing an empty table",
            lines.size() == 2 && lines.get(1).contains("no breakdown"));
    }

    private static void detailStartsAtTheThreshold() {
        CalculationTrace.begin("minecraft:redstone", 1);
        for (long i = 0; i < CalculationTrace.DETAIL_THRESHOLD - 1; i++) {
            CalculationTrace.noteNode(() -> "a");
        }
        expect("still off one node short", !CalculationTrace.isDetailed());
        CalculationTrace.noteNode(() -> "a");
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
            });
        }
        expect("the name supplier is never invoked below the threshold", calls[0] == 0);
    }

    private static void breakdownRanksByNodeCount() {
        CalculationTrace.begin("target", 1);
        pushPast();
        for (int i = 0; i < 50_000; i++) {
            CalculationTrace.noteNode(() -> "alltheores:tin_ingot");
        }
        for (int i = 0; i < 10; i++) {
            CalculationTrace.noteNode(() -> "minecraft:stick");
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
            CalculationTrace.noteNode(() -> "alltheores:raw_tin");
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

    /** Drives the trace past the detail threshold with filler nodes. */
    private static void pushPast() {
        for (long i = 0; i <= CalculationTrace.DETAIL_THRESHOLD; i++) {
            CalculationTrace.noteNode(() -> "filler");
        }
    }

    private static void expect(final String what, final boolean condition) {
        ++checks;
        if (!condition) {
            FAILURES.add(what);
        }
    }
}
