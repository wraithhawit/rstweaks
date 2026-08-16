package com.wraithhawit.rstweaks.storage;

import com.refinedmods.refinedstorage.api.autocrafting.Ingredient;
import com.refinedmods.refinedstorage.api.autocrafting.PatternLayout;
import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.common.autocrafting.ProcessingPatternState;
import com.refinedmods.refinedstorage.common.support.resource.FluidResource;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import com.refinedmods.refinedstorage.neoforge.support.resource.VariantUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javax.annotation.Nullable;

import net.minecraft.world.item.ItemStack;

import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.IFluidHandlerItem;

/**
 * Recognises a pattern that only moves fluid between a container and the network — a
 * lava bucket becoming an empty bucket plus 1000mB of lava, or the reverse.
 *
 * <p>Refined Storage can already express this as a processing pattern, but a processing
 * pattern is {@code PatternLayout.external}, which means "hand this to a machine and wait
 * for the result". There is no machine involved in emptying a bucket, so the craft can
 * never run. Recognised here, the same pattern becomes {@code internal} and Refined
 * Storage settles it in its own ledger, the way it does a crafting-table recipe.
 *
 * <p>Detection goes through the fluid handler capability rather than hardcoding buckets,
 * so any container the pack has — a tank in item form, a mod's canister — is covered by
 * the same rule.
 *
 * <p>The test is deliberately strict. Anything that is not exactly a container losing or
 * gaining its own contents stays external, because turning a real machine recipe into
 * bookkeeping would let Refined Storage conjure the output without ever running the
 * machine.
 */
public final class FluidSwap {
    private FluidSwap() {
    }

    /** What a container holds, and what is left once it is emptied. */
    public record Contents(FluidResource fluid, long amount, ItemResource emptied) {
    }

    /**
     * A recognised swap, broken into its parts and which way round it goes.
     *
     * <p>Both directions are the same four things — a full container, an empty one, a fluid and an
     * amount — so reversing a swap is just flipping {@code emptying}. That is what lets one encoded
     * pattern serve both directions without either being written out twice.
     */
    public record Swap(boolean emptying,
                       ItemResource filled,
                       FluidResource fluid,
                       long amount,
                       ItemResource emptied) {
        Swap reversed() {
            return new Swap(!emptying, filled, fluid, amount, emptied);
        }
    }

    /**
     * The layout Refined Storage should actually run for this swap.
     *
     * <p>When emptying, the fluid is the output and the empty container is a <em>byproduct</em>.
     * That distinction is the whole reason this method exists: Refined Storage indexes patterns by
     * output to decide what is craftable, so listing the empty container there advertised buckets
     * as something the network could make. It cannot — it can only hand back the one you gave it.
     * A byproduct is returned to storage just the same, without ever claiming to be craftable.
     */
    public static PatternLayout internalLayout(final Swap swap) {
        if (swap.emptying()) {
            return PatternLayout.internal(
                List.of(new Ingredient(1L, List.of(swap.filled()))),
                List.of(new ResourceAmount(swap.fluid(), swap.amount())),
                List.of(new ResourceAmount(swap.emptied(), 1L)));
        }
        return PatternLayout.internal(
            List.of(new Ingredient(1L, List.of(swap.emptied())),
                new Ingredient(swap.amount(), List.of(swap.fluid()))),
            List.of(new ResourceAmount(swap.filled(), 1L)),
            List.of());
    }

    /** The same swap run the other way. */
    public static PatternLayout mirrorLayout(final Swap swap) {
        return internalLayout(swap.reversed());
    }

    /**
     * Recognises a swap in a layout we already built, where the empty container has been moved to
     * the byproducts. Putting the two sides back together is enough to reuse the same detection.
     */
    @Nullable
    public static Swap detect(final PatternLayout layout) {
        final List<ResourceAmount> produced = new ArrayList<>(layout.outputs());
        produced.addAll(layout.byproducts());
        return detect(layout.ingredients(), produced);
    }

    /**
     * Recognises a swap in an encoded pattern item, without resolving it.
     *
     * <p>Reads the stored component directly because the places that need this — the item's name
     * and its tooltip — run on the client with no {@code Level} and no resolved pattern to consult.
     *
     * <p>Both lists are sparse: a processing matrix stores an {@code Optional} per slot, most of
     * them empty. Dropping the empties is what turns nine-by-nine of mostly nothing into the one
     * ingredient and two outputs a swap actually has.
     */
    @Nullable
    public static Swap detect(final ProcessingPatternState state) {
        final List<Ingredient> ingredients = state.ingredients().stream()
            .flatMap(ingredient ->
                ingredient.map(ProcessingPatternState.ProcessingIngredient::toIngredient).stream())
            .toList();
        final List<ResourceAmount> outputs = state.outputs().stream()
            .flatMap(Optional::stream)
            .toList();
        return detect(ingredients, outputs);
    }

