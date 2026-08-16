package com.wraithhawit.rstweaks.mixin;

import com.refinedmods.refinedstorage.common.autocrafting.patterngrid.PatternGridContainerMenu;
import com.refinedmods.refinedstorage.common.support.ResourceSlotRendering;
import com.refinedmods.refinedstorage.common.support.containermenu.ResourceSlot;
import com.refinedmods.refinedstorage.common.support.widget.ScrollbarWidget;
import com.wraithhawit.rstweaks.storage.FluidSwapLayout;

import javax.annotation.Nullable;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.resources.ResourceLocation;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws the fluid substitution arrangement — one input, a two-way arrow, two outputs — in place of
 * the two scrolling 9x9 matrices.
 *
 * <p>Targeted by name because {@code ProcessingPatternGridRenderer} is package-private. Mixing into
 * it is what lets us avoid implementing {@code PatternGridRenderer} ourselves, which would have
 * meant putting a class of ours inside Refined Storage's package to see the interface at all.
 * Nothing here is a new renderer; it is the processing renderer, drawing a different picture when
 * the fluid substitution tab is selected.
 *
 * <p>Three injections, each a choke point rather than a special case:
 * <ul>
 *   <li>{@code renderBackground} paints our layout instead of the matrices;</li>
 *   <li>{@code render} suppresses the scrollbar, which has nothing left to scroll;</li>
 *   <li>{@code onScrollbarChanged} is cancelled — it is the one method that rewrites every slot's
 *       position, so leaving it live would undo the layout the moment anything nudged it.</li>
 * </ul>
 *
 * <p>Slot <em>positions</em> are set by the menu; this only draws. The two are kept in step by
 * {@link FluidSwapLayout}, which both read.
 */
@Mixin(targets =
    "com.refinedmods.refinedstorage.common.autocrafting.patterngrid.ProcessingPatternGridRenderer")
public abstract class ProcessingPatternGridRendererMixin {
    @Shadow
    @Final
    private PatternGridContainerMenu menu;

    @Shadow
    @Final
    private int leftPos;

    @Shadow
    @Final
    private int topPos;

    @Shadow
    @Final
    private int x;

    @Shadow
    @Final
    private int y;

    /** A 54x54 sheet of nine slot cells; we take the top-left one for each slot we draw. */
    @Shadow
    @Final
    private static ResourceLocation PROCESSING_MATRIX;

    @Shadow
    @Nullable
    private ScrollbarWidget scrollbar;

    /** "Inputs" and "Outputs" head two columns that the fluid layout does not have. */
    @Inject(method = "renderLabels", at = @At("HEAD"), cancellable = true)
    private void rstweaks$hideMatrixLabels(final GuiGraphics graphics,
                                         final Font font,
                                         final int mouseX,
                                         final int mouseY,
                                         final CallbackInfo ci) {
        if (FluidSwapLayout.active) {
            ci.cancel();
        }
    }

    @Inject(method = "renderBackground", at = @At("HEAD"), cancellable = true)
    private void rstweaks$renderFluidSubstitution(final GuiGraphics graphics,
                                                final float partialTicks,
                                                final int mouseX,
                                                final int mouseY,
                                                final CallbackInfo ci) {
        if (!FluidSwapLayout.active) {
            return;
        }
        // Refined Storage's 130x54 backdrop is deliberately not drawn. It has the two 54-wide
        // recessed wells baked into it, one per matrix, and those wells are the wrong shape for
        // three slots in a row -- they framed the layout in a pair of boxes that no longer mean
        // anything. Without it the grid's own flat panel shows through, which is the right
        // surface for three individually recessed slots to sit on.
        if (this.scrollbar != null) {
            // Added to the screen as a widget, so cancelling our render() is not enough to hide it.
            this.scrollbar.visible = false;
        }
        for (final int cellX : FluidSwapLayout.inputCells()) {
            rstweaks$renderCell(graphics, cellX);
        }
        for (final int cellX : FluidSwapLayout.outputCells()) {
            rstweaks$renderCell(graphics, cellX);
        }
        rstweaks$renderArrow(graphics);
        rstweaks$renderSlots(graphics, mouseX, mouseY);
        ci.cancel();
    }

