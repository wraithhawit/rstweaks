package com.wraithhawit.rstweaks.storage;

import com.refinedmods.refinedstorage.common.support.resource.ItemResource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * Maps a resource to the slots of an external inventory that hold it, so Refined
 * Storage does not have to walk every slot to find one.
 *
 * <p>RS's extraction scans linearly:
 * {@code for (slot = 0; slot < getSlots(); slot++) if (matches) extract}. Behind a
 * drawer controller or a wall of Sophisticated barrels that is thousands of
 * {@code getStackInSlot} calls, and RS <em>simulates</em> constantly, so it runs far
 * more often than real extractions. On a struggling server this path measured
 * 42.9 ms/tick.
 *
 * <p><b>The index never decides whether an item is extractable.</b> It supplies slot
 * numbers to try; the caller re-reads each one live and compares with
 * {@code isSameItemSameComponents} exactly as before. A wrong index therefore costs
 * one slow scan, never a wrong answer — which matters because a wrong answer means
 * phantom items in the grid or a craft that stalls.
 *
 * <p>Absence is the one thing that cannot be verified by reading a slot, so it is
 * bounded by time instead: the index expires after {@code ttlTicks}. Reporting "this
 * inventory has no iron" from an index up to a second old is consistent with RS
 * itself, whose {@code ExternalStorage.cache} is only refreshed by
 * {@code detectChanges()} on a 5–40 tick schedule. Absence caching is also where most
 * of the win is — a network with ten external storages asks all ten for every
 * resource, and nine of them typically do not have it.
 */
public final class ExternalSlotIndex {
    private final Map<ItemResource, List<Integer>> slotsByResource = new HashMap<>();

    /** Identity of the handler this index describes; a different one invalidates it. */
    private IItemHandler indexedHandler;
    private int indexedSlotCount;
    private long builtAtTick = Long.MIN_VALUE;

    /** True when this index can answer questions about {@code handler} right now. */
    public boolean isUsable(final IItemHandler handler, final long nowTick, final int ttlTicks) {
        return this.indexedHandler == handler
            && this.builtAtTick != Long.MIN_VALUE
            && nowTick - this.builtAtTick < ttlTicks;
    }

    /**
     * Full scan, recording where everything is. This costs exactly what one of RS's
     * own scans costs, and pays for itself across every subsequent lookup until it
     * expires.
     */
    public void rebuild(final IItemHandler handler, final int slotCount, final long nowTick) {
        this.slotsByResource.clear();
        for (int slot = 0; slot < slotCount; slot++) {
            final ItemStack stack = handler.getStackInSlot(slot);
            if (stack.isEmpty()) {
                continue;
            }
            this.slotsByResource
                .computeIfAbsent(ItemResource.ofItemStack(stack), k -> new ArrayList<>(2))
                .add(slot);
        }
        this.indexedHandler = handler;
        this.indexedSlotCount = slotCount;
        this.builtAtTick = nowTick;
    }

    /**
     * Slots believed to hold this resource, or an empty list if the index says the
     * inventory does not have it. Never null — an empty result from a usable index is
     * a real answer, not "unknown".
     */
    public List<Integer> candidates(final ItemResource resource) {
        final List<Integer> slots = this.slotsByResource.get(resource);
        return slots == null ? List.of() : slots;
    }

    public int indexedSlotCount() {
        return this.indexedSlotCount;
    }

    /**
     * Drops a slot from a resource's candidate list once it has been drained, so
     * repeat extractions do not keep retrying an empty slot. Cheap bookkeeping, not
     * a correctness mechanism — the caller still verifies every slot it uses.
     */
    public void forget(final ItemResource resource, final int slot) {
        final List<Integer> slots = this.slotsByResource.get(resource);
        if (slots == null) {
            return;
        }
        slots.remove(Integer.valueOf(slot));
        if (slots.isEmpty()) {
            this.slotsByResource.remove(resource);
        }
    }

    /**
     * Marks the index untrustworthy. Called when the candidates could not satisfy a
     * request, which means the inventory changed underneath us.
     */
    public void invalidate() {
        this.builtAtTick = Long.MIN_VALUE;
        this.indexedHandler = null;
        this.slotsByResource.clear();
    }
}
