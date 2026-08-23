package com.wraithhawit.rstweaks.mixin;

import com.refinedmods.refinedstorage.api.autocrafting.Pattern;
import com.refinedmods.refinedstorage.api.autocrafting.PatternRepositoryImpl;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.wraithhawit.rstweaks.Config;
import com.wraithhawit.rstweaks.Stats;
import com.wraithhawit.rstweaks.pattern.PatternHolderAccess;
import com.wraithhawit.rstweaks.pattern.PatternOrdering;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes {@code getByOutput} return patterns in priority order, which Refined Storage intends
 * and does not achieve.
 *
 * <p>RS 2.0.9 stores each output's patterns in a {@code PriorityQueue} and reads them back with
 * {@code holders.stream()}, but {@code PriorityQueue} guarantees ordering only for its head --
 * its iterator and spliterator explicitly do not traverse in order. So every alternative after
 * the first comes back in raw heap-array layout, which depends on the sequence patterns were
 * added in.
 *
 * <p>{@code CraftingTree.calculateChild} consumes that list in order, returns on the first
 * pattern that succeeds, and explores every failure to exhaustion while copying the crafting
 * state at each node. An arbitrary order is therefore an arbitrary cost -- and it changes
 * silently whenever patterns are re-added, which is what made consolidating a network's
 * patterns into one provider take three Step Requesters from 0.199 to 20.249 ms/tick with the
 * same patterns and the same recipes. See {@link PatternOrdering} for the full measurement and
 * for why the tiebreak is the pattern's UUID rather than an insertion counter.
 *
 * <p>Injected at RETURN rather than overwriting the method: RS's own body still runs, and this
 * replaces only the order of what it produced. If the injection ever fails to apply, the result
 * is today's behaviour rather than a broken repository.
 */
@Mixin(PatternRepositoryImpl.class)
public abstract class PatternRepositoryImplMixin {
    /**
     * Raw type on purpose. The field's generic parameter names RS's private {@code PatternHolder}
     * record, which cannot be written in Java source; mixin matches shadows on erased
     * descriptors, so {@code Map} is both sufficient and the only expressible option.
     */
    @Shadow(remap = false)
    @Final
    private Map patternsByOutput;

    @Inject(method = "getByOutput", at = @At("RETURN"), cancellable = true, remap = false)
    private void rstweaks$sortByPriority(final ResourceKey output,
                                         final CallbackInfoReturnable<List<Pattern>> cir) {
        if (!Config.sortPatternsByPriority) {
            return;
        }
        final List<Pattern> unsorted = cir.getReturnValue();
        if (unsorted == null || unsorted.size() <= 1) {
            return;
        }
        final Object holders = this.patternsByOutput.get(output);
        if (!(holders instanceof PriorityQueue<?> queue)) {
            return;
        }
        // Read the holders, not the returned patterns: priority lives only on the holder, and
        // the list RS just built has already thrown it away.
        final List<PatternHolderAccess> entries = new ArrayList<>(queue.size());
        for (final Object holder : queue) {
            if (!(holder instanceof PatternHolderAccess access)) {
                // The access mixin did not apply. Leaving RS's own order in place is correct
                // here -- a partially ordered list would be worse than an unordered one.
                return;
            }
            entries.add(access);
        }
        final List<PatternHolderAccess> sorted = PatternOrdering.sorted(
            entries, PatternHolderAccess::rstweaks$priority, holder -> holder.rstweaks$pattern().id());
        if (sorted == entries) {
            return;
        }
        final List<Pattern> result = new ArrayList<>(sorted.size());
        for (final PatternHolderAccess holder : sorted) {
            result.add(holder.rstweaks$pattern());
        }
        ++Stats.patternListsSorted;
        cir.setReturnValue(List.copyOf(result));
    }
}
