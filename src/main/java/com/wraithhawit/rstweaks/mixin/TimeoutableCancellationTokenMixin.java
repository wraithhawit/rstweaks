package com.wraithhawit.rstweaks.mixin;

import com.refinedmods.refinedstorage.api.network.impl.autocrafting.TimeoutableCancellationToken;

import com.wraithhawit.rstweaks.Config;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * Makes Refined Storage's crafting calculation timeout configurable.
 *
 * <p>RS gives a craft request five seconds: {@code TimeoutableCancellationToken.TIMEOUT_MS = 5000}.
 * That is five seconds <em>of the server thread</em> -- a hundred ticks in which nothing else in the
 * world happens -- and a request that is going to fail spends all of it before saying so.
 *
 * <p>It is the direct cause of a multi-second freeze on a network where something keeps asking for a
 * craft that cannot be made. {@code boundCraftableSearch} took the doubling search out of that
 * budget; this is the budget itself.
 *
 * <h2>Why the default does not change anything</h2>
 *
 * <p>Shortening this cancels calculations that were simply <em>slow</em>, not impossible, and a
 * genuinely large craft on a big network can legitimately take a while. A cancelled calculation
 * reports {@code MISSING_RESOURCES}, which is indistinguishable from "cannot be made" -- so too low
 * a value silently refuses crafts that would have worked.
 *
 * <p>rstweaks has made that mistake before, capping a solver and quietly blocking late-game crafts,
 * so the default here is RS's own 5000 and this changes nothing until someone chooses otherwise.
 * Lowering it is a trade a server owner should make deliberately, knowing what it costs.
 */
@Mixin(TimeoutableCancellationToken.class)
public abstract class TimeoutableCancellationTokenMixin {
    /**
     * The literal in {@code isCancelled}, not the field initialiser: the field is only read for the
     * log line, and the comparison is what actually bounds the work.
     */
    @ModifyConstant(method = "isCancelled", constant = @Constant(longValue = 5000L), require = 1)
    private long rstweaks$configurableTimeout(final long original) {
        return Config.craftingCalculationTimeoutMs;
    }
}
