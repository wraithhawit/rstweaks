package com.wraithhawit.rstweaks.mixin;

import com.refinedmods.refinedstorage.api.autocrafting.Ingredient;
import com.refinedmods.refinedstorage.api.autocrafting.PatternLayout;
import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.wraithhawit.rstweaks.Config;
import com.wraithhawit.rstweaks.RSTweaks;
import com.wraithhawit.rstweaks.Stats;
import com.wraithhawit.rstweaks.storage.FluidSubstitutionMark;
import com.wraithhawit.rstweaks.storage.FluidSwap;

import java.util.List;

import javax.annotation.Nullable;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Lets a pattern that only empties or fills a container run without a machine.
 *
 * <p>Refined Storage builds every processing pattern as
 * {@code PatternLayout.external(ingredients, outputs)}. External means "give this to a
 * pattern provider and wait for a machine to hand the result back". That is right for a
 * furnace and wrong for a bucket: nothing has to happen for a lava bucket to become an
 * empty bucket and 1000mB of lava, and with no machine to accept it the craft can never
 * start.
 *
 * <p>One word changes it. An {@code internal} layout is settled by Refined Storage's own
 * task engine as bookkeeping, exactly as a crafting-table recipe is — which is a true
 * description of what emptying a container does.
 *
 * <p>Only patterns {@link FluidSwap} recognises are converted, and that test is strict:
 * the same container and the same fluid on opposite sides, verified through the item's
 * own fluid handler capability. Everything else stays external. The failure mode this
 * guards against is not a stalled craft but a fabricated one — turn a real machine recipe
 * into bookkeeping and Refined Storage would produce the output without the machine ever
 * running.
 *
 * <p><b>Strict was never quite enough on its own.</b> A machine recipe that genuinely takes a full
 * container and returns the empty one plus its contents reads as a swap under any amount of
 * inspection, because at the level of ingredients and outputs it <em>is</em> one. So since 0.2.65
 * the pattern also has to say it is one: {@link FluidSubstitutionMark} is written when it is
 * encoded on the fluid tab, and read here. Contents alone no longer convert anything once
 * {@code convertUnmarkedFluidPatterns} is off.
 */
@Mixin(targets = "com.refinedmods.refinedstorage.common.autocrafting."
    + "PatternResolver$ResolvedProcessingPattern")
public abstract class ResolvedProcessingPatternMixin {
    @Redirect(
        method = "<init>(Ljava/util/UUID;Ljava/util/List;Ljava/util/List;)V",
        at = @At(
            value = "INVOKE",
            target = "Lcom/refinedmods/refinedstorage/api/autocrafting/PatternLayout;"
                + "external(Ljava/util/List;Ljava/util/List;)"
                + "Lcom/refinedmods/refinedstorage/api/autocrafting/PatternLayout;"
        )
    )
    private static PatternLayout rstweaks$internalWhenFluidSwap(final List<Ingredient> ingredients,
                                                              final List<ResourceAmount> outputs) {
        // The mark authorises the conversion; the contents still have to earn it. Both are
        // required, and in this order: an unmarked pattern is not read at all once the legacy
        // fallback is off, and a marked one that is not really a swap stays external.
        final boolean allowed = Config.fluidSubstitutionPatterns
            && FluidSubstitutionMark.mayConvert(FluidSubstitutionMark.resolvingMarked());
        final FluidSwap.Swap swap = allowed ? detect(ingredients, outputs) : null;
        if (swap == null) {
            return PatternLayout.external(ingredients, outputs);
        }
        ++Stats.fluidSwapPatterns;
        // Not simply internal(ingredients, outputs): when emptying, the empty container moves to
        // the byproducts so the network stops advertising buckets as craftable.
        return FluidSwap.internalLayout(swap);
    }

    /**
     * Pattern resolution happens while reading an item's components, which is somewhere
     * a thrown exception is unusually destructive — it would make the pattern look
     * corrupt rather than merely unoptimised. A detection failure falls back to the
     * behaviour Refined Storage already had.
     */
    @Nullable
    private static FluidSwap.Swap detect(final List<Ingredient> ingredients,
                                         final List<ResourceAmount> outputs) {
        try {
            return FluidSwap.detect(ingredients, outputs);
        } catch (final RuntimeException | LinkageError e) {
            RSTweaks.LOGGER.warn("[rstweaks] could not test a pattern for fluid substitution", e);
            return null;
        }
    }
}
