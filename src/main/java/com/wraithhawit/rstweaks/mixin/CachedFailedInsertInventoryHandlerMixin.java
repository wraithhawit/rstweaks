package com.wraithhawit.rstweaks.mixin;

import com.wraithhawit.rstweaks.Config;
import com.wraithhawit.rstweaks.Stats;

import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

import net.minecraft.world.item.ItemStack;

import net.p3pp3rf1y.sophisticatedcore.inventory.CachedFailedInsertInventoryHandler;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes Sophisticated Storage's own failed-insert cache able to hit.
 *
 * <p>Diagnosis, from a 60-second server-thread profile of a Refined Storage network with
 * External Storage pointed at Sophisticated barrels: a single autocrafting task returning
 * its outputs was <b>94.5% of the server thread</b>, and <b>54% of the whole thread</b>
 * (32.6 s of 60 s) was spent inside {@code CachedFailedInsertInventoryHandler.insertItem}
 * -- the class whose entire purpose is to stop that work from happening twice.
 *
 * <p>The reason it never stops anything is one line upstream:
 *
 * <pre>{@code   private final Set<ItemStack> failedInsertStacks = new HashSet<>(); }</pre>
 *
 * <p>{@link ItemStack} declares neither {@code equals} nor {@code hashCode} in 1.21.1 --
 * verified against the actual game jar, not assumed -- so that {@link java.util.HashSet}
 * is an <em>identity</em> set. It can only hit when the caller hands back the very same
 * {@code ItemStack} object it was given before. Refined Storage builds a fresh stack for
 * every insert attempt ({@code ItemResource.toItemStack}, itself 3.1% of the thread), so
 * {@code failedInsertStacks.contains(stack)} is false every single time and the barrel is
 * rescanned in full on every attempt. The profile shows exactly that shape: 32.6 s in
 * {@code insertItem} against 0.1 s in {@code HashSet.contains}.
 *
 * <p>This adds a second cache beside it, keyed by value instead of by identity, using the
 * same primitives Sophisticated's own {@code ItemStackKey} uses for equality
 * ({@code hashItemAndComponents} / {@code isSameItemSameComponents}). We deliberately do
 * not call {@code ItemStackKey.of}: that memoises into a {@code ConcurrentHashMap} keyed
 * by {@code ItemStack}, which is identity-keyed too, so feeding it RS's fresh stacks would
 * add an entry per attempt and grow without bound.
 *
 * <p><b>This caches rejections, never item data.</b> That distinction is the safety
 * argument, and it is the same one that justified {@link ControllerInventoryHandlerMixin}.
 * Nothing here reports what a barrel contains or how much room it has; the cached answer
 * is only "an insert of this exact item into this exact barrel already came back
 * untouched, this tick". Contents are still read live on every call not short-circuited.
 *
 * <p>Three things bound the staleness, and the first two are upstream's own contract:
 *
 * <ul>
 *   <li><b>Per tick.</b> Upstream already clears on every game-tick change, which is the
 *       assertion "within one tick, a stack that failed will fail again". We keep that
 *       clock and clear on the same edge.
 *   <li><b>Total failures only.</b> A rejection is recorded solely when
 *       {@code insertItem} returns the argument object itself, which happens only when
 *       <em>nothing</em> moved. A partial insert returns a different remainder stack and
 *       is never cached.
 *   <li><b>Cleared on extraction.</b> Upstream does not do this; we do. Taking items out
 *       is the one thing that can make room appear mid-tick, so any successful
 *       {@code extractItem} drops the map. That makes this strictly tighter than the
 *       identity cache it sits beside, not looser.
 * </ul>
 *
 * <p>The recorded value is the smallest count that has failed, and a later attempt is
 * short-circuited only when it is at least that large. A total rejection means the barrel
 * had room for zero of the item, so a smaller stack should fail too and the count is
 * arguably redundant -- it is kept anyway, because "should fail too" reasons about
 * upstream's slot-limit maths from the outside, and a void or compression upgrade is
 * exactly the kind of thing that could make a barrel non-monotonic in count. One int
 * comparison is a cheap price for not having to be right about that.
 *
 * <p>Simulated inserts are left alone, exactly as upstream leaves them: a simulation is
 * what a caller uses to ask whether room exists, and answering that from a cache is how a
 * planner ends up believing something that is no longer true.
 */
