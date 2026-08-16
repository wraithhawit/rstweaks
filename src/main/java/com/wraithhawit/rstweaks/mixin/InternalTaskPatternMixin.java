package com.wraithhawit.rstweaks.mixin;

import com.refinedmods.refinedstorage.api.autocrafting.Ingredient;
import com.refinedmods.refinedstorage.api.autocrafting.PatternLayout;
import com.refinedmods.refinedstorage.api.autocrafting.task.ExternalPatternSinkProvider;
import com.refinedmods.refinedstorage.api.autocrafting.task.TaskListener;
import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.api.resource.list.MutableResourceList;
import com.refinedmods.refinedstorage.api.storage.root.RootStorage;
import com.wraithhawit.rstweaks.Config;
import com.wraithhawit.rstweaks.RSTweaks;
import com.wraithhawit.rstweaks.planner.Durability;
import com.wraithhawit.rstweaks.storage.WornToolAware;

import java.util.ArrayList;
import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Keeps a pattern's byproducts inside the crafting task instead of pushing the root
 * pattern's straight to the network, so a recycled container can be reused by the next
 * iteration.
 *
 * <p>This is why Refined Storage asks for 64 buckets to craft 64 rice slimeballs, and it
 * is not a planning mistake — the executor forces it. {@code returnOutput} routes on one
 * condition:
 *
 * <pre>{@code   if (this.root) rootStorage.insert(output);  else internalStorage.add(output); }</pre>
 *
 * <p>and {@code step()} sends outputs <em>and byproducts</em> through it. So when the
 * pattern producing the requested item is also the one handing the bucket back — which
 * is what container recycling is — the bucket leaves the task the moment it is made. A
 * task stops drawing from the network once it reaches RUNNING, so iteration two has no
 * bucket and never will. One slimeball works; two deadlock.
 *
 * <p>Only <b>byproducts</b> are diverted. The requested item still reaches the network as
 * each one is crafted, so a long craft fills up as it goes exactly as before. Byproducts
 * arrive when the task finishes rather than during it — {@code RETURNING_INTERNAL_STORAGE}
 * hands the whole internal storage back on completion <em>and</em> on cancellation, so
 * nothing is lost either way, only delayed.
 *
 * <p>Without this the LP planner cannot help with container recycling at all, which is
 * why {@code lpPlanner} refuses to run when it is switched off.
 */
@Mixin(targets = "com.refinedmods.refinedstorage.api.autocrafting.task.InternalTaskPattern")
public abstract class InternalTaskPatternMixin {
    /**
     * Redirects {@code layout().byproducts()} rather than {@code returnOutput} itself,
     * because {@code returnOutput} receives outputs and byproducts through the same
     * parameter and cannot tell them apart. Returning an empty list leaves the loop that
     * follows with nothing to do. Verified against the compiled class: {@code step} calls
     * {@code PatternLayout.byproducts()} exactly once, so there is no ordinal to get
     * wrong.
     *
     * <p>The trailing parameters are {@code step}'s own arguments, which is how the
     * internal storage becomes reachable from here. Mixin requires all of them, in order,
     * and all four types are public Refined Storage API.
     *
     * <p>There is deliberately no check for whether this is the root pattern. For a
     * non-root pattern {@code returnOutput} already does {@code internalStorage.add},
     * so doing it here instead is the same operation by a shorter path — and avoiding
     * the check avoids shadowing {@code root}, which lives on the superclass where
     * Mixin will not find it.
     */
    @Redirect(
        method = "step",
        at = @At(
            value = "INVOKE",
            target = "Lcom/refinedmods/refinedstorage/api/autocrafting/PatternLayout;"
                + "byproducts()Ljava/util/List;"
        )
    )
    private List<ResourceAmount> rstweaks$keepByproductsInTask(
        final PatternLayout layout,
        final MutableResourceList internalStorage,
        final RootStorage rootStorage,
        final ExternalPatternSinkProvider sinkProvider,
        final TaskListener listener
    ) {
        try {
            return rstweaks$tryKeepByproductsInTask(layout, internalStorage);
        } catch (RuntimeException | LinkageError e) {
            // Returning the layout's own list is exactly what Refined Storage would have done
            // without this mixin. See the note in AbstractTaskPatternMixin: a throw here does not
            // merely lose the optimization, it makes TaskContainer drop the task and destroy the
            // internal storage while telling the player the craft completed.
            RSTweaks.LOGGER.error("[rstweaks] byproduct handling failed; returning them to the "
                + "network unchanged. The task continues.", e);
            return layout.byproducts();
        }
    }

