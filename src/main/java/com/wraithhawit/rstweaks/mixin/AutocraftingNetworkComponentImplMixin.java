package com.wraithhawit.rstweaks.mixin;

import com.refinedmods.refinedstorage.api.autocrafting.calculation.CancellationToken;
import com.refinedmods.refinedstorage.api.network.autocrafting.AutocraftingNetworkComponent;
import com.refinedmods.refinedstorage.api.network.impl.autocrafting.AutocraftingNetworkComponentImpl;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.api.storage.Actor;
import com.wraithhawit.rstweaks.Config;
import com.wraithhawit.rstweaks.ServerTicks;
import com.wraithhawit.rstweaks.Stats;

import java.util.HashMap;
import java.util.Map;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Caches "this resource is not craftable right now" so the network stops
 * re-deriving it every tick.
 *
 * <p>{@code ensureTask} is what an Exporter with an autocrafting upgrade calls
 * whenever its resource is missing. The cost of a negative answer is severe:
 *
 * <ol>
 *   <li>{@code calculatePlan} runs a full recursive crafting calculation;</li>
 *   <li>if that fails, {@code ensureTaskForCraftableAmount} calls
 *       {@code IsCraftableCraftingCalculatorListener.binarySearchMaxAmount},
 *       which doubles from 1 until the amount is no longer craftable and then
 *       binary-searches the gap — <b>each probe being another full recursive
 *       calculation</b>;</li>
 *   <li>then it calculates the plan a third time for the amount it found.</li>
 * </ol>
 *
 * <p>So a single failing exporter tick can run dozens of complete crafting-tree
 * calculations, and nothing remembers the outcome — the next tick repeats all of
 * it. This is the same shape of defect as the Step Requester storm from round 1,
 * just reached through a different door.
 *
 * <p>Worse, {@code ensureTaskForCraftableAmount} passes {@code CancellationToken.NONE}
 * into the binary search rather than the caller's {@code TimeoutableCancellationToken}.
 * The timeout that is supposed to bound this work is explicitly discarded at
 * precisely the point where the most work happens, so a pathological request cannot
 * time out of the search at all.
 *
 * <p>Keying the cache on the resource alone — ignoring the requested amount — is
 * sound rather than approximate: {@code MISSING_RESOURCES} is only returned when
 * the binary search found a maximum craftable amount of zero, or when the plan
 * failed even at that maximum. Both mean nothing is craftable, for any amount.
 *
 * <p>Any non-negative outcome clears the entry immediately, so a resource becoming
 * craftable is never masked for longer than the recheck interval.
 */
@Mixin(AutocraftingNetworkComponentImpl.class)
public abstract class AutocraftingNetworkComponentImplMixin {
    @Unique
    private final Map<ResourceKey, Long> rstweaks$uncraftableUntil = new HashMap<>();

    @Inject(method = "ensureTask", at = @At("HEAD"), cancellable = true)
    private void rstweaks$skipKnownUncraftable(
        final ResourceKey resource,
        final long amount,
        final Actor actor,
        final CancellationToken cancellationToken,
        final CallbackInfoReturnable<AutocraftingNetworkComponent.EnsureResult> cir
    ) {
        final Long until = this.rstweaks$uncraftableUntil.get(resource);
        if (until == null) {
            return;
        }
        if (ServerTicks.current() < until) {
            ++Stats.uncraftableChecksSkipped;
            cir.setReturnValue(AutocraftingNetworkComponent.EnsureResult.MISSING_RESOURCES);
            return;
        }
        this.rstweaks$uncraftableUntil.remove(resource);
    }

    @Inject(method = "ensureTask", at = @At("RETURN"))
    private void rstweaks$recordOutcome(
        final ResourceKey resource,
        final long amount,
        final Actor actor,
        final CancellationToken cancellationToken,
        final CallbackInfoReturnable<AutocraftingNetworkComponent.EnsureResult> cir
    ) {
        if (cir.getReturnValue() != AutocraftingNetworkComponent.EnsureResult.MISSING_RESOURCES) {
            this.rstweaks$uncraftableUntil.remove(resource);
            return;
        }
        final long now = ServerTicks.current();
        this.rstweaks$uncraftableUntil.put(
            resource,
            now + Config.UNCRAFTABLE_RECHECK_TICKS.getAsInt()
        );
        // Bounded by the number of distinct resources ever requested, which is
        // small in practice, but a long-lived network with a wandering exporter
        // filter should not accumulate entries forever.
        if (this.rstweaks$uncraftableUntil.size() > 256) {
            this.rstweaks$uncraftableUntil.values().removeIf(expiry -> expiry <= now);
        }
    }
}
