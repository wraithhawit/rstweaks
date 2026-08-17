package com.wraithhawit.rstweaks.mixin;

import com.refinedmods.refinedstorage.api.autocrafting.calculation.CancellationToken;
import com.refinedmods.refinedstorage.api.network.autocrafting.AutocraftingNetworkComponent;
import com.refinedmods.refinedstorage.api.network.autocrafting.PatternProvider;
import com.refinedmods.refinedstorage.api.network.impl.autocrafting.AutocraftingNetworkComponentImpl;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.api.storage.Actor;
import com.wraithhawit.rstweaks.Config;
import com.wraithhawit.rstweaks.ServerTicks;
import com.wraithhawit.rstweaks.Stats;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
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
    /**
     * <p>Deliberately not {@code final} and deliberately without an inline initializer. The same
     * shape in {@code AbstractTaskPatternMixin} silently failed to apply and threw
     * {@code NullPointerException} on first use for seven versions, which Refined Storage turned
     * into destroyed items — see that class for the full account. This one has always worked, but
     * "has always worked" is what that one looked like too, so it is built on first use instead.
     */
    @Unique
    @Nullable
    private Map<ResourceKey, Long> rstweaks$uncraftableUntil;

    @Unique
    private Map<ResourceKey, Long> rstweaks$uncraftable() {
        Map<ResourceKey, Long> map = this.rstweaks$uncraftableUntil;
        if (map == null) {
            map = new HashMap<>();
            this.rstweaks$uncraftableUntil = map;
        }
        return map;
    }

    /**
     * The providers Refined Storage would sum over itself. Shadowed rather than re-derived
     * so this can never drift from what {@code ensureTask} sees one line later.
     */
    @Shadow
    @Final
    private Set<PatternProvider> providers;

    /**
     * Refuses a request when a craft for that resource is already running (issue #14).
     *
     * <p>Stock {@code ensureTask} answers {@code TASK_ALREADY_RUNNING} only when the running
     * tasks for the resource total <em>at least the amount asked for</em>. Where the amount is
     * constant and achievable that is enough — one task covers every later request, on the
     * tiered path too, since Cable Tiers builds its exporter from Refined Storage's own
     * factories and its several calls per tick each see the previous call's task
     * ({@code TaskContainer} keeps them in a {@code CopyOnWriteArrayList} added to
     * synchronously). <b>The gap is every case where the running total never catches up.</b>
     *
     * <p><b>The common one needs no upgrade beyond the ones that make it ask.</b> When the
     * network cannot craft as many as the exporter wants, {@code calculatePlan} fails and
     * {@code ensureTaskForCraftableAmount} clamps the request to what is craftable right now.
     * Quota 64 with ingredients for one craft yields a task for <em>1</em> — and 1 is not 64,
     * so the next request starts another task for 1, and the next, and the next. Ingredients
     * trickling in is the normal state of an autocraft, which is why this is the case people
     * actually hit. Measured in
     * {@link com.wraithhawit.rstweaks.test.AutocraftingRequestSelfTest}: twelve requests,
     * twelve tasks, one item each, same resource, no upgrades but the stack one.
     *
     * <p><b>A regulator upgrade is the other one.</b> The autocrafting quota provider is built
     * with {@code respectTransferQuotaWhenRegulating = false}, so the amount is the entire
     * outstanding shortfall rather than a transfer quota, and that shortfall grows every time
     * the destination is drained. Twenty requests against a growing shortfall, twenty tasks.
     *
     * <p>Both are one defect seen from two directions, which is why the guard below asks only
     * whether anything is running rather than enumerating the ways an amount can outrun it.
     * Issue #14 reported the first; this was diagnosed as the second and shipped claiming so,
     * and the first was only found by taking seriously a report that did not fit the
     * explanation. The reporter said "no regulator" and he was right.
     *
     * <p>Those are not free. Each runs a full crafting calculation to build its plan, carries
     * its own internal storage, and is stepped every tick until it finishes — which is the
     * same expense the Step Requester backoff and the uncraftable cache exist to avoid, met
     * from the direction where the request <em>succeeds</em>.
     *
     * <p>Deliberately "anything running is enough" rather than a timed cooldown. A cooldown
     * has to guess a duration and can suppress a request when nothing is running at all; this
     * cannot, because it reads the same live task list Refined Storage does. When the running
     * task finishes, the next request is answered normally. The cost is that a large regulated
     * buffer refills serially instead of in parallel.
     */
    @Inject(method = "ensureTask", at = @At("HEAD"), cancellable = true)
    private void rstweaks$waitForRunningCraft(
        final ResourceKey resource,
        final long amount,
        final Actor actor,
        final CancellationToken cancellationToken,
        final CallbackInfoReturnable<AutocraftingNetworkComponent.EnsureResult> cir
    ) {
        if (!Config.waitForRunningCraft) {
            return;
        }
        for (final PatternProvider provider : this.providers) {
            // Short-circuits on the first provider holding one; the sum is never needed,
            // only whether it is above zero.
            if (provider.getAmount(resource) > 0L) {
                ++Stats.duplicateRequestsSuppressed;
                cir.setReturnValue(AutocraftingNetworkComponent.EnsureResult.TASK_ALREADY_RUNNING);
                return;
            }
        }
    }

    @Inject(method = "ensureTask", at = @At("HEAD"), cancellable = true)
    private void rstweaks$skipKnownUncraftable(
        final ResourceKey resource,
        final long amount,
        final Actor actor,
        final CancellationToken cancellationToken,
        final CallbackInfoReturnable<AutocraftingNetworkComponent.EnsureResult> cir
    ) {
        final Long until = rstweaks$uncraftable().get(resource);
        if (until == null) {
            return;
        }
        if (ServerTicks.current() < until) {
            ++Stats.uncraftableChecksSkipped;
            cir.setReturnValue(AutocraftingNetworkComponent.EnsureResult.MISSING_RESOURCES);
            return;
        }
        rstweaks$uncraftable().remove(resource);
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
            rstweaks$uncraftable().remove(resource);
            return;
        }
        final long now = ServerTicks.current();
        rstweaks$uncraftable().put(
            resource,
            now + Config.UNCRAFTABLE_RECHECK_TICKS.getAsInt()
        );
        // Bounded by the number of distinct resources ever requested, which is
        // small in practice, but a long-lived network with a wandering exporter
        // filter should not accumulate entries forever.
        if (rstweaks$uncraftable().size() > 256) {
            rstweaks$uncraftable().values().removeIf(expiry -> expiry <= now);
        }
    }
}