    @Unique
    private List<ResourceAmount> rstweaks$tryKeepByproductsInTask(
        final PatternLayout layout,
        final MutableResourceList internalStorage
    ) {
        final List<ResourceAmount> byproducts = layout.byproducts();
        if (byproducts.isEmpty()) {
            return byproducts;
        }
        if (!Config.keepRecycledResourcesInTask) {
            // Wear still has to be applied even when recycling is off, or the pattern
            // hands the network a repaired tool. Ageing is about correctness; keeping it
            // in the task is about the cycle closing.
            return aged(layout, byproducts);
        }
        for (final ResourceAmount byproduct : aged(layout, byproducts)) {
            internalStorage.add(byproduct);
        }
        return List.of();
    }

    /**
     * The byproducts as they will really come back, given what was really consumed.
     *
     * <p>Refined Storage bakes the byproduct into the pattern when it is encoded, from
     * whatever was in the grid at the time. Left alone, a pattern encoded with a fresh
     * crystal returns a crystal at damage 1 <em>every</em> iteration — so a crystal used
     * sixty-four times comes out at damage 1 and never breaks. That is not autocrafting,
     * it is a repair station, and it is why this half cannot be skipped: without it the
     * feature is a durability duplication glitch rather than an optimization.
     *
     * <p>Applied Energistics avoids the whole problem by asking the recipe fresh, per
     * craft, with the real item in the slot — {@code IInput.getRemainingKey(actual)}. We
     * cannot, because Refined Storage does not keep the recipe on the pattern, so the
     * wear step is derived from the tool that {@link AbstractTaskPatternMixin} recorded
     * taking. Equivalent for the damage-per-craft recipes this targets; a recipe with a
     * bespoke remainder would need the recipe itself.
     */
    /**
     * How much durability one iteration costs, read off this pattern.
     *
     * <p>Not assumed to be one. The pattern was encoded with some tool and hands back a
     * more worn one, and the gap between them is the recipe's real cost — five, for a
     * recipe that burns five. Ageing by one instead would make the tool last five times
     * too long, which is a duplication bug rather than a rounding error.
     */
    @Unique
    private int wearStep(final Durability durability, final PatternLayout layout, final ResourceAmount byproduct) {
        for (final Ingredient ingredient : layout.ingredients()) {
            for (final ResourceKey input : ingredient.inputs()) {
                if (durability.sameTool(input, byproduct.resource())) {
                    final int step = durability.usesLeft(input)
                        - durability.usesLeft(byproduct.resource());
                    return Math.max(1, step);
                }
            }
        }
        return 1;
    }

    @Unique
    private List<ResourceAmount> aged(final PatternLayout layout, final List<ResourceAmount> byproducts) {
        if (!Config.durabilityAwarePlanning || !(this instanceof WornToolAware aware)) {
            return byproducts;
        }
        final Durability durability = Durability.Holder.get();
        List<ResourceAmount> result = null;
        for (int i = 0; i < byproducts.size(); i++) {
            final ResourceAmount byproduct = byproducts.get(i);
            // Asked per byproduct, so a recipe burning two different tools ages each against the
            // one actually taken for it. Matching a single "the tool this iteration used" left the
            // other coming back exactly as encoded, which repairs it.
            final ResourceKey consumed = aware.rstweaks$consumedTool(byproduct.resource());
            if (consumed == null) {
                continue;
            }
            if (result == null) {
                result = new ArrayList<>(byproducts);
            }
            final ResourceKey after = durability.afterUses(consumed, wearStep(durability, layout, byproduct));
            if (after == null) {
                // It broke on this use. Nothing comes back, which is what makes the plan
                // finite and is why a replacement eventually has to be crafted.
                result.set(i, null);
            } else {
                result.set(i, new ResourceAmount(after, byproduct.amount()));
            }
        }
        if (result == null) {
            return byproducts;
        }
        result.removeIf(java.util.Objects::isNull);
        return result;
    }

