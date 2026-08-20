package com.wraithhawit.rstweaks.mixin;

import com.buuz135.functionalstorage.inventory.BigInventoryHandler;
import com.wraithhawit.rstweaks.Config;
import com.wraithhawit.rstweaks.storage.DrawerDenylist;

import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Answers a drawer's denylist check from a cache instead of a tag lookup.
 *
 * <p>{@code BigInventoryHandler} asks {@code DRAWER_STORAGE_DENYLIST} twice per insert
 * attempt — the guard at the top of {@code insertItem}, and again inside the {@code isValid}
 * that same call goes on to make:
 *
 * <pre>{@code
 *   public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
 *       if (stack.is(StorageTags.DRAWER_STORAGE_DENYLIST)) return stack;   // here
 *       ...
 *       if (this.isValid(slot, stack)) {                                   // and again inside
 * }</pre>
 *
 * <p>Refined Storage drives that path once per slot per returned craft output, and
 * {@code ImmutableCollections$SetN.probe} underneath it measured <b>11.6% of the server
 * thread</b> — the second largest self frame in the profile. {@link DrawerDenylist} carries
 * the reasoning for why an apparently trivial set lookup costs that much, and why an
 * {@link Item}-keyed cache is exact rather than approximate.
 *
 * <p>Both call sites are redirected rather than only the redundant one. Skipping the second
 * would need the mixin to know who called {@code isValid}, and {@code isValid} is reachable
 * from elsewhere; caching both is simpler and leaves no path uncovered.
 *
 * <p>Nothing here changes what the answer is. When the toggle is off, or on the first sighting
 * of an item, the original {@code stack.is(tag)} runs and its result is what gets returned.
 */
@Mixin(BigInventoryHandler.class)
public abstract class BigInventoryHandlerMixin {

    /**
     * Matches both {@code insertItem} and {@code isValid}; each contains exactly one
     * {@code ItemStack.is(TagKey)} call, so {@code defaultRequire = 1} still proves both were
     * found. The {@code tag} parameter is deliberately unused — it can only ever be
     * {@code DRAWER_STORAGE_DENYLIST} at these two sites, and {@link DrawerDenylist} is keyed
     * to that tag specifically.
     */
    @Redirect(
        method = {"insertItem", "isValid"},
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/item/ItemStack;is(Lnet/minecraft/tags/TagKey;)Z"
        )
    )
    private boolean rstweaks$cachedDenylist(final ItemStack stack, final TagKey<Item> tag) {
        if (!Config.cacheDrawerDenylist) {
            return stack.is(tag);
        }
        return DrawerDenylist.isDenied(stack, tag);
    }
}
