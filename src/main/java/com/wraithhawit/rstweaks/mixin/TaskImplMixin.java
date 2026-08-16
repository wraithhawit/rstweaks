package com.wraithhawit.rstweaks.mixin;

import com.refinedmods.refinedstorage.api.autocrafting.task.TaskPlan;
import com.refinedmods.refinedstorage.api.storage.Actor;
import com.wraithhawit.rstweaks.storage.TaskConsumption;

import org.spongepowered.asm.mixin.Mixin;
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
    }
}
