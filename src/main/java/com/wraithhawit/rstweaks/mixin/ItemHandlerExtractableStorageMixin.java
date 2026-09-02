package com.wraithhawit.rstweaks.mixin;

import com.refinedmods.refinedstorage.api.core.Action;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import com.refinedmods.refinedstorage.neoforge.storage.ItemHandlerExtractableStorage;
import com.wraithhawit.rstweaks.Config;
import com.wraithhawit.rstweaks.ServerTicks;
import com.wraithhawit.rstweaks.Stats;
import com.wraithhawit.rstweaks.storage.ExternalSlotIndex;

import java.util.List;

import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Stops Refined Storage calling {@code getSlots()} once per slot while scanning an
 * external inventory.
 *
 * <p>The extraction loop is written as:
 *
 * <pre>{@code   for (int slot = 0; slot < itemHandler.getSlots(); slot++) }</pre>
 *
 * <p>Java re-evaluates the condition every iteration, and the JIT cannot hoist it
 * because {@code getSlots()} is an interface call to an arbitrary implementation.
 * So scanning a 5,000-slot drawer controller makes 5,001 calls to it.
 *
 * <p>That is only free if {@code getSlots()} is a field read. It frequently is not —
 * Sophisticated Storage's {@code CachedFailedInsertInventoryHandler.getSlots()}
 * resolves a {@code Supplier} chain on every call. In a profile of a struggling
 * server this showed as {@code getSlots} costing 2.58% + 1.28% of the entire server
 * thread, roughly 6 ms/tick, purely from being asked the same question thousands of
 * times per extraction.
 *
 * <p>Fixing it here rather than in each storage mod is deliberate: the waste is in
 * the caller, and one change covers every {@code IItemHandler} implementation at
 * once — drawers, barrels, backpacks, everything.
 *
 * <p><b>Why re-reading after an extraction is necessary.</b> Naively hoisting the
 * call out of the loop is not safe. Extraction can change the slot count — pulling
 * from a Drawer Controller whose handler has gone away triggers
 * {@code invalidateSlots()}, shrinking it. The loop would then run past the end and
 * call {@code getStackInSlot} out of range; a Drawer Controller tolerates that and
 * returns empty, but a plain {@code ItemStackHandler} throws.
 *
 * <p>The count can only change as a consequence of our own {@code extractItem} call,
 * since the server thread is not doing anything else during this method. So
 * invalidating the cache after each real extraction is sufficient and exact:
 * {@code getSlots()} is called once for the scan plus once per slot actually
 * extracted from, instead of once per slot examined.
 *
 * <p>Simulated extractions cannot mutate anything, so they skip the invalidation —
 * which matters because Refined Storage simulates constantly.
 */
@Mixin(ItemHandlerExtractableStorage.class)
public abstract class ItemHandlerExtractableStorageMixin {
    private static final String EXTRACT =
        "extract(JLcom/refinedmods/refinedstorage/api/core/Action;"
            + "Lnet/neoforged/neoforge/items/IItemHandler;"
            + "Lnet/minecraft/world/item/ItemStack;)J";

    private static final String EXTRACT_SLOT =
        "extractSlot(Lcom/refinedmods/refinedstorage/api/core/Action;"
            + "Lnet/neoforged/neoforge/items/IItemHandler;"
            + "Lnet/minecraft/world/item/ItemStack;II)I";

    /** Slot count for the scan in progress; negative means "not yet read". */
    @Unique
    private int rstweaks$cachedSlots = -1;

    /** Where each resource lives in this handler. Never authoritative; see the class. */
    @Unique
    private ExternalSlotIndex rstweaks$index;

    /**
     * Slots the indexed pass already consulted, so the fallback scan can skip them.
     *
     * <p>This is the one thing that must not be got wrong. Under {@code SIMULATE}
     * nothing is mutated, so examining a slot twice would count its contents twice
     * and tell Refined Storage it can extract more than exists.
     */
    @Unique
    private boolean[] rstweaks$visited;

