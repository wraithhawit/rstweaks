package com.wraithhawit.rstweaks.test;

import com.wraithhawit.rstweaks.pattern.PatternOrdering;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * Self-tests for {@link PatternOrdering}. Run with {@code ./gradlew patternOrderCheck}.
 *
 * <p>Deliberately includes a reproduction of the underlying Java behaviour itself — that
 * {@code PriorityQueue.stream()} does not yield priority order — because the entire fix rests
 * on that claim, and a claim about the JDK is exactly the sort of thing that should be executed
 * rather than believed. {@link #priorityQueueStreamIsNotSorted} fails loudly if a future JDK
 * ever starts ordering its iterator, which would make this mixin unnecessary.
 *
 * <p>As with {@code HeadlessBackoffCheck}, this cannot prove the mixin applies or that RS calls
 * it — only that the ordering it installs is the intended one. The in-game proof is the
 * "pattern lists reordered" counter.
 */
public final class HeadlessPatternOrderCheck {
    private static final List<String> FAILURES = new ArrayList<>();
    private static int checks;

    /** Stand-in for RS's private PatternHolder record. */
    private record Holder(String name, int priority, UUID id) {
    }

    private static final ToIntFunction<Holder> PRIORITY = Holder::priority;
    private static final Function<Holder, UUID> ID = Holder::id;

    private HeadlessPatternOrderCheck() {
    }

    public static void main(final String[] args) {
        final CraftingPlanSelfTest.Result result = run();
        System.out.printf("scenarios: %d%n", result.scenarios());
        if (result.failures().isEmpty()) {
            System.out.println("PASS");
            return;
        }
        result.failures().forEach(f -> System.out.println("FAIL  " + f));
        System.out.printf("%d of %d scenarios failed%n", result.failures().size(), result.scenarios());
        System.exit(1);
    }

    /**
     * The same scenarios, returning their failures instead of printing them and calling
     * {@code System.exit}. That exit is why PIT could not drive this suite: it kills the minion
     * JVM. Shaped like {@code PatternOrderSelfTest.run()}, which already did it this way.
     *
     * <p>Resets the static counters first. A mutation run calls this thousands of times in one
     * JVM, and without the reset every call would inherit the last one's failures.
     */
    public static CraftingPlanSelfTest.Result run() {
        FAILURES.clear();
        checks = 0;
        priorityQueueStreamIsNotSorted();

        higherPriorityComesFirst();
        equalPrioritiesFallBackToId();
        orderIsIndependentOfInsertionSequence();
        orderIsIndependentOfHeapLayout();
        emptyAndSingletonPassThrough();
        pairIsOrderedWithoutCopying();
        alreadySortedPairIsReturnedUnchanged();
        inputIsNeverMutated();
        negativePrioritiesOrderCorrectly();
        nullIdSortsLast();
        orderIsTotalAndRepeatable();

        return new CraftingPlanSelfTest.Result(checks, List.copyOf(FAILURES));
    }

    /**
     * The premise of the whole fix, executed rather than asserted.
     *
     * <p>Builds the same structure RS builds — a PriorityQueue ordered by priority descending —
     * and shows that streaming it does not yield that order. If this ever starts passing in
     * order, the mixin has become a no-op and should be deleted.
     */
    private static void priorityQueueStreamIsNotSorted() {
        final PriorityQueue<Holder> queue =
            new PriorityQueue<>(Comparator.comparingInt(Holder::priority).reversed());
        // Insertion sequence chosen so the heap array ends up out of order. Sifting only
        // guarantees each parent beats its children, never that siblings are ordered.
        for (final int priority : new int[] {0, 0, 0, 0, 5, 0, 9}) {
            queue.add(new Holder("p" + priority, priority, UUID.randomUUID()));
        }
        final List<Holder> streamed = queue.stream().toList();

        expect("the queue's head IS the highest priority", streamed.get(0).priority() == 9);

        boolean descending = true;
        for (int i = 1; i < streamed.size(); i++) {
            if (streamed.get(i - 1).priority() < streamed.get(i).priority()) {
                descending = false;
                break;
            }
        }
        expect("but PriorityQueue.stream() is NOT in priority order -- the premise of this fix",
            !descending);

        // And the fix does put it in order.
        final List<Holder> fixed = PatternOrdering.sorted(streamed, PRIORITY, ID);
        expect("sorted() yields descending priority", isDescending(fixed));
    }

    // ---- the ordering itself ---------------------------------------------------------

    private static void higherPriorityComesFirst() {
        final UUID low = UUID.fromString("00000000-0000-0000-0000-000000000001");
        final UUID high = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
        // The high-priority holder deliberately has the id that would sort LAST, so this
        // cannot pass by accident if priority were ignored.
        final List<Holder> input = List.of(
            new Holder("low", 0, low),
            new Holder("high", 10, high));
        final List<Holder> sorted = PatternOrdering.sorted(input, PRIORITY, ID);
        expect("priority beats id", sorted.get(0).name().equals("high"));
    }

    private static void equalPrioritiesFallBackToId() {
        final UUID first = UUID.fromString("00000000-0000-0000-0000-00000000000a");
        final UUID second = UUID.fromString("00000000-0000-0000-0000-00000000000b");
        final List<Holder> input = List.of(
            new Holder("b", 0, second),
            new Holder("a", 0, first),
            new Holder("c", 0, UUID.fromString("00000000-0000-0000-0000-00000000000c")));
        final List<Holder> sorted = PatternOrdering.sorted(input, PRIORITY, ID);
        expect("equal priorities order by id",
            sorted.get(0).name().equals("a")
                && sorted.get(1).name().equals("b")
                && sorted.get(2).name().equals("c"));
    }

    /**
     * The regression that started all of this: patterns moved into a different provider are
     * re-added in a different sequence. With an insertion-order tiebreak the search order would
     * change; with a UUID tiebreak it must not.
     */
    private static void orderIsIndependentOfInsertionSequence() {
        final List<Holder> holders = sampleHolders();

        final List<Holder> asBuilt = PatternOrdering.sorted(holders, PRIORITY, ID);

        final List<Holder> reversed = new ArrayList<>(holders);
        java.util.Collections.reverse(reversed);
        final List<Holder> asReinserted = PatternOrdering.sorted(reversed, PRIORITY, ID);

        expect("reversing the input does not change the search order",
            names(asBuilt).equals(names(asReinserted)));

        final List<Holder> shuffled = new ArrayList<>(holders);
        java.util.Collections.shuffle(shuffled, new java.util.Random(20260823L));
        expect("nor does shuffling it",
            names(asBuilt).equals(names(PatternOrdering.sorted(shuffled, PRIORITY, ID))));
    }

    /** The same claim, but going through a real PriorityQueue as RS does. */
    private static void orderIsIndependentOfHeapLayout() {
        final List<Holder> holders = sampleHolders();
        final List<String> expected = names(PatternOrdering.sorted(holders, PRIORITY, ID));

        for (int seed = 0; seed < 12; seed++) {
            final List<Holder> insertion = new ArrayList<>(holders);
            java.util.Collections.shuffle(insertion, new java.util.Random(seed));
            final PriorityQueue<Holder> queue =
                new PriorityQueue<>(Comparator.comparingInt(Holder::priority).reversed());
            queue.addAll(insertion);
            final List<String> got = names(PatternOrdering.sorted(queue.stream().toList(), PRIORITY, ID));
            expect("heap layout " + seed + " yields the same order", expected.equals(got));
        }
    }

    // ---- the fast paths, which must not change behaviour ------------------------------

    private static void emptyAndSingletonPassThrough() {
        final List<Holder> empty = List.of();
        expect("empty is returned as-is", PatternOrdering.sorted(empty, PRIORITY, ID) == empty);
        final List<Holder> one = List.of(new Holder("only", 0, UUID.randomUUID()));
        expect("singleton is returned as-is", PatternOrdering.sorted(one, PRIORITY, ID) == one);
    }

    private static void pairIsOrderedWithoutCopying() {
        final Holder low = new Holder("low", 0, UUID.randomUUID());
        final Holder high = new Holder("high", 7, UUID.randomUUID());
        final List<Holder> sorted = PatternOrdering.sorted(List.of(low, high), PRIORITY, ID);
        expect("a two-element list is reordered", sorted.get(0).name().equals("high"));
        expect("and stays two elements", sorted.size() == 2);
    }

    private static void alreadySortedPairIsReturnedUnchanged() {
        final Holder high = new Holder("high", 7, UUID.randomUUID());
        final Holder low = new Holder("low", 0, UUID.randomUUID());
        final List<Holder> input = List.of(high, low);
        // Identity, not just equality: an already-ordered list must cost no allocation, since
        // this runs for every ingredient of every crafting-tree node.
        expect("an already-ordered pair is returned as-is",
            PatternOrdering.sorted(input, PRIORITY, ID) == input);
    }

    private static void inputIsNeverMutated() {
        final List<Holder> holders = new ArrayList<>(sampleHolders());
        final List<String> before = names(holders);
        PatternOrdering.sorted(holders, PRIORITY, ID);
        expect("the caller's list is not reordered in place", names(holders).equals(before));
    }

    // ---- edges ------------------------------------------------------------------------

    private static void negativePrioritiesOrderCorrectly() {
        final List<Holder> input = List.of(
            new Holder("neg", -5, UUID.randomUUID()),
            new Holder("zero", 0, UUID.randomUUID()),
            new Holder("pos", 5, UUID.randomUUID()));
        final List<Holder> sorted = PatternOrdering.sorted(input, PRIORITY, ID);
        expect("negative priorities sort below zero",
            names(sorted).equals(List.of("pos", "zero", "neg")));
    }

    private static void nullIdSortsLast() {
        final List<Holder> input = List.of(
            new Holder("null", 0, null),
            new Holder("real", 0, UUID.fromString("00000000-0000-0000-0000-000000000001")));
        final List<Holder> sorted = PatternOrdering.sorted(input, PRIORITY, ID);
        expect("a null id does not throw and sorts last",
            sorted.get(0).name().equals("real"));
    }

    private static void orderIsTotalAndRepeatable() {
        final List<Holder> holders = sampleHolders();
        final List<String> once = names(PatternOrdering.sorted(holders, PRIORITY, ID));
        final List<String> twice = names(PatternOrdering.sorted(holders, PRIORITY, ID));
        expect("sorting twice gives the same answer", once.equals(twice));
        expect("nothing is lost or duplicated", once.size() == holders.size());
    }

    // ---- harness ---------------------------------------------------------------------

    /** A dozen alternatives for one output, which is the shape a base material has. */
    private static List<Holder> sampleHolders() {
        final List<Holder> holders = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            holders.add(new Holder(
                "pattern" + i,
                i % 4 == 0 ? 1 : 0,
                UUID.fromString(String.format("00000000-0000-0000-0000-%012d", i))));
        }
        return List.copyOf(holders);
    }

    private static List<String> names(final List<Holder> holders) {
        return holders.stream().map(Holder::name).toList();
    }

    private static boolean isDescending(final List<Holder> holders) {
        for (int i = 1; i < holders.size(); i++) {
            if (holders.get(i - 1).priority() < holders.get(i).priority()) {
                return false;
            }
        }
        return true;
    }

    private static void expect(final String what, final boolean condition) {
        ++checks;
        if (!condition) {
            FAILURES.add(what);
        }
    }
}
