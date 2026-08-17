package com.wraithhawit.rstweaks.mixin;

import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.api.resource.list.MutableResourceList;
import com.refinedmods.refinedstorage.api.resource.repository.ResourceRepositoryImpl;
import com.wraithhawit.rstweaks.Config;
import com.wraithhawit.rstweaks.RSTweaks;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
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
 * <h2>0.2.89: that is exactly what happened, so read the audit first</h2>
 *
 * <p>A 0.2.88 session reproduced the phantom with this probe on, and the probe came back clean:
 * every removal reported {@code REMOVED}, nothing added a row over an empty backing entry. Two
 * more candidates died with it — the row still clears on a SHIFT tap, so it is not sticky
 * ({@code ViewList.createSorted} re-adds sticky rows on every rebuild, so a sticky one would
 * survive), and {@code MutableResourceListImpl.removeCompletely} drops an entry rather than
 * zeroing it, so a rebuilt view cannot contain a phantom either.
 *
 * <p>{@link #rstweaks$audit} therefore stops watching suspected paths and checks the invariant
 * itself, after every update and every sort: <b>nothing may sit in the view list with an empty
 * backing entry unless it is sticky.</b> It names whatever violates that and carries a stack
 * trace, so the search no longer depends on having guessed the right code path. Grep for
 * {@code PHANTOM ROW}; the lines above it are the context.
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

    // ------------------------------------------------------------------ the audit
    //
    // Added in 0.2.89, after the probe above came back clean on a session that still showed
    // the phantom. Every removal reported "REMOVED", no line reported "backing now 0" on a
    // path that added or kept a row, and the ghost was there anyway. That is this file's own
    // documented signal that the row is not created by the three paths it watches.
    //
    // So this stops watching suspects and checks the invariant directly: no resource may sit
    // in the view list with nothing behind it in the backing list unless it is sticky, which
    // is the legitimate "you can craft this" row. It reports whatever violates that, whoever
    // did it, and takes a stack trace the first few times so the creator names itself.
    //
    // Reaching the view list needs reflection. ViewList is package-private in Refined
    // Storage's own package, so its type cannot be named from here at all, and its index is
    // the only place the view's ResourceKeys exist -- GridResource exposes an amount and a
    // name but never its key. A diagnostic is the one place where paying that price is
    // right, and it is wrapped so a failed lookup degrades to silence.

    @Unique
    @Nullable
    private Set<ResourceKey> rstweaks$phantomsReported;

    @Unique
    private Set<ResourceKey> rstweaks$reported() {
        // No inline initializer, and not final: Mixin does not reliably carry either onto the
        // instance. That exact mistake destroyed items for seven versions -- see
        // AbstractTaskPatternMixin#rstweaks$consumed.
        Set<ResourceKey> reported = this.rstweaks$phantomsReported;
        if (reported == null) {
            reported = new HashSet<>();
            this.rstweaks$phantomsReported = reported;
        }
        return reported;
    }

    /**
     * Checks the invariant after every change that could break it.
     *
     * <p>Walks the whole view index, so it is O(view size) per grid update and belongs behind
     * a flag that is off by default — a busy network updates this many times a second.
     *
     * @param when which call produced the state being checked, so the log says whether the
     *     row appeared during an update or survived a full rebuild. A phantom seen right
     *     after {@code sort()} would be a different bug entirely: {@code createSorted} builds
     *     from the backing list, and {@code MutableResourceListImpl.removeCompletely} drops
     *     an entry rather than zeroing it, so a rebuilt view cannot contain one.
     */
    @Unique
    private void rstweaks$audit(final String when) {
        if (!Config.logGridViewDiagnostics) {
            return;
        }
        try {
            final Set<ResourceKey> inView = rstweaks$viewKeys(this);
            if (inView == null) {
                return;
            }
            for (final ResourceKey resource : inView) {
                if (this.backingList.get(resource) != 0L
                    || this.stickyResources.contains(resource)) {
                    continue;
                }
                if (!this.rstweaks$reported().add(resource)) {
                    continue;
                }
                RSTweaks.LOGGER.warn("[rstweaks][grid] PHANTOM ROW after {}: {} is in the view "
                        + "list, the backing list holds none of it, and it is not sticky. This "
                        + "row will render 0 until the next sort(). Step Crafter says: {}. "
                        + "Stack trace is where it was noticed, not necessarily where it was "
                        + "created.", when, resource, rstweaks$stepCrafterClaim(this, resource),
                    new Throwable("phantom row noticed here"));
            }
            // Forget rows that have since been cleaned up, so a phantom that comes back is
            // reported again rather than silently deduplicated against the first sighting.
            this.rstweaks$reported().removeIf(resource -> !inView.contains(resource)
                || this.backingList.get(resource) != 0L);
        } catch (final RuntimeException | LinkageError e) {
            RSTweaks.LOGGER.warn("[rstweaks][grid] audit failed", e);
        }
    }

    @Inject(method = "update", at = @At("RETURN"), require = 0)
    private void rstweaks$auditAfterUpdate(final ResourceKey resource,
                                           final long amount,
                                           final CallbackInfo ci) {
        this.rstweaks$audit("update " + resource + " by " + amount);
    }

    @Inject(method = "sort", at = @At("RETURN"), require = 0)
    private void rstweaks$auditAfterSort(final CallbackInfo ci) {
        this.rstweaks$audit("sort()");
    }

    /**
     * What Step Crafter says about this resource, asked by reflection.
     *
     * <p>Step Crafter's {@code MixinResourceRepositoryImpl} puts a {@code @Redirect} on the very
     * {@code stickyResources.contains(resource)} call that decides whether a row is removed, and
     * answers {@code sticky || isMaintained} so that a resource one of its Step Crafters maintains
     * keeps its row. Our own logging reads the raw set and cannot see that redirect, so a row kept
     * by Step Crafter is logged by us as REMOVED — which is exactly the contradiction the 0.2.89
     * audit turned up.
     *
     * <p>The repository object itself implements Step Crafter's {@code MaintainingResource}, whose
     * {@code stepcrafter$getMaintainingResources} returns what it claims for a given resource. That
     * is not a dependency we have or want, so it is asked for by name; a pack without Step Crafter
     * simply has no such method and this reports as much.
     */
    @Unique
    private static String rstweaks$stepCrafterClaim(final Object repository,
                                                    final ResourceKey resource) {
        try {
            final Object claimed = repository.getClass()
                .getMethod("stepcrafter$getMaintainingResources", ResourceKey.class)
                .invoke(repository, resource);
            if (claimed instanceof Iterable<?> entries && entries.iterator().hasNext()) {
                return "MAINTAINING this resource " + entries + " -- Step Crafter's redirect on "
                    + "stickyResources.contains is what kept this row";
            }
            return "not maintaining it (so Step Crafter is not the cause)";
        } catch (final NoSuchMethodException absent) {
            return "not installed";
        } catch (final ReflectiveOperationException | RuntimeException e) {
            return "could not be asked (" + e + ")";
        }
    }

    /**
     * The view list's resource keys, or null if Refined Storage's shape has moved.
     *
     * <p>Cached reflectively rather than shadowed because neither the field's type nor the map
     * inside it can be named from this package.
     */
    @Unique
    @Nullable
    private static Set<ResourceKey> rstweaks$viewKeys(final Object repository) {
        try {
            if (RSTWEAKS$VIEW_LIST == null || RSTWEAKS$INDEX == null) {
                final Field viewListField =
                    ResourceRepositoryImpl.class.getDeclaredField("viewList");
                viewListField.setAccessible(true);
                final Field indexField = viewListField.getType().getDeclaredField("index");
                indexField.setAccessible(true);
                RSTWEAKS$VIEW_LIST = viewListField;
                RSTWEAKS$INDEX = indexField;
            }
            final Object viewList = RSTWEAKS$VIEW_LIST.get(repository);
            if (viewList == null) {
                return null;
            }
            final Object index = RSTWEAKS$INDEX.get(viewList);
            if (!(index instanceof Map<?, ?> map)) {
                return null;
            }
            final Set<ResourceKey> keys = new HashSet<>(map.size());
            for (final Object key : map.keySet()) {
                if (key instanceof ResourceKey resource) {
                    keys.add(resource);
                }
            }
            return keys;
        } catch (final ReflectiveOperationException | RuntimeException e) {
            RSTweaks.LOGGER.warn("[rstweaks][grid] cannot read the view list; the audit is off "
                + "for this session. Refined Storage's ViewList has probably changed shape.", e);
            return null;
        }
    }

    @Unique
    @Nullable
    private static Field RSTWEAKS$VIEW_LIST;

    @Unique
    @Nullable
    private static Field RSTWEAKS$INDEX;
}
