package dev.erinlkolp.glassnotify.glass;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class StalenessTest {

    @Test
    public void freshContactIsNotStale() {
        assertFalse(SnapshotStore.isStale(1_000L, 1_000L));
        assertFalse(SnapshotStore.isStale(1_000L, 5_000L));
    }

    @Test
    public void goesStaleAfterTheThreshold() {
        long lastContact = 1_000L;
        assertFalse(SnapshotStore.isStale(lastContact, lastContact + SnapshotStore.STALE_AFTER_MS - 1));
        assertTrue(SnapshotStore.isStale(lastContact, lastContact + SnapshotStore.STALE_AFTER_MS));
        assertTrue(SnapshotStore.isStale(lastContact, lastContact + 600_000L));
    }

    @Test
    public void aClockThatWentBackwardsIsNotTreatedAsStale() {
        // elapsedRealtime should never go backwards, but a bug that made it
        // appear to must not silently blank the queue.
        assertFalse(SnapshotStore.isStale(10_000L, 9_000L));
    }

    @Test
    public void neverContactedIsStale() {
        // Sentinel: nothing has ever arrived, so whatever is cached on disk
        // came from a previous boot and must be labelled.
        assertTrue(SnapshotStore.isStale(SnapshotStore.NEVER, 0L));
        assertTrue(SnapshotStore.isStale(SnapshotStore.NEVER, 500_000L));
    }
}
