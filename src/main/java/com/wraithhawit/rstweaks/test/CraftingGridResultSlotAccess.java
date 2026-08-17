package com.wraithhawit.rstweaks.test;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * Calls the Crafting Grid's remaining-item path from a test.
 *
 * <p>{@code CraftingGridResultSlot} is package-private and the method is private, so
 * neither can be named from here. Reflection is not an option either: Refined Storage is
 * a named module and {@code setAccessible} across one is refused. A mixin already has to
 * reach into this class, so it is the mixin that opens the door — see
 * {@code CraftingGridResultSlotTestMixin}.
 *
 * <p>Deliberately the real entry point rather than the refill helper next to it. What is
 * under test is not only "does the network get charged" but "does cancelling leave the
 * full container in the slot", and only the injected method can answer the second.
 */
public interface CraftingGridResultSlotAccess {
    void rstweaks$useIngredientWithRemainingItem(Player player, int slot, ItemStack remainingItem);
}