@Mixin(CachedFailedInsertInventoryHandler.class)
public abstract class CachedFailedInsertInventoryHandlerMixin {

    @Shadow
    @Final
    private LongSupplier timeSupplier;

    /** Smallest count of each item that has come back untouched this tick. */
    @Unique
    private Map<Object, Integer> rstweaks$rejected;

    /** The tick {@link #rstweaks$rejected} was last cleared on. */
    @Unique
    private long rstweaks$rejectedTick = Long.MIN_VALUE;

    /**
     * Value-keyed identity for an item plus its components, matching {@code ItemStackKey}'s
     * equality without its identity-keyed memo table. Holds a count-1 copy so a stack the
     * caller later mutates cannot change what we hashed.
     */
    @Unique
    private static final class Key {
        private final ItemStack stack;
        private final int hash;

        Key(final ItemStack stack) {
            this.stack = stack.copyWithCount(1);
            this.hash = ItemStack.hashItemAndComponents(this.stack);
        }

        @Override
        public boolean equals(final Object o) {
            return o instanceof Key other
                && this.hash == other.hash
                && ItemStack.isSameItemSameComponents(this.stack, other.stack);
        }

        @Override
        public int hashCode() {
            return this.hash;
        }
    }

    /** Drops the map when the tick rolls over, mirroring upstream's own clock. */
    @Unique
    private Map<Object, Integer> rstweaks$currentMap() {
        final long now = this.timeSupplier.getAsLong();
        if (this.rstweaks$rejected == null) {
            this.rstweaks$rejected = new HashMap<>();
        } else if (this.rstweaks$rejectedTick != now) {
            this.rstweaks$rejected.clear();
        }
        this.rstweaks$rejectedTick = now;
        return this.rstweaks$rejected;
    }

    @Inject(method = "insertItem", at = @At("HEAD"), cancellable = true)
    private void rstweaks$shortCircuitRejected(final int slot, final ItemStack stack,
        final boolean simulate, final CallbackInfoReturnable<ItemStack> cir) {
        if (simulate || stack.isEmpty() || !Config.cacheFailedInsertsByValue) {
            return;
        }
        final Integer minFailed = this.rstweaks$currentMap().get(new Key(stack));
        if (minFailed != null && stack.getCount() >= minFailed) {
            Stats.failedInsertScansAvoided++;
            cir.setReturnValue(stack);
        }
    }

    @Inject(method = "insertItem", at = @At("RETURN"))
    private void rstweaks$recordRejection(final int slot, final ItemStack stack,
        final boolean simulate, final CallbackInfoReturnable<ItemStack> cir) {
        if (simulate || stack.isEmpty() || !Config.cacheFailedInsertsByValue) {
            return;
        }
        // Reference equality, not isEmpty: upstream uses the same test, and it is the only
        // one that separates "nothing moved" from "moved some, here is the remainder".
        if (cir.getReturnValue() != stack) {
            return;
        }
        this.rstweaks$currentMap().merge(new Key(stack), stack.getCount(), Math::min);
    }

    /**
     * Any successful extraction can free room, so the recorded rejections stop being
     * trustworthy. Simulated extractions move nothing and are ignored.
     */
    @Inject(method = "extractItem", at = @At("RETURN"))
    private void rstweaks$invalidateOnExtract(final int slot, final int amount,
        final boolean simulate, final CallbackInfoReturnable<ItemStack> cir) {
        if (simulate || this.rstweaks$rejected == null || this.rstweaks$rejected.isEmpty()) {
            return;
        }
        if (!cir.getReturnValue().isEmpty()) {
            this.rstweaks$rejected.clear();
        }
    }
}
