package com.wraithhawit.rstweaks.gate;

import com.wraithhawit.rstweaks.gate.UpstreamGate.Superseded;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A transcription of {@link com.wraithhawit.rstweaks.test.HeadlessGateCheck} into JUnit, one test
 * per scenario, so a mutation run can attribute each surviving mutant to a named case. Same
 * assertions, same order; nothing new is claimed here that gateCheck did not already claim.
 */
class UpstreamGateTest {

    @Test
    void ordersNumericallyNotLexically() {
        assertFalse(UpstreamGate.isAtLeast("1.21.1-0.6.9", "1.21.1-0.6.14"));
        assertTrue(UpstreamGate.isAtLeast("1.21.1-0.6.14", "1.21.1-0.6.9"));
        assertFalse(UpstreamGate.isAtLeast("1.21.1-0.1.7", "1.21.1-0.1.10"));
        assertTrue("1.21.1-0.6.9".compareTo("1.21.1-0.6.14") > 0,
            "a string comparison would have said otherwise");
    }

    @Test
    void equalVersionSupersedes() {
        assertTrue(UpstreamGate.isAtLeast("1.21.1-0.1.7", "1.21.1-0.1.7"));
        assertTrue(UpstreamGate.isAtLeast("1.21.1-0.1.7+build2", "1.21.1-0.1.7"));
    }

    @Test
    void shorterIsOlder() {
        assertFalse(UpstreamGate.isAtLeast("1.21.1-0.6", "1.21.1-0.6.1"));
        assertTrue(UpstreamGate.isAtLeast("1.21.1-0.6.1", "1.21.1-0.6"));
    }

    @Test
    void unreadableVersionKeepsTheMixin() {
        for (final Superseded tweak : UpstreamGate.SUPERSEDED) {
            assertTrue(UpstreamGate.stillNeeded(tweak, null), tweak.modId());
        }
    }

    @Test
    void theRealReleases() {
        final Superseded step = UpstreamGate.forMixin(
            "com.wraithhawit.rstweaks.mixin.StepRequesterNetworkNodeMixin");
        assertNotNull(step);
        assertTrue(UpstreamGate.stillNeeded(step, "1.21.1-0.1.5"));
        assertTrue(UpstreamGate.stillNeeded(step, "1.21.1-0.1.6"));
        assertFalse(UpstreamGate.stillNeeded(step, "1.21.1-0.1.7"));

        final Superseded cable = UpstreamGate.forMixin(
            "com.wraithhawit.rstweaks.mixin.TieredAutocrafterBlockEntityMixin");
        assertNotNull(cable);
        assertTrue(UpstreamGate.stillNeeded(cable, "1.21.1-0.6.13"));
        assertFalse(UpstreamGate.stillNeeded(cable, "1.21.1-0.6.14"));

        assertNull(UpstreamGate.forMixin("com.wraithhawit.rstweaks.mixin.TaskContainerMixin"));
    }

    /**
     * The saturation in {@code numbersIn} had no test until a mutation run said so: replacing the
     * overflow guard's subtraction with an addition survived the whole gateCheck suite. A mod is
     * free to put a date or a build number in its version, and a run of digits too long for a
     * {@code long} must saturate rather than wrap negative and invert the comparison.
     */
    @Test
    void saturatesInsteadOfOverflowing() {
        assertTrue(UpstreamGate.isAtLeast("1.21.1-99999999999999999999", "1.21.1-0.6.14"));
        assertFalse(UpstreamGate.isAtLeast("1.21.1-0.6.14", "1.21.1-99999999999999999999"));
    }

    /**
     * A zero component is a component. Nothing in the original suite distinguished
     * {@code [1,21,1,0,9,9]} from {@code [1,21,1,9,9]}, because every case it compared happened to
     * order the same either way.
     */
    @Test
    void zeroComponentsAreNotDropped() {
        assertFalse(UpstreamGate.isAtLeast("1.21.1-0.9.9", "1.21.1-1.0.0"));
        assertTrue(UpstreamGate.isAtLeast("1.21.1-1.0.0", "1.21.1-0.9.9"));
    }

    /**
     * Digits must be read as their value, not as their character code. Every ordering the original
     * suite asserted also holds under a constant offset per digit, so that mistake was invisible;
     * leading zeros are the case that is not.
     */
    @Test
    void leadingZerosDoNotChangeTheNumber() {
        assertTrue(UpstreamGate.isAtLeast("1.21.1-7", "1.21.1-007"));
        assertTrue(UpstreamGate.isAtLeast("1.21.1-007", "1.21.1-7"));
    }
}
