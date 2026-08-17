package com.wraithhawit.rstweaks.mixin;

import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.wraithhawit.rstweaks.Config;
import com.wraithhawit.rstweaks.RSTweaks;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Records which branch Refined Storage's grid view actually took, for issue #15.
 *
 * <p>The 0.2.89 audit caught the phantom and produced a contradiction that this exists to
 * resolve. On the craft that reproduced it:
 *
 * <pre>{@code
 * update iron_ore_hammer@6 by -1 (backing had 1, sticky=false)
 *   existing row: REMOVED (removedFromBacking=true, sticky=false, backing now 0)
 * PHANTOM ROW ... iron_ore_hammer@6 is in the view list, the backing list holds none of it
 * }</pre>
 *
 * <p>Those two lines cannot both be describing the same thing. {@code ResourceRepositoryImpl}
 * removes a row by calling {@code ViewList.remove}, which does {@code index.remove(resource)}
 * on the same key that {@code viewList.get(resource)} had just found — a {@link java.util.HashMap}
 * that finds a key with {@code get} always drops it with {@code remove}. So if the key survived,
 * {@code remove} was never called, and the branch condition
 * {@code removedFromBackingList && !stickyResources.contains(resource)} must have been false.
 *
 * <p>Which makes {@code contains} the interesting call: our probe reads that same set from the
 * same object and gets {@code false}, so the copy of the call <em>inside</em> the method answered
 * differently from the one outside it. A {@code @Redirect} does exactly that, and Step Crafter
 * ships one on precisely this call — {@code MixinResourceRepositoryImpl.checkForMaintainingResources},
 * which returns {@code sticky || isMaintained} so that resources a Step Crafter maintains keep
 * their row. The line above the branch is our own logging, which reads the raw set and cannot see
 * the redirect, which is why it reported REMOVED for a row that was kept.
 *
 * <p>That is a deduction, and four deductions about this bug have already been wrong, so this
 * mixin stops deducing: it logs whether {@code remove} was called at all. Combined with
 * {@code ResourceRepositoryImplDiagnosticMixin}'s report of what Step Crafter says about the same
 * resource, one reproduction settles it.
 *
 * <p>Targeted by name because {@code ViewList} is package-private — the same trick as
 * {@code ProcessingPatternClientTooltipComponentMixin}.
 */
@Mixin(targets = "com.refinedmods.refinedstorage.api.resource.repository.ViewList", remap = false)
public abstract class ViewListDiagnosticMixin {
    @Inject(method = "remove", at = @At("HEAD"), require = 0)
    private void rstweaks$logRemove(final ResourceKey resource,
                                    final Object mapped,
                                    final CallbackInfo ci) {
        if (Config.logGridViewDiagnostics) {
            RSTweaks.LOGGER.info("[rstweaks][grid]   ViewList.remove CALLED for {}", resource);
        }
    }
}
