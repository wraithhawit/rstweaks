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

    /**
     * How many more crafting iterations batched stepping may run this tick.
     *
     * <p><b>This exists because leaving it out froze a world for 114 seconds.</b>
     * {@code TaskImpl.stepPattern} runs {@code for (i = 0; i < steps; i++) pattern.step(...)}, and
     * {@code steps} is Refined Storage's throughput budget — a multiblock crafter hands it around
     * 10<sup>5</sup>. A batch made one of those calls do up to a thousand iterations while still
     * consuming a single step, so the per-tick work was multiplied rather than made cheaper, and a
     * single tick took 114,516 ms.
     *
     * <p>The batching mixin cannot see {@code steps} from where it hooks, so the bound lives here
     * instead: a budget refilled once per tick and drawn down by every batch, across every task.
     * When it runs out, batching stands down and Refined Storage's own stepping continues, which is
     * bounded by {@code steps} as it always was. Fewer extractions for the same work is the point;
     * more work per tick never was.
     */
    public static int batchBudget() {
        return batchBudget;
    }

    /** Draws from this tick's batch budget. */
    public static void spendBatchBudget(final long iterations) {
        batchBudget = (int) Math.max(0L, batchBudget - iterations);
    }

    private static int batchBudget;

    @SubscribeEvent
    public static void onServerTick(final ServerTickEvent.Post event) {
        ++current;
        batchBudget = Config.maxBatchedIterationsPerTick;
    }
}
