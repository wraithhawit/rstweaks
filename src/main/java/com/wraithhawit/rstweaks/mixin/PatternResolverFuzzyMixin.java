package com.wraithhawit.rstweaks.mixin;

import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.common.autocrafting.CraftingPatternState;
import com.refinedmods.refinedstorage.common.autocrafting.PatternResolver;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import com.wraithhawit.rstweaks.Config;
import com.wraithhawit.rstweaks.RSTweaks;
import com.wraithhawit.rstweaks.planner.Durability;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps the tool a fuzzy pattern was actually encoded with.
 *
 * <p>{@code getFuzzyInput} replaces the encoded stack with the recipe's own ingredient items:
 *
 * <pre>{@code   ItemStack[] items = recipe.getIngredients().get(i).getItems();
 *   return Arrays.stream(items).map(ItemResource::ofItemStack).toList(); }</pre>
 *
 * <p>Those are plain instances, so a damageable item comes back at <b>damage 0</b> however worn the
 * encoded one was. The byproduct is not rewritten — it comes from
 * {@code recipe.getRemainingItems()} on the encoded input and keeps the real damage. Encode with a
 * crystal at damage 50 and the resolved pattern reads {@code @0 in, @51 out}.
 *
 * <p>Everything downstream then draws the obvious and wrong conclusion, because that layout is
 * indistinguishable from a recipe that genuinely burns fifty-one durability a craft.
 * {@code DurabilityClasses.wearStep} measures exactly that gap, so one craft is costed at 51 uses
 * instead of 1 and a fresh crystal becomes worth a single craft. <b>The planner is not at fault and
 * must not be "fixed" here</b> — its arithmetic on that layout is right, and a recipe really can
 * burn fifty-one durability. The lie is introduced before it ever sees it.
 *
 * <p>So the encoded resource goes back in, at the front. Fuzzy still means what it meant: every
 * alternative the recipe accepts is still listed, and this only adds the one the player actually
 * put in the grid — which non-fuzzy patterns have always used. Being first matters twice over:
 * {@code CraftingGraph.buildEffects} takes {@code inputs().getFirst()} to decide the resource class,
 * and {@code DurabilityClasses.wearStep} takes the first input belonging to the tool group.
 *
 * <p>Restricted to durable items on purpose. For anything else the encoded stack is already among
 * the recipe's items, and prepending it would reorder alternatives for every fuzzy pattern in the
 * game to fix a problem only tools have.
 */
@Mixin(PatternResolver.class)
public abstract class PatternResolverFuzzyMixin {
    @Inject(method = "getFuzzyInput", at = @At("RETURN"), cancellable = true)
    private void rstweaks$keepEncodedTool(final CraftingRecipe recipe,
                                          final CraftingPatternState state,
                                          final int index,
                                          final ItemStack input,
                                          final CallbackInfoReturnable<List<ResourceKey>> cir) {
        if (!Config.durabilityAwarePlanning || input.isEmpty()) {
            return;
        }
        try {
            final ItemResource encoded = ItemResource.ofItemStack(input);
            if (!Durability.Holder.get().isDurable(encoded)) {
                return;
            }
            final List<ResourceKey> fuzzy = cir.getReturnValue();
            if (fuzzy == null || fuzzy.isEmpty() || fuzzy.contains(encoded)) {
                // Already there, which is the case when the tool was encoded pristine — the common
                // one, and the one that worked by accident before this.
                return;
            }
            final List<ResourceKey> withEncoded = new ArrayList<>(fuzzy.size() + 1);
            withEncoded.add(encoded);
            withEncoded.addAll(fuzzy);
            cir.setReturnValue(List.copyOf(withEncoded));
        } catch (final RuntimeException | LinkageError e) {
            // The fuzzy list Refined Storage built is always a valid answer; losing the encoded
            // tool costs correct wear accounting, not the pattern.
            RSTweaks.LOGGER.warn("[rstweaks] could not keep the encoded tool on a fuzzy pattern", e);
        }
    }
}
