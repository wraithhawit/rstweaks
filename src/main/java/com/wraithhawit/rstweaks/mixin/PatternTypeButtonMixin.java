package com.wraithhawit.rstweaks.mixin;

import com.wraithhawit.rstweaks.client.PatternTabHighlight;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Opens up whether a pattern type tab is drawn lit.
 *
 * <p>Targeted by string because the class is package-private, which also rules out calling its
 * {@code setSelected}. The field it sets is the one thing needed, and shadowing it is legal since
 * it is declared here rather than on a superclass.
 *
 * @see PatternTabHighlight
 */
@Mixin(targets = "com.refinedmods.refinedstorage.common.autocrafting.patterngrid.PatternTypeButton")
public abstract class PatternTypeButtonMixin implements PatternTabHighlight {
    @Shadow
    private boolean selected;

    @Override
    public void rstweaks$setHighlighted(final boolean highlighted) {
        this.selected = highlighted;
    }
}
