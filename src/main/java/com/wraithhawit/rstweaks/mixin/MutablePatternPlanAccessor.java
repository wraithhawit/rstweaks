package com.wraithhawit.rstweaks.mixin;

import java.util.Map;

import com.refinedmods.refinedstorage.api.resource.ResourceKey;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Lets {@link MutablePatternPlanMixin} hand a shared ingredients map to the plan it just copied.
 *
 * <p>Needed because the sharing has to be done to <em>another</em> instance -- the fresh copy
 * returned by {@code copy()} -- and a {@code @Shadow} field only reaches {@code this}.
 *
 * <p>{@code @Mutable} because the field is {@code final}. That is safe here for the same reason the
 * whole optimization is: the field is assigned exactly once by the constructor and never again, so
 * nothing in Refined Storage can be holding an assumption that it does not change -- it simply
 * never had a reason to.
 */
@Mixin(targets = "com.refinedmods.refinedstorage.api.autocrafting.task.MutablePatternPlan")
public interface MutablePatternPlanAccessor {
    @Mutable
    @Accessor("ingredients")
    void rstweaks$setIngredients(Map<Integer, Map<ResourceKey, Long>> ingredients);

    @Accessor("ingredients")
    Map<Integer, Map<ResourceKey, Long>> rstweaks$getIngredients();
}
