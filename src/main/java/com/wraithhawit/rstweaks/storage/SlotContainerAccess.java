package com.wraithhawit.rstweaks.storage;

import com.refinedmods.refinedstorage.common.api.support.resource.ResourceContainer;

/**
 * Exposes which container a {@code ResourceSlot} belongs to.
 *
 * <p>The field is protected and declared on {@code ResourceSlot} itself, so a mixin can shadow it,
 * and its type is public API. Together with {@link ProcessingInputContainer} this is enough to tell
 * the processing input slots apart from every other slot in the menu.
 */
public interface SlotContainerAccess {
    ResourceContainer rstweaks$container();

    /**
     * Points this slot at a different container.
     *
     * <p>How the fluid tab gets its own matrix without a second set of slots. Slots belong to a
     * menu and containers belong to the block entity, so pointing one menu's slots at the fluid
     * pair and another's at the Processing pair lets two players hold the same grid on different
     * tabs — which the copy-in/copy-out stash could never express, having only one live matrix.
     *
     * <p>Nothing has to be synced afterwards. {@code ResourceSlot.broadcastChanges} compares the
     * container's current contents against what the client was last told, so the next tick sends
     * the difference by itself; rebinding to a container holding the same thing correctly sends
     * nothing at all.
     */
    void rstweaks$rebind(ResourceContainer container);
}