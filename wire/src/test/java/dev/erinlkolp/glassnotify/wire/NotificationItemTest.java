package dev.erinlkolp.glassnotify.wire;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.fail;

import org.junit.Test;

public class NotificationItemTest {

    private static NotificationItem item(String key) {
        return new NotificationItem(key, "Signal", "Jordan Reyes", "are you still good for 7pm?",
                1785870000000L, Tier.INTERRUPT);
    }

    @Test
    public void exposesItsFields() {
        NotificationItem i = item("k1");
        assertEquals("k1", i.key);
        assertEquals("Signal", i.appLabel);
        assertEquals("Jordan Reyes", i.title);
        assertEquals("are you still good for 7pm?", i.text);
        assertEquals(1785870000000L, i.postedAt);
        assertEquals(Tier.INTERRUPT, i.tier);
    }

    @Test
    public void equalityIsByValue() {
        assertEquals(item("k1"), item("k1"));
        assertEquals(item("k1").hashCode(), item("k1").hashCode());
        assertNotEquals(item("k1"), item("k2"));
    }

    @Test
    public void rejectsNullKey() {
        try {
            new NotificationItem(null, "Signal", "t", "x", 1L, Tier.QUEUE);
            fail("expected NullPointerException");
        } catch (NullPointerException expected) {
            // A null key would break identity matching on the Glass side.
        }
    }

    @Test
    public void rejectsNullTier() {
        try {
            new NotificationItem("k", "Signal", "t", "x", 1L, null);
            fail("expected NullPointerException");
        } catch (NullPointerException expected) {
        }
    }
}
