package com.wraithhawit.rstweaks.test;

import com.refinedmods.refinedstorage.api.autocrafting.Ingredient;
import com.refinedmods.refinedstorage.api.autocrafting.Pattern;
import com.refinedmods.refinedstorage.api.autocrafting.PatternLayout;
import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.wraithhawit.rstweaks.ledger.Ledger;
import com.wraithhawit.rstweaks.ledger.Pools;
import com.wraithhawit.rstweaks.ledger.Quantity;
import com.wraithhawit.rstweaks.ledger.ResourceIndex;
import com.wraithhawit.rstweaks.ledger.Slot;
import com.wraithhawit.rstweaks.ledger.Transform;
import com.wraithhawit.rstweaks.ledger.rs.PatternTransforms;
import com.wraithhawit.rstweaks.ledger.rs.Remainder;
import com.wraithhawit.rstweaks.ledger.rs.ToolPools;
import com.wraithhawit.rstweaks.planner.Durability;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Self-tests for the ledger model. Run with {@code ./gradlew ledgerCheck}.
 *
 * <p>Every scenario below is one row of the table that motivated the model, and every one of them
 * is a real failure with a log line or a profile behind it: the catalyst that asked for 712
 * million, the crystal that did 64 jobs forever, the thousand cakes that planned three thousand
 * buckets, the honey bucket that went missing. They are tested as one property —
 * {@code initial + produced - consumed == final} — because that is what they all are.
 *
 * <p><b>What this suite cannot show.</b> Nothing here is wired into the planner or the task engine
 * yet, so a green run says the arithmetic is sound and says nothing about Refined Storage. The
 * inference in {@link PatternTransforms} is tested against hand-built patterns and fake adapters;
 * against the real game it is only as good as {@link Durability} and {@link Remainder}, and
 * {@code Remainder} has no game-side implementation at all yet.
 */
public final class HeadlessLedgerCheck {
    private static final List<String> FAILURES = new ArrayList<>();
    private static int checks;

    private HeadlessLedgerCheck() {
    }

    public static void main(final String[] args) {
        final CraftingPlanSelfTest.Result result = run();
        System.out.println("ledger checks: " + result.scenarios());
        if (result.passed()) {
            System.out.println("PASS");
            return;
        }
        result.failures().forEach(failure -> System.out.println("  FAIL " + failure));
        System.out.println(result.failures().size() + " of " + result.scenarios() + " checks failed");
        System.exit(1);
    }

    /**
     * The scenarios, returning their failures rather than printing them and calling
     * {@code System.exit} — that exit is what stops PIT driving a suite, and it kills the minion
     * JVM. Shaped like {@code HeadlessTraceCheck.run()}, which already did it this way.
     */
    public static CraftingPlanSelfTest.Result run() {
        FAILURES.clear();
        checks = 0;

        catalystCostsNothing();
        wornToolIsCountedInUses();
        theWearStepIsReadNotAssumed();
        aDestroyedToolLosesEveryRemainingUse();
        containerCycleConservesItsBuckets();
        fluidContainerKeepsTheShell();
        selfDuplicationIsProduction();
        batchAndSerialLeaveIdenticalBooks();
        booksThatDifferDoNotMatch();
        zeroIterationsAreANoOp();
        aFullyWornToolContributesNothing();
        violationsNameTheirDirection();
        identityKeysAreRefused();
        conservationHoldsOverRandomPlans();

        inferenceFindsTheCatalyst();
        inferenceFindsWear();
        inferenceFindsTheContainer();
        anUnexplainedByproductStaysProduction();
        aToolHandedBackLessWornIsRefusedAndNoted();
        equalWearIsNotWear();
        aRemainderNotHandedBackIsNotAFate();
        aPartialReturnSplitsTheSlot();
        aFuzzySlotThatDisagreesIsNoted();
        aDestructiveRecipeRefusesThePool();
        aFamilyHandedBackNoMoreWornIsNotAPool();
        aFamilyNothingWearsIsNotAPool();

        return new CraftingPlanSelfTest.Result(checks, List.copyOf(FAILURES));
    }

    // ------------------------------------------------------------------ the algebra

