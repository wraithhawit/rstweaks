package com.wraithhawit.rstweaks.mixin;

import com.refinedmods.refinedstorage.api.autocrafting.Ingredient;
import com.refinedmods.refinedstorage.api.autocrafting.Pattern;
import com.refinedmods.refinedstorage.api.autocrafting.PatternLayout;
import com.refinedmods.refinedstorage.api.autocrafting.PatternType;
import com.refinedmods.refinedstorage.api.network.autocrafting.PatternProvider;
import com.refinedmods.refinedstorage.api.network.impl.autocrafting.AutocraftingNetworkComponentImpl;
import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.wraithhawit.rstweaks.Config;
import com.wraithhawit.rstweaks.RSTweaks;
import com.wraithhawit.rstweaks.storage.FluidSwap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Makes one fluid substitution pattern work in both directions.
 *
 * <p>Emptying a container and filling it are the same fact stated twice — if a water bucket is a
 * bucket plus 1000mB of water, then a bucket plus 1000mB of water is a water bucket. Refined
 * Storage models a pattern as a single direction, so rather than change that model, the mirrored
 * pattern is registered alongside the one the player inserted, from the same provider and at the
 * same priority. Insert one pattern, get both recipes.
 *
 * <p>The mirror is derived rather than stored: outputs become ingredients and ingredients become
 * outputs. That is only a valid reading because {@link FluidSwap} has already established this is
 * a container and its own contents on opposite sides, where both directions are physically real.
 * A furnace pattern reversed would be a machine for turning iron ingots back into ore.
 *
 * <p>Lifetime is tied to the original: the mirror is removed when the pattern it came from is,
 * so breaking the crafter does not leave a recipe behind that nothing can execute.
 *
 * <h2>This creates a cycle, on purpose</h2>
 *
 * <p>A water bucket makes water, and water makes a water bucket. That is a genuine cycle in the
 * crafting graph and it is the reason this is behind its own config flag. The LP planner handles
 * cycles by construction — it is the problem it was written for, and a swap cycle is degenerate
 * because its net production is zero. Refined Storage's own calculator is depth-first and more
 * likely to struggle, so this should be run with {@code lpPlanner} on.
 */
@Mixin(AutocraftingNetworkComponentImpl.class)
public abstract class ReversibleFluidSwapMixin {
    /**
     * The mirror we registered for each pattern, so it can be taken away again. Keyed by the
     * original pattern, which is a record and therefore compares by value.
     */
    @Unique
    private final Map<Pattern, Pattern> rstweaks$mirrors = new HashMap<>();

    /**
     * Guards the one recursion that matters: registering the mirror re-enters {@code add}, and the
     * mirror of a swap is itself a swap, so without this the two would generate each other until
     * the stack ran out.
     */
    @Unique
    private boolean rstweaks$mirroring;

    @Inject(method = "add", at = @At("RETURN"))
    private void rstweaks$addMirror(final PatternProvider provider,
                                  final Pattern pattern,
                                  final int priority,
                                  final CallbackInfo ci) {
        if (!Config.fluidSubstitutionPatterns
            || !Config.reversibleFluidSwapPatterns
            || this.rstweaks$mirroring) {
            return;
        }
        final Pattern mirror = rstweaks$mirrorOf(pattern);
        if (mirror == null) {
            return;
        }
        this.rstweaks$mirroring = true;
        try {
            ((AutocraftingNetworkComponentImpl) (Object) this).add(provider, mirror, priority);
            this.rstweaks$mirrors.put(pattern, mirror);
        } finally {
            this.rstweaks$mirroring = false;
        }
    }

    /**
     * No re-entrancy guard needed here: removing the mirror looks up the mirror's own mirror,
     * finds nothing, and stops.
     */
    @Inject(method = "remove", at = @At("RETURN"))
    private void rstweaks$removeMirror(final PatternProvider provider,
                                     final Pattern pattern,
                                     final CallbackInfo ci) {
        final Pattern mirror = this.rstweaks$mirrors.remove(pattern);
        if (mirror != null) {
            ((AutocraftingNetworkComponentImpl) (Object) this).remove(provider, mirror);
        }
    }

    /**
     * @return the reversed pattern, or {@code null} if this is not a fluid swap and so cannot be
     *     run backwards.
     */
    @Unique
    @Nullable
    private Pattern rstweaks$mirrorOf(final Pattern pattern) {
        try {
            final PatternLayout layout = pattern.layout();
            // Only patterns we already converted; an external one is still waiting on a machine.
            if (layout.type() != PatternType.INTERNAL) {
                return null;
            }
            // Reads the layout in the canonical form we build, where an emptied container sits in
            // the byproducts rather than the outputs. Reversing is then just flipping the
            // direction and asking for the layout again, so the mirror is built by exactly the
            // code that built the original -- including putting the empty container back into the
            // byproducts on the way round, so neither direction claims buckets are craftable.
            final FluidSwap.Swap swap = FluidSwap.detect(layout);
            if (swap == null) {
                return null;
            }
            return new Pattern(rstweaks$mirrorId(pattern.id()), FluidSwap.mirrorLayout(swap));
        } catch (final RuntimeException | LinkageError e) {
            RSTweaks.LOGGER.warn("[rstweaks] could not mirror a fluid substitution pattern", e);
            return null;
        }
    }

    /**
     * Derived from the original rather than random, so the same pattern yields the same mirror id
     * every time the network is rebuilt — otherwise a chunk reload would look like a different
     * recipe to anything holding on to pattern ids.
     */
    @Unique
    private static UUID rstweaks$mirrorId(final UUID id) {
        return new UUID(id.getMostSignificantBits() ^ 0x7273_7065_7266_524CL,
            id.getLeastSignificantBits());
    }
}