    /** No scrollbar: one input and two outputs never overflow the panel. */
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void rstweaks$hideScrollbar(final GuiGraphics graphics,
                                      final int mouseX,
                                      final int mouseY,
                                      final float partialTicks,
                                      final CallbackInfo ci) {
        if (FluidSwapLayout.active) {
            ci.cancel();
        }
    }

    /**
     * The one method that rewrites every processing slot's y. Cancelled while our layout is up, so
     * a stray scroll event cannot put the 9x9 grid back underneath our three slots.
     */
    @Inject(method = "onScrollbarChanged", at = @At("HEAD"), cancellable = true)
    private void rstweaks$keepLayout(final int offset, final CallbackInfo ci) {
        if (FluidSwapLayout.active) {
            ci.cancel();
        }
    }

    @Unique
    private void rstweaks$renderCell(final GuiGraphics graphics, final int cellX) {
        graphics.blitSprite(PROCESSING_MATRIX,
            FluidSwapLayout.INSET_HEIGHT, FluidSwapLayout.INSET_HEIGHT,
            0, 0,
            this.leftPos + cellX, rstweaks$cellY(),
            FluidSwapLayout.CELL, FluidSwapLayout.CELL);
    }

    /**
     * A two-way arrow, drawn from filled rectangles rather than a texture.
     *
     * <p>Vanilla has no double-headed arrow sprite and inventing one would mean shipping an asset
     * for a twenty-pixel glyph. Four columns of increasing height make each head; the shaft joins
     * them. The colour is the grey Minecraft uses for inset detail, so it reads as part of the
     * panel rather than as something painted on top.
     */
    @Unique
    private void rstweaks$renderArrow(final GuiGraphics graphics) {
        final int centreX = this.leftPos + FluidSwapLayout.arrowCentreX();
        final int centreY = rstweaks$cellY() + FluidSwapLayout.CELL / 2;
        graphics.fill(centreX - 8, centreY - 1, centreX + 8, centreY + 1, RSTWEAKS_ARROW_COLOUR);
        for (int i = 0; i < 4; i++) {
            final int halfHeight = i + 1;
            graphics.fill(centreX - 11 + i, centreY - halfHeight,
                centreX - 10 + i, centreY + halfHeight, RSTWEAKS_ARROW_COLOUR);
            graphics.fill(centreX + 10 - i, centreY - halfHeight,
                centreX + 11 - i, centreY + halfHeight, RSTWEAKS_ARROW_COLOUR);
        }
    }

    /**
     * Draws whatever is in the three slots, plus the hover highlight.
     *
     * <p>Refined Storage does this inside a scissor so its matrices can scroll under a clipped
     * window. There is nothing to clip here, so the scissor is gone and the slots are matched by
     * position — which is exactly where the menu put them.
     */
    @Unique
    private void rstweaks$renderSlots(final GuiGraphics graphics, final int mouseX, final int mouseY) {
        for (final ResourceSlot slot : this.menu.getResourceSlots()) {
            if (!slot.isActive() || !rstweaks$isLaidOut(slot)) {
                continue;
            }
            ResourceSlotRendering.render(graphics, slot, this.leftPos, this.topPos);
            final int slotX = this.leftPos + slot.x;
            final int slotY = this.topPos + slot.y;
            if (mouseX >= slotX && mouseX < slotX + 16 && mouseY >= slotY && mouseY < slotY + 16) {
                AbstractContainerScreen.renderSlotHighlight(graphics, slotX, slotY, 0);
            }
        }
    }

    @Unique
    private boolean rstweaks$isLaidOut(final ResourceSlot slot) {
        return rstweaks$atAnyCell(slot, FluidSwapLayout.inputCells())
            || rstweaks$atAnyCell(slot, FluidSwapLayout.outputCells());
    }

    @Unique
    private boolean rstweaks$atAnyCell(final ResourceSlot slot, final int[] cells) {
        for (final int cellX : cells) {
            if (slot.x == FluidSwapLayout.contentX(cellX)) {
                return true;
            }
        }
        return false;
    }

    /** Vertically centred in the inset: 54 tall, an 18-tall cell, one pixel of overhang above. */
    @Unique
    private int rstweaks$cellY() {
        return this.y + 4 + 9 + (FluidSwapLayout.INSET_HEIGHT - FluidSwapLayout.CELL) / 2;
    }

    @Unique
    private static final int RSTWEAKS_ARROW_COLOUR = 0xFF8B8B8B;
}
