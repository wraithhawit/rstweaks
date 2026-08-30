package com.wraithhawit.rstweaks.mixin;

import com.refinedmods.refinedstorage.api.autocrafting.task.ExternalPatternSinkProvider;
import com.refinedmods.refinedstorage.api.autocrafting.task.TaskListener;
import com.refinedmods.refinedstorage.api.resource.list.MutableResourceList;
import com.refinedmods.refinedstorage.api.storage.root.RootStorage;

import com.wraithhawit.rstweaks.Config;
import com.wraithhawit.rstweaks.RSTweaks;
import com.wraithhawit.rstweaks.Stats;
import com.wraithhawit.rstweaks.storage.VersionedResourceList;

import javax.annotation.Nullable;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Skips a crafting step that has already been proven to do nothing.
 *
 * <h2>Why this is worth doing at all</h2>
 *
 * <p>Caching inside the substitution has run out of room. Profile {@code L7lGOu8YyD}:
 * {@code rstweaks$replayDecision} alone is 10.42% of the server thread for 236,864,784 replays —
 * about 53ns each, on a method called <b>2.7 million times a second</b>. At that volume any per-call
 * work costs seconds, so the next win cannot come from making the call cheaper. It has to come from
 * not making the call.
 *
 * <p>{@code InternalTaskPattern.step} returns {@code IDLE} the instant {@code extractAll(SIMULATE)}
 * is false, having done nothing else. When nothing has changed since such a step, it will reach the
 * same conclusion by the same route — so returning {@code IDLE} up front is not an approximation of
 * what Refined Storage does, it is exactly what it does. Skipping it also skips
 * {@code calculateIterationInputs} (28.24% inclusive) and {@code extractAll} (34.56%), neither of
 * which we could otherwise touch.
 *
 * <h2>Why a failed simulate has no side effects</h2>
 *
 * <p>Checked against the 2.0.9 bytecode rather than assumed, because the whole idea rests on it:
 *
 * <ul>
 *   <li>{@code calculateIterationInputs}' mutating branch is gated on {@code Action.EXECUTE} — the
 *       simulate pass reads the ingredient budget and writes nothing;</li>
 *   <li>{@code step} returns {@code IDLE} before touching outputs, byproducts, wear or the root
 *       storage;</li>
 *   <li>{@code TaskImpl.stepPatterns} only asks whether the result is {@code COMPLETED} and whether
 *       {@code isChanged()}, so {@code IDLE} is its ordinary "nothing happened" path.</li>
 * </ul>
 *
 * <h2>How it returns a value it cannot name</h2>
 *
 * <p>{@code PatternStepResult} is package-private, so this class cannot reference the type or its
 * {@code IDLE} constant. It does not need to: it <b>captures the instance</b> the first time a real
 * step returns one, recognising it by {@link Enum#name()}, and hands that same object back later.
 *
 * <p>That is fail-safe in the direction that matters. Until an {@code IDLE} has actually been
 * observed there is nothing to return and nothing is ever skipped, and if Refined Storage ever
 * renames the constant the recognition simply stops matching and the optimization disappears rather
 * than misbehaving.
 */
@Mixin(targets = "com.refinedmods.refinedstorage.api.autocrafting.task.InternalTaskPattern")
public abstract class StepSkipMixin {
    /**
     * The {@code IDLE} instance, captured from a real return. Null until one has been seen.
     *
     * <p>No initializer and null-safe, for the reason {@code AbstractTaskPatternMixin} records at
     * length: Mixin does not run field initialisers reliably, and this project destroyed a player's
     * items over exactly that.
     */
    @Unique
    @Nullable
    private Object rstweaks$idleResult;

    /** Storage version at which this pattern was last proven to do nothing. */
    @Unique
    private long rstweaks$idleAtVersion;

    /** Whether {@link #rstweaks$idleAtVersion} means anything yet. */
    @Unique
    private boolean rstweaks$idleValid;

    /** Set at HEAD while verifying, read at RETURN, so the check compares like for like. */
    @Unique
    private boolean rstweaks$wouldHaveSkipped;

    @Inject(method = "step", at = @At("HEAD"), cancellable = true)
    private void rstweaks$skipUnchangedStep(final MutableResourceList internalStorage,
                                            final RootStorage rootStorage,
                                            final ExternalPatternSinkProvider sinks,
                                            final TaskListener listener,
                                            final CallbackInfoReturnable<Object> cir) {
        final boolean skippable = rstweaks$canSkip(internalStorage);
        this.rstweaks$wouldHaveSkipped = skippable;
        if (!skippable) {
            return;
        }
        if (Config.verifyStepSkip) {
            // The verifier deliberately lets the step run so the RETURN hook can find out whether
            // it really would have done nothing. Cancelling and then checking the cancelled value
            // would be asking the cache about itself, which is the mistake every probe in this mod
            // is shaped to avoid.
            return;
        }
        Stats.stepsSkipped++;
        cir.setReturnValue(this.rstweaks$idleResult);
    }

    @Unique
    private boolean rstweaks$canSkip(final MutableResourceList internalStorage) {
        return Config.skipUnchangedSteps
            && this.rstweaks$idleValid
            && this.rstweaks$idleResult != null
            && internalStorage instanceof VersionedResourceList versioned
            && versioned.rstweaks$version() == this.rstweaks$idleAtVersion;
    }

    @Inject(method = "step", at = @At("RETURN"))
    private void rstweaks$rememberIdleStep(final MutableResourceList internalStorage,
                                           final RootStorage rootStorage,
                                           final ExternalPatternSinkProvider sinks,
                                           final TaskListener listener,
                                           final CallbackInfoReturnable<Object> cir) {
        final Object result = cir.getReturnValue();
        final boolean idle = result instanceof Enum<?> constant && "IDLE".equals(constant.name());
        if (Config.verifyStepSkip && this.rstweaks$wouldHaveSkipped) {
            Stats.stepSkipsVerified++;
            if (!idle) {
                Stats.stepSkipsDiverged++;
                if (Stats.stepSkipsDiverged <= DIVERGENCES_LOGGED_MAX) {
                    RSTweaks.LOGGER.warn("[rstweaks] step skip would have been WRONG (#{}): the "
                        + "step returned {} where the skip would have returned IDLE",
                        Stats.stepSkipsDiverged, result);
                }
            }
        }
        this.rstweaks$wouldHaveSkipped = false;
        if (idle && internalStorage instanceof VersionedResourceList versioned) {
            this.rstweaks$idleResult = result;
            this.rstweaks$idleAtVersion = versioned.rstweaks$version();
            this.rstweaks$idleValid = true;
            return;
        }
        // Anything that is not an idle step invalidates: the pattern did work, so its ingredient
        // budget and iteration count have moved and nothing about the previous answer still holds.
        this.rstweaks$idleValid = false;
    }

    /** Enough to see a pattern, few enough that a burst cannot flood the log. */
    @Unique
    private static final int DIVERGENCES_LOGGED_MAX = 20;
}
