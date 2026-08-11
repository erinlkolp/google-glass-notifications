package dev.erinlkolp.glassnotify.wire;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

public class GlassStateTest {

    @Test
    public void keepsWhatItIsGiven() {
        GlassState state = new GlassState(72, true);
        assertEquals(72, state.batteryLevel);
        assertTrue(state.onPower);
    }

    @Test
    public void acceptsBothBounds() {
        assertEquals(0, new GlassState(0, false).batteryLevel);
        assertEquals(100, new GlassState(100, true).batteryLevel);
    }

    @Test
    public void rejectsNegativeLevel() {
        try {
            new GlassState(-1, false);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // The constructor is the only guard; a bad level must not travel.
        }
    }

    @Test
    public void rejectsLevelOverOneHundred() {
        try {
            new GlassState(101, false);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void equalsComparesBothFields() {
        assertEquals(new GlassState(100, true), new GlassState(100, true));
        assertNotEquals(new GlassState(100, true), new GlassState(100, false));
        assertNotEquals(new GlassState(99, true), new GlassState(100, true));
    }

    @Test
    public void equalStatesShareAHashCode() {
        assertEquals(new GlassState(55, true).hashCode(), new GlassState(55, true).hashCode());
    }

    @Test
    public void isNotEqualToOtherTypes() {
        assertFalse(new GlassState(50, false).equals("50"));
    }
}
