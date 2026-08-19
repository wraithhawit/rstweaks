package com.wraithhawit.rstweaks;

import com.refinedmods.refinedstorage.api.resource.ResourceKey;

import java.util.HashSet;
import java.util.Set;

/**
 * Bookkeeping for {@code MutableResourceListEntryMixin}, kept out of the mixin on purpose.
 *
 * <p>A mixin's static state is merged into the target class, which means its static
 * initializer is merged into that class's {@code <clinit>}. That works often enough to be
 * tempting and fails in ways that are hard to read when it does not. The mixin here has to be
 * reliable above all else — it is the thing standing between a network and a crash loop — so
 * it holds no state at all and calls this instead.
 *
 * <p>Not thread safe, deliberately. Every caller is a storage mutation on the server thread,
 * and the counter next door in {@link Stats} makes the same trade for the same reason.
 */
public final class ResourceListOverflow {
    /**
     * Resources already reported. A network in this state overflows on every rebuild and
     * often every tick, so the log has to say it once and then stop.
     */
    private static final Set<ResourceKey> REPORTED = new HashSet<>();

    /**
     * Capped because the set is keyed by something a mod supplies. Sixty-four distinct
     * resources overflowing is already far past the point where the first line was the useful
     * one, and this is diagnostics — it must not be able to grow without bound.
     */
    private static final int MAX_REPORTED = 64;

    private ResourceListOverflow() {
    }

    /** Records one clamped total and names the resource the first time each one appears. */
    public static void clamped(final ResourceKey resource, final long amountToIncrement) {
        ++Stats.resourceAmountOverflowsClamped;
        if (REPORTED.size() >= MAX_REPORTED || !REPORTED.add(resource)) {
            return;
        }
        RSTweaks.LOGGER.warn(
            "The network's cached total for {} overflowed past Long.MAX_VALUE (adding {}) and"
                + " has been clamped. Left to wrap it would have gone negative, and the next"
                + " read of the storage list -- opening any grid, or planning any autocraft --"
                + " would have crashed the server. Something on this network reports an enormous"
                + " amount of this resource; if it is energy or another addon resource type,"
                + " look for an External Storage on a creative or very large buffer.",
            resource, amountToIncrement
        );
    }

    /** Test seam: lets a self-test assert on the first report rather than on a quiet no-op. */
    public static void forgetReported() {
        REPORTED.clear();
    }
}
