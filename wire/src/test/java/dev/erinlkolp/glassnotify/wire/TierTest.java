package dev.erinlkolp.glassnotify.wire;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TierTest {

    @Test
    public void codesAreStableOnTheWire() {
        // These numbers are protocol, not implementation detail. Changing them
        // breaks compatibility with any already-installed build.
        assertEquals(1, Tier.INTERRUPT.code);
        assertEquals(2, Tier.QUEUE.code);
        assertEquals(3, Tier.INTERRUPT_CHIRP.code);
    }

    @Test
    public void roundTripsThroughItsCode() {
        for (Tier tier : Tier.values()) {
            assertEquals(tier, Tier.fromCode(tier.code));
        }
    }

    @Test
    public void unknownCodeReturnsNullRatherThanThrowing() {
        // Decoders turn this into a ProtocolException with useful context;
        // the enum itself stays free of IO concerns.
        assertNull(Tier.fromCode(0));
        assertNull(Tier.fromCode(99));
        assertNull(Tier.fromCode(-1));
    }

    @Test
    public void chirpTierHasStableCodeThree() {
        assertEquals(3, Tier.INTERRUPT_CHIRP.code);
    }

    @Test
    public void bothInterruptTiersLightUpThePrism() {
        assertTrue(Tier.INTERRUPT.interrupts());
        assertTrue(Tier.INTERRUPT_CHIRP.interrupts());
        assertFalse(Tier.QUEUE.interrupts());
    }

    @Test
    public void onlyTheChirpTierMakesSound() {
        assertTrue(Tier.INTERRUPT_CHIRP.chirps());
        assertFalse(Tier.INTERRUPT.chirps());
        assertFalse(Tier.QUEUE.chirps());
    }
}
