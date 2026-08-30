package com.wraithhawit.rstweaks.mixin;

import com.refinedmods.refinedstorage.api.autocrafting.Pattern;
import com.refinedmods.refinedstorage.api.autocrafting.task.TaskPlan;
import com.refinedmods.refinedstorage.api.core.Action;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.api.resource.list.MutableResourceList;
import com.refinedmods.refinedstorage.api.resource.list.ResourceList;
import com.wraithhawit.rstweaks.Config;
import com.wraithhawit.rstweaks.RSTweaks;
import com.wraithhawit.rstweaks.Stats;
import com.wraithhawit.rstweaks.planner.Durability;
import com.wraithhawit.rstweaks.storage.TaskConsumption;
import com.wraithhawit.rstweaks.storage.TaskPatternInternals;
import com.wraithhawit.rstweaks.storage.WornToolAware;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets a crafting task use the tool it actually has, whatever state of wear it is in.
 *
 * <p>A pattern records the exact resources it was encoded with, and damage lives in an
 * item's component patch, so {@code crystal@0} and {@code crystal@1} are different
 * resources. A pattern encoded with a fresh Mystical Agriculture infusion crystal
 * therefore asks for a fresh crystal on every iteration — while the task's internal
 * storage, after the first craft, holds one at damage 1. The exact match fails and the
 * task idles forever.
 *
 * <p>This substitutes at the point the ingredients meet the storage: if the requested
 * resource is a tool that wears out and is not present, but the same tool at some other
 * wear level is, that is what gets used. Which one was taken is remembered so
 * {@link InternalTaskPatternMixin} can hand back the right one — Applied Energistics does
 * the same thing through {@code IPatternDetails.IInput.getRemainingKey}, deriving the
 * remainder from the item actually consumed rather than from the encoded template.
 *
 * <p>Substituting here rather than in {@code calculateIterationInputs} is deliberate:
 * this is the only place with both the ingredient list and the internal storage in scope,
 * and it leaves Refined Storage's per-ingredient budget accounting untouched. The budget
 * totals {@code amount × iterations} because the plan credits the returned tool as
 * supply, so it still lasts exactly as many iterations as there are — it merely names the
 * wrong wear levels, which is what this corrects.
 */
@Mixin(targets = "com.refinedmods.refinedstorage.api.autocrafting.task.AbstractTaskPattern")
public abstract class AbstractTaskPatternMixin implements WornToolAware, TaskPatternInternals {
    @Shadow
    protected Pattern pattern;

    @Shadow
    protected Map<Integer, Map<ResourceKey, Long>> ingredients;

    @Override
    public Pattern rstweaks$pattern() {
        return this.pattern;
    }

    @Override
    public Map<Integer, Map<ResourceKey, Long>> rstweaks$ingredients() {
        return this.ingredients;
    }

    /**
     * The tools taken this iteration, so each byproduct can be aged to match.
     *
     * <p>A list rather than one field: a recipe may burn two different tools, and recording only
     * the last one meant the other's byproduct came back as encoded — a repaired tool, which is
     * durability created from nothing.
     *
     * <p><b>Deliberately not {@code final} and deliberately without an inline initializer.</b>
     * It had both from 0.2.57 until 0.2.64, and the initializer did not reach the instance:
     * {@code rstweaks$substituteWornTool} threw {@code NullPointerException} on
     * {@code rstweaks$consumed.clear()}, which is the first statement it runs and sits above the
     * {@code durabilityAwarePlanning} check, so the config could not switch it off. Refined
     * Storage catches that in {@code TaskContainer.step}, sets {@code completed = true}, fires
     * {@code taskCompleted} — the toast — and drops the task <em>with its internal storage still
     * in it</em>. Materials extracted, no output, "task completed": items destroyed.
     *
     * <p>{@code ItemHandlerExtractableStorageMixin} already carried this lesson in a comment —
     * Mixin handles field initialisers unreliably — and this class did not follow it. Every
     * {@code @Unique} instance field here now defaults to null and is read through an accessor
     * that copes, which is correct however Mixin chooses to treat the initializer.
     */
    @Unique
    @Nullable
    private List<ResourceKey> rstweaks$consumed;

    /**
     * Captured at construction, because that is the only moment the task's plan is in
     * scope. A pattern has no other way to learn what its siblings need.
     *
     * <p>Null-safe for the same reason as {@link #rstweaks$consumed}. This one never threw,
     * because the {@code <init>} injector below assigns it on every path — which is precisely
     * what disguised the problem: the injector ran, so the class looked initialised.
     */
    @Unique
    @Nullable
    private Set<ResourceKey> rstweaks$taskConsumes;