    /**
     * The 712-million case. A Master Infusion Crystal goes into the recipe and comes back
     * identical, so it is a slot whose fate is itself — and it disappears from the arithmetic
     * without one line of code knowing what a catalyst is.
     */
    private static void catalystCostsNothing() {
        final ResourceIndex index = new ResourceIndex();
        final int crystal = index.idOf("master_crystal");
        final int inferium = index.idOf("inferium");
        final int essence = index.idOf("essence");
        final Transform infuse = Transform.of("essence",
            List.of(Slot.catalyst(crystal, 1), Slot.consumed(inferium, 4)),
            List.of(new Quantity(essence, 1)));

        expect("a catalyst nets to zero and leaves the matrix",
            !infuse.net().containsKey(crystal));

        final Ledger ledger = Ledger.fromStock(Pools.NONE, Map.of(crystal, 1L, inferium, 4000L));
        ledger.apply(infuse, 1000L);
        expect("one crystal covers a thousand crafts", ledger.balance(crystal) == 1L);
        expect("and the inferium is what was actually spent", ledger.balance(inferium) == 0L);
        expect("a thousand essence came out", ledger.balance(essence) == 1000L);
        expect("nothing was duplicated or destroyed", ledger.reconcile(
            Map.of(crystal, 1L, inferium, 0L, essence, 1000L)).isEmpty());
    }

    /** A 1000-use crystal is one pool of a thousand crafts, not a thousand items. */
    private static void wornToolIsCountedInUses() {
        final ResourceIndex index = new ResourceIndex();
        final int fresh = index.idOf("crystal@0");
        final int used = index.idOf("crystal@1");
        final Pools pools = wear(index, "crystal", 1000, fresh);

        final Transform craft = Transform.of("thing",
            List.of(Slot.transforming(fresh, 1, used)),
            List.of(new Quantity(index.idOf("thing"), 1)));

        expect("one craft costs exactly one use", craft.net(pools).get(fresh) == -1L);

        final Ledger ledger = Ledger.fromStock(pools, Map.of(fresh, 1L));
        expect("a fresh crystal opens at a thousand crafts", ledger.balance(fresh) == 1000L);
        ledger.apply(craft, 1000L);
        expect("a thousand crafts use it up exactly", ledger.balance(fresh) == 0L);
        expect("and it is not immortal", !ledger.deficits().containsKey(fresh));

        final Ledger overdrawn = Ledger.fromStock(pools, Map.of(fresh, 1L));
        overdrawn.apply(craft, 1001L);
        expect("one craft too many is a deficit of one use",
            overdrawn.deficits().get(fresh) == 1L);
    }

    /**
     * A recipe that burns five points a craft. Assuming one would make the tool last five times
     * too long — a duplication bug wearing the costume of a working feature — and nothing in the
     * ledger assumes: the pattern says so in the gap between the tool it takes and the one it
     * hands back.
     */
    private static void theWearStepIsReadNotAssumed() {
        final ResourceIndex index = new ResourceIndex();
        final int fresh = index.idOf("crystal@0");
        final int worn = index.idOf("crystal@5");
        final Pools pools = wear(index, "crystal", 1000, fresh);

        final Transform craft = Transform.of("heavy",
            List.of(Slot.transforming(fresh, 1, worn)),
            List.of(new Quantity(index.idOf("heavy"), 1)));

        expect("five points read off the pattern", craft.net(pools).get(fresh) == -5L);

        final Ledger ledger = Ledger.fromStock(pools, Map.of(fresh, 1L));
        ledger.apply(craft, 200L);
        expect("so the crystal covers two hundred crafts, not a thousand",
            ledger.balance(fresh) == 0L);
    }

    /**
     * The other half of that rule. A recipe that eats the tool outright costs every use it had
     * left, which is why a pool is only sound when every consumer gives the tool back — see
     * {@link ToolPools}.
     */
    private static void aDestroyedToolLosesEveryRemainingUse() {
        final ResourceIndex index = new ResourceIndex();
        final int fresh = index.idOf("crystal@0");
        index.idOf("crystal@1");
        final Pools pools = wear(index, "crystal", 1000, fresh);

        final Transform sacrifice = Transform.of("altar",
            List.of(Slot.consumed(fresh, 1)),
            List.of(new Quantity(index.idOf("altar"), 1)));

        expect("destroying a fresh tool costs its whole thousand",
            sacrifice.net(pools).get(fresh) == -1000L);
    }

