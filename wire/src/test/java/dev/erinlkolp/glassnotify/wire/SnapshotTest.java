package dev.erinlkolp.glassnotify.wire;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class SnapshotTest {

    private static NotificationItem item(String key) {
        return new NotificationItem(key, "Signal", "t", "x", 1L, Tier.QUEUE);
    }

    @Test
    public void exposesIdAndItems() {
        Snapshot s = new Snapshot(7L, Arrays.asList(item("a"), item("b")));
        assertEquals(7L, s.snapshotId);
        assertEquals(2, s.items.size());
        assertEquals("a", s.items.get(0).key);
    }

    @Test
    public void itemsAreUnmodifiable() {
        Snapshot s = new Snapshot(1L, new ArrayList<NotificationItem>(Arrays.asList(item("a"))));
        try {
            s.items.add(item("b"));
            fail("expected UnsupportedOperationException");
        } catch (UnsupportedOperationException expected) {
        }
    }

    @Test
    public void defensivelyCopiesTheCallersList() {
        List<NotificationItem> source = new ArrayList<NotificationItem>();
        source.add(item("a"));
        Snapshot s = new Snapshot(1L, source);
        source.add(item("b"));
        assertEquals("later mutation of the caller's list must not leak in", 1, s.items.size());
    }

    @Test
    public void emptySnapshotIsLegal() {
        // "You have nothing waiting" is a real state the phone must be able to send.
        assertEquals(0, new Snapshot(1L, new ArrayList<NotificationItem>()).items.size());
    }
}
