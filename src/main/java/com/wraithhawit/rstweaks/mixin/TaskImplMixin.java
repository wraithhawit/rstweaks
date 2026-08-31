package com.wraithhawit.rstweaks.mixin;

import com.refinedmods.refinedstorage.api.autocrafting.task.TaskPlan;
import com.refinedmods.refinedstorage.api.autocrafting.task.TaskState;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.api.storage.Actor;
import com.wraithhawit.rstweaks.CraftTimings;
import com.wraithhawit.rstweaks.storage.TaskConsumption;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Works out what the whole task consumes, before its patterns are built.
 *
 * <p>{@code TaskImpl}'s constructor turns the plan into {@code AbstractTaskPattern}s, and
 * that is the only point where the plan and the individual patterns are both in scope. A
 * pattern needs to know whether something it produces is wanted by a sibling — see
 * {@link TaskConsumption} — and this is where it can be told.
 *
 * <p>Cleared at RETURN so nothing leaks into the next task built on this thread.
 *
 * <p>The HEAD handler is {@code static} and the RETURN handler is not, which looks
 * inconsistent and is required: this constructor delegates through {@code this(...)}, and
 * a HEAD injection therefore runs before the instance exists. Mixin rejects a non-static
 * handler there outright — {@code "@At(\"HEAD\") selector @Inject handler before this()
 * invocation must be static"} — and it rejects it at class-load, which for this class
 * means the first time anybody starts a craft, not at startup.
 */
@Mixin(targets = "com.refinedmods.refinedstorage.api.autocrafting.task.TaskImpl")
public abstract class TaskImplMixin {
    @Inject(
        method = "<init>(Lcom/refinedmods/refinedstorage/api/autocrafting/task/TaskPlan;"
            + "Lcom/refinedmods/refinedstorage/api/storage/Actor;Z)V",
        at = @At("HEAD")
    )
    private static void rstweaks$captureConsumption(final TaskPlan plan,
                                                   final Actor actor,
                                                   final boolean notify,
                                                   final CallbackInfo ci) {
        TaskConsumption.beginBuilding(TaskConsumption.of(plan));
    }

    @Inject(
        method = "<init>(Lcom/refinedmods/refinedstorage/api/autocrafting/task/TaskPlan;"
            + "Lcom/refinedmods/refinedstorage/api/storage/Actor;Z)V",
        at = @At("RETURN")
    )
    private void rstweaks$clearConsumption(final TaskPlan plan,
                                          final Actor actor,
                                          final boolean notify,
                                          final CallbackInfo ci) {
        TaskConsumption.endBuilding();
        this.rstweaks$startedNanos = System.nanoTime();
    }

    @Shadow
    public abstract ResourceKey getResource();

    @Shadow
    public abstract long getAmount();

    /**
     * Whether the task was called off rather than finishing.
     *
     * <p>Declared on {@code TaskImpl} itself, so shadowing it is safe -- an inherited field would
     * fail at APPLY time and take the task engine with it.
     */
    @Shadow
    private boolean cancelled;

    /**
     * When this task was built, so its wall-clock duration can be reported when it finishes.
     *
     * <p>{@code TaskImpl} keeps a {@code startTime} of its own, but it is milliseconds from
     * {@code System.currentTimeMillis()} and is used for the status display. {@code nanoTime} is the
     * one to measure an elapsed interval with — it is monotonic, so a clock adjustment mid-craft
     * cannot produce a negative duration.
     *
     * <p>Primitive with no initializer, deliberately: Mixin does not run field initialisers
     * reliably, and a {@code long} defaults to zero without one.
     */
    @Unique
    private long rstweaks$startedNanos;

    /** So a task that reaches COMPLETED more than once is only timed the first time. */
    @Unique
    private boolean rstweaks$timed;

    /**
     * Times the craft, because a share of the server thread cannot.
     *
     * <p>Every performance figure this mod has produced is a percentage of {@code tickNode}, and the
     * crafter has no time budget — it expands to fill the tick, so making a step cheaper raises the
     * number of steps rather than lowering the share. Wall-clock time for a known craft is the one
     * measurement that compares two builds without any of that.
     *
     * <p>Hooked on {@code updateState} rather than on a listener because every completion path in
     * {@code TaskImpl} goes through it. That includes a CANCELLED task, which is why the two are
     * told apart here rather than both being reported as crafts.
     */
    @Inject(method = "updateState", at = @At("HEAD"))
    private void rstweaks$timeCraft(final TaskState state, final CallbackInfo ci) {
        if (state != TaskState.COMPLETED || this.rstweaks$timed
            || this.rstweaks$startedNanos == 0L) {
            return;
        }
        this.rstweaks$timed = true;
        final long millis = (System.nanoTime() - this.rstweaks$startedNanos) / 1_000_000L;
        final String resource = CraftTimings.shorten(String.valueOf(getResource()));
        if (this.cancelled) {
            // A cancelled task reaches COMPLETED like any other, and 0.19.0 recorded it as a
            // finished craft: "1,000,000 x insanium in 56s" for a craft that was called off after
            // a fraction of it. That number is not wrong so much as meaningless -- the amount is
            // what was ASKED for, not what was made -- and worse, it evicted a real benchmark entry
            // from a history that only keeps eight.
            CraftTimings.recordCancelled(resource, getAmount(), millis);
            return;
        }
        CraftTimings.record(resource, getAmount(), millis);
    }
}
