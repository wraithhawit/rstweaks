package com.wraithhawit.rstweaks.storage;

import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import com.wraithhawit.rstweaks.planner.Durability;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * The Minecraft-backed {@link Durability}, kept out of the planner package so the solver
 * stays runnable without the game.
 *
 * <p>Damage lives in the {@code ItemResource}'s component patch, which is why a crystal
 * at damage 0 and the same crystal at damage 1 are different resources to Refined Storage
 * and why a pattern encoded with one cannot consume the other.
 */
public final class ItemDurability implements Durability {
    public static final ItemDurability INSTANCE = new ItemDurability();

    private ItemDurability() {
    }

    @Override
    public boolean isDurable(final ResourceKey resource) {
        return resource instanceof ItemResource item && maxDamage(item) > 0;
    }

    @Override
    public int maxUses(final ResourceKey resource) {
        return resource instanceof ItemResource item ? maxDamage(item) : 0;
    }

    @Override
    public int usesLeft(final ResourceKey resource) {
        if (!(resource instanceof ItemResource item)) {
            return 0;
        }
        final int max = maxDamage(item);
        return max <= 0 ? 0 : Math.max(0, max - damage(item));
    }

    @Nullable
    @Override
    public ResourceKey afterUses(final ResourceKey resource, final int uses) {
        if (!(resource instanceof ItemResource item) || uses <= 0) {
            return resource;
        }
        final int max = maxDamage(item);
        final int worn = damage(item) + uses;
        if (max <= 0 || worn >= max) {
            // Vanilla destroys a tool when damage reaches its maximum, so nothing comes
            // back. The planner reads null as "this use was the last one".
            return null;
        }
        final ItemStack stack = item.toItemStack(1L);
        stack.set(DataComponents.DAMAGE, worn);
        return ItemResource.ofItemStack(stack);
    }

    @Override
    public boolean sameTool(final ResourceKey a, final ResourceKey b) {
        if (!(a instanceof ItemResource left) || !(b instanceof ItemResource right)) {
            return false;
        }
        if (!left.item().equals(right.item()) || maxDamage(left) <= 0) {
            return false;
        }
        // Same item and both damageable is not enough: two differently enchanted tools
        // are not interchangeable. Compare everything except the damage.
        return withoutDamage(left).equals(withoutDamage(right));
    }

    private static ItemResource withoutDamage(final ItemResource resource) {
        final ItemStack stack = resource.toItemStack(1L);
        stack.remove(DataComponents.DAMAGE);
        return ItemResource.ofItemStack(stack);
    }

    /**
     * Cached per item, because the only way to ask is to build an {@link ItemStack} and
     * this runs inside {@code extractAll} — once per ingredient, per iteration, per
     * crafting task. Allocating a stack there to answer a question whose answer is fixed
     * for the item would be a perf regression shipped inside a perf mod.
     *
     * <p>Bounded by the item registry, and every value is immutable, so a plain
     * concurrent map needs no eviction. Keyed by {@code Item} rather than by resource:
     * damage varies, maximum damage does not.
     */
    private static final Map<Item, Integer> MAX_DAMAGE = new ConcurrentHashMap<>();

    private static int maxDamage(final ItemResource resource) {
        return MAX_DAMAGE.computeIfAbsent(resource.item(), item -> {
            final ItemStack stack = new ItemStack(item);
            return stack.isDamageableItem() ? stack.getMaxDamage() : 0;
        });
    }

    private static int damage(final ItemResource resource) {
        return resource.toItemStack(1L).getDamageValue();
    }
}