    /** The record of tools taken, created on first use rather than at construction. */
    @Unique
    private List<ResourceKey> rstweaks$consumedList() {
        List<ResourceKey> consumed = this.rstweaks$consumed;
        if (consumed == null) {
            consumed = new ArrayList<>(1);
            this.rstweaks$consumed = consumed;
        }
        return consumed;
    }

    @Nullable
    @Override
    public ResourceKey rstweaks$consumedTool(final ResourceKey encoded) {
        final List<ResourceKey> consumed = this.rstweaks$consumed;
        if (consumed == null || consumed.isEmpty()) {
            return null;
        }
        final Durability durability = Durability.Holder.get();
        for (final ResourceKey taken : consumed) {
            if (durability.sameTool(taken, encoded)) {
                return taken;
            }
        }
        return null;
    }

    @Override
    public Set<ResourceKey> rstweaks$taskConsumes() {
        final Set<ResourceKey> consumes = this.rstweaks$taskConsumes;
        return consumes == null ? Set.of() : consumes;
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void rstweaks$rememberTaskConsumption(final Pattern pattern,
                                                 final TaskPlan.PatternPlan plan,
                                                 final CallbackInfo ci) {
        this.rstweaks$taskConsumes = TaskConsumption.current();
    }

    /**
     * Wrapped so that nothing this optimization does can ever kill a task.
     *
     * <p>{@code TaskContainer.step} treats <em>any</em> exception out of a task step as
     * completion: it logs, marks the task completed, notifies listeners and drops it — internal
     * storage and all. So a throw from here does not degrade autocrafting, it <b>destroys the
     * items the task was holding</b> and tells the player it succeeded. That is what the null
     * field did for seven versions. Substituting a worn tool is an optimization; failing to do it
     * must cost the optimization and nothing else.
     */
    @Inject(method = "extractAll", at = @At("HEAD"))
    private void rstweaks$substituteWornTool(final ResourceList inputs,
                                           final MutableResourceList internalStorage,
                                           final Action action,
                                           final CallbackInfoReturnable<Boolean> cir) {
        try {
            rstweaks$trySubstituteWornTool(inputs, internalStorage, action);
        } catch (RuntimeException | LinkageError e) {
            RSTweaks.LOGGER.error("[rstweaks] durability substitution failed; leaving this "
                + "iteration's ingredients untouched. The task continues.", e);
        }
    }

    /**
     * Whether anything this pattern can draw is a tool that wears out, decided once.
     *
     * <p>Read from the <b>ingredient budget</b> rather than the pattern's layout, and the
     * distinction matters: the planner allocates concrete resources per slot, so a pattern encoded
     * with {@code crystal@0} may have {@code crystal@50} in its budget. Checking the layout would
     * miss exactly the substitution this whole mixin exists to perform.
     *
     * <p>The budget's keys are fixed for the task's lifetime — only the amounts change as it runs —
     * so the answer cannot change either.
     */
    @Unique
    private boolean rstweaks$touchesADurableResource(final Durability durability) {
        Boolean cached = this.rstweaks$durable;
        if (cached == null) {
            cached = false;
            for (final Map<ResourceKey, Long> possibilities
                : ((TaskPatternInternals) this).rstweaks$ingredients().values()) {
                for (final ResourceKey candidate : possibilities.keySet()) {
                    if (durability.isDurable(candidate)) {
                        cached = true;
                        break;
                    }
                }
                if (cached) {
                    break;
                }
            }
            this.rstweaks$durable = cached;
        }
        return cached;
    }

    @Unique
    private Boolean rstweaks$durable;

    @Unique
    private void rstweaks$trySubstituteWornTool(final ResourceList inputs,
                                               final MutableResourceList internalStorage,
                                               final Action action) {
        // Before the clear, deliberately. On a pattern with nothing durable in it the consumed
        // list is never written, so clearing it is a no-op -- and this runs twice per iteration
        // on the hottest loop in the mod. Measured at 1.43% of the server thread on a compression
        // craft even after the guard below was added, purely from the work done reaching it.
        if (Boolean.FALSE.equals(this.rstweaks$durable)) {
            return;
        }
        // Cleared every call, including the SIMULATE that precedes each EXECUTE, so a
        // tool recorded by one iteration can never age the byproduct of the next.
        this.rstweaks$consumedList().clear();
        if (!Config.durabilityAwarePlanning) {
            return;
        }
        final Durability durability = Durability.Holder.get();
        if (!rstweaks$touchesADurableResource(durability)) {
            // Most patterns have no tool in them at all, and this runs twice per iteration on the
            // hottest loop in the mod. Measured at 3.57% of the server thread on a compression
            // craft with nothing durable anywhere in it -- an allocation and a walk of every
            // ingredient, every time, to conclude there was nothing to do.
            return;
        }
        if (!(inputs instanceof MutableResourceList mutableInputs)) {
            return;
        }

        // Collected before applying: rewriting the list while iterating its key set
        // would throw, and the substitution has to be decided against a stable view.
        final List<ResourceKey[]> swaps = new ArrayList<>(1);
        for (final ResourceKey wanted : inputs.getAll()) {
            if (!durability.isDurable(wanted)) {
                continue;
            }
            final long needed = inputs.get(wanted);
            if (internalStorage.get(wanted) >= needed) {
                // The encoded wear level happens to be the one in hand.
                this.rstweaks$consumedList().add(wanted);
                continue;
            }
            final ResourceKey substitute =
                rstweaks$findWornToolReusing(internalStorage, durability, wanted, needed, action);
            if (substitute != null) {
                swaps.add(new ResourceKey[] {wanted, substitute});
                this.rstweaks$consumedList().add(substitute);
            }
        }

        rstweaks$probeSimulateExecutePair(action, swaps);
        rstweaks$rememberDecision(action, swaps);

        for (final ResourceKey[] swap : swaps) {
            final long amount = inputs.get(swap[0]);
            mutableInputs.remove(swap[0], amount);
            mutableInputs.add(swap[1], amount);
        }
    }

    /**
     * {@code findWornTool}, but reusing the SIMULATE pass's answer when the EXECUTE pass can.
     *
     * <p><b>The measurement that justifies this.</b> Refined Storage's
     * {@code InternalTaskPattern.step} runs every iteration twice — once with {@code SIMULATE} to
     * test it, once with {@code EXECUTE} to do it — and {@code SIMULATE} does not mutate internal
     * storage. So the second scan walks the same storage looking for the same tool. The 0.12.1
     * probe measured that over a real 1M insanium craft: <b>63,247,889 pairs, 63,247,889 agreed,
     * 0 disagreed, 0 execute-without-simulate.</b>
     *
     * <p>Sixty-three million agreements is evidence, not a proof, so this does not trust it blindly.
     * The remembered substitute is re-validated against internal storage with a single {@code O(1)}
     * lookup before it is used, and anything that does not validate — a resource that was not in the
     * remembered decision, a substitute no longer present in the amount needed, an EXECUTE with no
     * SIMULATE before it — falls straight through to the full scan. The fast path can only ever
     * return an answer the slow path would also have returned.
     *
     * <p>What it removes is the scan itself: {@code findWornTool} walks the task's entire internal
     * storage per durable ingredient, and it was 57.24% of this mixin's 37.03% of the server thread
     * in profile {@code IiXxJ4Mk4j}. Half of those walks were re-deriving what the other half had
     * just worked out.
     */
    @Unique
    @Nullable
    private ResourceKey rstweaks$findWornToolReusing(final MutableResourceList internalStorage,
                                                     final Durability durability,
                                                     final ResourceKey wanted,
                                                     final long needed,
                                                     final Action action) {
        // The probe and the cache are mutually exclusive on purpose. With the cache serving the
        // EXECUTE pass, the probe would be comparing the remembered answer against itself and would
        // report agreement no matter what -- a measurement that cannot fail is not a measurement.
        if (action != Action.EXECUTE || !Config.reuseSimulatedSubstitution
            || Config.substitutionProbe) {
            Stats.substitutionScansNotEligible++;
            return findWornTool(internalStorage, durability, wanted, needed);
        }
        final ResourceKey remembered = rstweaks$rememberedSubstitute(wanted);
        if (remembered == null) {
            // Counted apart from a failed revalidation, because the two have completely different
            // causes: nothing was remembered for this resource at all, versus the remembered answer
            // no longer being usable. 0.13.0 shipped with neither counter surfaced and the profile
            // said the reuse was firing about 1% of the time -- with no way to tell which of these
            // was the reason.
            Stats.substitutionNothingRemembered++;
            return findWornTool(internalStorage, durability, wanted, needed);
        }
        if (internalStorage.get(remembered) >= needed) {
            Stats.substitutionScansAvoided++;
            return remembered;
        }
        Stats.substitutionRevalidationFailed++;
        return findWornTool(internalStorage, durability, wanted, needed);
    }

    /** The substitute the SIMULATE pass chose for this resource, or null if it chose none. */
    @Unique
    @Nullable
    private ResourceKey rstweaks$rememberedSubstitute(final ResourceKey wanted) {
        final List<ResourceKey> remembered = this.rstweaks$simulatedSwaps;
        if (remembered == null) {
            return null;
        }
        for (int i = 0; i < remembered.size(); i += 2) {
            // Identity first: both passes draw their resources from the same pattern budget, so the
            // same key object usually turns up in each, and ItemResource.equals hashes a
            // DataComponentPatch. The list holds one or two entries, so the fallback is cheap.
            final ResourceKey candidate = remembered.get(i);
            if (candidate == wanted || candidate.equals(wanted)) {
                return remembered.get(i + 1);
            }
        }
        return null;
    }

    /**
     * Holds the SIMULATE pass's decision for the EXECUTE pass that follows it.
     *
     * <p>Cleared on EXECUTE whatever happened, so a decision can never outlive the single iteration
     * it was made for: the next EXECUTE without a SIMULATE in front of it finds nothing remembered
     * and scans, which is exactly the old behaviour.
     */
    @Unique
    private void rstweaks$rememberDecision(final Action action, final List<ResourceKey[]> swaps) {
        if (Config.substitutionProbe
            || (!Config.reuseSimulatedSubstitution && !Config.simulateRepeatProbe)) {
            return;
        }
        if (action != Action.SIMULATE) {
            this.rstweaks$simulatedSwaps = null;
            this.rstweaks$simulateStreak = 0;
            return;
        }
        rstweaks$probeRepeatedSimulate(swaps);
        final List<ResourceKey> flat = new ArrayList<>(swaps.size() * 2);
        for (final ResourceKey[] swap : swaps) {
            flat.add(swap[0]);
            flat.add(swap[1]);
        }
        this.rstweaks$simulatedSwaps = flat;
    }

    /**
     * Measures whether a <em>failing</em> SIMULATE pass repeats itself unchanged.
     *
     * <p><b>Why this is the target now.</b> The 0.13.1 counters came back
     * {@code 33,271,287 scans avoided of 33,271,287 eligible (100.0%)} with
     * {@code 125,013,928 not eligible} — the EXECUTE cache is perfect, and "not eligible" can only
     * be the SIMULATE pass. That is <b>3.76 simulates per execute</b>: {@code InternalTaskPattern
     * .step} returns IDLE when {@code extractAll(SIMULATE)} is false, so roughly 73% of iterations
     * fail their simulate and never execute. The EXECUTE cache could therefore only ever reach a
     * fifth of the scans, and it already reaches all of it.
     *
     * <p>The other four fifths are failing simulates — and Refined Storage re-steps the same pattern
     * up to 175,552 times a tick, so a pattern that cannot proceed is rescanning the task's whole
     * internal storage for an answer that has not changed.
     *
     * <p><b>This detects the repeat with no extra state.</b> {@link #rstweaks$simulatedSwaps} is
     * nulled by every EXECUTE, so finding it still populated at the top of a SIMULATE means the
     * previous simulate was never consumed by an execute — which is precisely a failing repeat.
     *
     * <p>Counts agreement rather than assuming it, and records the longest streak, because the
     * streak length is the payoff: a cache that saves one rescan is not worth writing, and one that
     * saves a thousand is.
     */
    @Unique
    private void rstweaks$probeRepeatedSimulate(final List<ResourceKey[]> swaps) {
        if (!Config.simulateRepeatProbe) {
            return;
        }
        final List<ResourceKey> previous = this.rstweaks$simulatedSwaps;
        if (previous == null) {
            this.rstweaks$simulateStreak = 1;
            return;
        }
        Stats.simulateRepeats++;
        if (rstweaks$sameDecision(previous, swaps)) {
            Stats.simulateRepeatsAgreed++;
        } else {
            Stats.simulateRepeatsDisagreed++;
        }
        this.rstweaks$simulateStreak++;
        if (this.rstweaks$simulateStreak > Stats.simulateStreakLongest) {
            Stats.simulateStreakLongest = this.rstweaks$simulateStreak;
        }
    }

    /** Consecutive failing simulates on this pattern, for the streak-length figure. */
    @Unique
    private int rstweaks$simulateStreak;

    /** Whether a remembered flat decision names exactly the same swaps, in the same order. */
    @Unique
    private static boolean rstweaks$sameDecision(final List<ResourceKey> remembered,
                                                 final List<ResourceKey[]> swaps) {
        if (remembered.size() != swaps.size() * 2) {
            return false;
        }
        for (int i = 0; i < swaps.size(); i++) {
            if (!remembered.get(i * 2).equals(swaps.get(i)[0])
                || !remembered.get(i * 2 + 1).equals(swaps.get(i)[1])) {
                return false;
            }
        }
        return true;
    }

    /**
     * Measures whether the SIMULATE pass and the EXECUTE pass reach the same substitution.
     *
     * <p><b>Why this exists.</b> {@code InternalTaskPattern.step} runs every iteration twice —
     * {@code calculateIterationInputs(SIMULATE)} then {@code extractAll(.., SIMULATE)} to test it,
     * and the identical pair with {@code EXECUTE} to do it. Verified in the 2.0.9 bytecode. Our
     * substitution therefore scans the task's whole internal storage <em>twice per iteration</em>,
     * and SIMULATE does not mutate storage, so the second scan is looking at the same world.
     *
     * <p>Caching the first answer for the second pass would halve the 37% of the server thread this
     * mixin costs — <b>if</b> the two passes really do agree. They are not obliged to:
     * {@code calculateIterationInputs} takes the {@code Action} as a parameter and is entitled to
     * return different resources for the two. Assuming otherwise is the same class of mistake as
     * 0.11.2's "almost every candidate is a different item", which was true in general and false
     * for the only case that mattered.
     *
     * <p>So this measures the invariant instead of assuming it, and changes no behaviour: it
     * compares, counts, and returns. Off by default; turn on {@code substitutionProbe} and read
     * {@code /rstweaks stats}. A disagreement count of zero over millions of iterations is what
     * would justify building the cache.
     */
    @Unique
    private void rstweaks$probeSimulateExecutePair(final Action action,
                                                   final List<ResourceKey[]> swaps) {
        if (!Config.substitutionProbe) {
            return;
        }
        if (action == Action.SIMULATE) {
            final List<ResourceKey> flat = new ArrayList<>(swaps.size() * 2);
            for (final ResourceKey[] swap : swaps) {
                flat.add(swap[0]);
                flat.add(swap[1]);
            }
            this.rstweaks$simulatedSwaps = flat;
            return;
        }
        final List<ResourceKey> simulated = this.rstweaks$simulatedSwaps;
        // Cleared whatever the outcome, so the next pair starts clean and an EXECUTE that arrives
        // without its SIMULATE is counted rather than silently compared against a stale answer.
        this.rstweaks$simulatedSwaps = null;
        if (simulated == null) {
            Stats.substitutionExecuteWithoutSimulate++;
            return;
        }
        Stats.substitutionPairs++;
        if (rstweaks$sameDecision(simulated, swaps)) {
            Stats.substitutionAgreed++;
        } else {
            Stats.substitutionDisagreed++;
        }
    }

    /**
     * The SIMULATE pass's substitution, held only until the matching EXECUTE compares against it.
     *
     * <p>Null-safe and without an inline initializer, for the reason {@link #rstweaks$consumed}
     * records at length: Mixin does not reliably run field initialisers, and a throw from this
     * mixin does not degrade autocrafting — it destroys the items the task is holding.
     */
    @Unique
    @Nullable
    private List<ResourceKey> rstweaks$simulatedSwaps;

    /** The most worn matching tool that still covers this iteration. */
    @Unique
    @Nullable
    private static ResourceKey findWornTool(final MutableResourceList internalStorage,
                                            final Durability durability,
                                            final ResourceKey wanted,
                                            final long needed) {
        // Resolved once for the whole scan. sameTool needs the wanted side's tool family on every
        // single comparison and it cannot change across the loop, so deriving it per candidate was
        // half of every family lookup this method made -- and a family lookup hashes and
        // equality-compares a DataComponentPatch.
        //
        // Profile qRRh2NJvYs is why this was still worth doing after 0.11.2's item-first rejection:
        // that rejection assumed almost every candidate is a different item, which is false for the
        // very case this exists for. Wearing a tool down fills internal storage with many wear
        // levels of the SAME item, so the cheap reference comparison matches and both lookups ran.
        final int wantedFamily = durability.toolFamily(wanted);
        ResourceKey best = null;
        int bestLeft = Integer.MAX_VALUE;
        for (final ResourceKey candidate : internalStorage.getAll()) {
            if (!durability.sameTool(wanted, wantedFamily, candidate)
                || internalStorage.get(candidate) < needed) {
                continue;
            }
            // Finish off the most worn one first, so a nearly spent crystal is used up
            // before a fresh one is touched. Same reasoning as the planner's requisition.
            final int left = durability.usesLeft(candidate);
            if (left > 0 && left < bestLeft) {
                bestLeft = left;
                best = candidate;
            }
        }
        return best;
    }
}