    /**
     * A thousand cakes plans three thousand buckets today. Modelled as a fate, the bucket goes
     * round and round: the milk pattern consumes it, the cake pattern hands it straight back.
     */
    private static void containerCycleConservesItsBuckets() {
        final ResourceIndex index = new ResourceIndex();
        final int bucket = index.idOf("bucket");
        final int milk = index.idOf("milk_bucket");
        final int wheat = index.idOf("wheat");
        final int cake = index.idOf("cake");

        final Transform fill = Transform.of("milk_bucket",
            List.of(Slot.consumed(bucket, 1)),
            List.of(new Quantity(milk, 1)));
        final Transform bake = Transform.of("cake",
            List.of(Slot.transforming(milk, 3, bucket), Slot.consumed(wheat, 3)),
            List.of(new Quantity(cake, 1)));

        expect("baking a cake gives the buckets back", bake.net().get(bucket) == 3L);

        final Ledger ledger = Ledger.fromStock(Pools.NONE, Map.of(bucket, 1L, wheat, 3000L));
        ledger.apply(fill, 3000L);
        ledger.apply(bake, 1000L);
        expect("a thousand cakes still needs exactly one bucket", ledger.balance(bucket) == 1L);
        expect("and the books balance", ledger.reconcile(
            Map.of(bucket, 1L, wheat, 0L, milk, 0L, cake, 1000L)).isEmpty());
    }

    /** Honey out of the bucket is production; the bucket itself is a fate. */
    private static void fluidContainerKeepsTheShell() {
        final ResourceIndex index = new ResourceIndex();
        final int honeyBucket = index.idOf("honey_bucket");
        final int bucket = index.idOf("bucket");
        final int honey = index.idOf("honey");

        final Transform drain = Transform.of("honey",
            List.of(Slot.transforming(honeyBucket, 1, bucket)),
            List.of(new Quantity(honey, 1000)));

        final Map<Integer, Long> net = drain.net();
        expect("the honey bucket is spent", net.get(honeyBucket) == -1L);
        expect("the bucket comes back", net.get(bucket) == 1L);
        expect("and the honey is real production", net.get(honey) == 1000L);
    }

    /** One template makes two: the second is production, not a fate. */
    private static void selfDuplicationIsProduction() {
        final ResourceIndex index = new ResourceIndex();
        final int template = index.idOf("smithing_template");
        final Transform duplicate = Transform.of("smithing_template",
            List.of(Slot.consumed(template, 1), Slot.consumed(index.idOf("diamond"), 7)),
            List.of(new Quantity(template, 2)));

        expect("duplicating a template nets one", duplicate.net().get(template) == 1L);
    }

    /**
     * The equivalence that stands between an optimisation and a dupe bug. Phase 05 is the whole
     * reason the model exists — one extraction and one insertion for N iterations — so a batch
     * must leave the books identical to the same N run one at a time.
     */
    private static void batchAndSerialLeaveIdenticalBooks() {
        final ResourceIndex index = new ResourceIndex();
        final int fresh = index.idOf("crystal@0");
        final int worn = index.idOf("crystal@1");
        final Pools pools = wear(index, "crystal", 1000, fresh);
        final Transform craft = Transform.of("thing",
            List.of(Slot.transforming(fresh, 1, worn), Slot.consumed(index.idOf("inferium"), 4),
                Slot.catalyst(index.idOf("altar"), 1)),
            List.of(new Quantity(index.idOf("thing"), 1)));

        final Ledger batched = Ledger.fromStock(pools, Map.of(fresh, 5L));
        batched.apply(craft, 500L);
        final Ledger serial = Ledger.fromStock(pools, Map.of(fresh, 5L));
        for (int i = 0; i < 500; i++) {
            serial.apply(craft, 1L);
        }
        expect("a batch of five hundred is five hundred singles", batched.totalsMatch(serial));
        expect("down to the balances", batched.balances().equals(serial.balances()));
    }

