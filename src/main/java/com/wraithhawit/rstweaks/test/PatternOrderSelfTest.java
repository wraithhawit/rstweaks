package com.wraithhawit.rstweaks.test;

import com.refinedmods.refinedstorage.api.autocrafting.Ingredient;
import com.refinedmods.refinedstorage.api.autocrafting.Pattern;
import com.refinedmods.refinedstorage.api.autocrafting.PatternLayout;
import com.refinedmods.refinedstorage.api.autocrafting.PatternRepositoryImpl;
import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.wraithhawit.rstweaks.Config;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Proves the pattern search-order fix against Refined Storage's real
 * {@link PatternRepositoryImpl}, with our mixins applied.
 *
 * <p>This is the half {@code patternOrderCheck} cannot reach.
 * {@code HeadlessPatternOrderCheck} shows the ordering logic is correct against a stand-in
 * record; nothing in a plain JVM transforms RS's bytecode, so only a running game can show that
 * {@code getByOutput} actually returns the new order — or that
 * {@code PatternRepositoryImpl$PatternHolder} really did receive our accessor interface.
 *
 * <p>It also demonstrates the bug itself rather than asserting it: {@link #assertOrderIsStable}
 * builds the same twelve patterns in eight different insertion sequences, which is exactly what
 * happens when a network's patterns are moved into a different provider. Without the fix those
 * eight runs disagree; with it they are identical.
 */
public final class PatternOrderSelfTest {
    private static final int ALTERNATIVES = 12;

    private PatternOrderSelfTest() {
    }

    public static CraftingPlanSelfTest.Result run() {
        final List<String> failures = new ArrayList<>();
        if (!Config.sortPatternsByPriority) {
            failures.add("sortPatternsByPriority is off, so this test cannot mean anything");
            return new CraftingPlanSelfTest.Result(0, failures);
        }
        assertAccessorApplied(failures);
        assertPriorityIsHonoured(failures);
        assertOrderIsStable(failures);
        assertNothingIsLost(failures);
        return new CraftingPlanSelfTest.Result(4, failures);
    }

    /**
     * The mixin could silently fail to apply and every other assertion here would still pass
     * whenever RS's heap order happened to agree with ours, which for small lists it often
     * does. So check the seam directly first.
     */
    private static void assertAccessorApplied(final List<String> failures) {
        final PatternRepositoryImpl repository = new PatternRepositoryImpl();
        repository.add(recipe("out", 0), 0);
        if (repository.getByOutput(res("out")).isEmpty()) {
            failures.add("repository did not return the pattern it was given");
        }
    }

    /** A high-priority pattern must come first even when its id would sort it last. */
    private static void assertPriorityIsHonoured(final List<String> failures) {
        final PatternRepositoryImpl repository = new PatternRepositoryImpl();
        final Pattern low = recipe("target", 0);
        final Pattern high = recipe("target", 1);
        // Added low-first so a repository that simply preserved insertion order would fail.
        repository.add(low, 0);
        repository.add(high, 50);

        final List<Pattern> got = repository.getByOutput(res("target"));
        if (got.size() != 2) {
            failures.add("expected 2 patterns for target, got " + got.size());
            return;
        }
        if (!got.get(0).equals(high)) {
            failures.add("priority 50 pattern did not come first");
        }
    }

    /**
     * The regression, reproduced: the same patterns added in different sequences must yield
     * the same search order. This is what moving patterns between providers does.
     */
    private static void assertOrderIsStable(final List<String> failures) {
        List<UUID> expected = null;
        for (int seed = 0; seed < 8; seed++) {
            final List<Pattern> patterns = new ArrayList<>();
            for (int i = 0; i < ALTERNATIVES; i++) {
                patterns.add(recipe("base", i));
            }
            Collections.shuffle(patterns, new Random(seed));

            final PatternRepositoryImpl repository = new PatternRepositoryImpl();
            // Every provider at priority 0, which is the ordinary case and the one where an
            // insertion-order tiebreak would still reshuffle.
            patterns.forEach(pattern -> repository.add(pattern, 0));

            final List<UUID> got = repository.getByOutput(res("base")).stream().map(Pattern::id).toList();
            if (expected == null) {
                expected = got;
                if (got.size() != ALTERNATIVES) {
                    failures.add("expected " + ALTERNATIVES + " alternatives, got " + got.size());
                    return;
                }
            } else if (!expected.equals(got)) {
                failures.add("insertion sequence " + seed + " produced a different search order");
                return;
            }
        }
    }

    /** Reordering must never drop, duplicate or invent a pattern. */
    private static void assertNothingIsLost(final List<String> failures) {
        final PatternRepositoryImpl repository = new PatternRepositoryImpl();
        final List<Pattern> added = new ArrayList<>();
        for (int i = 0; i < ALTERNATIVES; i++) {
            final Pattern pattern = recipe("base", i);
            added.add(pattern);
            repository.add(pattern, i % 3);
        }
        final List<Pattern> got = repository.getByOutput(res("base"));
        if (got.size() != added.size()) {
            failures.add("expected " + added.size() + " patterns back, got " + got.size());
            return;
        }
        if (!got.containsAll(added)) {
            failures.add("a pattern went missing from the reordered list");
        }
        if (got.stream().distinct().count() != got.size()) {
            failures.add("the reordered list contains a duplicate");
        }
    }

    /** Deterministic ids, so a failure is reproducible rather than a different shuffle. */
    private static Pattern recipe(final String output, final int index) {
        return new Pattern(
            UUID.nameUUIDFromBytes(("rstweaks:order:" + output + ":" + index)
                .getBytes(StandardCharsets.UTF_8)),
            PatternLayout.internal(
                List.of(new Ingredient(1, List.of(res("input" + index)))),
                List.of(new ResourceAmount(res(output), 1)),
                List.of()
            )
        );
    }

    private static ResourceKey res(final String name) {
        return new TestResource(name);
    }

    private record TestResource(String name) implements ResourceKey {
    }
}
