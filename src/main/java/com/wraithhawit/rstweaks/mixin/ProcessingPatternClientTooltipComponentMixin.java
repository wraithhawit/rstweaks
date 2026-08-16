package com.wraithhawit.rstweaks.mixin;

import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.common.api.RefinedStorageClientApi;
import com.refinedmods.refinedstorage.common.api.support.resource.ResourceRendering;
import com.refinedmods.refinedstorage.common.autocrafting.ProcessingPatternState;
import com.refinedmods.refinedstorage.common.support.ResourceSlotRendering;
import com.refinedmods.refinedstorage.common.support.Sprites;
import com.wraithhawit.rstweaks.Config;
import com.wraithhawit.rstweaks.storage.FluidSubstitutionMark;
import com.wraithhawit.rstweaks.storage.FluidSwap;

import javax.annotation.Nullable;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Draws a fluid substitution pattern as what it is: a container on one side, its contents on the
 * other, and an arrow that goes both ways.
 *
 * <p>Refined Storage renders every processing pattern as its matrix, which for a swap means a row
 * of three cells with two of them blank, then an arrow, then three more cells with one blank.
 * Everything in it is true and almost none of it is the point.
 *
 * <p>The arrow still points one way, which understates a swap that registers its mirror. See the
 * note further down for what was tried and why it is not simply a matter of drawing it twice.
 *
 * <p>Only the drawing changes. The pattern is still a processing pattern, still encoded the same
 * way, and anything that is not a swap falls through to Refined Storage's own rendering on the
 * first line of each hook.
 *
 * <p>Targeted by string because the class is package-private.
 */
@Mixin(targets = "com.refinedmods.refinedstorage.common.autocrafting.ProcessingPatternClientTooltipComponent")
public abstract class ProcessingPatternClientTooltipComponentMixin {
    /**
     * The swap this pattern describes, or {@code null} for an ordinary processing pattern, which is
     * every hook's cue to leave well alone.
     *
     * <p>Resolved once in the constructor rather than per frame: a tooltip is rebuilt every frame
     * it is hovered, and detection asks the container's fluid handler capability.
     */
    @Unique
    @Nullable
    private FluidSwap.Swap rstweaks$swap;

    /**
     * Drawn as a swap only if the network would actually treat it as one.
     *
     * <p>The contents test alone is not that question any more. Since 0.2.66 an unmarked pattern is
     * an ordinary processing pattern — named one, resolved as one — so drawing it as a container and
     * a fluid would contradict both. The mark is looked up by contents because this constructor is
     * given nothing else; see {@link FluidSubstitutionMark#rememberMarked}.
     */
    @Inject(method = "<init>", at = @At("RETURN"))
    private void rstweaks$detectSwap(final ProcessingPatternState state, final CallbackInfo ci) {
        if (Config.fluidSubstitutionPatterns
            && FluidSubstitutionMark.mayConvert(FluidSubstitutionMark.wasMarked(state))) {
            this.rstweaks$swap = FluidSwap.detect(state);
        }
    }

    @Inject(method = "getHeight", at = @At("HEAD"), cancellable = true)
    private void rstweaks$height(final CallbackInfoReturnable<Integer> cir) {
        if (this.rstweaks$swap != null) {
            cir.setReturnValue(RSTWEAKS_HEIGHT);
        }
    }

    @Inject(method = "getWidth", at = @At("HEAD"), cancellable = true)
    private void rstweaks$width(final Font font, final CallbackInfoReturnable<Integer> cir) {
        if (this.rstweaks$swap != null) {
            cir.setReturnValue(RSTWEAKS_SLOT + RSTWEAKS_GAP + Sprites.LIGHT_ARROW_WIDTH
                + RSTWEAKS_GAP + RSTWEAKS_SLOT);
        }
    }

    @Inject(method = "renderImage", at = @At("HEAD"), cancellable = true)
    private void rstweaks$renderSwap(final Font font,
                                     final int x,
                                     final int y,
                                     final GuiGraphics graphics,
                                     final CallbackInfo ci) {
        final FluidSwap.Swap swap = this.rstweaks$swap;
        if (swap == null) {
            return;
        }
        rstweaks$slot(graphics, x, y, swap.filled(), 1L);

        final int arrowX = x + RSTWEAKS_SLOT + RSTWEAKS_GAP;
        final int arrowY = y + (RSTWEAKS_SLOT - Sprites.LIGHT_ARROW_HEIGHT) / 2;
        graphics.blitSprite(Sprites.LIGHT_ARROW, arrowX, arrowY,
            Sprites.LIGHT_ARROW_WIDTH, Sprites.LIGHT_ARROW_HEIGHT);

        rstweaks$slot(graphics, arrowX + Sprites.LIGHT_ARROW_WIDTH + RSTWEAKS_GAP, y,
            swap.fluid(), swap.amount());
        ci.cancel();
    }

    /*
     * The arrow points one way, and a swap goes both. That is a known shortcoming, not an
     * oversight.
     *
     * Drawing a second copy mirrored was tried and does not render: pushing the pose, translating
     * to the arrow's far edge and applying scale(-1, 1, 1) draws nothing at all. Almost certainly
     * the negative scale reverses the quad's winding and the GUI render type culls it -- the same
     * trap mirrored blits usually fall into. Do not simply retry it.
     *
     * Whoever picks this up has three routes, in rough order of effort: disable culling around the
     * mirrored draw; emit the quad directly with its vertices reversed instead of going through
     * blitSprite; or ship a two-way sprite of our own, which is the only one guaranteed to work and
     * the only one that has to be restyled by hand if Refined Storage ever changes its arrow art.
     */

    /** One slot, drawn the way the matrix draws one: the sprite, then the resource inset by a pixel. */
    @Unique
    private static void rstweaks$slot(final GuiGraphics graphics,
                                      final int x,
                                      final int y,
                                      final ResourceKey resource,
                                      final long amount) {
        graphics.blitSprite(Sprites.SLOT, x, y, RSTWEAKS_SLOT, RSTWEAKS_SLOT);
        final ResourceRendering rendering =
            RefinedStorageClientApi.INSTANCE.getResourceRendering(resource.getClass());
        rendering.render(resource, graphics, x + 1, y + 1);
        ResourceSlotRendering.renderAmount(graphics, x + 1, y + 1, amount, rendering);
    }

    @Unique
    private static final int RSTWEAKS_SLOT = 18;

    @Unique
    private static final int RSTWEAKS_GAP = 3;

    /** A slot tall: the arrow is shorter and sits centred against it. */
    @Unique
    private static final int RSTWEAKS_HEIGHT = RSTWEAKS_SLOT;
}