    /**
     * The other half of that equivalence, which is the half worth having: a comparison that can
     * only say yes proves nothing about the batch it was written to guard.
     */
    private static void booksThatDifferDoNotMatch() {
        final ResourceIndex index = new ResourceIndex();
        final int stone = index.idOf("stone");
        final Transform once = Transform.of("stone", List.of(Slot.consumed(index.idOf("cobble"), 1)),
            List.of(new Quantity(stone, 1)));

        final Ledger five = Ledger.empty(Pools.NONE);
        five.apply(once, 5L);
        final Ledger four = Ledger.empty(Pools.NONE);
        four.apply(once, 4L);
        expect("five crafts are not four", !five.totalsMatch(four));

        final Ledger alsoFive = Ledger.empty(Pools.NONE);
        alsoFive.apply(once, 5L);
        expect("but five crafts are five crafts", five.totalsMatch(alsoFive));
    }

    /**
     * A plan may legitimately say "run this one zero times" — a solver leaves those in rather than
     * compacting the vector — and that has to be nothing at all, not an error and not one run.
     */
    private static void zeroIterationsAreANoOp() {
        final ResourceIndex index = new ResourceIndex();
        final Transform craft = Transform.of("thing",
            List.of(Slot.consumed(index.idOf("stone"), 1)),
            List.of(new Quantity(index.idOf("thing"), 1)));

        final Ledger ledger = Ledger.fromStock(Pools.NONE, Map.of(index.idOf("stone"), 4L));
        ledger.apply(craft, 0L);
        expect("zero iterations touch nothing", ledger.consumedTotals().isEmpty()
            && ledger.producedTotals().isEmpty());
        expect("and leave the stock alone", ledger.balance(index.idOf("stone")) == 4L);

        ledger.apply(craft, 3L);
        expect("whereas three iterations do write to both sides of the books",
            ledger.consumedTotals().get(index.idOf("stone")) == 3L
                && ledger.producedTotals().get(index.idOf("thing")) == 3L);

        boolean refused = false;
        try {
            ledger.apply(craft, -1L);
        } catch (final IllegalArgumentException expected) {
            refused = true;
        }
        expect("while a negative count is a bug, not a rewind", refused);
    }

    /**
     * A tool with nothing left is worth nothing, and worth <em>nothing</em> is not worth one.
     * Counting it as an item is how a planner promises a craft on a broken crystal.
     */
    private static void aFullyWornToolContributesNothing() {
        final ResourceIndex index = new ResourceIndex();
        final int dead = index.idOf("crystal@1000");
        final int fresh = index.idOf("crystal@0");
        final Pools pools = wear(index, "crystal", 1000, fresh);

        expect("a spent tool is worth no uses", pools.unitsOf(dead) == 0L);

        final Ledger stocked = Ledger.fromStock(pools, Map.of(dead, 5L));
        expect("so five of them are still no crafts", stocked.balance(fresh) == 0L);

        // A zero-unit entry has to pass through the books quietly. Treating it as an error would
        // turn "this crystal is finished" into a crash on a perfectly ordinary plan.
        final Transform craft = Transform.of("thing", List.of(Slot.consumed(dead, 1)),
            List.of(new Quantity(index.idOf("thing"), 1)));
        stocked.apply(craft, 1L);
        expect("and spending one writes nothing to its column", stocked.balance(fresh) == 0L);
        expect("while the craft itself is still recorded",
            stocked.balance(index.idOf("thing")) == 1L);
    }

    private static void violationsNameTheirDirection() {
        final ResourceIndex index = new ResourceIndex();
        final int diamond = index.idOf("diamond");
        final Ledger ledger = Ledger.fromStock(Pools.NONE, Map.of(diamond, 10L));

        final List<Ledger.Violation> more = ledger.reconcile(Map.of(diamond, 12L));
        expect("finding more than the books allow is a duplication",
            more.size() == 1 && more.getFirst().duplicated() && more.getFirst().delta() == 2L);
        expect("and it says so in words", more.getFirst().describe(index).contains("+2 duplicated"));

        final List<Ledger.Violation> fewer = ledger.reconcile(Map.of(diamond, 7L));
        expect("finding fewer is items destroyed",
            fewer.size() == 1 && !fewer.getFirst().duplicated());
        expect("which is the half players never forgive, so it is named too",
            fewer.getFirst().describe(index).contains("-3 destroyed"));
    }