    @Inject(method = EXTRACT, at = @At("HEAD"), cancellable = true)
    private void rstweaks$indexedExtract(final long amount,
                                       final Action action,
                                       final IItemHandler itemHandler,
                                       final ItemStack stack,
                                       final CallbackInfoReturnable<Long> cir) {
        // Set here rather than relying on a field initialiser, which Mixin handles
        // unreliably, and so state can never carry across two scans.
        this.rstweaks$cachedSlots = -1;
        this.rstweaks$visited = null;
        // Must be cleared here too: when this injector cancels, the RETURN injector
        // that normally consumes it never runs, and a leftover value would be added
        // to an unrelated later extraction.
        this.rstweaks$partial = 0L;

        if (!Config.externalStorageSlotIndex) {
            return;
        }
        final int slots = itemHandler.getSlots();
        if (slots < Config.MIN_SLOTS_TO_INDEX.getAsInt()) {
            // Small inventories scan faster than they index.
            return;
        }
        this.rstweaks$cachedSlots = slots;

        final long now = ServerTicks.current();
        final int ttl = Config.EXTERNAL_INDEX_TTL_TICKS.getAsInt();
        ExternalSlotIndex index = this.rstweaks$index;
        if (index == null) {
            index = new ExternalSlotIndex();
            this.rstweaks$index = index;
        }
        if (!index.isUsable(itemHandler, now, ttl)) {
            index.rebuild(itemHandler, slots, now);
            ++Stats.externalIndexRebuilds;
        }

        final ItemResource wanted = ItemResource.ofItemStack(stack);
        final List<Integer> candidates = index.candidates(wanted);
        if (candidates.isEmpty()) {
            // A usable index reporting no slots is a real answer, not "unknown".
            // This is the common case worth optimising: a network of ten external
            // storages asks all ten for every resource, and most do not have it.
            ++Stats.externalIndexHits;
            cir.setReturnValue(0L);
            return;
        }

        long extracted = 0L;
        final boolean[] visited = new boolean[slots];
        // Copy: extraction can call forget(), mutating the candidate list.
        for (final int slot : List.copyOf(candidates)) {
            if (slot < 0 || slot >= slots) {
                continue;
            }
            final ItemStack inSlot = itemHandler.getStackInSlot(slot);
            visited[slot] = true;
            if (!ItemStack.isSameItemSameComponents(inSlot, stack)) {
                // Stale entry. Verification caught it, so nothing is extracted wrongly -- but the
                // index no longer describes reality.
                index.invalidate();
                this.rstweaks$visited = visited;
                // Carry what earlier candidate slots already gave up. Without this line, a real
                // extraction that pulled from one slot and then met a stale entry took those items
                // out of the inventory and never told Refined Storage: the scan below returns only
                // what it finds itself, and the difference is destroyed. Items silently
                // disappearing from a drawer wall is exactly what that looks like in game.
                this.rstweaks$partial = extracted;
                ++Stats.externalIndexFallbacks;
                return;
            }
            final long remaining = amount - extracted;
            extracted += this.rstweaks$extractFromSlot(action, itemHandler, inSlot, slot, remaining);
            if (extracted >= amount) {
                ++Stats.externalIndexHits;
                cir.setReturnValue(extracted);
                return;
            }
        }

        // Candidates exhausted without satisfying the request. Hand the remainder to
        // the original scan, telling it which slots are already accounted for.
        index.invalidate();
        this.rstweaks$visited = visited;
        this.rstweaks$partial = extracted;
        ++Stats.externalIndexFallbacks;
    }

    /** Extracted by the indexed pass before falling back; added to the scan's result. */
    @Unique
    private long rstweaks$partial;

