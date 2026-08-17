package com.wraithhawit.rstweaks.mixin;

import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.api.resource.list.MutableResourceList;
import com.refinedmods.refinedstorage.api.resource.repository.ResourceRepositoryImpl;
import com.wraithhawit.rstweaks.Config;
import com.wraithhawit.rstweaks.RSTweaks;

import java.util.Set;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A read-only probe for issue #15, the row left showing {@code 0} after a durability craft.
 *
 * <p><b>This changes no behaviour.</b> It logs and returns. It exists because three separate
 * theories built by reading this class were wrong, and the last two cost a build and an in-game
 * test each to disprove. The facts that settle it are already computed here every time the grid
 * changes — Refined Storage just logs them at DEBUG, where a pack's log level buries them.
 *
 * <h2>What is known, so the log can be read quickly</h2>
 *
 * <p>The stranded row is <em>client-side only</em>: it survives neither a re-sort (tapping a key)
 * nor reopening the grid, and the server's own snapshot is correct. So the client's view list holds
 * a row whose backing-list entry is gone — {@code AbstractGridResource.getAmount} reads the backing
 * list live, which is why it renders zero.
 *
 * <p>Reading {@code ResourceRepositoryImpl}, exactly three paths can leave that state, and two are
 * already excluded by the symptom:
 *
 * <ul>
 *   <li>{@code preventSorting} — {@code updateExisting} skips the removal entirely. Fixed in 0.2.86
 *       for the case that latched it, and the phantom outlived that fix.</li>
 *   <li><b>sticky</b> — {@code updateExisting} calls {@code viewList.update} instead of
 *       {@code remove}. Excluded because {@code ViewList.createSorted} re-adds sticky resources on
 *       every rebuild, so a sticky row would <em>survive</em> the re-sort. This one does not.</li>
 *   <li>{@code tryAddNewResource} <b>on a removal</b> — reached when the view list has no row for a
 *       resource the backing list did have. It re-adds the row from the mapper, and if the update
 *       that got there was the removal of the last one, the row it adds is backed by nothing. This
 *       is the remaining candidate and the reason the probe reports the branch.</li>
 * </ul>
 *
 * <p>So the line to look for is one reporting {@code backing now 0} on a path that <b>added</b> or
 * <b>kept</b> a row. That names the resource and the branch in one go. If instead every removal
 * reports {@code view row REMOVED} and the phantom still appears, the row is not coming from this
 * class at all and the search moves to whatever else mixes into it.
 *
 * <p>Gated behind {@code logGridViewDiagnostics}, default off. Registered as a client mixin: the
 * server builds a repository for the grid menu but never populates or updates it, so nothing here
 * would ever fire there anyway.
 */
@Mixin(ResourceRepositoryImpl.class)
public abstract class ResourceRepositoryImplDiagnosticMixin {
    @Shadow(remap = false)
    @Final
    private MutableResourceList backingList;

    @Shadow(remap = false)
    @Final
    private Set<ResourceKey> stickyResources;

    /**
     * The incoming delta, before anything has been applied.
     *
     * <p>Logged separately from the outcome because the interesting case is a mismatch between
     * them — a negative delta that ends with a row present is precisely the bug.
     */
    @Inject(method = "update", at = @At("HEAD"), require = 0)
    private void rstweaks$logUpdate(final ResourceKey resource,
                                    final long amount,
                                    final CallbackInfo ci) {
        if (!Config.logGridViewDiagnostics) {
            return;
        }
        try {
            RSTweaks.LOGGER.info("[rstweaks][grid] update {} by {} (backing had {}, sticky={})",
                resource, amount, this.backingList.get(resource),
                this.stickyResources.contains(resource));
        } catch (final RuntimeException | LinkageError e) {
            // A diagnostic must never be able to break the thing it is diagnosing.
            RSTweaks.LOGGER.warn("[rstweaks][grid] diagnostic failed", e);
        }
    }

    /**
     * The branch that keeps a row rather than removing it.
     *
     * <p>{@code updateExisting} is private and takes the already-computed
     * {@code removedFromBackingList}, which is the whole question, so it is worth injecting
     * separately rather than inferring the branch from the update line.
     */
    @Inject(method = "updateExisting", at = @At("HEAD"), require = 0)
    private void rstweaks$logUpdateExisting(final ResourceKey resource,
                                            final boolean removedFromBackingList,
                                            final Object mapped,
                                            final CallbackInfo ci) {
        if (!Config.logGridViewDiagnostics) {
            return;
        }
        try {
            final boolean sticky = this.stickyResources.contains(resource);
            RSTweaks.LOGGER.info("[rstweaks][grid]   existing row: {} (removedFromBacking={}, "
                    + "sticky={}, backing now {})",
                removedFromBackingList && !sticky ? "REMOVED" : "KEPT",
                removedFromBackingList, sticky, this.backingList.get(resource));
        } catch (final RuntimeException | LinkageError e) {
            RSTweaks.LOGGER.warn("[rstweaks][grid] diagnostic failed", e);
        }
    }

    /**
     * The suspect path. Reached only when the view list had no row for this resource.
     *
     * <p>{@code backing now 0} here is the bug caught in the act: a row is about to be created for
     * a resource the network no longer holds, and it will render zero until the next sort.
     */
    @Inject(method = "tryAddNewResource", at = @At("HEAD"), require = 0)
    private void rstweaks$logTryAddNewResource(final ResourceKey resource, final CallbackInfo ci) {
        if (!Config.logGridViewDiagnostics) {
            return;
        }
        try {
            final long backing = this.backingList.get(resource);
            RSTweaks.LOGGER.info("[rstweaks][grid]   no existing row -> tryAddNewResource {} "
                + "(backing now {}){}", resource, backing,
                backing == 0L ? "  <-- PHANTOM: adding a row for a resource that is gone" : "");
        } catch (final RuntimeException | LinkageError e) {
            RSTweaks.LOGGER.warn("[rstweaks][grid] diagnostic failed", e);
        }
    }

    /** Every rebuild-from-truth, so a phantom's lifetime can be bounded in the log. */
    @Inject(method = "sort", at = @At("HEAD"), require = 0)
    private void rstweaks$logSort(final CallbackInfo ci) {
        if (!Config.logGridViewDiagnostics) {
            return;
        }
        RSTweaks.LOGGER.info("[rstweaks][grid] sort() - view list rebuilt from the backing list");
    }
}
