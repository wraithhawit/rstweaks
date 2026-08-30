package com.wraithhawit.rstweaks.test;


import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import com.wraithhawit.rstweaks.storage.ItemDurability;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * {@link ItemDurability} against the real item registry.
 *
 * <p>Nothing tested it before this. The task-engine scenarios install a {@code FakeDurability} —
 * they are about the planner and the executor, not about how damage is read — so the class that
 * actually answers the question in game had no coverage at all, which only became obvious when its
 * {@code damage()} was rewritten to read the component patch directly instead of building an
 * {@link ItemStack} to ask.
 *
 * <p>That rewrite is why the central assertion here is <b>differential</b>: for every fixture, the
 * patch-reading answer must equal what {@code toItemStack(1L).getDamageValue()} says. The slow path
 * it replaced is the oracle, so an item whose components behave unusually shows up as a
 * disagreement rather than as a wrong number nobody notices.
 */
public final class DurabilitySelfTest {
    private static final List<String> FAILURES = new ArrayList<>();
    private static int checks;

    private DurabilitySelfTest() {
    }

    public static CraftingPlanSelfTest.Result run() {
        FAILURES.clear();
        checks = 0;

        theCachedAnswerMatchesTheSlowOne();
        wearIsCountedInUses();
        toolsAreTheSameToolAcrossWearLevels();
        differentToolsAreNotTheSameTool();
        aToolBreaksOnItsLastUse();
        ordinaryItemsAreNotDurable();
        theHoistedFamilyAgreesWithTheSlowSameTool();

        return new CraftingPlanSelfTest.Result(checks, List.copyOf(FAILURES));
    }

    /**
     * The differential for 0.12.0's hoist.
     *
     * <p>{@code findWornTool} now resolves the wanted side's family once and passes the token into
     * a three-argument {@code sameTool}, instead of re-deriving it for every candidate. That is a
     * pure optimisation, so the only thing worth asserting is that it did not become a different
     * answer — over a matrix that deliberately includes the cases the two-argument version leans
     * on: wear levels of one tool, two different tools, a non-durable item, and the pairing where
     * the item matches but the components do not.
     *
     * <p>Run against the real {@code ItemDurability}, not a fake, because the expensive half being
     * skipped is {@code DataComponentPatch} hashing and only Minecraft has one of those.
     */
    private static void theHoistedFamilyAgreesWithTheSlowSameTool() {
        final List<ItemResource> matrix = new ArrayList<>();
        for (final Item item : List.of(Items.DIAMOND_PICKAXE, Items.DIAMOND_AXE, Items.SHEARS,
            Items.STONE)) {
            for (final int damage : new int[] {0, 1, 100}) {
                matrix.add(worn(item, damage));
            }
        }
        // The item matches but the components do not -- the pairing the item-first rejection in
        // sameTool cannot decide on its own, so it is the one that reaches the family comparison.
        final ItemStack named = new ItemStack(Items.DIAMOND_PICKAXE);
        named.set(DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("Bob"));
        matrix.add(ItemResource.ofItemStack(named));
        for (final ItemResource wanted : matrix) {
            final int family = ItemDurability.INSTANCE.toolFamily(wanted);
            for (final ItemResource candidate : matrix) {
                final boolean slow = ItemDurability.INSTANCE.sameTool(wanted, candidate);
                final boolean hoisted =
                    ItemDurability.INSTANCE.sameTool(wanted, family, candidate);
                expect("hoisted sameTool agrees for " + wanted + " vs " + candidate,
                    slow == hoisted);
            }
        }
    }

    /**
     * The differential. {@code damage()} now reads the component patch; this asserts it agrees with
     * building the stack and asking, which is what it used to do.
     */
    private static void theCachedAnswerMatchesTheSlowOne() {
        for (final Item item : List.of(Items.DIAMOND_PICKAXE, Items.NETHERITE_AXE, Items.SHEARS,
            Items.ELYTRA, Items.STONE, Items.DIAMOND)) {
            for (final int damage : new int[] {0, 1, 7, 100}) {
                final ItemResource resource = worn(item, damage);
                final int viaStack = resource.toItemStack(1L).getDamageValue();
                final int max = ItemDurability.INSTANCE.maxUses(resource);
                final int expectedLeft = max <= 0 ? 0 : Math.max(0, max - viaStack);
                expect(item + "@" + damage + " has " + expectedLeft + " uses left",
                    ItemDurability.INSTANCE.usesLeft(resource) == expectedLeft);
            }
        }
    }

    private static void wearIsCountedInUses() {
        final ItemResource fresh = worn(Items.DIAMOND_PICKAXE, 0);
        final int max = ItemDurability.INSTANCE.maxUses(fresh);
        expect("a diamond pickaxe has a maximum", max > 0);
        expect("a fresh one has all of it", ItemDurability.INSTANCE.usesLeft(fresh) == max);
        expect("one used a hundred times has a hundred fewer",
            ItemDurability.INSTANCE.usesLeft(worn(Items.DIAMOND_PICKAXE, 100)) == max - 100);
    }

    private static void toolsAreTheSameToolAcrossWearLevels() {
        expect("two wear levels are the same tool", ItemDurability.INSTANCE.sameTool(
            worn(Items.DIAMOND_PICKAXE, 0), worn(Items.DIAMOND_PICKAXE, 250)));
        // Twice, because the answer is cached on both sides and a cache that returns something
        // different the second time would be worse than no cache.
        expect("and still are on the second ask", ItemDurability.INSTANCE.sameTool(
            worn(Items.DIAMOND_PICKAXE, 0), worn(Items.DIAMOND_PICKAXE, 250)));
    }

    /**
     * The fuzzy-slot case, which cost issue #9: a copper hammer and an iron hammer are different
     * tools, not wear levels of one, and a planner told otherwise demands the first it saw.
     */
    private static void differentToolsAreNotTheSameTool() {
        expect("a pickaxe is not an axe", !ItemDurability.INSTANCE.sameTool(
            worn(Items.DIAMOND_PICKAXE, 0), worn(Items.DIAMOND_AXE, 0)));

        final ItemStack named = new ItemStack(Items.DIAMOND_PICKAXE);
        named.set(DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("Bob"));
        expect("and a differently-componented one is not interchangeable either",
            !ItemDurability.INSTANCE.sameTool(
                ItemResource.ofItemStack(named), worn(Items.DIAMOND_PICKAXE, 0)));
    }

    private static void aToolBreaksOnItsLastUse() {
        final ItemResource fresh = worn(Items.SHEARS, 0);
        final int max = ItemDurability.INSTANCE.maxUses(fresh);
        expect("using it once leaves something behind",
            ItemDurability.INSTANCE.afterUses(fresh, 1) != null);
        expect("using it up leaves nothing, the way vanilla destroys a tool",
            ItemDurability.INSTANCE.afterUses(fresh, max) == null);
    }

    private static void ordinaryItemsAreNotDurable() {
        expect("stone does not wear out", !ItemDurability.INSTANCE.isDurable(worn(Items.STONE, 0)));
        expect("and has no uses", ItemDurability.INSTANCE.usesLeft(worn(Items.STONE, 0)) == 0);
    }

    private static ItemResource worn(final Item item, final int damage) {
        final ItemStack stack = new ItemStack(item);
        if (damage > 0) {
            stack.set(DataComponents.DAMAGE, damage);
        }
        return ItemResource.ofItemStack(stack);
    }

    private static void expect(final String what, final boolean ok) {
        checks++;
        if (!ok) {
            FAILURES.add(what);
        }
    }
}
