package com.wraithhawit.rstweaks.test;

import com.wraithhawit.rstweaks.gate.UpstreamGate;
import com.wraithhawit.rstweaks.gate.UpstreamGate.Superseded;

import java.util.ArrayList;
import java.util.List;

/**
 * Self-tests for {@link UpstreamGate}, which decides whether an addon mixin stands down because
 * the addon now carries the same fix. Run with {@code ./gradlew gateCheck}; exits non-zero on the
 * first failure.
 *
 * <p><b>What this suite is not.</b> Nothing here proves {@code AddonMixinGate} is wired into the
 * two mixin configs, that Mixin calls it, or that {@code LoadingModList} answers at that stage —
 * no plain JVM transforms bytecode, and per {@code rstweaks-gametest-harness} a headless suite can
 * never test a mixin. That needs a real launch against a real Step Crafter.
 *
 * <p>What it does cover is the decision, which is the half that can be wrong without anyone
 * noticing. Version strings here look like {@code 1.21.1-0.6.14}, and the two obvious ways to
 * compare them both misplace {@code 0.6.9} — a string comparison puts it above {@code 0.6.14},
 * and Maven's rules treat everything past the dash as one opaque qualifier. Either mistake leaves
 * our mixin applied on exactly the release that supersedes it, which is silent.
 */
public final class HeadlessGateCheck {
    private static final List<String> FAILURES = new ArrayList<>();
    private static int checks;

    private HeadlessGateCheck() {
    }

    public static void main(final String[] args) {
        ordersNumericallyNotLexically();
        equalVersionSupersedes();
        shorterIsOlder();
        unreadableVersionKeepsTheMixin();
        theRealReleases();

        System.out.println("gate checks: " + checks);
        if (!FAILURES.isEmpty()) {
            FAILURES.forEach(failure -> System.out.println("  FAIL " + failure));
            System.out.println("FAIL");
            System.exit(1);
        }
        System.out.println("PASS");
    }

    /** The case that motivated writing the comparison by hand rather than reaching for a library. */
    private static void ordersNumericallyNotLexically() {
        expect("0.6.9 is older than 0.6.14",
            !UpstreamGate.isAtLeast("1.21.1-0.6.9", "1.21.1-0.6.14"));
        expect("0.6.14 is newer than 0.6.9",
            UpstreamGate.isAtLeast("1.21.1-0.6.14", "1.21.1-0.6.9"));
        expect("0.1.7 is older than 0.1.10",
            !UpstreamGate.isAtLeast("1.21.1-0.1.7", "1.21.1-0.1.10"));
        expect("a string comparison would have said otherwise",
            "1.21.1-0.6.9".compareTo("1.21.1-0.6.14") > 0);
    }

    private static void equalVersionSupersedes() {
        expect("the superseding version itself counts",
            UpstreamGate.isAtLeast("1.21.1-0.1.7", "1.21.1-0.1.7"));
        expect("a build suffix does not make it older",
            UpstreamGate.isAtLeast("1.21.1-0.1.7+build2", "1.21.1-0.1.7"));
    }

    private static void shorterIsOlder() {
        expect("0.6 precedes 0.6.1", !UpstreamGate.isAtLeast("1.21.1-0.6", "1.21.1-0.6.1"));
        expect("0.6.1 follows 0.6", UpstreamGate.isAtLeast("1.21.1-0.6.1", "1.21.1-0.6"));
    }

    /**
     * A version we cannot read must keep the mixin. Withdrawing an optimization the user believes
     * is running is the one outcome this mod refuses to produce quietly.
     */
    private static void unreadableVersionKeepsTheMixin() {
        for (final Superseded tweak : UpstreamGate.SUPERSEDED) {
            expect(tweak.modId() + ": unknown version keeps our mixin",
                UpstreamGate.stillNeeded(tweak, null));
        }
    }

    /** The versions this actually shipped for, so the constants cannot drift from the intent. */
    private static void theRealReleases() {
        final Superseded step = UpstreamGate.forMixin(
            "com.wraithhawit.rstweaks.mixin.StepRequesterNetworkNodeMixin");
        expect("step crafter tweak is registered", step != null);
        if (step != null) {
            expect("0.1.5 still wants our backoff",
                UpstreamGate.stillNeeded(step, "1.21.1-0.1.5"));
            expect("0.1.6 still wants our backoff",
                UpstreamGate.stillNeeded(step, "1.21.1-0.1.6"));
            expect("0.1.7 stands it down",
                !UpstreamGate.stillNeeded(step, "1.21.1-0.1.7"));
        }

        final Superseded cable = UpstreamGate.forMixin(
            "com.wraithhawit.rstweaks.mixin.TieredAutocrafterBlockEntityMixin");
        expect("cable tiers tweak is registered", cable != null);
        if (cable != null) {
            expect("0.6.13 still wants our lookup",
                UpstreamGate.stillNeeded(cable, "1.21.1-0.6.13"));
            expect("0.6.14 stands it down",
                !UpstreamGate.stillNeeded(cable, "1.21.1-0.6.14"));
        }

        expect("an unregistered mixin is never gated",
            UpstreamGate.forMixin("com.wraithhawit.rstweaks.mixin.TaskContainerMixin") == null);
    }

    private static void expect(final String what, final boolean condition) {
        ++checks;
        if (!condition) {
            FAILURES.add(what);
        }
    }
}
