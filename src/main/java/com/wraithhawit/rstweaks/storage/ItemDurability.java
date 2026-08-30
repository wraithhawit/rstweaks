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
        // On the resource itself when the memo mixin applied, in a map when it did not. Profile
        // mWcYLBc220 put ConcurrentHashMap.get at 12.64% of the server thread across these five
        // caches: each lookup is cheap and there are an enormous number of them. A field read is
        // not cheap-per-call, it is free. See ResourceMemo for why the race is safe.
        if ((Object) resource instanceof ResourceMemo memo) {
            final int offset = memo.rstweaks$familyPlusOne();
            if (offset != 0) {
                return offset - 1;
            }
            final int computed = computeFamily(resource);
            memo.rstweaks$familyPlusOne(computed + 1);
            return computed;
        }
        final Integer cached = FAMILY.get(resource);
        if (cached != null) {
            return cached;
        }
        final int family = computeFamily(resource);
        FAMILY.put(resource, family);
        return family;
    }

    /** The family itself, with no caching of any kind — both paths above call this. */
    private static int computeFamily(final ItemResource resource) {
        if (maxDamage(resource) <= 0) {
            return NOT_A_TOOL;
        }
        // Everything but the damage decides the family, so a differently enchanted tool gets
        // its own — two of those are not interchangeable wear levels of one another.
        //
        // FAMILY_OF_CANONICAL stays a map whichever path got here: it is keyed on the
        // damage-stripped resource, which is a different object from the one being asked about, so
        // there is no instance to hang it on. It is also asked once per distinct wear level rather
        // than per lookup, which is why it never showed up in a profile.
        return FAMILY_OF_CANONICAL.computeIfAbsent(
            withoutDamage(resource), key -> NEXT_FAMILY.getAndIncrement());
    }

    // NOT_A_TOOL is inherited from Durability: it is part of the toolFamily contract now, and two
    // copies of a sentinel are two things that can drift apart.
    private static final Map<ItemResource, Integer> FAMILY = new ConcurrentHashMap<>();
    private static final Map<ItemResource, Integer> FAMILY_OF_CANONICAL = new ConcurrentHashMap<>();
    private static final java.util.concurrent.atomic.AtomicInteger NEXT_FAMILY =
        new java.util.concurrent.atomic.AtomicInteger();

    /**
     * Whether this wears out at all — answered from {@link #maxDamage}, not from {@link #family}.
     *
     * <p>The distinction is the whole cost. {@code maxDamage} is keyed by {@link Item}, so its
     * lookup is an identity hash on a registry object. {@code family} is keyed by
     * {@code ItemResource}, so its lookup hashes and equality-compares a {@code DataComponentPatch}
     * — and this is asked once per ingredient, twice per crafting iteration.
     *
     * <p>0.10.1 routed it through {@code family} and profile {@code 8GrQL66Tfd} shows what that
     * cost: {@code ItemResource.equals} went from 6.19% of the server thread to <b>24.03%</b>, and
     * the durability path barely improved overall despite {@code findWornTool} collapsing from
     * 29.06% to 1.43%. The scan was fixed and the map lookup replaced it.
     *
     * <p>Only {@link #sameTool} needs the family, and it is asked far less often.
     */
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
        if (uses == 1) {
            final ItemResource cached = (Object) item instanceof ResourceMemo memo
                ? memo.rstweaks$afterOneUse()
                : AFTER_ONE_USE.get(item);
            if (cached != null) {
                return cached;
            }
        }
        final int max = maxDamage(item);
        final int worn = damage(item) + uses;
        if (max <= 0 || worn >= max) {
            // Vanilla destroys a tool when damage reaches its maximum, so nothing comes
            // back. The planner reads null as "this use was the last one".
            //
            // Deliberately not cached. It happens once per tool, at the end of its life, and the
            // early return costs nothing anyway -- no stack is built on this path. Caching it would
            // mean a sentinel for null, which is machinery to make the rare case slightly faster.
            return null;
        }
        final ItemStack stack = item.toItemStack(1L);
        stack.set(DataComponents.DAMAGE, worn);
        final ItemResource worse = ItemResource.ofItemStack(stack);
        if (uses == 1) {
            if ((Object) item instanceof ResourceMemo memo) {
                memo.rstweaks$afterOneUse(worse);
            } else {
                AFTER_ONE_USE.put(item, worse);
            }
        }
        return worse;
    }

    /**
     * The same tool one use further worn, cached.
     *
     * <p>Profile {@code ajw2GTmG3M}: once whole steps started being skipped, byproduct aging became
     * the largest thing left, and <b>{@code afterUses} was 14.4% of the entire server thread</b> —
     * building an {@code ItemStack}, copying its component map and rebuilding an
     * {@code ItemResource}, per byproduct, per iteration, to compute something completely determined
     * by its input.
     *
     * <p><b>Keyed on the resource, not on {@code (item, damage)}.</b> The new resource is built from
     * the old one's stack, so it carries the whole component patch forward — two differently
     * enchanted tools at the same wear level produce different results, and an {@code (item, damage)}
     * key would hand one of them the other's answer. That is exactly the fuzzy-slot bug issue 9 was
     * about, and it would be silent.
     *
     * <p>Only {@code uses == 1} is cached, which is what wear steps actually ask for; anything else
     * falls through and allocates as before. Bounded the same way {@link #WITHOUT_DAMAGE} is: one
     * entry per distinct wear level of each tool actually handled, and every value is immutable.
     *
     * <p>{@code ItemResource} caches its own {@code hashCode} in a field, so the lookup here is a
     * field read and an equality check rather than a walk of the component patch.
     */
    private static final Map<ItemResource, ItemResource> AFTER_ONE_USE = new ConcurrentHashMap<>();

    @Override
    public int toolFamily(final ResourceKey resource) {
        return resource instanceof ItemResource item ? family(item) : NOT_A_TOOL;
    }

    /**
     * The wanted side's family resolved once, for the whole of {@code findWornTool}'s scan.
     *
     * <p>Profile {@code qRRh2NJvYs} is why. 0.11.2 added the item-first rejection below on the
     * reasoning that "almost every candidate is a completely different item" — <b>and that is false
     * for exactly the craft this code exists to serve</b>. A tool being worn down fills the task's
     * internal storage with many wear levels of the <em>same</em> item, so the reference comparison
     * matches and both family lookups run. {@code sameTool} was still 12.7% of the server thread.
     *
     * <p>One of those two lookups is for {@code wanted}, which does not change across the scan.
     * Hoisting it removes half of them outright.
     */
    @Override
    public boolean sameTool(final ResourceKey wanted, final int wantedFamily,
                            final ResourceKey candidate) {
        if (!(wanted instanceof ItemResource left) || !(candidate instanceof ItemResource right)) {
            return false;
        }
        if (left.item() != right.item()) {
            return false;
        }
        return wantedFamily != NOT_A_TOOL && family(right) == wantedFamily;
    }

    @Override
    public boolean sameTool(final ResourceKey a, final ResourceKey b) {
        if (!(a instanceof ItemResource left) || !(b instanceof ItemResource right)) {
            return false;
        }
        // Reject on the item first, and it is nearly always a rejection: findWornTool asks this
        // once per resource in the task's whole internal storage, and almost none of them are the
        // tool it is looking for. Item is a registry singleton, so this is a reference comparison,
        // where each family lookup hashes and equality-compares a DataComponentPatch.
        //
        // Sound because a family is derived from the resource with its damage removed, so two
        // resources in one family necessarily share an item. Skipping on a different item can
        // never skip a real match.
        //
        // Profile r3z1C0CsZx put findWornTool at 25.85% of the server thread once the cheaper
        // costs around it were gone, and this is what that 25.85% is made of.
        if (left.item() != right.item()) {
            return false;
        }
        // Same item and both damageable is still not enough: two differently enchanted tools are
        // not interchangeable. That distinction is baked into the family, so this stays an int
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
        if ((Object) resource instanceof ResourceMemo memo) {
            final ItemResource cached = memo.rstweaks$withoutDamage();
            if (cached != null) {
                return cached;
            }
            final ItemResource computed = stripDamage(resource);
            memo.rstweaks$withoutDamage(computed);
            return computed;
        }
        return WITHOUT_DAMAGE.computeIfAbsent(resource, ItemDurability::stripDamage);
    }

    private static ItemResource stripDamage(final ItemResource resource) {
        final ItemStack stack = resource.toItemStack(1L);
        stack.remove(DataComponents.DAMAGE);
        return ItemResource.ofItemStack(stack);
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
        // A plain get on the hit path, because this is 8.86% of the server thread in profile
        // qRRh2NJvYs purely on call volume -- isDurable asks it once per ingredient, twice per
        // iteration. computeIfAbsent has to be prepared to insert on every call; get does not.
        if ((Object) resource instanceof ResourceMemo memo) {
            final int offset = memo.rstweaks$maxDamagePlusOne();
            if (offset != 0) {
                return offset - 1;
            }
            final int computed = maxDamageOf(resource.item());
            memo.rstweaks$maxDamagePlusOne(computed + 1);
            return computed;
        }
        final Item item = resource.item();
        final Integer cached = MAX_DAMAGE.get(item);
        if (cached != null) {
            return cached;
        }
        return MAX_DAMAGE.computeIfAbsent(item, ItemDurability::maxDamageOf);
    }

    /**
     * Keyed by {@link Item} in the map fallback, but stored per resource in the memo.
     *
     * <p>That looks like a duplicated answer and is the right trade: the map has one entry per item
     * where the memo has one per resource, but the memo costs a field read against a hash and a
     * bucket walk. Both are bounded and both are immutable, and only one of them is on the hot path.
     */
    private static int maxDamageOf(final Item item) {
        final ItemStack stack = new ItemStack(item);
        return stack.isDamageableItem() ? stack.getMaxDamage() : 0;
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
        if ((Object) resource instanceof ResourceMemo memo) {
            final int offset = memo.rstweaks$damagePlusOne();
            if (offset != 0) {
                return offset - 1;
            }
            final int computed = damageOf(resource);
            memo.rstweaks$damagePlusOne(computed + 1);
            return computed;
        }
        return damageOf(resource);
    }

    private static int damageOf(final ItemResource resource) {
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
