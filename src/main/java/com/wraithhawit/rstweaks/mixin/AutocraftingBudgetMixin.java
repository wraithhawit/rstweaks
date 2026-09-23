package com.wraithhawit.rstweaks.mixin;

import com.refinedmods.refinedstorage.api.autocrafting.calculation.CancellationToken;
import com.refinedmods.refinedstorage.api.autocrafting.calculation.CraftingCalculator;
import com.refinedmods.refinedstorage.api.autocrafting.task.TaskPlan;
import com.refinedmods.refinedstorage.api.autocrafting.task.TaskPlanCraftingCalculatorListener;
import com.refinedmods.refinedstorage.api.network.impl.autocrafting.AutocraftingNetworkComponentImpl;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.wraithhawit.rstweaks.Config;
import com.wraithhawit.rstweaks.ensure.AutomationBudget;
import com.wraithhawit.rstweaks.pattern.CalculationTrace;

import java.util.Optional;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Puts automation crafting calculations on the automation budget instead of a player-sized one.
 *
 * <p>0.22.3 bounded what a Step Requester may spend planning and took it from 36.62% of the server
 * thread to 1.27%. That budget reaches {@code startTask} only. An Exporter or an Interface with an
 * autocrafting upgrade asks through {@code ensureTask}, and everything down that path runs on RS's
 * full {@code craftingCalculationTimeoutMs} -- five seconds, a hundred ticks in which nothing else
 * in the world happens.
 *
 * <p>Measured once the Step Requester stopped dominating the same world:
 * {@code ExporterNetworkNode.doWork} at <b>67.25% of the server thread</b>, TPS 4.3, worst tick
 * 7,052ms, with 13.78% of the thread in the craftable-amount search alone. A failing automated
 * request runs up to three complete crafting calculations -- {@code calculatePlan}, the binary
 * search inside {@code ensureTaskForCraftableAmount}, then {@code calculatePlan} again for the
 * amount it found.
 *
 * <p><b>One request owns one deadline.</b> The token is created at the first calculation and held
 * in {@link AutomationBudget} for the rest of the request; the later calculations ask for it back
 * rather than making their own. Bounding each calculation separately was the first attempt and is
 * not the same thing -- see that class for what review found wrong with it, including a false
 * negative that could suppress a craftable resource for {@code uncraftableRecheckTicks}.
 *
 * <p><b>A player is never affected.</b> Clicking craft goes through {@code startTask}, which this
 * does not touch. Every caller of {@code ensureTask} is automation:
 * {@code MissingResourcesListeningExporterTransferStrategy}, {@code InterfaceNetworkNode} and
 * {@code AutocraftOnMissingResourcesConstructorStrategy}. None of them waits on the answer -- they
 * ask again on a schedule regardless, so refusing one costs a retry while freezing the world costs
 * everyone. That is the argument 0.22.3 made and then measured.
 *
 * <p>A second mixin on the same target rather than more methods in
 * {@code AutocraftingNetworkComponentImplMixin}: that class is the uncraftable cache and the
 * duplicate-request guard, and this is a budget. They meet at two points, and both are edits to
 * that class rather than to this one -- the craftable-amount search token, which it already
 * substitutes and which cannot be redirected twice, and the end of the request, where its existing
 * RETURN hook now also finishes the budget and declines to cache a result we cut short.
 *
 * <p>Verified against Refined Storage 2.0.9 bytecode. The target holds three {@code calculatePlan}
 * call sites, one per enclosing method: {@code startTask} (left alone -- the player's path and the
 * Step Requester's), {@code ensureTask}, and {@code ensureTaskForCraftableAmount}.
 *
 * <p>The trace starts here too. {@code CalculationTrace} could previously only describe a Step
 * Requester stall, so an exporter burning thirty-five seconds went unexplained -- and worse, its
 * tree nodes were counted into whatever trace a Step Requester had last begun.
 */
@Mixin(AutocraftingNetworkComponentImpl.class)
public abstract class AutocraftingBudgetMixin {

    @Redirect(
        method = "ensureTask",
        at = @At(
            value = "INVOKE",
            target = "Lcom/refinedmods/refinedstorage/api/autocrafting/task/"
                + "TaskPlanCraftingCalculatorListener;calculatePlan("
                + "Lcom/refinedmods/refinedstorage/api/autocrafting/calculation/CraftingCalculator;"
                + "Lcom/refinedmods/refinedstorage/api/resource/ResourceKey;J"
                + "Lcom/refinedmods/refinedstorage/api/autocrafting/calculation/CancellationToken;)"
                + "Ljava/util/Optional;"
        )
    )
    private Optional<TaskPlan> rstweaks$budgetEnsurePlan(final CraftingCalculator calculator,
                                                         final ResourceKey resource,
                                                         final long amount,
                                                         final CancellationToken token) {
        return rstweaks$plan(calculator, resource, amount, token, "plan");
    }

    @Redirect(
        method = "ensureTaskForCraftableAmount",
        at = @At(
            value = "INVOKE",
            target = "Lcom/refinedmods/refinedstorage/api/autocrafting/task/"
                + "TaskPlanCraftingCalculatorListener;calculatePlan("
                + "Lcom/refinedmods/refinedstorage/api/autocrafting/calculation/CraftingCalculator;"
                + "Lcom/refinedmods/refinedstorage/api/resource/ResourceKey;J"
                + "Lcom/refinedmods/refinedstorage/api/autocrafting/calculation/CancellationToken;)"
                + "Ljava/util/Optional;"
        )
    )
    private Optional<TaskPlan> rstweaks$budgetCraftablePlan(final CraftingCalculator calculator,
                                                            final ResourceKey resource,
                                                            final long amount,
                                                            final CancellationToken token) {
        return rstweaks$plan(calculator, resource, amount, token, "clamped plan");
    }

    /**
     * One phase of the request. It asks {@link AutomationBudget} for the request's token rather
     * than making one, times itself for the log, and moves nothing: the ladder, the counters and
     * the verdict all belong to the end of the request.
     *
     * <p>Calling {@code calculatePlan} by name here is safe: a {@code @Redirect} rewrites the
     * matched instruction inside the named target method only, and this is not that method.
     */
    @Unique
    private static Optional<TaskPlan> rstweaks$plan(final CraftingCalculator calculator,
                                                    final ResourceKey resource,
                                                    final long amount,
                                                    final CancellationToken token,
                                                    final String what) {
        if (!Config.budgetEnsureTaskCalculations) {
            return TaskPlanCraftingCalculatorListener.calculatePlan(
                calculator, resource, amount, token);
        }
        final String key = String.valueOf(resource);
        if (Config.traceSlowCalculations) {
            CalculationTrace.begin(key, amount);
        }
        final CancellationToken budgeted = AutomationBudget.tokenFor(key, token);
        final long startedAt = System.nanoTime();
        final Optional<TaskPlan> result = TaskPlanCraftingCalculatorListener.calculatePlan(
            calculator, resource, amount, budgeted);
        AutomationBudget.notePhase(key, what, startedAt);
        return result;
    }
}
