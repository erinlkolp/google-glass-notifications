package dev.erinlkolp.glassnotify.wire;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class TierTest {

    @Test
    public void codesAreStableOnTheWire() {
        // These numbers are protocol, not implementation detail. Changing them
        // breaks compatibility with any already-installed build.
        assertEquals(1, Tier.INTERRUPT.code);
        assertEquals(2, Tier.QUEUE.code);
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
}
