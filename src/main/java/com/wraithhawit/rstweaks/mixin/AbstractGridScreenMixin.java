package com.wraithhawit.rstweaks.mixin;

import java.util.Arrays;
import java.util.List;

import com.refinedmods.refinedstorage.api.network.node.grid.GridExtractMode;
import com.refinedmods.refinedstorage.api.network.node.grid.GridInsertMode;
import com.refinedmods.refinedstorage.api.resource.repository.ResourceRepository;
import com.refinedmods.refinedstorage.common.api.grid.strategy.GridExtractionStrategy;
import com.refinedmods.refinedstorage.common.api.grid.view.GridResource;
import com.refinedmods.refinedstorage.common.grid.AbstractGridContainerMenu;
import com.refinedmods.refinedstorage.common.grid.screen.AbstractGridScreen;
import com.refinedmods.refinedstorage.common.util.ClientPlatformUtil;

import com.wraithhawit.rstweaks.Config;
import com.wraithhawit.rstweaks.GridContainers;
import com.wraithhawit.rstweaks.RSTweaks;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.item.ItemStack;

import org.jetbrains.annotations.Nullable;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

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

    // ------------------------------------------------------------------------------------
    // Holding a tank changes what a grid click means. (Issue #17.)
    //
    // With a container on the cursor the carried stack is a DESTINATION, not cargo, and
    // Refined Storage's stock bindings read badly for it:
    //
    //   right-click  -> SINGLE_RESOURCE, one bucket                   (the only precise action)
    //   left-click   -> ENTIRE_RESOURCE, "give me everything"
    //   shift        -> nothing at all on insert; "to inventory" on extract
    //
    // and ENTIRE_RESOURCE does not mean the whole tank, because it cannot. Refined Storage
    // asks the container's own handler for Long.MAX_VALUE and takes what it is given, and a
    // Mekanism tank hands over one tier transfer rate per operation - 64 B on an Ultimate,
    // against a 256 B capacity (FluidTankTier: BASIC 32 B/1 B, ADVANCED 64/4, ELITE 128/16,
    // ULTIMATE 256/64). So "dump the whole tank" is not a bigger transfer, it is the same
    // transfer several times, and the count comes from GridContainers, which measures the
    // container with a simulated transfer instead of assuming a tier table.
    //
    // The bindings below, active only while a fluid or chemical container is on the cursor:
    //
    //   left-click            on a row it can accept: fill the container by one bucket
    //   right-click           empty one bucket of it into the network, anywhere in the grid
    //   shift + either        as many operations as it takes to fill or empty it
    //
    // Left fills and right empties whichever you clicked, so a tank no longer has to be
    // dumped by hunting for blank space in the grid: right-clicking the fluid's own row now
    // does it. Everything else - an ordinary item on the cursor, an empty cursor, ctrl-click,
    // autocrafting - routes exactly as it did, because the hook returns without touching the
    // callback unless GridContainers says the cursor holds a tank.
    //
    // The click is resolved from the coordinates it happened at, not from the row Refined
    // Storage last DREW as hovered. See rstweaks$containerClick - that distinction is a bug
    // fix, not a refinement.
    //
    // EVERY insert here passes tryAlternatives = true, and that flag is not optional. Read
    // CompositeGridInsertionStrategy:
    //
    //   if (tryAlternatives) {
    //       for (GridInsertionStrategy alt : alternativeStrategies) {
    //           if (alt.onInsert(insertMode, true)) return true;
    //       }
    //   }
    //   return this.defaultStrategy.onInsert(insertMode, tryAlternatives);
    //
    // The fluid and chemical strategies are the ALTERNATIVES; the default is the item
    // strategy, which inserts the carried stack itself and never declines. So the flag does
    // not mean "try harder after failing" - it means "consider fluids at all", and passing
    // false does not fall back to storing the tank, it goes straight there. 0.2.101 passed
    // Refined Storage's own (clickedButton == 1) through and put the tank in the network on
    // every left-click and on every tick of a shift-repeat. Passing true keeps the fallback
    // that stores an EMPTY tank as an item, because the fluid strategy declines an empty
    // container on its own and the default runs after it.
    // ------------------------------------------------------------------------------------

    /**
     * The slot rectangles Refined Storage drew last frame, so a click can be resolved against
     * the coordinates it actually happened at.
     *
     * <p>Three parallel arrays rather than objects because this is rebuilt every frame and
     * read on every click; 63 cells is the usual grid and the arrays grow if a stretched
     * screen wants more.
     */
    @Unique
    private int[] rstweaks$cellIndex = new int[128];

    @Unique
    private int[] rstweaks$cellX = new int[128];

    @Unique
    private int[] rstweaks$cellY = new int[128];

    @Unique
    private int rstweaks$cellCount;

    /**
     * Starts a fresh frame's worth of slot rectangles.
     *
     * <p>Same place Refined Storage clears {@code currentGridSlotIndex}, for the same reason.
     */
    @Inject(method = "renderRows", at = @At("HEAD"), require = 0)
    private void rstweaks$beginCells(
        final GuiGraphics graphics,
        final int x,
        final int y,
        final int topHeight,
        final int rows,
        final int mouseX,
        final int mouseY,
        final CallbackInfo ci
    ) {
        rstweaks$cellCount = 0;
    }

    /**
     * Records where one grid cell was drawn.
     *
     * <p>Hooked here rather than at {@code renderCell} because this method is <em>handed</em>
     * {@code slotX} and {@code slotY} already computed. Nothing about the layout - the seven
     * pixel inset, the eighteen pixel pitch, nine columns, the scrollbar offset - is repeated
     * on our side, so a change to any of it is picked up rather than drifted from.
     */
    @Inject(
        method = "renderSlot(Lnet/minecraft/client/gui/GuiGraphics;IIILcom/refinedmods/"
            + "refinedstorage/api/resource/repository/ResourceRepository;II)V",
        at = @At("HEAD"),
        require = 0
    )
    private void rstweaks$recordCell(
        final GuiGraphics graphics,
        final int mouseX,
        final int mouseY,
        final int idx,
        final ResourceRepository<GridResource> repository,
        final int slotX,
        final int slotY,
        final CallbackInfo ci
    ) {
        if (rstweaks$cellCount == rstweaks$cellIndex.length) {
            rstweaks$cellIndex = Arrays.copyOf(rstweaks$cellIndex, rstweaks$cellCount * 2);
            rstweaks$cellX = Arrays.copyOf(rstweaks$cellX, rstweaks$cellCount * 2);
            rstweaks$cellY = Arrays.copyOf(rstweaks$cellY, rstweaks$cellCount * 2);
        }
        rstweaks$cellIndex[rstweaks$cellCount] = idx;
        rstweaks$cellX[rstweaks$cellCount] = slotX;
        rstweaks$cellY[rstweaks$cellCount] = slotY;
        ++rstweaks$cellCount;
    }

    /**
     * Every grid click made while holding a container, decided from the click's own position.
     *
     * <p>This takes over the whole routing decision instead of hooking the two entry points
     * underneath it, because <b>the routing itself is what was wrong</b>. Refined Storage
     * resolves the clicked row from {@code currentGridSlotIndex}, which is assigned while
     * <em>rendering</em>:
     *
     * <pre>{@code   protected void renderRows(...) {
     *       this.currentGridSlotIndex = -1;
     *       ...
     *   }
     *   private void renderSlot(GuiGraphics g, int mouseX, int mouseY, int idx, ...) {
     *       boolean inBounds = mouseX >= slotX && ...;
     *       if (inBounds && isOverStorageArea(mouseX, mouseY)) {
     *           if (resource != null) this.currentGridSlotIndex = idx;
     *       }
     *   } }</pre>
     *
     * <p>So a click is answered with the row the <em>last drawn frame</em> was hovering. Move
     * the cursor onto a row and click before the next frame and the index is stale or -1,
     * {@code canExtract} is false, and the click falls through to the insert branch. In stock
     * Refined Storage that is nearly harmless - a left-click inserts the carried stack, which
     * is what a left-click on blank space does anyway. Holding a tank it is not: the tank goes
     * into the network instead of being filled. Reported in game on 0.2.103 as "sometimes it
     * won't fill and will just insert, I think it's if I hover over it too fast".
     *
     * <p>{@code rstweaks$cellAt} answers from the rectangles recorded above and the
     * coordinates this click carries, so the outcome no longer depends on whether a frame
     * happened to land between the mouse moving and the button going down.
     *
     * <p>All four outcomes are handled here and the callback is cancelled for every one of
     * them. Falling through for some would put stock back in charge of those, reading the same
     * stale index - the bug this exists to avoid.
     */
    @Inject(
        method = "mouseClicked(DDILcom/refinedmods/refinedstorage/common/api/grid/view/"
            + "GridResource;Lnet/minecraft/world/item/ItemStack;)Z",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private void rstweaks$containerClick(
        final double mouseX,
        final double mouseY,
        final int clickedButton,
        @Nullable final GridResource staleResource,
        final ItemStack carriedStack,
        final CallbackInfoReturnable<Boolean> cir
    ) {
        if (!Config.containerGridClicks
            || (clickedButton != 0 && clickedButton != 1)
            || ClientPlatformUtil.isCommandOrControlDown()
            || !GridContainers.isBulkContainer(carriedStack)) {
            return;
        }
        final AbstractGridContainerMenu menu = rstweaks$menu();
        final int cell = rstweaks$cellAt(mouseX, mouseY);
        final GridResource resource = rstweaks$rowAt(menu, cell, staleResource);
        final boolean all = Screen.hasShiftDown();
        if (Config.logGridViewDiagnostics) {
            RSTweaks.LOGGER.info(
                "[rstweaks][grid] container click button={} shift={} at=({},{}) cells={} "
                    + "cell={} stale={} resolved={} canExtract={}",
                clickedButton, all, (int) mouseX, (int) mouseY, rstweaks$cellCount, cell,
                staleResource == null ? "none" : staleResource.getName(),
                resource == null ? "none" : resource.getName(),
                resource != null && resource.canExtract(carriedStack, menu.getRepository())
            );
        }
        if (clickedButton == 1) {
            if (cell < 0 && resource == null) {
                // Nothing says we are over the grid at all. Let stock route it; if it decides
                // this is an insert, rstweaks$containerInsert picks it up there.
                return;
            }
            rstweaks$insert(menu, carriedStack, all);
            cir.setReturnValue(true);
            return;
        }
        if (resource != null && resource.canExtract(carriedStack, menu.getRepository())) {
            rstweaks$fill(menu, carriedStack, resource, all);
            cir.setReturnValue(true);
            return;
        }
        if (cell < 0) {
            // No cell under the click and no row to fall back on. Stock decides, which for a
            // left-click over the grid means storing the container as an item.
            return;
        }
        // An empty cell, or a resource this container will not take. Left-click then means
        // what it means in stock Refined Storage: put the thing you are holding away.
        menu.onInsert(GridInsertMode.ENTIRE_RESOURCE, false);
        cir.setReturnValue(true);
    }

    /**
     * The row this click landed on: measured if we can, Refined Storage's own answer if not.
     *
     * <p>The fallback is the point. 0.2.104 trusted the recorded rectangles alone and returned
     * early when none matched, which made every click worse than 0.2.103 if that recording is
     * ever empty or misplaced - one unproven assumption silently disabling the feature. Falling
     * back to {@code staleResource} means the fresh answer can only ever be an improvement on
     * the stale one: when the rectangles are right the click is resolved from its own
     * coordinates, and when they are not, behaviour is exactly 0.2.103's.
     *
     * <p>Which of the two actually answered is in the diagnostic line above, under
     * {@code logGridViewDiagnostics}. That is the thing to read before theorising about this
     * method again.
     */
    @Unique
    @Nullable
    private GridResource rstweaks$rowAt(
        final AbstractGridContainerMenu menu,
        final int cell,
        @Nullable final GridResource staleResource
    ) {
        if (cell < 0) {
            return staleResource;
        }
        final List<GridResource> viewList = menu.getRepository().getViewList();
        return cell < viewList.size() ? viewList.get(cell) : null;
    }

    /**
     * Right-clicking somewhere the router above declined to claim.
     *
     * <p>Reached only when {@code rstweaks$containerClick} returned without cancelling and
     * Refined Storage then decided the click was an insert - which is what it decides for
     * blank grid space. Emptying a container should work anywhere in the grid, including the
     * blank area past the end of the list, and this is what covers that.
     *
     * <p>Kept as a separate hook rather than folded into the router because it answers a
     * question the router cannot: "is this inside the storage area", which
     * {@code AbstractGridScreen.canInsert} has already decided by the time this runs. Left
     * click is deliberately not handled - over blank space it means store the container, which
     * is stock behaviour and needs no help from us.
     */
    @Inject(method = "mouseClickedInGrid(I)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void rstweaks$containerInsert(final int clickedButton, final CallbackInfo ci) {
        if (!Config.containerGridClicks
            || clickedButton != 1
            || ClientPlatformUtil.isCommandOrControlDown()) {
            return;
        }
        final AbstractGridContainerMenu menu = rstweaks$menu();
        final ItemStack carried = menu.getCarried();
        if (!GridContainers.isBulkContainer(carried)) {
            return;
        }
        if (Config.logGridViewDiagnostics) {
            RSTweaks.LOGGER.info("[rstweaks][grid] container insert fallback (blank area)");
        }
        rstweaks$insert(menu, carried, Screen.hasShiftDown());
        ci.cancel();
    }

    /**
     * The index of the grid cell drawn under this point, or -1.
     *
     * <p>Refined Storage's own hit test, {@code mouseX >= slotX && ... <= slotX + 16}, against
     * rectangles it computed itself.
     */
    @Unique
    private int rstweaks$cellAt(final double mouseX, final double mouseY) {
        for (int cell = 0; cell < rstweaks$cellCount; cell++) {
            final int x = rstweaks$cellX[cell];
            final int y = rstweaks$cellY[cell];
            if (mouseX >= x && mouseY >= y && mouseX <= x + 16 && mouseY <= y + 16) {
                return rstweaks$cellIndex[cell];
            }
        }
        return -1;
    }

    /**
     * Fill the carried container from this row.
     *
     * <p>{@code cursor = true} unconditionally. Stock derives that from "is shift down",
     * meaning "put the result in my inventory instead" - a bucket rule that makes no sense
     * once shift means "fill it up", and one the fluid strategy ignores anyway when a
     * container is on the cursor. Passing true says the thing being filled is the thing you
     * are holding, which is the only reading available here.
     */
    @Unique
    private void rstweaks$fill(
        final AbstractGridContainerMenu menu,
        final ItemStack carried,
        final GridResource resource,
        final boolean all
    ) {
        final int operations = all
            ? GridContainers.operationsToFill(carried, resource.getResourceForRecipeMods())
            : 1;
        final GridExtractMode mode = all
            ? GridExtractMode.ENTIRE_RESOURCE
            : GridExtractMode.SINGLE_RESOURCE;
        for (int operation = 0; operation < operations; operation++) {
            resource.onExtract(mode, true, (GridExtractionStrategy) menu);
        }
    }

    /**
     * One insert operation, or as many as it takes to empty the container on a shift-click.
     *
     * <p>{@code tryAlternatives} is always true. See the block comment above: it is what
     * makes the fluid and chemical strategies eligible at all, and false sends the tank
     * itself into the network instead of what is inside it.
     */
    @Unique
    private void rstweaks$insert(
        final AbstractGridContainerMenu menu,
        final ItemStack carried,
        final boolean all
    ) {
        final int operations = all ? GridContainers.operationsToEmpty(carried) : 1;
        final GridInsertMode mode = all
            ? GridInsertMode.ENTIRE_RESOURCE
            : GridInsertMode.SINGLE_RESOURCE;
        for (int operation = 0; operation < operations; operation++) {
            menu.onInsert(mode, true);
        }
    }

    // A shift-click sends its operations together, in the tick it was made, because the count
    // is known before the first one leaves. GridContainers divides the container's contents -
    // or its free space - by what one operation moves, and both figures come from simulating
    // that very operation against the container on the cursor, so the count is measured and
    // not derived from a tier table we would have to keep in step with Mekanism.
    //
    // 0.2.101 and 0.2.102 instead re-sent one operation per tick for as long as the cursor
    // stack kept changing, and stopped after eight quiet ticks. That needed no measurement at
    // all, which is why it was tried first, but it spread a click over most of a second and
    // spent its last eight ticks waiting to find out it was finished.
    //
    // Overshooting is harmless and undershooting is unlikely: an operation with nothing left
    // to move is a packet the server answers with a zero-length transfer, and the count is
    // capped. What the network can accept is deliberately not part of the sum - it is the
    // server's to enforce, and asking the client to predict it would be predicting a
    // condition rather than measuring a container.

    @Unique
    private AbstractGridContainerMenu rstweaks$menu() {
        return (AbstractGridContainerMenu) ((AbstractGridScreen<?>) (Object) this).getMenu();
    }
}
