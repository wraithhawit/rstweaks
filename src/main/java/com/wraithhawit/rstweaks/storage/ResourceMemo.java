package com.wraithhawit.rstweaks.storage;

import com.refinedmods.refinedstorage.common.support.resource.ItemResource;

import javax.annotation.Nullable;

/**
 * Per-resource answers cached on the resource itself instead of in a map keyed by it.
 *
 * <h2>Why</h2>
 *
 * <p>{@code ItemDurability} keeps five caches — tool family, damage-stripped identity, the resource
 * one use further worn, maximum damage and default damage — and every one is a
 * {@code ConcurrentHashMap} keyed on {@code ItemResource}. Profile {@code mWcYLBc220} puts
 * {@code ConcurrentHashMap.get} at <b>12.64% of the server thread</b>, and it grew when 0.17.0 added
 * the third of them: the caches are cheap individually and there are a great many lookups.
 *
 * <p>An {@code ItemResource} is immutable, so an answer derived from one can live on it. Refined
 * Storage already does exactly this with its own {@code private int hash} field, which is the
 * precedent worth naming — including for thread safety.
 *
 * <h2>Sentinels rather than extra flags</h2>
 *
 * <p>Mixin does not run field initialisers reliably, so every field here has to be meaningful when
 * it defaults to zero. Values that can legitimately be zero or negative are therefore stored offset
 * by one, and {@code 0} always means "not worked out yet". That is cheaper than a parallel
 * {@code boolean} per field and impossible to get half-right.
 *
 * <h2>Racy on purpose</h2>
 *
 * <p>The planner runs on Refined Storage's autocrafting threads as well as the server thread, and
 * none of these fields are volatile. That is safe because every one is a pure function of an
 * immutable object: two threads racing compute the same value, and the worst case is that one of
 * them does the work twice. Making them volatile would put a memory barrier on the hottest path in
 * the mod to prevent something that cannot happen.
 */
public interface ResourceMemo {
    /** Tool family, offset by one so an unset field reads as absent. */
    int rstweaks$familyPlusOne();

    void rstweaks$familyPlusOne(int value);

    /** Maximum damage, offset by one: zero is a real answer for anything that does not wear out. */
    int rstweaks$maxDamagePlusOne();

    void rstweaks$maxDamagePlusOne(int value);

    /** Current damage, offset by one: an undamaged tool is a real answer of zero. */
    int rstweaks$damagePlusOne();

    void rstweaks$damagePlusOne(int value);

    /** This resource with its damage removed, or null when not worked out yet. */
    @Nullable
    ItemResource rstweaks$withoutDamage();

    void rstweaks$withoutDamage(ItemResource value);

    /**
     * This resource one use further worn, or null when not worked out yet.
     *
     * <p>A tool that breaks on its next use has no answer, and that case is deliberately not stored
     * — it happens once per tool, and the path that computes it allocates nothing anyway.
     */
    @Nullable
    ItemResource rstweaks$afterOneUse();

    void rstweaks$afterOneUse(ItemResource value);
}
