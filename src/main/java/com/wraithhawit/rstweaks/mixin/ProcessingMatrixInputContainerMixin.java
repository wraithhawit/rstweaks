package com.wraithhawit.rstweaks.mixin;

import com.wraithhawit.rstweaks.storage.ProcessingInputContainer;

import org.spongepowered.asm.mixin.Mixin;

/**
 * Tags the processing-input container so its slots can be recognised.
 *
 * <p>Targeted by string because the class is package-private: a {@code targets} entry is just text
 * and does not need the type to be accessible, whereas naming it in Java source would not compile.
 */
@Mixin(targets = "com.refinedmods.refinedstorage.common.autocrafting.patterngrid."
    + "ProcessingMatrixInputResourceContainer")
public abstract class ProcessingMatrixInputContainerMixin implements ProcessingInputContainer {
}