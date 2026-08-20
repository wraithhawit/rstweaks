package com.wraithhawit.rstweaks.mixin;

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

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.item.ItemStack;

import org.jetbrains.annotations.Nullable;

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
    // transfer repeated, which is what rstweaks$driveContainerRepeat does.
    //
    // The bindings below, active only while a fluid or chemical container is on the cursor:
    //
    //   left-click            on a row it can accept: fill the container by one bucket
    //   right-click           empty one bucket of it into the network, anywhere in the grid
    //   shift + either        run that direction until it stops moving
    //
    // Left fills and right empties whichever you clicked, so a tank no longer has to be
    // dumped by hunting for blank space in the grid: right-clicking the fluid's own row now
    // does it. Everything else - an ordinary item on the cursor, an empty cursor, ctrl-click,
    // autocrafting - routes exactly as it did, because both hooks return without touching the
    // callback unless GridContainers says the cursor holds a tank.
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
     * Ceiling on repeated operations, so a bug here cannot spin forever.
     *
     * <p>Four covers an Ultimate fluid tank; the headroom is for a creative tank
     * (2,147,483,647 mB capacity against a 1,073,741,823 mB rate, so two) and for whatever
     * ratio a mod we have never seen picks. Reaching this limit is not an error worth
     * reporting - the transfer simply stops, and clicking again continues it.
     */
    @Unique
    private static final int RSTWEAKS_REPEAT_LIMIT = 64;

    /**
     * How many ticks of an unchanged cursor stack end a repeat.
     *
     * <p>Each operation is a packet to the server and the changed stack coming back, so a
     * tick or two of "nothing yet" is the normal case on a real server, not the end of the
     * transfer. This waits long enough to tell one from the other.
     */
    @Unique
    private static final int RSTWEAKS_REPEAT_IDLE_TICKS = 8;

    /** Operations left in the current repeat; zero when no repeat is running. */
    @Unique
    private int rstweaks$repeatsLeft;

    /** Consecutive ticks the cursor stack has not changed. */
    @Unique
    private int rstweaks$repeatIdleTicks;

    /** The cursor stack as it was when the last operation was sent. */
    @Unique
    private ItemStack rstweaks$repeatCarried = ItemStack.EMPTY;

    /** The row being drained into the container, or null when the repeat is a dump. */
    @Unique
    @Nullable
    private GridResource rstweaks$repeatResource;

    /**
     * Right-clicking blank grid space with a container held: empty it into the network.
     *
     * <p>Injected at HEAD of the insert entry point rather than at the click router, so
     * Refined Storage has already decided this is an insert - the storage-area bounds, the
     * button filter and the empty-cursor check are all upstream of here and are not
     * restated. The reroute of a right-click on a resource row is the other hook.
     *
     * <p><b>Left-click is deliberately not handled here.</b> Left means "fill the container",
     * and blank grid space is not something to fill from - there is no resource under the
     * cursor to name. Left-clicking away from a row therefore keeps stock behaviour, which
     * stores the tank as an item; that is the only sensible reading of the gesture and it is
     * how an empty tank has always been put into the network.
     */
    @Inject(method = "mouseClickedInGrid(I)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void rstweaks$containerInsert(final int clickedButton, final CallbackInfo ci) {
        if (!Config.containerGridClicks || clickedButton != 1) {
            return;
        }
        final AbstractGridContainerMenu menu = rstweaks$menu();
        final ItemStack carried = menu.getCarried();
        if (!GridContainers.isBulkContainer(carried)) {
            return;
        }
        rstweaks$insert(menu, carried, Screen.hasShiftDown());
        ci.cancel();
    }

    /**
     * Clicking a resource row with a container held.
     *
     * <p>Left fills the container from that row, right empties the container into the
     * network. The right-click case is the one reroute in all of this: stock Refined Storage
     * has no way to insert while the cursor is over a row it could extract from, because
     * {@code mouseClicked} commits to the extract branch as soon as {@code canExtract}
     * agrees, whichever button was pressed.
     *
     * <p>The extract passes {@code cursor = true} unconditionally. Stock derives that from
     * "is shift down", meaning "put the result in my inventory instead" - a bucket rule that
     * makes no sense once shift means "fill it up", and one the fluid strategy ignores anyway
     * when a container is on the cursor. Passing true says the thing being filled is the
     * thing you are holding, which is the only reading available here.
     */
    @Inject(
        method = "mouseClickedInGrid(ILcom/refinedmods/refinedstorage/common/api/grid/view/GridResource;)V",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private void rstweaks$containerExtract(
        final int clickedButton,
        final GridResource resource,
        final CallbackInfo ci
    ) {
        if (!Config.containerGridClicks) {
            return;
        }
        final AbstractGridContainerMenu menu = rstweaks$menu();
        final ItemStack carried = menu.getCarried();
        if (!GridContainers.isBulkContainer(carried)) {
            return;
        }
        final boolean all = Screen.hasShiftDown();
        if (clickedButton == 1) {
            rstweaks$insert(menu, carried, all);
        } else {
            resource.onExtract(
                all ? GridExtractMode.ENTIRE_RESOURCE : GridExtractMode.SINGLE_RESOURCE,
                true,
                (GridExtractionStrategy) menu
            );
            if (all) {
                rstweaks$armRepeat(resource, carried);
            }
        }
        ci.cancel();
    }

    /**
     * One insert operation, arming a repeat when it was a shift-click.
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
        menu.onInsert(
            all ? GridInsertMode.ENTIRE_RESOURCE : GridInsertMode.SINGLE_RESOURCE,
            true
        );
        if (all) {
            rstweaks$armRepeat(null, carried);
        }
    }

    @Unique
    private void rstweaks$armRepeat(
        @Nullable final GridResource resource,
        final ItemStack carried
    ) {
        rstweaks$repeatResource = resource;
        rstweaks$repeatsLeft = RSTWEAKS_REPEAT_LIMIT;
        rstweaks$repeatIdleTicks = 0;
        rstweaks$repeatCarried = carried.copy();
    }

    /**
     * Repeats a shift-click's operation until the container stops changing.
     *
     * <p>The stop condition is the cursor stack itself, not a predicted count. Predicting one
     * would mean asking the container for its capacity and its per-operation rate and
     * dividing - two numbers a mod is free to make dynamic, through an API we would have to
     * reach reflectively for chemicals. Watching the stack instead needs neither: whatever
     * the container did, the item that came back from the server either differs from the one
     * we sent or it does not, and only the second is the end of the transfer. Issue #15 was
     * five wrong answers long because its probes re-evaluated a condition rather than
     * observing an effect; this is the same lesson spent in advance.
     *
     * <p>Shift is deliberately not re-checked. A shift-click released quickly is still a
     * shift-click, and requiring the modifier to be held for the whole run would make the
     * result depend on how fast the player let go.
     */
    @Inject(method = "containerTick", at = @At("TAIL"), require = 0)
    private void rstweaks$driveContainerRepeat(final CallbackInfo ci) {
        if (rstweaks$repeatsLeft <= 0) {
            return;
        }
        final AbstractGridContainerMenu menu = rstweaks$menu();
        final ItemStack carried = menu.getCarried();
        if (!GridContainers.isBulkContainer(carried)) {
            // The container left the cursor - put away, dropped, or swapped mid-transfer.
            rstweaks$stopRepeat();
            return;
        }
        if (ItemStack.matches(carried, rstweaks$repeatCarried)) {
            if (++rstweaks$repeatIdleTicks >= RSTWEAKS_REPEAT_IDLE_TICKS) {
                rstweaks$stopRepeat();
            }
            return;
        }
        rstweaks$repeatCarried = carried.copy();
        rstweaks$repeatIdleTicks = 0;
        --rstweaks$repeatsLeft;
        if (rstweaks$repeatResource == null) {
            menu.onInsert(GridInsertMode.ENTIRE_RESOURCE, true);
        } else {
            rstweaks$repeatResource.onExtract(
                GridExtractMode.ENTIRE_RESOURCE,
                true,
                (GridExtractionStrategy) menu
            );
        }
    }

    @Unique
    private void rstweaks$stopRepeat() {
        rstweaks$repeatsLeft = 0;
        rstweaks$repeatIdleTicks = 0;
        rstweaks$repeatResource = null;
        rstweaks$repeatCarried = ItemStack.EMPTY;
    }

    @Unique
    private AbstractGridContainerMenu rstweaks$menu() {
        return (AbstractGridContainerMenu) ((AbstractGridScreen<?>) (Object) this).getMenu();
    }
}
