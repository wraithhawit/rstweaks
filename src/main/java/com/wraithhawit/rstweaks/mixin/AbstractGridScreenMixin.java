package com.wraithhawit.rstweaks.mixin;

import com.refinedmods.refinedstorage.api.resource.repository.ResourceRepository;
import com.refinedmods.refinedstorage.common.api.grid.view.GridResource;
import com.refinedmods.refinedstorage.common.grid.AbstractGridContainerMenu;
import com.refinedmods.refinedstorage.common.grid.screen.AbstractGridScreen;
import com.refinedmods.refinedstorage.common.util.ClientPlatformUtil;

import net.minecraft.client.gui.screens.Screen;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Stops a plain mouse-wheel scroll from switching the grid's sorting off forever.
 *
 * <p>Refined Storage keeps a {@code preventSorting} flag so the list does not reorder under your
 * cursor while you are working in it. It is documented and configured as a <em>while SHIFT is
 * down</em> behaviour — {@code keyPressed} sets it only when {@code hasShiftDown()} and the
 * {@code preventSortingWhileShiftIsDown} option agree. The two mouse-scroll handlers do not honour
 * either condition:
 *
 * <pre>{@code   private void mouseScrolledInGrid(boolean up, GridResource resource) {
 *       getMenu().getRepository().setPreventSorting(true);   // <-- first statement
 *       GridScrollMode scrollMode = getScrollModeWhenScrollingOnGridArea(up);
 *       if (scrollMode != null) { ... }                      // null unless shift or ctrl
 *   } }</pre>
 *
 * <p>The flag is set before anything checks whether a transfer will happen, and no modifier means
 * {@code scrollMode} is null and nothing else happens. So scrolling the list — the ordinary way to
 * look through a grid — latches sorting off. The only code that ever clears it is
 * {@code keyReleased}, which a mouse never reaches, so it stays latched for the life of the screen.
 *
 * <p>The visible result is a row stuck at {@code 0}. With the flag set,
 * {@code ResourceRepositoryImpl.updateExisting} takes its {@code else if (removedFromBackingList)}
 * branch, which logs "no longer available" and leaves the row in the view list while the backing
 * list entry is gone. {@code AbstractGridResource.getAmount} reads the backing list live, so the
 * orphaned row renders zero. Pressing and releasing any key calls {@code sort()}, which rebuilds
 * the view from the backing list and clears it — which is why the bug looks like it is about SHIFT
 * when it is really about the wheel.
 *
 * <p><b>This is a stock Refined Storage bug and reproduces without this mod</b> (scroll the grid,
 * extract the last of anything, watch it sit at 0). Confirmed in game 2026-08-16, and the same
 * structure is present on Refined Storage's {@code develop} branch, so it affects the 3.x line too.
 * We ship a fix because durability-aware planning is what makes it constant rather than rare: a
 * worn tool leaves the network as {@code tool@N} and comes back — via
 * {@link InternalTaskPatternMixin} — only when the task ends, so a resource retires to zero on
 * every single craft. Other packs have the same latch and nothing pulling it. (Issue #15.)
 *
 * <p>Reported upstream; when Refined Storage fixes it this mixin becomes a no-op that agrees with
 * the code beneath it, and can be deleted at the version that carries the fix.
 */
@Mixin(AbstractGridScreen.class)
public abstract class AbstractGridScreenMixin {
    /**
     * Scrolling over the item list.
     *
     * <p>Redirected rather than cancelled at HEAD: the rest of the method is Refined Storage's
     * scroll-transfer handling and must run untouched. Only the latch is conditional.
     */
    @Redirect(
        method = "mouseScrolledInGrid",
        at = @At(
            value = "INVOKE",
            target = "Lcom/refinedmods/refinedstorage/api/resource/repository/"
                + "ResourceRepository;setPreventSorting(Z)Z"
        ),
        require = 0
    )
    private boolean rstweaks$latchGridScrollOnlyForTransfers(
        final ResourceRepository<GridResource> repository,
        final boolean prevent
    ) {
        return rstweaks$latchOnlyForTransfers(repository, prevent);
    }

    /**
     * Scrolling over the player inventory. The descriptor is spelled out because
     * {@code mouseScrolledInInventory} is overloaded and only this one latches.
     */
    @Redirect(
        method = "mouseScrolledInInventory(ZLnet/minecraft/world/inventory/Slot;)V",
        at = @At(
            value = "INVOKE",
            target = "Lcom/refinedmods/refinedstorage/api/resource/repository/"
                + "ResourceRepository;setPreventSorting(Z)Z"
        ),
        require = 0
    )
    private boolean rstweaks$latchInventoryScrollOnlyForTransfers(
        final ResourceRepository<GridResource> repository,
        final boolean prevent
    ) {
        return rstweaks$latchOnlyForTransfers(repository, prevent);
    }

    /**
     * Latch only when a modifier is held.
     *
     * <p>Deliberately a superset of "a transfer will actually happen" rather than a copy of
     * {@code getScrollModeWhenScrollingOnGridArea}'s exact table, which differs between the two
     * call sites and between scroll directions. Reproducing that table here would mean two private
     * static methods to keep in step with Refined Storage across versions, to gain nothing: every
     * combination that transfers is inside this condition, and the combinations that are inside it
     * without transferring are ones where the player is holding a modifier anyway, so keeping the
     * list still is the behaviour they want.
     *
     * <p>The condition also guarantees the latch ends. Holding shift or ctrl means eventually
     * releasing it, and that release reaches {@code keyReleased}, which clears the flag and sorts.
     * A wheel with no modifier had no such exit, which is the whole defect.
     *
     * <p>{@code isCommandOrControlDown} is Refined Storage's own helper, so cmd on macOS counts
     * exactly as it does in the scroll-mode table.
     */
    @Unique
    private static boolean rstweaks$latchOnlyForTransfers(
        final ResourceRepository<GridResource> repository,
        final boolean prevent
    ) {
        if (prevent && !Screen.hasShiftDown() && !ClientPlatformUtil.isCommandOrControlDown()) {
            // Report "unchanged", which is what setPreventSorting returns when the flag already
            // held the requested value. Nothing reads this at either call site, but a redirect
            // that lies about a state change it did not make is a trap for whoever reads it next.
            return false;
        }
        return repository.setPreventSorting(prevent);
    }

    /**
     * Ends the latch when the modifier is no longer held, whoever set it.
     *
     * <p>0.2.86 assumed above that "holding shift or ctrl means eventually releasing it, and that
     * release reaches {@code keyReleased}". That is the hole the phantom row kept coming through.
     * {@code keyReleased} <b>on this screen</b> is the flag's only exit in all of Refined Storage,
     * so any path that ends the modifier somewhere else leaves it latched for the life of the
     * screen — and starting an autocraft is exactly such a path, because the autocrafting preview
     * opens over the grid and takes the key events with it. Release shift there and the grid never
     * learns. `keyPressed` latches on any key pressed with shift down, which we deliberately left
     * alone as stock behaviour, so this is reachable without touching the wheel at all.
     *
     * <p>Latched, {@code ResourceRepositoryImpl.updateExisting} takes its third arm —
     * {@code else if (removedFromBackingList)}, which logs "no longer available" and returns
     * without calling {@code ViewList.remove} — so a resource that leaves the network keeps its
     * row while the backing entry is gone, and {@code getAmount} renders it as 0. Durability
     * crafting retires a wear level on every craft, which is what turns a rare stock wart into a
     * ghost row after every single craft.
     *
     * <p>Rather than enumerate the ways a modifier can end elsewhere, this restates the invariant
     * once a tick: <b>the flag may only be set while a modifier is actually held.</b> The body is
     * {@code keyReleased}'s, so a healed latch behaves exactly as if the release had arrived. While
     * a modifier is genuinely down this does nothing and Refined Storage's intended "hold shift to
     * keep the list still" is untouched.
     *
     * @implNote {@code setPreventSorting} returns whether it changed anything, so the common case —
     *     flag already clear — costs one field compare and no sort.
     */
    @Inject(method = "containerTick", at = @At("TAIL"))
    private void rstweaks$unlatchWhenNoModifierHeld(final CallbackInfo ci) {
        if (Screen.hasShiftDown() || ClientPlatformUtil.isCommandOrControlDown()) {
            return;
        }
        final ResourceRepository<GridResource> repository =
            ((AbstractGridContainerMenu) ((AbstractGridScreen<?>) (Object) this).getMenu())
                .getRepository();
        if (repository.setPreventSorting(false)) {
            repository.sort();
        }
    }
}
