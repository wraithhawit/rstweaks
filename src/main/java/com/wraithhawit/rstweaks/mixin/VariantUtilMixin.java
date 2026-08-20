package com.wraithhawit.rstweaks.mixin;

import com.refinedmods.refinedstorage.neoforge.support.resource.VariantUtil;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Clamps a fluid amount that will not fit in a {@link net.neoforged.neoforge.fluids.FluidStack}
 * instead of letting it wrap negative.
 *
 * <p>Refined Storage carries fluid amounts as {@code long} and {@code FluidStack} carries them
 * as {@code int}, and the conversion between the two truncates:
 *
 * <pre>{@code   public static FluidStack toFluidStack(FluidResource fluidResource, long amount) {
 *       if (amount > 2147483647L) {
 *           LOGGER.warn("Truncating too large amount for {} to fit into FluidStack {}", ...);
 *       }
 *       return new FluidStack(..., (int) amount, ...);
 *   } }</pre>
 *
 * <p>The warning is accurate and the cast is not a truncation - it is a wrap. Above
 * {@code Integer.MAX_VALUE} the sign flips, so 2.2 billion mB arrives as roughly -2.1 billion.
 * Every consumer then reads it as "nothing", or worse.
 *
 * <p>Two places this bites, both found chasing issue #17 against ~2.1 MB of XP fluid behind an
 * External Storage:
 *
 * <ul>
 *   <li>{@code FluidGridResource.canExtract} offers the container the <b>entire network
 *       total</b> and asks whether it would take any of it. Handed a negative stack the
 *       container says no, and the grid concludes your tank cannot hold that fluid at all - so
 *       it cannot be bucketed out of a grid <em>in stock Refined Storage either</em>.</li>
 *   <li>{@code GridExtractMode.ENTIRE_RESOURCE} asks for {@code min(network total,
 *       Long.MAX_VALUE)}, which meets the same conversion on its way to the container, so the
 *       real transfer fails for exactly the resources the check already rejected.</li>
 * </ul>
 *
 * <p>Clamping is correct wherever truncating was not, which is everywhere: no caller wants a
 * negative amount, and every caller that asks for more than an {@code int} can hold is asking
 * for "as much as possible". A transfer is a two-sided negotiation - the container answers with
 * what it will actually take - so offering {@code Integer.MAX_VALUE} rather than the true total
 * loses nothing. Only a single transfer of more than 2.147 billion mB in one operation would
 * notice, and no container in existence has that capacity.
 *
 * <p>Reported upstream. This mixin becomes a no-op that agrees with the code beneath it once
 * Refined Storage clamps, and can be deleted at that version.
 */
@Mixin(VariantUtil.class)
public abstract class VariantUtilMixin {
    /**
     * The {@code amount} parameter, capped at what a {@code FluidStack} can hold.
     *
     * <p>{@code argsOnly} with {@code index = 1} names the parameter rather than a local:
     * {@code toFluidStack} is static, so slot 0 is {@code fluidResource} and slot 1 is
     * {@code amount}. Modifying the argument leaves Refined Storage's warning intact, which is
     * deliberate - it is a true statement about the caller and worth keeping in the log.
     */
    @ModifyVariable(
        method = "toFluidStack",
        at = @At("HEAD"),
        argsOnly = true,
        index = 1,
        require = 0
    )
    private static long rstweaks$clampInsteadOfWrapping(final long amount) {
        return Math.min(amount, Integer.MAX_VALUE);
    }
}
