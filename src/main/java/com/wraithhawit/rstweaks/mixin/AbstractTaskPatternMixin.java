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
import com.wraithhawit.rstweaks.storage.VersionedResourceList;
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
        // The encoded side's family resolved once, exactly as findWornTool does. Profile
        // ajw2GTmG3M put this method at 5.2% of the server thread purely on that lookup being
        // repeated per entry -- the same mistake 0.12.0 fixed in the scan and left here, because
        // nothing was measuring this loop at the time.
        final int encodedFamily = durability.toolFamily(encoded);
        for (final ResourceKey taken : consumed) {
            if (durability.sameTool(encoded, encodedFamily, taken)) {
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

        // Decided once for the whole call rather than per ingredient: the version and the input
        // count cannot change while this method runs, and asking per resource would put two extra
        // reads on the hottest loop in the mod to answer the same question repeatedly.
        this.rstweaks$repeatValid = rstweaks$canReuseFailedSimulate(action, inputs, internalStorage);
        // The verifier deliberately does NOT take the fast path. It runs the full computation and
        // then checks it against what the replay would have produced, which is the only way to
        // catch a replay that is confidently wrong -- comparing the cache against itself is the
        // mistake both earlier probes were designed around.
        final boolean verifying = this.rstweaks$repeatValid && Config.verifyReplayedDecisions;
        final List<ResourceKey> expected = verifying ? this.rstweaks$simulatedSwaps : null;
        final List<ResourceKey> expectedConsumed = verifying ? this.rstweaks$rememberedConsumed : null;
        if (this.rstweaks$repeatValid && !verifying) {
            // The whole decision is replayed, not re-derived. 0.14.0 skipped only the storage scan
            // and left the loop around it running: profile m4dQmEhBRW put findWornTool at 1.60% of
            // the server thread while the machinery enclosing it — walking every input, asking
            // isDurable, one map lookup per durable ingredient — was over ten times that. Nothing
            // in that loop can reach a different answer while the storage version and the input
            // count are unchanged, which is exactly what got us here.
            rstweaks$replayDecision(inputs, mutableInputs);
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

        if (verifying) {
            rstweaks$verifyReplay(expected, expectedConsumed, swaps);
        }
        rstweaks$probeSimulateExecutePair(action, swaps);
        rstweaks$rememberDecision(action, swaps, inputs, internalStorage);

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
        if (action == Action.SIMULATE) {
            // A reusable simulate never reaches this method any more -- the whole decision is
            // replayed before the loop starts -- so reaching here means the decision is genuinely
            // being recomputed and the scan is the point.
            Stats.substitutionScansNotEligible++;
            return findWornTool(internalStorage, durability, wanted, needed);
        }
        if (!Config.reuseSimulatedSubstitution || Config.substitutionProbe) {
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
    private void rstweaks$rememberDecision(final Action action, final List<ResourceKey[]> swaps,
                                           final ResourceList inputs,
                                           final MutableResourceList internalStorage) {
        if (Config.substitutionProbe
            || (!Config.reuseSimulatedSubstitution && !Config.simulateRepeatProbe)) {
            return;
        }
        if (action != Action.SIMULATE) {
            this.rstweaks$simulatedSwaps = null;
            this.rstweaks$simulateStreak = 0;
            return;
        }
        rstweaks$probeRepeatedSimulate(swaps, inputs, internalStorage);
        Stats.simulateDecisionsComputed++;
        final List<ResourceKey> flat = new ArrayList<>(swaps.size() * 2);
        for (final ResourceKey[] swap : swaps) {
            flat.add(swap[0]);
            flat.add(swap[1]);
        }
        this.rstweaks$simulatedSwaps = flat;
        // Copied, not aliased. rstweaks$consumed is cleared at the top of every call, so holding a
        // reference to it would leave the replay restoring an empty list -- and an empty consumed
        // list means byproducts come back as encoded, which is a repaired tool.
        this.rstweaks$rememberedConsumed = List.copyOf(this.rstweaks$consumedList());
        this.rstweaks$rememberedVersion =
            internalStorage instanceof VersionedResourceList versioned
                ? versioned.rstweaks$version()
                : 0L;
        this.rstweaks$rememberedInputSize = inputs.getAll().size();
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
    private void rstweaks$probeRepeatedSimulate(final List<ResourceKey[]> swaps,
                                                final ResourceList inputs,
                                                final MutableResourceList internalStorage) {
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
            rstweaks$reportDisagreement(previous, swaps, inputs, internalStorage);
        }
        this.rstweaks$simulateStreak++;
        if (this.rstweaks$simulateStreak > Stats.simulateStreakLongest) {
            Stats.simulateStreakLongest = this.rstweaks$simulateStreak;
        }
    }

    /**
     * Writes out what one disagreeing repeat actually looked like.
     *
     * <p>464 disagreements in 75,360,365 repeats — one in 162,000 — is small enough to be tempting
     * and far too large to gamble a wrong tool substitution on. <b>Two causes would produce it and
     * they need different fixes</b>, so this prints the evidence that separates them instead of
     * letting me pick the one I already believe:
     *
     * <ul>
     *   <li><b>Internal storage changed.</b> Refined Storage steps many patterns per tick inside one
     *       task; a sibling pattern executing adds or removes resources from the shared storage, so
     *       this pattern's next simulate legitimately sees a different world. The fix would be to
     *       version the storage and invalidate on mutation.</li>
     *   <li><b>The inputs changed.</b> {@code calculateIterationInputs} is recomputed every step and
     *       takes the {@code Action}; if the ingredient budget draws down, the <em>wanted</em> wear
     *       level itself can differ between two simulates. Versioning storage would not catch that
     *       at all.</li>
     * </ul>
     *
     * <p>So it logs, for every resource named on either side, what storage holds and what the inputs
     * ask for. A {@code wanted} key that differs points at the second cause; equal keys with
     * different amounts point at the first.
     *
     * <p>Capped at {@link #DISAGREEMENTS_LOGGED_MAX} lines. This runs on the hottest path in the
     * mod, and a rare event logged without a bound is how a diagnostic becomes the outage.
     */
    @Unique
    private void rstweaks$reportDisagreement(final List<ResourceKey> previous,
                                             final List<ResourceKey[]> swaps,
                                             final ResourceList inputs,
                                             final MutableResourceList internalStorage) {
        if (Stats.simulateRepeatsDisagreed > DISAGREEMENTS_LOGGED_MAX) {
            return;
        }
        final StringBuilder detail = new StringBuilder(256);
        detail.append("[rstweaks] simulate repeat disagreed (#")
            .append(Stats.simulateRepeatsDisagreed).append(", streak ")
            .append(this.rstweaks$simulateStreak).append(")\n  before:");
        for (int i = 0; i < previous.size(); i += 2) {
            rstweaks$describe(detail, previous.get(i), previous.get(i + 1), inputs, internalStorage);
        }
        detail.append("\n  now:   ");
        for (final ResourceKey[] swap : swaps) {
            rstweaks$describe(detail, swap[0], swap[1], inputs, internalStorage);
        }
        RSTweaks.LOGGER.info(detail.toString());
    }

    /** One {@code wanted -> substitute} pair with what storage and the inputs currently say. */
    @Unique
    private static void rstweaks$describe(final StringBuilder out,
                                          final ResourceKey wanted,
                                          final ResourceKey substitute,
                                          final ResourceList inputs,
                                          final MutableResourceList internalStorage) {
        out.append("\n    wanted ").append(wanted)
            .append(" (inputs ask ").append(inputs.get(wanted))
            .append(", storage has ").append(internalStorage.get(wanted))
            .append(")\n      -> ").append(substitute)
            .append(" (storage has ").append(internalStorage.get(substitute)).append(')');
    }

    /** Enough to see a pattern, few enough that a burst cannot flood the log. */
    @Unique
    private static final int DISAGREEMENTS_LOGGED_MAX = 20;

    /** Consecutive failing simulates on this pattern, for the streak-length figure. */
    @Unique
    private int rstweaks$simulateStreak;

    /**
     * Applies the remembered decision without re-deriving it.
     *
     * <p>The swaps still have to be applied: {@code calculateIterationInputs} builds a fresh input
     * list every step, so each call gets a new object that has not been rewritten yet. What is
     * skipped is everything that <em>decided</em> the swaps — the walk over every input, the
     * durability question per ingredient, and a storage lookup each.
     *
     * <p>The consumed list is restored rather than recomputed, because
     * {@link InternalTaskPatternMixin} reads it to age byproducts. Leaving it empty here would hand
     * back tools as encoded — a repaired tool, which is durability created out of nothing, and the
     * bug 0.2.57 shipped.
     */
    @Unique
    private void rstweaks$replayDecision(final ResourceList inputs,
                                         final MutableResourceList mutableInputs) {
        Stats.simulateDecisionsReplayed++;
        final List<ResourceKey> consumed = this.rstweaks$rememberedConsumed;
        if (consumed != null && !consumed.isEmpty()) {
            this.rstweaks$consumedList().addAll(consumed);
        }
        final List<ResourceKey> swaps = this.rstweaks$simulatedSwaps;
        if (swaps == null) {
            return;
        }
        for (int i = 0; i < swaps.size(); i += 2) {
            final ResourceKey wanted = swaps.get(i);
            final long amount = inputs.get(wanted);
            mutableInputs.remove(wanted, amount);
            mutableInputs.add(swaps.get(i + 1), amount);
        }
    }

    /**
     * Checks what the replay <em>would</em> have produced against what recomputing actually
     * produces.
     *
     * <p>The replay skips the whole decision loop on a repeated failing simulate, and no automated
     * test can cover it: the task-engine fixture treats a step that makes no progress as a deadlock
     * and fails the scenario, so it contains zero failing simulates by construction. That leaves a
     * change on the item-correctness path with no coverage, which is not something to ship on
     * reasoning alone.
     *
     * <p>So this exists to be switched on for one real craft. It costs the whole saving while it
     * runs — the point is to answer the question, not to be fast — and a single clean reading over
     * a craft that produces tens of millions of replays is worth more than any fixture could be.
     */
    @Unique
    private void rstweaks$verifyReplay(@Nullable final List<ResourceKey> expected,
                                       @Nullable final List<ResourceKey> expectedConsumed,
                                       final List<ResourceKey[]> swaps) {
        Stats.replaysVerified++;
        final boolean swapsMatch = expected != null && rstweaks$sameDecision(expected, swaps);
        final boolean consumedMatch = expectedConsumed != null
            && expectedConsumed.equals(this.rstweaks$consumedList());
        if (swapsMatch && consumedMatch) {
            return;
        }
        Stats.replaysDiverged++;
        if (Stats.replaysDiverged <= DISAGREEMENTS_LOGGED_MAX) {
            RSTweaks.LOGGER.warn("[rstweaks] replay would have been WRONG (#{}): swaps {}, "
                    + "consumed {}\n  replay swaps:    {}\n  recomputed:      {}"
                    + "\n  replay consumed: {}\n  recomputed:      {}",
                Stats.replaysDiverged, swapsMatch ? "match" : "DIFFER",
                consumedMatch ? "match" : "DIFFER",
                expected, swaps.stream().map(s -> s[0] + "->" + s[1]).toList(),
                expectedConsumed, this.rstweaks$consumedList());
        }
    }

    /**
     * The consumed list from the remembered decision, so a replay can restore it.
     *
     * <p>Held separately from {@link #rstweaks$simulatedSwaps} because the two are not the same
     * thing: a tool whose encoded wear level happens to be the one in hand is <em>consumed</em>
     * without being <em>swapped</em>, so it appears here and not there.
     */
    @Unique
    @Nullable
    private List<ResourceKey> rstweaks$rememberedConsumed;

    /**
     * Whether this SIMULATE may reuse the previous one's answer wholesale.
     *
     * <p><b>What earns this.</b> A real insanium craft produced 169,947,478 repeated failing
     * simulates and 1,052 disagreements — one in 161,547 — and the disagreement log named the cause
     * exactly: the {@code wanted} key was <em>identical</em> every time, and what moved was storage.
     * The crystal wears, a more-worn level appears, and {@code findWornTool}'s "most worn first"
     * rule flips the pick (fresh → {@code @275} → {@code @494}). One case went from <em>no
     * substitute</em> to a substitute, which a blind cache would have turned into a stalled craft.
     *
     * <p>Every one of those is a mutation of the internal storage, so a mutation counter turns the
     * 99.99938% into an exact test. Two guards, both {@code O(1)}:
     *
     * <ul>
     *   <li>the storage's version is unchanged since the remembered decision was made;</li>
     *   <li>the input list still holds the same number of resources — cheap insurance against
     *       {@code calculateIterationInputs} handing back a different ingredient set, which the
     *       observed disagreements never did but 20 logged samples cannot rule out.</li>
     * </ul>
     *
     * <p>A storage that does not implement {@link VersionedResourceList} — a different list class,
     * or the mixin not applying — reuses nothing and rescans exactly as before. Correctness is a
     * property of this check, not of the mixin having landed.
     */
    @Unique
    private boolean rstweaks$repeatValid;

    /** Storage version when {@link #rstweaks$simulatedSwaps} was recorded. */
    @Unique
    private long rstweaks$rememberedVersion;

    /** Input-list size when {@link #rstweaks$simulatedSwaps} was recorded. */
    @Unique
    private int rstweaks$rememberedInputSize;

    @Unique
    private boolean rstweaks$canReuseFailedSimulate(final Action action,
                                                    final ResourceList inputs,
                                                    final MutableResourceList internalStorage) {
        // Disabled while either probe runs, for the reason the execute-side cache is: a probe that
        // measures a cached answer against itself agrees no matter what.
        if (action != Action.SIMULATE || !Config.reuseFailedSimulate
            || Config.substitutionProbe || Config.simulateRepeatProbe) {
            return false;
        }
        if (this.rstweaks$simulatedSwaps == null
            || !(internalStorage instanceof VersionedResourceList versioned)) {
            return false;
        }
        return versioned.rstweaks$version() == this.rstweaks$rememberedVersion
            && inputs.getAll().size() == this.rstweaks$rememberedInputSize;
    }

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
