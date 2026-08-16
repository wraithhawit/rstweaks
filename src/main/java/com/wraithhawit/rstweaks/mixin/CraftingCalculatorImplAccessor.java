package com.wraithhawit.rstweaks.mixin;

import com.refinedmods.refinedstorage.api.autocrafting.PatternRepository;
import com.refinedmods.refinedstorage.api.autocrafting.calculation.CraftingCalculatorImpl;
import com.refinedmods.refinedstorage.api.storage.root.RootStorage;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the pattern repository and root storage that {@link CraftingCalculatorImpl}
 * holds privately.
 *
 * <p>{@code calculatePlan} is handed a {@code CraftingCalculator} interface, which
 * offers only {@code calculate(...)} — no way to reach the recipes or the network
 * inventory. The LP planner needs both to build its equations, and reconstructing
 * them from elsewhere would risk planning against different data than RS would use.
 */
@Mixin(CraftingCalculatorImpl.class)
public interface CraftingCalculatorImplAccessor {
    @Accessor("patternRepository")
    PatternRepository rstweaks$patternRepository();

    @Accessor("rootStorage")
    RootStorage rstweaks$rootStorage();
}