    /**
     * Refined Storage's own per-slot extraction, called rather than reimplemented.
     *
     * <p>Reimplementing it is what broke shift-click crafting from a drawer wall. The simulated
     * branch is not the one-liner it looks like: a drawer holding 16,900 of an item answers
     * {@code extractItem(slot, MAX_VALUE, true)} with a single stack, because one call can only
     * hand back one stack. Refined Storage recognises that shape --
     * {@code extracted == maxStackSize && amountInSlot > maxStackSize} -- and reports what the
     * slot really holds. Our indexed pass called {@code extractItem} directly and returned the 64,
     * so anything that asks "how many could I get?" was told 64 while the drawer held thousands.
     *
     * <p>Shadowed so the fast path and the scan it stands in for can never disagree again. The
     * signature is already load-bearing here -- {@link #rstweaks$invalidateAfterExtract} redirects
     * inside this same method -- so this adds no coupling that was not there.
     */
    @Shadow
    private int extractSlot(final Action action,
                            final IItemHandler itemHandler,
                            final ItemStack stackInSlot,
                            final int slot,
                            final int amount) {
        throw new AssertionError("shadow");
    }

    @Unique
    private long rstweaks$extractFromSlot(final Action action,
                                        final IItemHandler itemHandler,
                                        final ItemStack inSlot,
                                        final int slot,
                                        final long amount) {
        final int capped = (int) Math.min(amount, Integer.MAX_VALUE);
        // Goes through the redirect below, so a real extraction that resizes the handler drops the
        // cached slot count -- which the hand-rolled loop this replaced never did.
        final int extracted = this.extractSlot(action, itemHandler, inSlot, slot, capped);
        if (action == Action.SIMULATE) {
            return extracted;
        }
        if (extracted > 0 && itemHandler.getStackInSlot(slot).isEmpty()
            && this.rstweaks$index != null) {
            this.rstweaks$index.forget(ItemResource.ofItemStack(inSlot), slot);
        }
        return extracted;
    }

    @Redirect(
        method = EXTRACT,
        at = @At(value = "INVOKE",
            target = "Lnet/neoforged/neoforge/items/IItemHandler;"
                + "getStackInSlot(I)Lnet/minecraft/world/item/ItemStack;")
    )
    private ItemStack rstweaks$skipVisited(final IItemHandler itemHandler, final int slot) {
        final boolean[] visited = this.rstweaks$visited;
        if (visited != null && slot >= 0 && slot < visited.length && visited[slot]) {
            // Already counted by the indexed pass. Returning empty makes the scan skip
            // it, which is what stops SIMULATE double-counting.
            return ItemStack.EMPTY;
        }
        return itemHandler.getStackInSlot(slot);
    }

    @Inject(method = EXTRACT, at = @At("RETURN"), cancellable = true)
    private void rstweaks$addPartial(final long amount,
                                   final Action action,
                                   final IItemHandler itemHandler,
                                   final ItemStack stack,
                                   final CallbackInfoReturnable<Long> cir) {
        if (this.rstweaks$partial > 0L) {
            cir.setReturnValue(cir.getReturnValue() + this.rstweaks$partial);
            this.rstweaks$partial = 0L;
        }
        this.rstweaks$visited = null;
    }

    @Redirect(
        method = EXTRACT,
        at = @At(value = "INVOKE", target = "Lnet/neoforged/neoforge/items/IItemHandler;getSlots()I")
    )
    private int rstweaks$cachedSlotCount(final IItemHandler itemHandler) {
        if (this.rstweaks$cachedSlots < 0) {
            this.rstweaks$cachedSlots = itemHandler.getSlots();
            return this.rstweaks$cachedSlots;
        }
        ++Stats.slotCountLookupsAvoided;
        return this.rstweaks$cachedSlots;
    }

    @Redirect(
        method = EXTRACT_SLOT,
        at = @At(
            value = "INVOKE",
            target = "Lnet/neoforged/neoforge/items/IItemHandler;"
                + "extractItem(IIZ)Lnet/minecraft/world/item/ItemStack;"
        )
    )
    private ItemStack rstweaks$invalidateAfterExtract(final IItemHandler itemHandler,
                                                    final int slot,
                                                    final int amount,
                                                    final boolean simulate) {
        final ItemStack result = itemHandler.extractItem(slot, amount, simulate);
        if (!simulate) {
            // A real extraction may have resized the handler; force the next loop
            // check to read the live count.
            this.rstweaks$cachedSlots = -1;
        }
        return result;
    }
}
