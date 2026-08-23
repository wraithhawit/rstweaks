package com.wraithhawit.rstweaks.mixin;

import com.refinedmods.refinedstorage.api.autocrafting.calculation.CancellationToken;
import com.refinedmods.refinedstorage.api.autocrafting.task.TaskId;
import com.refinedmods.refinedstorage.api.network.autocrafting.AutocraftingNetworkComponent;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.api.storage.Actor;
import com.ultramega.stepcrafter.common.steprequester.StepRequesterNetworkNode;
import com.ultramega.stepcrafter.common.support.ResourceMinMaxAmount;
import com.ultramega.stepcrafter.common.support.patternresource.PatternResourceContainerImpl;
import com.wraithhawit.rstweaks.Config;
import com.wraithhawit.rstweaks.Stats;
import com.wraithhawit.rstweaks.backoff.SlotBackoff;

import java.util.Arrays;
import java.util.Optional;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Backs off a Step Requester filter slot whose craft attempt failed, or succeeded expensively.
 *
 * <p>Diagnosis, from a 120s spark profile of the survival world: 91.6% of the
 * server tick was {@code Level.tickBlockEntities}, of which <b>77.8% was a
 * single call path</b> — {@code StepRequesterNetworkNode.doWork} →
 * {@code AutocraftingNetworkComponentImpl.startTask} →
 * {@code CraftingCalculatorImpl.calculate} → the recursive {@code CraftingTree}.
 * Within that, {@code MutablePatternPlan.copy} alone accounted for 46.5% of the
 * entire server thread, essentially all of it inside {@code HashMap} copy
 * constructors. A concurrent heap summary showed 140,387 live {@code CraftingTree}
 * instances and 2.2M {@code MutableResourceList$OperationResult} objects.
 *
 * <p>Cause: {@code doWork()} runs every tick. For a filter slot whose request
 * cannot currently be satisfied, {@code startTask()} returns empty, the slot is
 * marked {@code NOT_ENOUGH_INGREDIENTS}, and nothing records that the attempt
 * failed. On the next tick the guard conditions are unchanged, so it runs the
 * whole recursive calculation again — 20 times a second, indefinitely.
 *
 * <p>This class originally asserted that the <em>satisfiable</em> path is already
 * cheap — that {@code isAlreadyRunningTask} short-circuits a slot with a task in
 * flight, and so it is specifically the failed attempt that repeats forever.
 * <b>That premise is false</b>, and was disproved on 2026-08-23 in a survival world
 * whose patterns had all been consolidated into a single multiblock provider. Three
 * Step Requesters held 34.8% of the server thread while only <b>45 attempts failed
 * in 100 seconds</b>, and {@code patternPlanCopiesAvoided} rose by 72.6 million in
 * 45 of them. The expensive calculations were <em>succeeding</em> — so each one hit
 * the reset branch below and ran again on the very next tick.
 *
 * <p>The cost is the branching factor, not the outcome.
 * {@code CraftingTree.calculateChild} iterates every pattern that outputs a resource
 * and explores each to exhaustion, copying the plan at every node. A slot asking for
 * a base material — {@code redstone}, {@code silicon}, {@code coal} — has a dozen
 * competing patterns in a large pack, and one successful plan cost roughly 400ms of
 * the server thread. Consolidating patterns into one provider is what exposed it: it
 * is the first time the repository offers the calculator every alternative at once.
 *
 * <p>So backoff is driven by <b>cost, not outcome</b>:
 *
 * <ul>
 *   <li>a craft that can start <em>cheaply</em> is <b>untouched</b>, beginning on
 *       exactly the tick it otherwise would;</li>
 *   <li>a failed slot sleeps {@code stepRequesterFailureBackoffTicks}, doubling on
 *       each consecutive failure up to {@code stepRequesterMaxBackoffTicks};</li>
 *   <li>a slot whose calculation <em>succeeded</em> but took longer than
 *       {@code stepRequesterSlowCalculationMs} sleeps on that same ladder — it still
 *       crafts, just not twenty times a second;</li>
 *   <li>only a fast success resets the slot to no delay at all.</li>
 * </ul>
 *
 * <p>A sleeping slot is skipped in full — no status write, no calculation —
 * because {@link #rstweaks$skipSleepingSlot} hands back {@code null} and the
 * existing {@code resource != null} guard does the rest.
 *
 * <p>The tick hook deliberately lands <em>after</em> the {@code super.doWork()}
 * call rather than at {@code HEAD}: {@code AbstractNetworkNode.doWork()} extracts
 * the node's energy usage from the network, so intervening at HEAD would risk
 * letting a Step Requester run for free. Energy is always drained normally.
 *
 * <p>All three injection points were verified against the actual bytecode of
 * Step Crafter 0.1.5 rather than inferred from source.
 */
@Mixin(StepRequesterNetworkNode.class)
public abstract class StepRequesterNetworkNodeMixin {
    /**
     * The whole backoff decision, in a class with no mixin or Minecraft types so it can be
     * tested. See {@link SlotBackoff} — {@code plannerCheck} cannot exercise a mixin, but it
     * can exercise this.
     */
    @Unique
    private final SlotBackoff rstweaks$backoff = new SlotBackoff();

    /**
     * How far under the configured budget still counts as having hit it. See the comment at
     * the comparison for why an exact test counts nothing at all.
     */
    @Unique
    private static final long TIMEOUT_TOLERANCE_MS = 50L;

    /** Last seen slot configuration, used only to detect a player edit at the cap. */
    @Unique
    private ResourceMinMaxAmount[] rstweaks$snapshot;

    /**
     * Slot currently being processed. Set when {@code doWork} reads the slot and
     * read back when it calls {@code startTask}. Safe because the server tick is
     * single-threaded and the read always immediately precedes that slot's work.
     */
    @Unique
    private int rstweaks$currentSlot = -1;

    @Inject(
        method = "doWork",
        at = @At(
            value = "INVOKE",
            target = "Lcom/refinedmods/refinedstorage/api/network/impl/node/SimpleNetworkNode;doWork()V",
            shift = At.Shift.AFTER
        )
    )
    private void rstweaks$tickBackoff(final CallbackInfo ci) {
        this.rstweaks$backoff.tick();
    }

    @Redirect(
        method = "doWork",
        at = @At(
            value = "INVOKE",
            target = "Lcom/ultramega/stepcrafter/common/support/patternresource/PatternResourceContainerImpl;"
                + "get(I)Lcom/ultramega/stepcrafter/common/support/ResourceMinMaxAmount;"
        )
    )
    private ResourceMinMaxAmount rstweaks$skipSleepingSlot(final PatternResourceContainerImpl container,
                                                        final int slot) {
        this.rstweaks$currentSlot = slot;
        this.rstweaks$ensureCapacity(container.getContainerSize());

        final ResourceMinMaxAmount actual = container.get(slot);

        if (!this.rstweaks$backoff.isSleeping(slot)) {
            this.rstweaks$snapshot[slot] = actual;
            return actual;
        }

        // Only once a slot has escalated all the way to the cap is it worth paying
        // for the edited-in-GUI check. Below the cap the timer expires soon enough
        // on its own that the comparison would be wasted work every tick.
        final int cap = Math.max(
            Config.STEP_REQUESTER_FAILURE_BACKOFF_TICKS.getAsInt(),
            Config.STEP_REQUESTER_MAX_BACKOFF_TICKS.getAsInt()
        );
        if (this.rstweaks$backoff.intervalOf(slot) >= cap
            && this.rstweaks$wasReconfigured(this.rstweaks$snapshot[slot], actual)) {
            this.rstweaks$backoff.reset(slot);
            this.rstweaks$snapshot[slot] = actual;
            return actual;
        }

        ++Stats.stepRequesterScansSkipped;
        return null;
    }

    @Redirect(
        method = "doWork",
        at = @At(
            value = "INVOKE",
            target = "Lcom/refinedmods/refinedstorage/api/network/autocrafting/AutocraftingNetworkComponent;"
                + "startTask(Lcom/refinedmods/refinedstorage/api/resource/ResourceKey;J"
                + "Lcom/refinedmods/refinedstorage/api/storage/Actor;Z"
                + "Lcom/refinedmods/refinedstorage/api/autocrafting/calculation/CancellationToken;)"
                + "Ljava/util/Optional;"
        )
    )
    private Optional<TaskId> rstweaks$recordOutcome(final AutocraftingNetworkComponent component,
                                                  final ResourceKey resource,
                                                  final long amount,
                                                  final Actor actor,
                                                  final boolean notifyListeners,
                                                  final CancellationToken cancellationToken) {
        final long startedAt = System.nanoTime();
        final Optional<TaskId> result =
            component.startTask(resource, amount, actor, notifyListeners, cancellationToken);
        final long elapsedNanos = System.nanoTime() - startedAt;
        final long elapsedMs = elapsedNanos / 1_000_000L;

        // Recorded for every call, backed off or not. 0.2.113 set its threshold from an
        // inference instead of a measurement and was wrong by two orders of magnitude; these
        // make the mean and the worst case readable in game so the next number comes from data.
        ++Stats.stepRequesterCalculations;
        Stats.stepRequesterCalculationNanos += elapsedNanos;
        if (elapsedMs > Stats.stepRequesterSlowestMs) {
            Stats.stepRequesterSlowestMs = elapsedMs;
        }
        // Cancelled at RS's ceiling. Compared against the configured budget rather than a
        // literal 5000 so lowering craftingCalculationTimeoutMs keeps this meaningful.
        //
        // The tolerance is not padding. 0.2.118 tested `>= timeout` exactly and never counted a
        // single one, while the session peak sat at 4,999ms: RS polls its cancellation token
        // rather than interrupting, and the elapsed nanos are truncated by integer division, so
        // a calculation that burned the whole budget measures just UNDER it. Nothing else lands
        // within 50ms of the ceiling by chance.
        final long budgetMs = Config.craftingCalculationTimeoutMs;
        if (budgetMs > 0 && elapsedMs >= budgetMs - TIMEOUT_TOLERANCE_MS) {
            ++Stats.stepRequesterTimeouts;
        }

        // Every branch below lives in SlotBackoff, which plannerCheck can exercise. All that
        // is left here is reading the live config and counting what it decided.
        final SlotBackoff.Outcome outcome = this.rstweaks$backoff.recordOutcome(
            this.rstweaks$currentSlot,
            result.isPresent(),
            elapsedMs,
            Config.STEP_REQUESTER_FAILURE_BACKOFF_TICKS.getAsInt(),
            Config.STEP_REQUESTER_MAX_BACKOFF_TICKS.getAsInt(),
            Config.STEP_REQUESTER_SLOW_CALCULATION_MS.getAsInt(),
            Config.STEP_REQUESTER_BUDGET_PERCENT.getAsInt(),
            Config.STEP_REQUESTER_COST_CAP_TICKS.getAsInt());

        switch (outcome) {
            case FAILED -> ++Stats.stepRequesterFailures;
            case SLOW -> ++Stats.stepRequesterSlowCalculations;
            case RESET -> { }
        }
        return result;
    }

    /**
     * Whether the player changed what this slot is asking for. Deliberately ignores
     * {@code status}, which {@code doWork} rewrites itself and would otherwise report
     * a change on every failure.
     */
    @Unique
    private boolean rstweaks$wasReconfigured(final ResourceMinMaxAmount before,
                                           final ResourceMinMaxAmount after) {
        if (before == after) {
            return false;
        }
        if (before == null || after == null) {
            return true;
        }
        return before.minAmount() != after.minAmount()
            || before.maxAmount() != after.maxAmount()
            || before.batchSize() != after.batchSize()
            || !before.resource().equals(after.resource());
    }

    @Unique
    private void rstweaks$ensureCapacity(final int size) {
        this.rstweaks$backoff.ensureCapacity(size);
        if (this.rstweaks$snapshot == null) {
            this.rstweaks$snapshot = new ResourceMinMaxAmount[size];
        } else if (this.rstweaks$snapshot.length < size) {
            this.rstweaks$snapshot = Arrays.copyOf(this.rstweaks$snapshot, size);
        }
    }
}
