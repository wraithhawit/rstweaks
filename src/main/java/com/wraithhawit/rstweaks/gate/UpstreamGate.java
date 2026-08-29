package com.wraithhawit.rstweaks.gate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

/**
 * Which of our addon tweaks the addon author has since implemented himself.
 *
 * <p>Three of this mod's optimizations were written against someone else's code, reported
 * upstream, and then <em>shipped</em> upstream. That is the outcome we wanted, and it leaves a
 * problem: our mixins still apply, and both of the ones below replace the author's own
 * implementation rather than sitting beside it. Ours injects at HEAD and cancels, so his new
 * code never runs — including any correctness fix folded into the same release.
 *
 * <p>The Step Crafter case is worse than redundant. Since 0.1.7 the node keeps a
 * {@code failedTaskTimeouts} map, and a null from {@code PatternResourceContainerImpl.get(slot)}
 * now means "this slot is empty, clear its timeout". Our redirect returns null for exactly the
 * slots we are sleeping, so on every sleeping tick we reset his backoff for the slots that are
 * failing. His timeout can never accumulate while ours is installed. Nothing crashes and nothing
 * logs; the two fixes just quietly cancel out into one.
 *
 * <p>So each tweak below names the version that supersedes it, and the mixin is skipped from that
 * version on. Older installs keep our fix, which is the whole point — an ATM10 instance is not
 * necessarily an up-to-date one, and this mod has to stay drag-and-drop.
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
        new Superseded(
            "com.wraithhawit.rstweaks.mixin.StepRequesterNetworkNodeMixin",
            "stepcrafter",
            "1.21.1-0.1.7",
            "step requester backoff",
            "Improved Step Requester performance by adding a timeout on failed requested crafts"),
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
