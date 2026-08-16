package com.wraithhawit.rstweaks.mixin;

import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.common.support.FilteredContainer;
import com.ultramega.cabletiers.common.autocrafting.autocrafter.TieredAutocrafterBlockEntity;
import com.ultramega.cabletiers.common.autocrafting.sidedinput.SidedInputPatternState;
import com.ultramega.cabletiers.common.autocrafting.sidedinput.SidedResourceAmount;
import com.ultramega.cabletiers.common.registry.DataComponents;
import com.wraithhawit.rstweaks.Stats;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.minecraft.world.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Removes redundant work from the Tiered Autocrafter's sided-input pattern lookup.
 *
 * <p>Diagnosis, from the round-2 profile: Refined Storage probes each external
 * pattern sink to ask whether it <em>would</em> accept an iteration's inputs —
 * {@code TaskImpl.stepPattern} → {@code ExternalTaskPattern.acceptsIterationInputs}
 * → {@code getSinkThatIsAcceptingResources} → {@code TieredAutocrafterBlockEntity.accept}
 * → {@code findResult} → {@code findSidedInputPatternState}. That probe ran on
 * every task step, and the lookup cost 2.68% of the server thread with 1.72% of it
 * inside a single {@code stream().toList()}.
 *
 * <p>The original walks every slot of the pattern container and, for each slot that
 * holds a sided-input pattern:
 * <ol>
 *   <li>materialises the slot's present sided resources into a new list via a
 *       stream pipeline;</li>
 *   <li>calls {@code resourcesMatchesIgnoringIndex}, which builds a grouping map
 *       for that slot <b>and rebuilds the map for {@code resources}</b>.</li>
 * </ol>
 *
 * <p>That second map depends only on {@code resources}, which does not change for
 * the duration of the scan — so it was being rebuilt once per slot for no reason.
 * This hoists it out of the loop, drops both stream pipelines for plain merges into
 * a reused map, and skips empty slots before the data-component lookup.
 *
 * <p>Behaviour is unchanged. The original compared key sets and then every value
 * individually, which is exactly {@link Map#equals}; both sides are built with the
 * same grouping semantics, so the same pattern state is selected for the same
 * inputs. Empty stacks carry no components, so the early-out cannot skip a slot the
 * original would have matched.
 */
@Mixin(TieredAutocrafterBlockEntity.class)
public abstract class TieredAutocrafterBlockEntityMixin {
    @Inject(method = "findSidedInputPatternState", at = @At("HEAD"), cancellable = true)
    private static void rstweaks$fastFindSidedInputPatternState(
        final FilteredContainer patternContainer,
        final List<ResourceAmount> resources,
        final CallbackInfoReturnable<SidedInputPatternState> cir
    ) {
        ++Stats.sidedInputLookups;

        // Invariant across the whole scan; the original rebuilt this per slot.
        final Map<ResourceKey, Long> wanted = new HashMap<>();
        for (final ResourceAmount amount : resources) {
            wanted.merge(amount.resource(), amount.amount(), Long::sum);
        }

        // Reused rather than reallocated for each candidate slot.
        final Map<ResourceKey, Long> offered = new HashMap<>();

        for (int slot = 0; slot < patternContainer.getContainerSize(); slot++) {
            final ItemStack stack = patternContainer.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            final SidedInputPatternState state =
                stack.get(DataComponents.INSTANCE.getSidedInputPatternState());
            if (state == null) {
                continue;
            }

            offered.clear();
            for (final Optional<SidedResourceAmount> sided : state.sidedResources()) {
                if (sided.isEmpty()) {
                    continue;
                }
                final ResourceAmount amount = sided.get().resource();
                offered.merge(amount.resource(), amount.amount(), Long::sum);
            }

            if (offered.equals(wanted)) {
                cir.setReturnValue(state);
                return;
            }
        }

        cir.setReturnValue(null);
    }
}
