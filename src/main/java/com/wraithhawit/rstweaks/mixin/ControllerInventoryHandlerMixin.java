package com.wraithhawit.rstweaks.mixin;

import com.buuz135.functionalstorage.inventory.ControllerInventoryHandler;
import com.buuz135.functionalstorage.util.ConnectedDrawers;
import com.wraithhawit.rstweaks.Stats;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.neoforged.neoforge.items.IItemHandler;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Makes the Drawer Controller's connectivity check O(1) instead of O(drawers).
 *
 * <p>Diagnosis, from a profile of a Refined Storage network with an External Storage
 * pointed at a Functional Storage Drawer Controller:
 * {@code ControllerInventoryHandler.getStackInSlot} was <b>13.97% of the server
 * thread</b> — the single largest frame, at 9.3 ms/tick, with another 3.4 ms in
 * {@code insertItem}/{@code extractItem}. RS walks the controller constantly, so
 * anything linear in there is multiplied by every extraction.
 *
 * <p>The slot lookup itself is already O(1) — {@code selectors[slot]} is an array
 * index. The cost is the guard that follows it:
 *
 * <pre>{@code   if (!getDrawers().getItemHandlers().contains(selector.handler)) }</pre>
 *
 * <p>a linear scan of every connected handler, run on every read, insert and extract,
 * purely to confirm the handler is still part of the network.
 *
 * <p><b>This caches membership, never item data.</b> That distinction is the whole
 * safety argument. Stack contents are still read live through
 * {@code selector.getStackInSlot()} on every single call, so a hopper, pipe, conduit
 * or player altering drawer contents is seen immediately — a cache of counts would
 * have been genuinely dangerous here, producing phantom items and failed extractions.
 * The cached set answers only "is this handler still connected", which changes solely
 * when drawers are added or removed.
 *
 * <p>Invalidation is already correct upstream and we simply reuse it:
 * {@code ConnectedDrawers.rebuild()} is the one place the handler list is replaced,
 * and it calls {@code invalidateSlots()} on both handlers immediately after
 * repopulating. Rebuilding the set at the tail of {@code invalidateSlots} therefore
 * cannot observe a half-built list.
 *
 * <p>A {@link HashSet} rather than an identity set, so {@code contains} keeps exactly
 * the {@code equals}-based semantics of the {@code List.contains} it replaces.
 */
@Mixin(ControllerInventoryHandler.class)
public abstract class ControllerInventoryHandlerMixin {
    @Shadow
    public abstract ConnectedDrawers getDrawers();

    /**
     * Connected handlers, mirroring {@code getDrawers().getItemHandlers()}.
     * Lazily assigned rather than field-initialised: {@code invalidateSlots()} is
     * called from the constructor, and Mixin's handling of instance field
     * initialisers is unreliable enough that one would risk clobbering this.
     */
    @Unique
    private Set<IItemHandler> rstweaks$connectedHandlers;

    @Inject(method = "invalidateSlots", at = @At("RETURN"))
    private void rstweaks$cacheMembership(final CallbackInfo ci) {
        final ConnectedDrawers drawers = this.getDrawers();
        if (drawers == null) {
            this.rstweaks$connectedHandlers = null;
            return;
        }
        this.rstweaks$connectedHandlers = new HashSet<>(drawers.getItemHandlers());
    }

    @Redirect(
        method = {"getStackInSlot", "insertItem", "extractItem"},
        at = @At(value = "INVOKE", target = "Ljava/util/List;contains(Ljava/lang/Object;)Z")
    )
    private boolean rstweaks$fastMembership(final List<?> handlers, final Object handler) {
        final Set<IItemHandler> cached = this.rstweaks$connectedHandlers;
        if (cached == null) {
            // Before the first invalidateSlots, or if the drawer set was unavailable.
            // Falling through to the original scan keeps behaviour identical.
            return handlers.contains(handler);
        }
        ++Stats.drawerMembershipChecks;
        return cached.contains(handler);
    }
}
