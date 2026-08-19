package com.wraithhawit.rstweaks.mixin;

import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.wraithhawit.rstweaks.Config;
import com.wraithhawit.rstweaks.ResourceListOverflow;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Stops a network's cached total for one resource wrapping past {@code Long.MAX_VALUE} into a
 * negative number, which turns every read of that network into a hard server crash.
 *
 * <p>{@code MutableResourceListImpl.Entry.increment} validates the addend and nothing else:
 *
 * <pre>{@code   private void increment(long amountToIncrement) {
 *       CoreValidations.validateLargerThanZero(amountToIncrement, "...");
 *       this.amount += amountToIncrement;   // no overflow check
 *   } }</pre>
 *
 * <p>Every other route into that field is guarded — {@code addNew} validates, {@code decrement}
 * refuses to reach zero, and {@code remove} deletes the entry outright rather than let it — so
 * this is the only way an entry can hold a non-positive amount. Once one does, the list is a
 * landmine: it is harmless until something calls {@code getAll()}, and then
 * {@code Entry.toResourceAmount} constructs a {@code ResourceAmount} with a negative amount and
 * {@code ResourceAmount.validate} throws {@code "Amount must be larger than 0"}.
 *
 * <p><b>Why that is a crash and not a glitch.</b> {@code copyState} is
 * {@code entries.values().stream().map(Entry::toResourceAmount).toList()} — a terminal collect, so
 * it throws part way through and returns nothing at all. Every consumer of the whole list dies
 * with it, and the two that matter both run on the server thread: opening any grid
 * ({@code GridWatcherRegistration.attach} replays {@code rootStorage.getAll()} to send the
 * initial contents) and starting any autocraft ({@code CraftingState.of} snapshots storage).
 * The result is a ticking-block-entity crash, not an empty screen.
 *
 * <p><b>How a real network gets there.</b> Reported by LavaSurf on 2026-08-18: crash on the Step
 * Requester, then reproducibly on opening a grid, and it survived a relog. Surviving the relog is
 * the diagnostic — {@code CompositeStorageImpl} rebuilds the list from scratch on every network
 * build via {@code addContentOfSourceToList}, which is
 * {@code source.getAll().forEach(this.list::add)}, so nothing corrupt is persisted and the sum
 * must be re-overflowing from the sources themselves every single load.
 *
 * <p>The sources on that network were Refined Types energy External Storages.
 * {@code EnergyCapabilityCache.createAmountIterator} reads Grand Power's
 * {@code ILongEnergyStorage.getAmount()} — a {@code long}, not NeoForge's {@code int}-capped
 * {@code IEnergyStorage.getEnergyStored()}, so one block can legitimately report a number near
 * {@code Long.MAX_VALUE} — and reports it under {@code EnergyResource.ENERGY_RESOURCE}, a static
 * singleton. One shared key means every energy External Storage on the network sums into a single
 * {@code Entry}. One large source plus anything else wraps it.
 *
 * <p><b>Saturating is the right answer, not merely the cheap one.</b> The alternative is to refuse
 * the addition, which would make the cached total quietly disagree with what the storages actually
 * hold, and this list is what {@code RootStorage.get} answers from — a silent undercount is the
 * shape of bug that eats items. Saturating keeps the invariant the rest of Refined Storage relies
 * on (an entry in the map holds a positive amount) and the only casualty is that a total nobody
 * could represent anyway reads as {@code Long.MAX_VALUE} instead of a wrapped negative.
 *
 * <p>Deliberately fixed here rather than in Refined Types: this is the layer where the invariant
 * lives, it covers every resource type including ones no addon has written yet, and it does not
 * need Refined Types to be present.
 *
 * <p>Nothing needs repairing on an affected save. The bad entry is never written to disk, so the
 * first network build after this mixin loads produces a sane list and the grid opens.
 */
@Mixin(targets = "com.refinedmods.refinedstorage.api.resource.list.MutableResourceListImpl$Entry")
public abstract class MutableResourceListEntryMixin {
    @Shadow
    @Final
    private ResourceKey resource;

    @Shadow
    private long amount;

    @Inject(method = "increment(J)V", at = @At("HEAD"), cancellable = true)
    private void rstweaks$saturateInsteadOfWrapping(final long amountToIncrement,
                                                    final CallbackInfo ci) {
        // Ordered so the overflow test comes first and the config read only happens on the path
        // that is about to crash. increment sits on every insert into every network; the ordinary
        // case has to cost one comparison and nothing else.
        if (amountToIncrement <= 0L || this.amount <= 0L) {
            // Not our case. A non-positive addend is vanilla's error to raise, and it does.
            return;
        }
        if (this.amount <= Long.MAX_VALUE - amountToIncrement) {
            // Fits. Note this is deliberately not `<`: landing exactly on Long.MAX_VALUE is a
            // representable total and vanilla should be the one to write it.
            return;
        }
        if (!Config.clampResourceAmountOverflow) {
            return;
        }

        this.amount = Long.MAX_VALUE;
        ResourceListOverflow.clamped(this.resource, amountToIncrement);
        ci.cancel();
    }
}