    /**
     * The same problem one level up, for a pattern that consumes what it produces:
     * netherite templates are 1 template + 7 diamonds + 1 netherite ingot → 2 templates.
     * The recycled resource is the requested item, and it is an <em>output</em> of the
     * root pattern, so it goes to the network the instant it is made and iteration two
     * has no template. Reported from the game as "gave it 3, asked for 3, it made 1 and
     * hung on the rest".
     *
     * <p>Unlike byproducts, outputs are held back only when this pattern consumes them.
     * Outputs are what the player asked for, and a long craft filling the network as it
     * goes is worth keeping; a self-duplicating recipe is the one case where it cannot
     * work. Everything held is handed over when the task finishes.
     *
     * <p>This does not cover a cycle that runs through two <em>different</em> patterns
     * via an output, because a single pattern cannot see the rest of the task. The
     * planner's replay models the same rule, so such a plan is declined rather than
     * emitted and left to deadlock.
     */
    @Redirect(
        method = "step",
        at = @At(
            value = "INVOKE",
            target = "Lcom/refinedmods/refinedstorage/api/autocrafting/PatternLayout;"
                + "outputs()Ljava/util/List;"
        )
    )
    private List<ResourceAmount> rstweaks$keepSelfConsumedOutputsInTask(
        final PatternLayout layout,
        final MutableResourceList internalStorage,
        final RootStorage rootStorage,
        final ExternalPatternSinkProvider sinkProvider,
        final TaskListener listener
    ) {
        try {
            return rstweaks$tryKeepSelfConsumedOutputsInTask(layout, internalStorage);
        } catch (RuntimeException | LinkageError e) {
            RSTweaks.LOGGER.error("[rstweaks] self-consumed output handling failed; sending "
                + "outputs to the network unchanged. The task continues.", e);
            return layout.outputs();
        }
    }

    @Unique
    private List<ResourceAmount> rstweaks$tryKeepSelfConsumedOutputsInTask(
        final PatternLayout layout,
        final MutableResourceList internalStorage
    ) {
        final List<ResourceAmount> outputs = layout.outputs();
        if (!Config.keepRecycledResourcesInTask) {
            return outputs;
        }
        List<ResourceAmount> passThrough = null;
        for (final ResourceAmount output : outputs) {
            if (stillNeeded(layout, output.resource())) {
                internalStorage.add(output);
            } else {
                if (passThrough == null) {
                    passThrough = new ArrayList<>(outputs.size());
                }
                passThrough.add(output);
            }
        }
        return passThrough == null ? List.of() : passThrough;
    }

    /**
     * Whether anything left in this task still wants this resource.
     *
     * <p>Checking only the pattern's own ingredients was enough for template duplication,
     * where one pattern consumes what it produces. It is not enough for a cycle that runs
     * through two patterns: a water bucket becomes an empty bucket and 1000mB of water,
     * and the empty bucket is what the <em>other</em> pattern needs to make the next water
     * bucket. Sent to the network, it never comes back, and the craft jams after exactly
     * one iteration.
     *
     * <p>The task's full ingredient set is captured when the task is built, because a
     * pattern cannot see its siblings on its own — see {@code TaskConsumption}.
     */
    @Unique
    private boolean stillNeeded(final PatternLayout layout, final ResourceKey resource) {
        if (consumes(layout, resource)) {
            return true;
        }
        return this instanceof WornToolAware aware && aware.rstweaks$taskConsumes().contains(resource);
    }

    /** Whether the pattern takes this resource as one of its own ingredients. */
    @Unique
    private static boolean consumes(final PatternLayout layout, final ResourceKey resource) {
        for (final Ingredient ingredient : layout.ingredients()) {
            if (ingredient.inputs().contains(resource)) {
                return true;
            }
        }
        return false;
    }
}