    /**
     * The {@code ItemStack} trap, made loud. A key without value equality would mint a fresh id
     * every time the same resource arrived, splitting every balance and hiding the split behind
     * numbers that still add up.
     */
    private static void identityKeysAreRefused() {
        final ResourceIndex index = new ResourceIndex();
        boolean refused = false;
        try {
            index.idOf(new Object());
        } catch (final IllegalArgumentException expected) {
            refused = expected.getMessage().contains("identity key");
        }
        expect("a key with no equals is refused, not silently indexed", refused);
        expect("while a value type is fine", index.idOf("stone") == index.idOf("stone"));
    }

    /**
     * The property itself, over a few thousand random plans.
     *
     * <p>The check runs the same transforms a second way — one iteration at a time, mutating a
     * balance map directly — rather than re-deriving the totals the ledger just wrote. That is
     * what makes it a check on {@link Ledger#apply}'s multiplication and bookkeeping rather than a
     * restatement of it.
     */
    private static void conservationHoldsOverRandomPlans() {
        final Random random = new Random(20260829L);
        final ResourceIndex index = new ResourceIndex();
        final List<Integer> resources = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            resources.add(index.idOf("r" + i));
        }
        // Two of the eight are pooled, so the property covers pool arithmetic too: r0 is a
        // three-use tool and r1 is worth two of whatever column it pays into.
        final Map<Integer, Integer> columns = Map.of(resources.get(1), resources.get(0));
        final Map<Integer, Long> units = Map.of(resources.get(0), 3L, resources.get(1), 2L);
        final Pools pools = new Pools() {
            @Override
            public int columnOf(final int resource) {
                return columns.getOrDefault(resource, resource);
            }

            @Override
            public long unitsOf(final int resource) {
                return units.getOrDefault(resource, 1L);
            }
        };

