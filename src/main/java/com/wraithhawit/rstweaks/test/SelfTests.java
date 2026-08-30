package com.wraithhawit.rstweaks.test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The self-test catalogue: what {@code /rstweaks selftest} runs, and how to ask for less of it.
 *
 * <p>Bare {@code /rstweaks selftest} runs <b>everything</b>. A name after it narrows to one
 * category — {@code /rstweaks selftest crafting} — for when you already know which half of the mod
 * you are suspicious of and do not want to wait for the rest.
 *
 * <p>The categories are deliberately <b>disjoint</b>, so the full run does not execute the same
 * scenario twice and a total is a total. {@code crafting} is the one exception and is documented as
 * a group rather than a category: it is what somebody means when they say "is autocrafting safe",
 * and that spans the planner, the executor, durability and the optimization differentials.
 */
public final class SelfTests {
    /** Every category, in the order a full run executes them. */
    private static final Map<String, Supplier<CraftingPlanSelfTest.Result>> CATEGORIES =
        new LinkedHashMap<>();

    /** Named groups of categories. Not categories themselves, so a full run cannot double-count. */
    private static final Map<String, List<String>> GROUPS = new LinkedHashMap<>();

    static {
        CATEGORIES.put("planner", CraftingPlanSelfTest::run);
        CATEGORIES.put("lp", PlannerExecutabilitySelfTest::run);
        CATEGORIES.put("maxcraftable", MaxCraftableSelfTest::run);
        CATEGORIES.put("ledger", LedgerParitySelfTest::run);
        CATEGORIES.put("patterns", PatternOrderSelfTest::run);
        CATEGORIES.put("executor", TaskEngineSelfTest::run);
        CATEGORIES.put("remainders", RemainderSelfTest::run);
        CATEGORIES.put("requests", AutocraftingRequestSelfTest::run);
        CATEGORIES.put("overflow", ResourceListOverflowSelfTest::run);
        CATEGORIES.put("durability", DurabilitySelfTest::run);
        CATEGORIES.put("storage", SelfTests::extraction);
        // The differentials: every optimization run off against on, each asserting its counter
        // moved. Slowest category by a distance -- it runs the task engine ten times over -- which
        // is exactly why being able to ask for it alone, or to skip it, is worth the plumbing.
        CATEGORIES.put("optimizations", OptimizationDifferentialSelfTest::run);

        GROUPS.put("crafting", List.of("planner", "lp", "maxcraftable", "ledger", "patterns",
            "executor", "remainders", "requests", "overflow", "durability", "optimizations"));
    }

    private SelfTests() {
    }

    /** Category and group names, for tab completion and for the "unknown name" message. */
    public static List<String> names() {
        final List<String> names = new ArrayList<>(GROUPS.keySet());
        names.addAll(CATEGORIES.keySet());
        return names;
    }

    /** Runs one category or group. Returns null when the name is not one. */
    public static Report run(final String name) {
        if (GROUPS.containsKey(name)) {
            return runEach(GROUPS.get(name));
        }
        if (CATEGORIES.containsKey(name)) {
            return runEach(List.of(name));
        }
        return null;
    }

    /** Runs every category exactly once. */
    public static Report runAll() {
        return runEach(List.copyOf(CATEGORIES.keySet()));
    }

    private static Report runEach(final List<String> categories) {
        final List<String> failures = new ArrayList<>();
        final Map<String, Integer> counts = new LinkedHashMap<>();
        int total = 0;
        for (final String category : categories) {
            final CraftingPlanSelfTest.Result result = CATEGORIES.get(category).get();
            counts.put(category, result.scenarios());
            total += result.scenarios();
            result.failures().forEach(failure -> failures.add(category + ": " + failure));
        }
        return new Report(total, List.copyOf(failures), Map.copyOf(counts));
    }

    /** {@link ExtractionSelfTest} has its own result type; this is the only reason for the shim. */
    private static CraftingPlanSelfTest.Result extraction() {
        final ExtractionSelfTest.Result result = ExtractionSelfTest.run();
        return new CraftingPlanSelfTest.Result(result.scenarios(), result.failures());
    }

    /**
     * What a run produced.
     *
     * @param scenarios total checks across every category that ran
     * @param failures  every failure, prefixed with the category it came from
     * @param perCategory how many checks each category contributed, in run order
     */
    public record Report(int scenarios, List<String> failures, Map<String, Integer> perCategory) {
        public boolean passed() {
            return failures.isEmpty();
        }
    }
}