    /** @return the swap these ingredients and outputs describe, or {@code null} if they do not. */
    @Nullable
    public static Swap detect(final List<Ingredient> ingredients,
                              final List<ResourceAmount> outputs) {
        final Swap emptying = detectEmptying(ingredients, outputs);
        return emptying != null ? emptying : detectFilling(ingredients, outputs);
    }

    /**
     * What this container would become and release if emptied, or {@code null} if it is not a full
     * container. Public so the Pattern Grid can fill both sides of a pattern from one bucket
     * without duplicating the capability handling.
     */
    @Nullable
    public static Contents contents(final ItemResource resource) {
        return contentsOf(resource);
    }

    /**
     * @return {@code true} when these ingredients and outputs are the same container and
     *     fluid on opposite sides, in either direction.
     */
    public static boolean isFluidSwap(final List<Ingredient> ingredients,
                                      final List<ResourceAmount> outputs) {
        return detect(ingredients, outputs) != null;
    }

    /** Filled container in; empty container and its fluid out. */
    @Nullable
    private static Swap detectEmptying(final List<Ingredient> ingredients,
                                       final List<ResourceAmount> outputs) {
        final ItemResource container = soleItem(ingredients);
        if (container == null || outputs.size() != 2) {
            return null;
        }
        final Contents contents = contentsOf(container);
        if (contents == null
            || !matches(outputs, contents.emptied(), 1L, contents.fluid(), contents.amount())) {
            return null;
        }
        return new Swap(true, container, contents.fluid(), contents.amount(), contents.emptied());
    }

    /** Empty container and a fluid in; filled container out. */
    @Nullable
    private static Swap detectFilling(final List<Ingredient> ingredients,
                                      final List<ResourceAmount> outputs) {
        if (ingredients.size() != 2 || outputs.size() != 1
            || !(outputs.getFirst().resource() instanceof ItemResource filled)
            || outputs.getFirst().amount() != 1L) {
            return null;
        }
        final Contents contents = contentsOf(filled);
        if (contents == null) {
            return null;
        }
        // The ingredient list is the same pair, so compare it as one.
        for (final Ingredient ingredient : ingredients) {
            if (ingredient.inputs().size() != 1) {
                return null;
            }
        }
        final List<ResourceAmount> asOutputs = ingredients.stream()
            .map(ingredient -> new ResourceAmount(ingredient.inputs().getFirst(), ingredient.amount()))
            .toList();
        if (!matches(asOutputs, contents.emptied(), 1L, contents.fluid(), contents.amount())) {
            return null;
        }
        return new Swap(false, filled, contents.fluid(), contents.amount(), contents.emptied());
    }

    /** Whether the pair is exactly these two resources and amounts, in either order. */
    private static boolean matches(final List<ResourceAmount> pair,
                                   final ItemResource item,
                                   final long itemAmount,
                                   final FluidResource fluid,
                                   final long fluidAmount) {
        for (int i = 0; i < 2; i++) {
            final ResourceAmount a = pair.get(i);
            final ResourceAmount b = pair.get(1 - i);
            if (a.resource().equals(item) && a.amount() == itemAmount
                && b.resource().equals(fluid) && b.amount() == fluidAmount) {
                return true;
            }
        }
        return false;
    }

    /**
     * What this item holds, or {@code null} if it is not a full container.
     *
     * <p>Asks the item's own fluid handler and then drains a copy, so the emptied form is
     * whatever the container itself says it becomes — a bucket for a bucket, and whatever
     * a mod's own container turns into.
     */
    @Nullable
    private static Contents contentsOf(final ItemResource resource) {
        final ItemStack stack = resource.toItemStack(1L);
        final IFluidHandlerItem handler = stack.getCapability(Capabilities.FluidHandler.ITEM);
        if (handler == null) {
            return null;
        }
        final FluidStack contained = handler.drain(Integer.MAX_VALUE, IFluidHandler.FluidAction.SIMULATE);
        if (contained.isEmpty()) {
            return null;
        }
        handler.drain(Integer.MAX_VALUE, IFluidHandler.FluidAction.EXECUTE);
        final ItemStack emptied = handler.getContainer();
        if (emptied.isEmpty() || emptied.getCount() != 1) {
            // A container that vanishes or multiplies when emptied is not a swap.
            return null;
        }
        return new Contents(
            VariantUtil.ofFluidStack(contained),
            contained.getAmount(),
            ItemResource.ofItemStack(emptied));
    }

    /** The single item ingredient, or {@code null} if there is not exactly one. */
    @Nullable
    private static ItemResource soleItem(final List<Ingredient> ingredients) {
        if (ingredients.size() != 1 || ingredients.getFirst().amount() != 1L) {
            return null;
        }
        final List<ResourceKey> inputs = ingredients.getFirst().inputs();
        // A slot offering alternatives is a choice, not a swap: which container comes
        // back would depend on which one was taken, and the pattern cannot say.
        return inputs.size() == 1 && inputs.getFirst() instanceof ItemResource item ? item : null;
    }
}
