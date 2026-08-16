package com.wraithhawit.rstweaks;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * A monotonic server tick counter.
 *
 * <p>Network components live in Refined Storage's API layer and have no access to
 * the level or the server, so they cannot ask what tick it is. Optimizations that
 * need to expire a cached result therefore need a clock from outside.
 *
 * <p>Deliberately ticks rather than wall time: when the server is lagging, a
 * tick-based backoff stretches with it, which is the behaviour we want — the
 * whole point is to do less work per tick when the server is struggling.
 *
 * <p>{@code long} rather than {@code int} so it never wraps; an {@code int} would
 * overflow after roughly three and a half years of continuous uptime and quietly
 * invert every comparison against it.
 */
public final class ServerTicks {
    private static long current;

    private ServerTicks() {
    }

    public static long current() {
        return current;
    }

    @SubscribeEvent
    public static void onServerTick(final ServerTickEvent.Post event) {
        ++current;
    }
}
