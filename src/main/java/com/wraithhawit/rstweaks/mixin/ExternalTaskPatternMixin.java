package com.wraithhawit.rstweaks.mixin;

import com.refinedmods.refinedstorage.api.autocrafting.Pattern;
import com.refinedmods.refinedstorage.api.autocrafting.task.ExternalPatternSink;
import com.refinedmods.refinedstorage.api.core.Action;
import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.wraithhawit.rstweaks.Config;
import com.wraithhawit.rstweaks.Stats;
import com.wraithhawit.rstweaks.RSTweaks;
import com.wraithhawit.rstweaks.sink.SinkCacheVerifier;
import com.wraithhawit.rstweaks.sink.SinkRejectionCache;

import java.util.Collection;

import javax.annotation.Nullable;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Stops a crafting task re-asking every sink the same question for the whole of one tick.
 *
 * <p>Diagnosis, from a 60-second server-thread profile of a survival world with a large rsmbac
 * multiblock and mekmm Stamping Factories attached:
 * {@code ExternalTaskPattern.getSinkThatIsAcceptingResources} was <b>35.47% of the server
 * thread</b> with 28ms of self time -- all of it in the sinks it was probing, and almost all of
 * <em>that</em> in Mekanism's {@code BasicInventorySlot.isItemValidForInsertion}, which for a
 * mekmm factory runs a full recipe lookup per probe.
 *
 * <p>Cause: {@code getSinkThatIsAcceptingResources} walks every sink, simulating a whole
 * {@code accept} on each, and returns null only once the list is exhausted -- so a tick in which
 * every machine is busy costs a full sweep. And {@code step()} is not called once per tick:
 * {@code TaskImpl.stepPatterns} runs it {@code stepBehavior.getSteps(pattern)} times, a budget
 * rsmbac derives from the multiblock's CPU tiers. The cost of a busy tick is therefore
 * <b>steps x sinks x validation</b>, and every sweep after the first asks the same question with
 * the same inputs and is told the same thing.
 *
 * <p>Fix: remember, for the length of that burst, which sinks have already refused, and skip them.
 * The reasoning about what may be cached and for how long lives in {@link SinkRejectionCache};
 * the short version is that only refusals are ever stored, an acceptance empties the cache because
 * the caller is about to insert, and nothing survives into another tick.
 *
 * <p><b>Deliberately above the sink rather than inside one.</b> The measured cost here happens to
 * be Mekanism's, but nothing in this mixin knows or cares -- it helps any sink whose {@code accept}
 * is expensive, including Sophisticated barrels and drawer controllers, and needs no cooperation
 * from the mod that owns the machine.
 *
 * <p>The {@code copyState()} call that builds this probe's argument list still runs even when the
 * probe itself is skipped, because it is evaluated before the call this redirects. It measured
 * inside the 524ms that was not {@code accept}, against 20,744ms that was, so it is left alone
 * rather than reached with a second and more fragile injection.
 *
 * <p>Scoped to {@code getSinkThatIsAcceptingResources} on purpose. {@code ExternalTaskPattern} has
 * a second {@code accept} call, the {@code EXECUTE} one in {@code acceptsIterationInputs}, and
 * that one must never be answered from a cache. Verified against Refined Storage 2.0.9 bytecode:
 * exactly one {@code ExternalPatternSink.accept} call inside this method, at offset 59.
 *
 * <p>Targeted by name because {@code ExternalTaskPattern} is package-private, the same reason
 * {@link AbstractTaskPatternMixin} and {@code MutablePatternPlanMixin} are.
 */
@Mixin(targets = "com.refinedmods.refinedstorage.api.autocrafting.task.ExternalTaskPattern")
public abstract class ExternalTaskPatternMixin {
    /**
     * Lazily created: Mixin's handling of instance field initializers on {@code @Unique} fields is
     * unreliable, the same reason {@code MutablePatternPlanMixin} creates its set on first use.
     */
    @Unique
    @Nullable
    private SinkRejectionCache rstweaks$sinkCache;

    @Redirect(
        method = "getSinkThatIsAcceptingResources",
        at = @At(
            value = "INVOKE",
            target = "Lcom/refinedmods/refinedstorage/api/autocrafting/task/ExternalPatternSink;"
                + "accept(Lcom/refinedmods/refinedstorage/api/autocrafting/Pattern;"
                + "Ljava/util/Collection;Lcom/refinedmods/refinedstorage/api/core/Action;)"
                + "Lcom/refinedmods/refinedstorage/api/autocrafting/task/ExternalPatternSink$Result;"
        )
    )
    private ExternalPatternSink.Result rstweaks$skipKnownRefusal(
        final ExternalPatternSink sink,
        final Pattern pattern,
        final Collection<ResourceAmount> resources,
        final Action action
    ) {
        if (!Config.cacheSinkRejections) {
            return sink.accept(pattern, resources, action);
        }
        if (this.rstweaks$sinkCache == null) {
            this.rstweaks$sinkCache = new SinkRejectionCache();
        }
        final Object remembered = this.rstweaks$sinkCache.cachedRejection(sink);
        if (remembered != null) {
            if (!Config.verifySinkCache) {
                ++Stats.sinkProbesSkipped;
                return (ExternalPatternSink.Result) remembered;
            }
            // Verification runs the probe the cache exists to avoid and returns ITS answer, so
            // the cache is checked without ever being acted on. See SinkCacheVerifier.
            final ExternalPatternSink.Result live = sink.accept(pattern, resources, action);
            final String line = SinkCacheVerifier.note(
                remembered,
                live,
                String.valueOf(sink),
                String.valueOf(pattern),
                String.valueOf(resources),
                SinkRejectionCache.currentBurst(),
                Thread.currentThread().getName());
            if (line != null) {
                RSTweaks.LOGGER.warn("[rstweaks] {}", line);
            }
            return live;
        }
        final ExternalPatternSink.Result result = sink.accept(pattern, resources, action);
        this.rstweaks$sinkCache.record(sink, result, result == ExternalPatternSink.Result.ACCEPTED);
        return result;
    }
}
