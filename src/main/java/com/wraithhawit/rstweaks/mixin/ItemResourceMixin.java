package com.wraithhawit.rstweaks.mixin;

import com.refinedmods.refinedstorage.common.support.resource.ItemResource;

import com.wraithhawit.rstweaks.storage.ResourceMemo;

import javax.annotation.Nullable;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Storage for {@link ResourceMemo}: five derived answers, held on the resource they describe.
 *
 * <p>Adds fields and nothing else — no injector, no behaviour, no method of Refined Storage's
 * touched. If this mixin ever fails to apply, {@code ItemResource} simply stops implementing
 * {@link ResourceMemo}, every caller falls back to the maps it used before, and the only thing lost
 * is the speed.
 *
 * <p>The precedent for putting a derived value on this class is the class itself: RS caches its own
 * {@code hashCode} in a plain non-volatile {@code private int hash}, for the same reason and with
 * the same race.
 */
@Mixin(ItemResource.class)
public abstract class ItemResourceMixin implements ResourceMemo {
    @Unique
    private int rstweaks$familyPlusOne;

    @Unique
    private int rstweaks$maxDamagePlusOne;

    @Unique
    private int rstweaks$damagePlusOne;

    @Unique
    @Nullable
    private ItemResource rstweaks$withoutDamage;

    @Unique
    @Nullable
    private ItemResource rstweaks$afterOneUse;

    @Override
    public int rstweaks$familyPlusOne() {
        return this.rstweaks$familyPlusOne;
    }

    @Override
    public void rstweaks$familyPlusOne(final int value) {
        this.rstweaks$familyPlusOne = value;
    }

    @Override
    public int rstweaks$maxDamagePlusOne() {
        return this.rstweaks$maxDamagePlusOne;
    }

    @Override
    public void rstweaks$maxDamagePlusOne(final int value) {
        this.rstweaks$maxDamagePlusOne = value;
    }

    @Override
    public int rstweaks$damagePlusOne() {
        return this.rstweaks$damagePlusOne;
    }

    @Override
    public void rstweaks$damagePlusOne(final int value) {
        this.rstweaks$damagePlusOne = value;
    }

    @Nullable
    @Override
    public ItemResource rstweaks$withoutDamage() {
        return this.rstweaks$withoutDamage;
    }

    @Override
    public void rstweaks$withoutDamage(final ItemResource value) {
        this.rstweaks$withoutDamage = value;
    }

    @Nullable
    @Override
    public ItemResource rstweaks$afterOneUse() {
        return this.rstweaks$afterOneUse;
    }

    @Override
    public void rstweaks$afterOneUse(final ItemResource value) {
        this.rstweaks$afterOneUse = value;
    }
}
