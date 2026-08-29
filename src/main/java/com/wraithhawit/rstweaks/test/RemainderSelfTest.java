package com.wraithhawit.rstweaks.test;

import com.refinedmods.refinedstorage.api.autocrafting.Ingredient;
import com.refinedmods.refinedstorage.api.autocrafting.Pattern;
import com.refinedmods.refinedstorage.api.autocrafting.PatternLayout;
import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import com.wraithhawit.rstweaks.ledger.ResourceIndex;
import com.wraithhawit.rstweaks.ledger.Slot;
import com.wraithhawit.rstweaks.ledger.rs.PatternTransforms;
import com.wraithhawit.rstweaks.ledger.rs.Remainder;
import com.wraithhawit.rstweaks.planner.Durability;
import com.wraithhawit.rstweaks.storage.ItemRemainder;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The one part of the ledger model that a plain JVM cannot check: {@link ItemRemainder}.
 *
 * <p>Everything else in the model is arithmetic and runs headlessly in milliseconds. This asks the
 * game a question — what does a milk bucket leave behind — and the answer only exists once the item
 * registry does. Until this existed, {@code ItemRemainder} had never been executed against a real
 * item at all: the headless suites install fakes, and a fixture agreeing with itself is not
 * evidence about the adapter.
 *
 * <p>It matters more since 0.7.2, because that is the version where the adapter went live:
 * {@code CraftingGraph} now reads {@code Remainder.Holder} on the autocrafting path, so whatever
 * this returns in game is what the planner reasons with.
 */
public final class RemainderSelfTest {
    private static final List<String> FAILURES = new ArrayList<>();
    private static int checks;

    private RemainderSelfTest() {
    }

    public static CraftingPlanSelfTest.Result run() {
        FAILURES.clear();
        checks = 0;

        vanillaContainersComeBack();
        ordinaryItemsLeaveNothing();
        aToolIsNotAContainer();
        aNonItemResourceIsRefusedQuietly();
        theCakeCaseEndToEnd();

        return new CraftingPlanSelfTest.Result(checks, List.copyOf(FAILURES));
    }

    /** The cases the whole container half of the model rests on. */
    private static void vanillaContainersComeBack() {
        expectRemainder(Items.MILK_BUCKET, Items.BUCKET);
        expectRemainder(Items.WATER_BUCKET, Items.BUCKET);
        expectRemainder(Items.LAVA_BUCKET, Items.BUCKET);
        expectRemainder(Items.HONEY_BOTTLE, Items.GLASS_BOTTLE);

        // Twice, because the answer is cached per item and a cache that returns something
        // different the second time would be worse than no cache.
        expectRemainder(Items.MILK_BUCKET, Items.BUCKET);
    }

    private static void ordinaryItemsLeaveNothing() {
        expectNothing(Items.STONE);
        expectNothing(Items.DIAMOND);
        expectNothing(Items.BUCKET);
    }

    /**
     * A damageable tool has no crafting remainder, and must not acquire one here: wear is
     * {@link Durability}'s question, and answering it twice in two different ways is how the two
     * rules would start to disagree.
     */
    private static void aToolIsNotAContainer() {
        expectNothing(Items.DIAMOND_PICKAXE);
        expectNothing(Items.NETHERITE_AXE);
    }

    private static void aNonItemResourceIsRefusedQuietly() {
        final ResourceKey notAnItem = new ResourceKey() {
            @Override
            public String toString() {
                return "not-an-item";
            }
        };
        expect("a resource that is not an item is refused rather than thrown at",
            ItemRemainder.INSTANCE.remainderOf(notAnItem) == null);
    }

    /**
     * The whole chain, with real items: registry to {@link ItemRemainder} to
     * {@link PatternTransforms}. A cake pattern takes three milk buckets and hands three buckets
     * back, and the model has to see one slot with a fate rather than an ingredient and an
     * unrelated byproduct.
     */
    private static void theCakeCaseEndToEnd() {
        final ResourceKey milk = item(Items.MILK_BUCKET);
        final ResourceKey bucket = item(Items.BUCKET);
        final Pattern cake = new Pattern(UUID.randomUUID(), PatternLayout.internal(
            List.of(new Ingredient(3L, List.of(milk)),
                new Ingredient(3L, List.of(item(Items.WHEAT)))),
            List.of(new ResourceAmount(item(Items.CAKE), 1L)),
            List.of(new ResourceAmount(bucket, 3L))));

        final ResourceIndex index = new ResourceIndex();
        final PatternTransforms.Result result = PatternTransforms.build(
            cake, index, Durability.NONE, ItemRemainder.INSTANCE);

        final Slot first = result.transform().slots().getFirst();
        expect("the milk bucket slot carries a fate", first.returnsSomething());
        expect("and that fate is the empty bucket",
            first.becomes() == index.lookup(bucket));
        expect("the wheat is simply consumed",
            !result.transform().slots().get(1).returnsSomething());
        expect("three buckets come back",
            result.transform().net().get(index.lookup(bucket)) == 3L);
        expect("and three milk buckets are spent",
            result.transform().net().get(index.lookup(milk)) == -3L);
        expect("with nothing to report", result.clean());
    }

    private static ResourceKey item(final Item item) {
        return ItemResource.ofItemStack(new ItemStack(item, 1));
    }

    private static void expectRemainder(final Item input, final Item expected) {
        final ResourceKey actual = ItemRemainder.INSTANCE.remainderOf(item(input));
        expect(input + " leaves a " + expected,
            actual != null && actual.equals(item(expected)));
    }

    private static void expectNothing(final Item input) {
        expect(input + " leaves nothing behind",
            ItemRemainder.INSTANCE.remainderOf(item(input)) == null);
    }

    private static void expect(final String what, final boolean ok) {
        checks++;
        if (!ok) {
            FAILURES.add(what);
        }
    }
}
