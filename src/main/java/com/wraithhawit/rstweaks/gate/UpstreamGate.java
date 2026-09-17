package com.wraithhawit.rstweaks.gate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

/**
 * Which of our addon tweaks the addon author has since implemented himself.
 *
 * <p>Some of this mod's optimizations were written against someone else's code, reported
 * upstream, and then <em>shipped</em> upstream. That is the outcome we wanted, and it leaves a
 * problem: our mixin still applies, and replaces the author's own implementation rather than
 * sitting beside it. Ours injects at HEAD and cancels, so his new code never runs — including
 * any correctness fix folded into the same release.
 *
 * <p>So each tweak below names the version that supersedes it, and the mixin is skipped from that
 * version on. Older installs keep our fix, which is the whole point — an ATM10 instance is not
 * necessarily an up-to-date one, and this mod has to stay drag-and-drop.
 *
 * <p><b>An entry belongs here only once the author's implementation has been read in his own
 * bytecode and found to cover the ground ours covers.</b> A changelog line is not enough, and the
 * Step Crafter entry that used to sit below is why. It stood down the entire Step Requester mixin
 * from stepcrafter {@code 1.21.1-0.1.7} on the strength of "Improved Step Requester performance by
 * adding a timeout on failed requested crafts". Read in 0.1.8 and 0.1.9 — whose {@code doWork} is
 * byte-identical — that timeout is a flat {@code FAILED_TASK_TIMEOUT_TICKS = 20}, written only on
 * the branch where {@code startTask} returns empty, over a bare
 * {@code TimeoutableCancellationToken} carrying RS's full 5,000ms. It does not escalate, it never
 * fires for a calculation that <em>succeeds</em> expensively, and it caps no calculation at all.
 * Standing down for it withdrew the 200ms→1,000ms calculation budget and the cost-derived sleep
 * and put a one-second nap in their place.
 *
 * <p>A spark profile of a survival world on stepcrafter 0.1.9 and rstweaks 0.22.1 measured what
 * that costs: <b>68.1% of the server thread</b> inside one
 * {@code StepRequesterNetworkNode.doWork}, TPS 6.4, worst tick 5,259ms — RS's uncapped timeout,
 * which is the exact freeze {@code stepRequesterCalculationBudgetMs} exists to prevent. No
 * {@code rstweaks$recordOutcome} frame sat between {@code doWork} and {@code startTask}, which is
 * how the stand-down was spotted at all. The entry is gone as of 0.22.3; do not restore it without
 * reading the bytecode first.
 *
 * <p>The interaction that motivated it is real, but it points the other way. Since 0.1.7 a null
 * from {@code PatternResourceContainerImpl.get(slot)} means "slot empty, clear its timeout", and
 * our redirect returns null for exactly the slots we are sleeping — so his timeout never
 * accumulates while ours is installed. The two fixes do collapse into one. That one is ours, and
 * ours is the stronger of the two. Nothing crashes and nothing logs.
 *
 * <p>Deliberately free of Minecraft, NeoForge and Mixin types so the comparison can be tested in a
 * plain JVM. {@link AddonMixinGate} is the thin part that reads the loaded version and calls in
 * here; this is the part with the arithmetic worth doubting.
 */
public final class UpstreamGate {
    /**
     * A tweak of ours that a later version of the target mod implements itself.
     *
     * @param mixinClass    fully-qualified mixin, as Mixin names it in {@code shouldApplyMixin}
     * @param modId         the mod whose version decides
     * @param supersededAt  the first version that carries the author's own implementation
     * @param feature       what {@code /rstweaks stats} and the startup line call this tweak
     * @param upstreamNote  the author's changelog line, so the log says whose fix took over
     */
    public record Superseded(String mixinClass,
                             String modId,
                             String supersededAt,
                             String feature,
                             String upstreamNote) {
    }

