package com.wraithhawit.rstweaks.mixin;

import com.wraithhawit.rstweaks.test.CraftingGridResultSlotAccess;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Opens {@code useIngredientWithRemainingItem} to the gametest, and nothing else.
 *
 * <p>Kept apart from {@link CraftingGridResultSlotMixin} on purpose: that one carries a
 * feature and this one carries a test seam, and mixing them would make it harder to see
 * that the feature's mixin does only what it says. Two mixins on one target are ordinary.
 *
 * <p>The shadow adds no behaviour — it compiles to a call to the method that is already
 * there, which is exactly the point. A test that reimplemented the call would stop
 * testing the {@code @Inject} the moment its signature drifted; this stops <em>compiling</em>.
 *
 * @see CraftingGridResultSlotAccess
 */
@Mixin(targets = "com.refinedmods.refinedstorage.common.grid.CraftingGridResultSlot")
public abstract class CraftingGridResultSlotTestMixin implements CraftingGridResultSlotAccess {
    @Shadow
    private void useIngredientWithRemainingItem(final Player player,
                                                final int slot,
                                                final ItemStack remainingItem) {
        throw new AssertionError("shadow");
    }

    @Override
    public void rstweaks$useIngredientWithRemainingItem(final Player player,
                                                        final int slot,
                                                        final ItemStack remainingItem) {
        useIngredientWithRemainingItem(player, slot, remainingItem);
    }
}
