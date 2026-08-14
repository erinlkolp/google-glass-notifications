package dev.erinlkolp.glassnotify.glass;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import dev.erinlkolp.glassnotify.wire.NotificationItem;
import dev.erinlkolp.glassnotify.wire.Snapshot;
import dev.erinlkolp.glassnotify.wire.Tier;

public class InterruptPolicyTest {

    private static NotificationItem item(String key, Tier tier, long postedAt) {
        return new NotificationItem(key, "Signal", "Jordan Reyes", "hello", postedAt, tier);
    }

    private static Snapshot snapshot(NotificationItem... items) {
        return new Snapshot(1L, Arrays.asList(items));
    }

    private static Snapshot empty() {
        return new Snapshot(0L, new ArrayList<NotificationItem>());
    }

    @Test
    public void aNewInterruptItemInterrupts() {
        NotificationItem incoming = item("a", Tier.INTERRUPT, 100L);

        assertEquals(incoming, InterruptPolicy.selectInterrupt(empty(), snapshot(incoming)));
    }

    @Test
    public void aNewQueueItemDoesNotInterrupt() {
        assertNull(InterruptPolicy.selectInterrupt(empty(), snapshot(item("a", Tier.QUEUE, 100L))));
    }

    @Test
    public void anItemAlreadySeenDoesNotInterruptAgain() {
        // Every change resends the whole queue, so an unchanged item appears in
        // snapshot after snapshot. Re-interrupting on each would be unusable.
        Snapshot previous = snapshot(item("a", Tier.INTERRUPT, 100L));
        Snapshot next = snapshot(item("a", Tier.INTERRUPT, 100L), item("b", Tier.QUEUE, 200L));

        assertNull(InterruptPolicy.selectInterrupt(previous, next));
    }

    @Test
    public void anUpdatedItemWithTheSameKeyInterruptsAgain() {
        // A messaging app reuses one key and rewrites the text as a thread
        // grows. A newer postedAt is a genuinely new message.
        Snapshot previous = snapshot(item("a", Tier.INTERRUPT, 100L));
        NotificationItem updated = item("a", Tier.INTERRUPT, 500L);

        assertEquals(updated, InterruptPolicy.selectInterrupt(previous, snapshot(updated)));
    }

    @Test
    public void collapsesAStormToTheNewestItem() {
        // Several arrive between snapshots. Show only the newest rather than
        // queueing seven seconds each - that pins the display on and drains
        // the battery. Spec section 10.1.
        NotificationItem newest = item("c", Tier.INTERRUPT, 300L);
        Snapshot next = snapshot(newest, item("b", Tier.INTERRUPT, 200L), item("a", Tier.INTERRUPT, 100L));

        assertEquals(newest, InterruptPolicy.selectInterrupt(empty(), next));
    }

    @Test
    public void picksTheNewestRegardlessOfPositionInTheList() {
        NotificationItem newest = item("b", Tier.INTERRUPT, 900L);
        Snapshot next = snapshot(item("a", Tier.INTERRUPT, 100L), newest);

        assertEquals(newest, InterruptPolicy.selectInterrupt(empty(), next));
    }

    @Test
    public void anEmptySnapshotInterruptsNothing() {
        assertNull(InterruptPolicy.selectInterrupt(snapshot(item("a", Tier.INTERRUPT, 100L)), empty()));
    }

    @Test
    public void removalDoesNotInterrupt() {
        Snapshot previous = snapshot(item("a", Tier.INTERRUPT, 100L), item("b", Tier.INTERRUPT, 200L));
        Snapshot next = snapshot(item("a", Tier.INTERRUPT, 100L));

        assertNull(InterruptPolicy.selectInterrupt(previous, next));
    }

    @Test
    public void theFirstSnapshotAfterReconnectDoesNotReplayTheBacklog() {
        // On reconnect the phone sends everything it holds. Those are not new
        // events - interrupting for each would be a wall of cards.
        List<NotificationItem> backlog = new ArrayList<NotificationItem>();
        for (int i = 0; i < 5; i++) {
            backlog.add(item("k" + i, Tier.INTERRUPT, 100L + i));
        }

        assertNull(InterruptPolicy.selectInterrupt(null, new Snapshot(1L, backlog)));
    }

    @Test
    public void aNewChirpTierItemAlsoInterrupts() {
        NotificationItem incoming = item("a", Tier.INTERRUPT_CHIRP, 100L);

        assertEquals(incoming, InterruptPolicy.selectInterrupt(empty(), snapshot(incoming)));
    }

    @Test
    public void aStormOfMixedTiersCollapsesToTheNewest() {
        NotificationItem older = item("a", Tier.INTERRUPT, 100L);
        NotificationItem newest = item("b", Tier.INTERRUPT_CHIRP, 300L);
        NotificationItem middle = item("c", Tier.INTERRUPT, 200L);

        assertEquals(newest,
                InterruptPolicy.selectInterrupt(empty(), snapshot(older, newest, middle)));
    }

    @Test
    public void queuedItemsStillNeverInterrupt() {
        NotificationItem incoming = item("a", Tier.QUEUE, 100L);

        assertNull(InterruptPolicy.selectInterrupt(empty(), snapshot(incoming)));
    }
}
