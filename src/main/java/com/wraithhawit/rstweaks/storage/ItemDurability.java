package com.wraithhawit.rstweaks.storage;

import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import com.wraithhawit.rstweaks.planner.Durability;

import java.util.Map;
import java.util.Optional;
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

    /**
     * Which tool family this resource belongs to, or {@link #NOT_A_TOOL}.
     *
     * <p>Two resources are the same tool exactly when they share a family, so {@link #sameTool}
     * becomes one lookup each and an {@code int} comparison. That matters because
     * {@code findWornTool} asks it once per resource in the task's internal storage, per
     * ingredient, per iteration.
     *
     * <p>Caching {@link #withoutDamage} (0.9.1) removed the {@code ItemStack} allocation from that
     * comparison and left the expensive half behind: comparing two cached results still means
     * {@code DataComponentPatch.equals}, which is {@code AbstractCollection.containsAll} over the
     * component maps — 8.97% plus 5.79% of the server thread in profile {@code K7rNc1lhrw}, with
     * {@code maxDamage}'s per-call map lookup another 11.80% on top. An integer has none of that.
     */
    private static int family(final ItemResource resource) {
        final Integer cached = FAMILY.get(resource);
        if (cached != null) {
            return cached;
        }
        final int family;
        if (maxDamage(resource) <= 0) {
            family = NOT_A_TOOL;
        } else {
            // Everything but the damage decides the family, so a differently enchanted tool gets
            // its own — two of those are not interchangeable wear levels of one another.
            family = FAMILY_OF_CANONICAL.computeIfAbsent(
                withoutDamage(resource), key -> NEXT_FAMILY.getAndIncrement());
        }
        FAMILY.put(resource, family);
        return family;
    }

    private static final int NOT_A_TOOL = -1;
    private static final Map<ItemResource, Integer> FAMILY = new ConcurrentHashMap<>();
    private static final Map<ItemResource, Integer> FAMILY_OF_CANONICAL = new ConcurrentHashMap<>();
    private static final java.util.concurrent.atomic.AtomicInteger NEXT_FAMILY =
        new java.util.concurrent.atomic.AtomicInteger();

    @Override
    public boolean isDurable(final ResourceKey resource) {
        return resource instanceof ItemResource item && family(item) != NOT_A_TOOL;
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
        // Same item and both damageable is not enough: two differently enchanted tools are not
        // interchangeable. That distinction is baked into the family, so this stays an int
        // comparison rather than becoming a component-map equality on the hot path.
        final int family = family(left);
        return family != NOT_A_TOOL && family == family(right);
    }

    /**
     * The same tool with its damage forgotten, so two wear levels compare equal.
     *
     * <p><b>Cached, and that cache is worth more than it looks.</b> Building this means creating an
     * {@code ItemStack}, mutating its component map and rebuilding an {@code ItemResource} — and
     * {@link #sameTool} needs it for <em>both</em> sides of every comparison, inside a scan over the
     * task's whole internal storage. On a large craft with a wearing tool that was <b>62% of the
     * server thread</b> in profile {@code zyyN62neOS}: {@code ItemStack.<init>} 13.7% self,
     * component-map copying another 25% between them.
     *
     * <p>Keyed on {@link ItemResource}, which is a record and therefore has real value equality.
     * Keying a cache on {@code ItemStack} would silently make it an identity map and it would never
     * hit — the mistake this project has already paid for once.
     *
     * <p>Bounded in practice: one entry per distinct wear level of each tool actually handled, so a
     * few hundred at worst, and nothing is added for items that are not durable.
     */
    private static ItemResource withoutDamage(final ItemResource resource) {
        return WITHOUT_DAMAGE.computeIfAbsent(resource, key -> {
            final ItemStack stack = key.toItemStack(1L);
            stack.remove(DataComponents.DAMAGE);
            return ItemResource.ofItemStack(stack);
        });
    }

    private static final Map<ItemResource, ItemResource> WITHOUT_DAMAGE = new ConcurrentHashMap<>();

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

    /**
     * How damaged this one is, read off the component patch instead of built into a stack.
     *
     * <p>{@code toItemStack(1L).getDamageValue()} allocates a stack and copies a component map to
     * read a single integer, and {@code findWornTool} calls it once per candidate in storage. The
     * patch already carries the answer whenever the resource has been damaged at all; only an
     * undamaged one falls through to the item's own default, and that is cached per item.
     */
    private static int damage(final ItemResource resource) {
        final Optional<? extends Integer> patched = resource.components().get(DataComponents.DAMAGE);
        if (patched != null) {
            // Present but empty means the component was explicitly removed, which is undamaged.
            return patched.isPresent() ? patched.get() : 0;
        }
        return DEFAULT_DAMAGE.computeIfAbsent(resource.item(),
            item -> new ItemStack(item).getDamageValue());
    }

    private static final Map<Item, Integer> DEFAULT_DAMAGE = new ConcurrentHashMap<>();
}