    public static final List<Superseded> SUPERSEDED = List.of(
        // No stepcrafter entry, deliberately. See the class javadoc: its own timeout is
        // failure-only and uncapped, so standing our mixin down for it is a straight loss.
        new Superseded(
            "com.wraithhawit.rstweaks.mixin.TieredAutocrafterBlockEntityMixin",
            "cabletiers",
            "1.21.1-0.6.14",
            "tiered autocrafter lookup",
            "Improved performance of sided input")
    );

    /** Feature name -> why it is off, for whoever asks what is actually running. */
    private static final Map<String, String> STOOD_DOWN = new LinkedHashMap<>();

    private UpstreamGate() {
    }

    @Nullable
    public static Superseded forMixin(final String mixinClass) {
        for (final Superseded candidate : SUPERSEDED) {
            if (candidate.mixinClass().equals(mixinClass)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Whether our mixin should still be applied.
     *
     * <p>A version we cannot read means we apply it, which is what this mod did before the gate
     * existed. Guessing the other way would silently withdraw an optimization the user believes
     * is running, and this mod's whole stance on mixins is that it fails loudly instead.
     *
     * @param installedVersion the target mod's version string, or {@code null} if unreadable
     */
    public static boolean stillNeeded(final Superseded tweak,
                                      @Nullable final String installedVersion) {
        if (installedVersion == null) {
            return true;
        }
        return !isAtLeast(installedVersion, tweak.supersededAt());
    }

    /** Records that a tweak stood down, so the startup line does not claim it is running. */
    public static synchronized void standDown(final Superseded tweak, final String version) {
        STOOD_DOWN.put(tweak.feature(), tweak.modId() + " " + version + " implements this itself");
    }

    public static synchronized boolean isStoodDown(final String feature) {
        return STOOD_DOWN.containsKey(feature);
    }

    /** Empty when nothing stood down. Ordered, so the report reads the same way twice. */
    public static synchronized Map<String, String> stoodDown() {
        return Map.copyOf(STOOD_DOWN);
    }

    /**
     * Whether {@code version} is at least {@code target}, comparing the numbers in each.
     *
     * <p>These are Minecraft mod versions — {@code 1.21.1-0.6.14} — and the obvious approaches
     * both get them wrong. A string comparison puts {@code 0.6.9} <em>above</em> {@code 0.6.14},
     * which would leave our mixin applied on the very release that supersedes it. Maven's
     * qualifier rules treat everything after the dash as one opaque token for the same reason.
     *
     * <p>So: read every run of digits as a number, in order, and compare those. {@code 1.21.1-0.6.9}
     * becomes {@code [1,21,1,0,6,9]} and sorts below {@code [1,21,1,0,6,14]}. Non-digits are
     * separators and nothing else, which also makes a {@code +build} suffix harmless. When one
     * list is a prefix of the other the shorter is older, so {@code 0.6} precedes {@code 0.6.1}.
     */
    public static boolean isAtLeast(final String version, final String target) {
        final List<Long> left = numbersIn(version);
        final List<Long> right = numbersIn(target);
        final int shared = Math.min(left.size(), right.size());
        for (int i = 0; i < shared; i++) {
            final int order = Long.compare(left.get(i), right.get(i));
            if (order != 0) {
                return order > 0;
            }
        }
        return left.size() >= right.size();
    }

    /**
     * Every run of digits, in order, as numbers.
     *
     * <p>Parsed into a {@code long} and saturated rather than overflowing: a mod is free to put
     * a date or a build number in its version, and a twenty-digit run must not wrap negative and
     * invert the comparison.
     */
    private static List<Long> numbersIn(final String version) {
        final List<Long> numbers = new java.util.ArrayList<>(6);
        long current = 0L;
        boolean inNumber = false;
        for (int i = 0; i < version.length(); i++) {
            final char c = version.charAt(i);
            if (c >= '0' && c <= '9') {
                inNumber = true;
                if (current <= (Long.MAX_VALUE - (c - '0')) / 10L) {
                    current = current * 10L + (c - '0');
                } else {
                    current = Long.MAX_VALUE;
                }
            } else if (inNumber) {
                numbers.add(current);
                current = 0L;
                inNumber = false;
            }
        }
        if (inNumber) {
            numbers.add(current);
        }
        return numbers;
    }
}
