package dev.erinlkolp.glassnotify.phone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

public class BackoffTest {

    private Backoff backoff;

    @Before
    public void setUp() {
        backoff = new Backoff();
    }

    @Test
    public void startsShort() {
        // The common case is Glass momentarily out of range. Waiting a minute
        // for the first retry would make that feel broken.
        assertEquals(Backoff.INITIAL_MS, backoff.nextDelayMs());
    }

    @Test
    public void doublesEachTime() {
        assertEquals(Backoff.INITIAL_MS, backoff.nextDelayMs());
        assertEquals(Backoff.INITIAL_MS * 2, backoff.nextDelayMs());
        assertEquals(Backoff.INITIAL_MS * 4, backoff.nextDelayMs());
        assertEquals(Backoff.INITIAL_MS * 8, backoff.nextDelayMs());
    }

    @Test
    public void capsAtTheCeiling() {
        for (int i = 0; i < 100; i++) {
            assertTrue(backoff.nextDelayMs() <= Backoff.MAX_MS);
        }
        assertEquals(Backoff.MAX_MS, backoff.nextDelayMs());
    }

    @Test
    public void neverOverflows() {
        // 100 doublings would overflow a long if implemented naively.
        for (int i = 0; i < 100; i++) {
            assertTrue("delay went negative at attempt " + i, backoff.nextDelayMs() > 0);
        }
    }

    @Test
    public void resetReturnsToTheStart() {
        backoff.nextDelayMs();
        backoff.nextDelayMs();
        backoff.nextDelayMs();

        backoff.reset();

        assertEquals(Backoff.INITIAL_MS, backoff.nextDelayMs());
    }

    @Test
    public void ceilingIsSixtySeconds() {
        // Spec section 10.2 fixes this; a longer ceiling makes walking back
        // into range feel dead.
        assertEquals(60_000L, Backoff.MAX_MS);
    }
}
