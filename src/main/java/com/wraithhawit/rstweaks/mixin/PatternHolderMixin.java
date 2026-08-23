package com.wraithhawit.rstweaks.mixin;

import com.refinedmods.refinedstorage.api.autocrafting.Pattern;
import com.wraithhawit.rstweaks.pattern.PatternHolderAccess;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Exposes RS's private {@code PatternHolder} record through {@link PatternHolderAccess}.
 *
 * <p>Targeted by name rather than by class literal because the record is private -- it cannot
 * be named in Java source at all.
 */
@Mixin(targets = "com.refinedmods.refinedstorage.api.autocrafting.PatternRepositoryImpl$PatternHolder")
public abstract class PatternHolderMixin implements PatternHolderAccess {
    @Shadow(aliases = "pattern")
    @Final
    private Pattern pattern;

    @Shadow(aliases = "priority")
    @Final
    private int priority;

    @Override
    public Pattern rstweaks$pattern() {
        return this.pattern;
    }

    @Override
    public int rstweaks$priority() {
        return this.priority;
    }
}