        int violations = 0;
        for (int trial = 0; trial < 2000; trial++) {
            final Map<Integer, Long> stock = new LinkedHashMap<>();
            for (final int resource : resources) {
                stock.put(resource, (long) random.nextInt(64));
            }
            final Ledger ledger = Ledger.fromStock(pools, stock);
            final Map<Integer, Long> replay = new LinkedHashMap<>(Ledger.toColumns(pools, stock));

            for (int step = 0; step < 1 + random.nextInt(4); step++) {
                final Transform transform = randomTransform(random, resources);
                final long times = 1L + random.nextInt(20);
                ledger.apply(transform, times);
                for (long i = 0; i < times; i++) {
                    replayOnce(transform, pools, replay);
                }
            }
            replay.values().removeIf(amount -> amount == 0L);
            final Map<Integer, Long> observed = new LinkedHashMap<>(replay);
            if (!ledger.reconcile(observed).isEmpty()) {
                violations++;
            }
        }
        expect("initial + produced - consumed == final, over two thousand random plans",
            violations == 0);
    }

    private static Transform randomTransform(final Random random, final List<Integer> resources) {
        final List<Slot> slots = new ArrayList<>();
        for (int i = 0; i < 1 + random.nextInt(3); i++) {
            final int resource = resources.get(random.nextInt(resources.size()));
            final long amount = 1L + random.nextInt(4);
            slots.add(switch (random.nextInt(3)) {
                case 0 -> Slot.consumed(resource, amount);
                case 1 -> Slot.catalyst(resource, amount);
                default -> Slot.transforming(resource, amount,
                    resources.get(random.nextInt(resources.size())));
            });
        }
        final List<Quantity> outputs = new ArrayList<>();
        for (int i = 0; i < 1 + random.nextInt(2); i++) {
            outputs.add(new Quantity(resources.get(random.nextInt(resources.size())),
                1L + random.nextInt(3)));
        }
        return Transform.of("random", slots, outputs);
    }

    private static void replayOnce(final Transform transform,
                                   final Pools pools,
                                   final Map<Integer, Long> balances) {
        for (final Slot slot : transform.slots()) {
            balances.merge(pools.columnOf(slot.resource()),
                -slot.amount() * pools.unitsOf(slot.resource()), Long::sum);
            if (slot.returnsSomething()) {
                balances.merge(pools.columnOf(slot.becomes()),
                    slot.amount() * pools.unitsOf(slot.becomes()), Long::sum);
            }
        }
        for (final Quantity output : transform.outputs()) {
            balances.merge(pools.columnOf(output.resource()),
                output.amount() * pools.unitsOf(output.resource()), Long::sum);
        }
    }

    // ------------------------------------------------------------------ the inference

    private static void inferenceFindsTheCatalyst() {
        final ResourceIndex index = new ResourceIndex();
        final Pattern pattern = recipe("essence", 1,
            List.of(ingredient(1, "master_crystal"), ingredient(4, "inferium")),
            List.of(amount("master_crystal", 1)));

        final PatternTransforms.Result result =
            PatternTransforms.build(pattern, index, Durability.NONE, Remainder.NONE);
        expect("the crystal is recognised as a catalyst",
            result.transform().slots().getFirst().isCatalyst());
        expect("so it costs nothing at all",
            !result.transform().net().containsKey(index.lookup(res("master_crystal"))));
        expect("and there was nothing to complain about", result.clean());
    }

    private static void inferenceFindsWear() {
        final ResourceIndex index = new ResourceIndex();
        final Durability durability = new FakeDurability("crystal", 1000);
        final Pattern pattern = recipe("thing", 1,
            List.of(ingredient(1, "crystal@0")),
            List.of(amount("crystal@1", 1)));

        final PatternTransforms.Result result =
            PatternTransforms.build(pattern, index, durability, Remainder.NONE);
        final Slot slot = result.transform().slots().getFirst();
        expect("the damaged crystal is the same crystal, worn",
            slot.returnsSomething() && !slot.isCatalyst());

        final Pools pools = ToolPools.build(index, List.of(pattern), List.of(), durability);
        expect("and one craft costs one use",
            result.transform().net(pools).get(index.lookup(res("crystal@0"))) == -1L);
    }

    private static void inferenceFindsTheContainer() {
        final ResourceIndex index = new ResourceIndex();
        final Remainder remainder = input ->
            "milk_bucket".equals(String.valueOf(input)) ? res("bucket") : null;
        final Pattern pattern = recipe("cake", 1,
            List.of(ingredient(3, "milk_bucket"), ingredient(3, "wheat")),
            List.of(amount("bucket", 3)));

        final PatternTransforms.Result result =
            PatternTransforms.build(pattern, index, Durability.NONE, remainder);
        final Map<Integer, Long> net = result.transform().net();
        expect("the bucket comes back", net.get(index.lookup(res("bucket"))) == 3L);
        expect("and the milk is what was spent", net.get(index.lookup(res("milk_bucket"))) == -3L);
    }

    private static void anUnexplainedByproductStaysProduction() {
        final ResourceIndex index = new ResourceIndex();
        final Pattern pattern = recipe("ingot", 1,
            List.of(ingredient(1, "ore")),
            List.of(amount("slag", 2)));

        final PatternTransforms.Result result =
            PatternTransforms.build(pattern, index, Durability.NONE, Remainder.NONE);
        expect("an unattributable byproduct is simply production",
            result.transform().net().get(index.lookup(res("slag"))) == 2L);
        expect("and the ore is still consumed",
            result.transform().net().get(index.lookup(res("ore"))) == -1L);
    }

    /**
     * The immortality guard, at the inference layer this time. A byproduct handed back
     * <em>less</em> worn than the ingredient is not wear; treating it as a fate would let the
     * planner run the tool forever.
     */
    private static void aToolHandedBackLessWornIsRefusedAndNoted() {
        final ResourceIndex index = new ResourceIndex();
        final Pattern pattern = recipe("thing", 1,
            List.of(ingredient(1, "crystal@5")),
            List.of(amount("crystal@0", 1)));

        final PatternTransforms.Result result = PatternTransforms.build(pattern, index,
            new FakeDurability("crystal", 1000), Remainder.NONE);
        expect("it is not modelled as wear",
            !result.transform().slots().getFirst().returnsSomething());
        expect("and it says why, out loud",
            result.notes().stream().anyMatch(note -> note.contains("immortal")));
    }

    /**
     * Equal wear is not wear. Two keys can be the same tool with the same uses left — different
     * enchantments, a different component patch — and calling that a fate would hand the planner a
     * tool that never runs out.
     */
    private static void equalWearIsNotWear() {
        final ResourceIndex index = new ResourceIndex();
        final Pattern pattern = recipe("thing", 1,
            List.of(ingredient(1, "twin_a")),
            List.of(amount("twin_b", 1)));

        final PatternTransforms.Result result =
            PatternTransforms.build(pattern, index, twins(100), Remainder.NONE);
        expect("a byproduct with the same uses left is not wear",
            !result.transform().slots().getFirst().returnsSomething());
        expect("and the refusal is explained", !result.clean());
    }

    /**
     * {@link Remainder} answers what an item <em>would</em> leave behind, which is not the same as
     * what this recipe actually hands back. Crediting a bucket the pattern never returns invents
     * an item, and inventing items is the dupe half of a conservation failure.
     */
    private static void aRemainderNotHandedBackIsNotAFate() {
        final ResourceIndex index = new ResourceIndex();
        final Remainder remainder = input ->
            "milk_bucket".equals(String.valueOf(input)) ? res("bucket") : null;
        final Pattern pattern = recipe("cake", 1,
            List.of(ingredient(3, "milk_bucket")),
            List.of());

        final PatternTransforms.Result result =
            PatternTransforms.build(pattern, index, Durability.NONE, remainder);
        expect("no bucket in the byproducts means no bucket comes back",
            !result.transform().slots().getFirst().returnsSomething());
        expect("and none is credited", result.transform().net().get(index.lookup(res("bucket"))) == null);
    }

    private static void aPartialReturnSplitsTheSlot() {
        final ResourceIndex index = new ResourceIndex();
        final Remainder remainder = input ->
            "milk_bucket".equals(String.valueOf(input)) ? res("bucket") : null;
        final Pattern pattern = recipe("cake", 1,
            List.of(ingredient(4, "milk_bucket")),
            List.of(amount("bucket", 1)));

        final PatternTransforms.Result result =
            PatternTransforms.build(pattern, index, Durability.NONE, remainder);
        expect("one bucket back out of four is two slots, not a rounded one",
            result.transform().slots().size() == 2);
        expect("exactly one comes back",
            result.transform().net().get(index.lookup(res("bucket"))) == 1L);
        expect("all four milk buckets are still spent",
            result.transform().net().get(index.lookup(res("milk_bucket"))) == -4L);
        expect("and the split is reported", result.notes().size() == 1);
    }

    /**
     * "Any bucket" where only one of them is handed back. Taking the first input is safe while the
     * alternatives are interchangeable; this is the shape in which that assumption breaks, and it
     * has cost this project a bug before, so it gets a line in the log rather than silence.
     */
    private static void aFuzzySlotThatDisagreesIsNoted() {
        final ResourceIndex index = new ResourceIndex();
        final Remainder remainder = input ->
            "milk_bucket".equals(String.valueOf(input)) ? res("bucket") : null;
        final Pattern pattern = recipe("cake", 1,
            List.of(ingredient(1, "milk_bucket", "milk_bottle")),
            List.of(amount("bucket", 1)));

        final PatternTransforms.Result result =
            PatternTransforms.build(pattern, index, Durability.NONE, remainder);
        expect("the disagreement is reported",
            result.notes().stream().anyMatch(note -> note.contains("fuzzy slot disagrees")));
        expect("so the result is not clean", !result.clean());

        final Pattern agreeing = recipe("cake", 1,
            List.of(ingredient(1, "milk_bucket")),
            List.of(amount("bucket", 1)));
        expect("while an unambiguous pattern says nothing",
            PatternTransforms.build(agreeing, index, Durability.NONE, remainder).clean());

        // Three alternatives that all agree, so the scan runs to the end rather than returning on
        // the first pair — the only shape in which walking off the end of the list would show.
        final Pattern allAgree = recipe("stew", 1,
            List.of(ingredient(1, "carrot", "potato", "beetroot")),
            List.of(amount("slag", 1)));
        expect("and three alternatives that agree say nothing either",
            PatternTransforms.build(allAgree, index, Durability.NONE, remainder).clean());
    }

    /**
     * Uses are fungible and destruction is not: ten crystals with a hundred uses each cannot
     * satisfy a recipe that eats one whole crystal. So one destructive pattern in the set means
     * the family stays items.
     */
    private static void aDestructiveRecipeRefusesThePool() {
        final ResourceIndex index = new ResourceIndex();
        final Durability durability = new FakeDurability("crystal", 1000);
        final Pattern wears = recipe("thing", 1,
            List.of(ingredient(1, "crystal@0")),
            List.of(amount("crystal@1", 1)));
        final Pattern destroys = recipe("altar", 1,
            List.of(ingredient(1, "crystal@0")),
            List.of());

        final Pools both = ToolPools.build(index, List.of(wears, destroys), List.of(), durability);
        expect("a family something eats is not a pool", both == Pools.NONE);

        final Pools wearOnly = ToolPools.build(index, List.of(wears), List.of(), durability);
        expect("while a family everything gives back is",
            wearOnly.unitsOf(index.lookup(res("crystal@0"))) == 1000L);
    }

    /** The immortality guard again, one layer down: a pool of a tool nothing actually wears. */
    private static void aFamilyHandedBackNoMoreWornIsNotAPool() {
        final ResourceIndex index = new ResourceIndex();
        final Pattern pattern = recipe("thing", 1,
            List.of(ingredient(1, "twin_a")),
            List.of(amount("twin_b", 1)));

        expect("a tool returned no more worn is not a supply of uses",
            ToolPools.build(index, List.of(pattern), List.of(), twins(100)) == Pools.NONE);
    }

    /**
     * Two wear levels sitting in a drawer that no recipe touches. There are uses in them, but
     * nothing spends uses, so calling it a pool would invent a currency with no market.
     */
    private static void aFamilyNothingWearsIsNotAPool() {
        final ResourceIndex index = new ResourceIndex();
        final Pattern unrelated = recipe("plank", 4, List.of(ingredient(1, "log")), List.of());

        expect("a tool no pattern uses is not a pool",
            ToolPools.build(index, List.of(unrelated),
                List.of(res("crystal@0"), res("crystal@7")),
                new FakeDurability("crystal", 1000)) == Pools.NONE);
    }

    // ------------------------------------------------------------------ fixtures

    /**
     * Two keys that are the same tool at the same wear. A real {@code Durability} can report this
     * — the keys differ by something that is not damage — and no fake built from a {@code @damage}
     * suffix ever can, which is why this one is written out by hand.
     */
    private static Durability twins(final int uses) {
        return new Durability() {
            @Override
            public boolean isDurable(final ResourceKey resource) {
                return String.valueOf(resource).startsWith("twin_");
            }

            @Override
            public int maxUses(final ResourceKey resource) {
                return isDurable(resource) ? uses : 0;
            }

            @Override
            public int usesLeft(final ResourceKey resource) {
                return isDurable(resource) ? uses : 0;
            }

            @Override
            public ResourceKey afterUses(final ResourceKey resource, final int used) {
                return resource;
            }

            @Override
            public boolean sameTool(final ResourceKey a, final ResourceKey b) {
                return isDurable(a) && isDurable(b);
            }
        };
    }

    /** A pool of one tool family, so the algebra tests need no Refined Storage behind them. */
    private static Pools wear(final ResourceIndex index,
                              final String tool,
                              final int maxUses,
                              final int column) {
        return new Pools() {
            @Override
            public int columnOf(final int resource) {
                return isTool(resource) ? column : resource;
            }

            @Override
            public long unitsOf(final int resource) {
                if (!isTool(resource)) {
                    return 1L;
                }
                final String label = index.label(resource);
                return maxUses - Long.parseLong(label.substring(label.indexOf('@') + 1));
            }

            private boolean isTool(final int resource) {
                return index.label(resource).startsWith(tool + "@");
            }
        };
    }

    private static ResourceKey res(final String id) {
        return PlannerExecutabilitySelfTest.res(id);
    }

    private static Ingredient ingredient(final long amount, final String... inputs) {
        final List<ResourceKey> keys = new ArrayList<>();
        for (final String input : inputs) {
            keys.add(res(input));
        }
        return new Ingredient(amount, keys);
    }

    private static ResourceAmount amount(final String resource, final long amount) {
        return new ResourceAmount(res(resource), amount);
    }

    private static Pattern recipe(final String output,
                                  final long outputAmount,
                                  final List<Ingredient> ingredients,
                                  final List<ResourceAmount> byproducts) {
        return new Pattern(UUID.randomUUID(), PatternLayout.internal(ingredients,
            List.of(amount(output, outputAmount)), byproducts));
    }

    private static void expect(final String what, final boolean ok) {
        checks++;
        if (!ok) {
            FAILURES.add(what);
        }
    }
}
