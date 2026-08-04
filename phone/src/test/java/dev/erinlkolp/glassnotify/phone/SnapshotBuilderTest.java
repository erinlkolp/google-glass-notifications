package dev.erinlkolp.glassnotify.phone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import dev.erinlkolp.glassnotify.wire.NotificationItem;
import dev.erinlkolp.glassnotify.wire.Protocol;
import dev.erinlkolp.glassnotify.wire.Snapshot;
import dev.erinlkolp.glassnotify.wire.Tier;

public class SnapshotBuilderTest {

    private static SourceNotification source(String key, String pkg, long postedAt) {
        return new SourceNotification(key, pkg, "Signal", "Jordan Reyes", "hello", postedAt, false);
    }

    private static Map<String, Tier> allow(String pkg, Tier tier) {
        Map<String, Tier> map = new HashMap<String, Tier>();
        map.put(pkg, tier);
        return map;
    }

    @Test
    public void dropsAnythingNotOnTheAllowlist() {
        // Filtering here is the biggest battery lever in the system: screened
        // notifications never reach the radio at all. Spec section 8.
        List<SourceNotification> sources = Arrays.asList(
                source("a", "org.thoughtcrime.securesms", 100L),
                source("b", "com.example.spam", 200L));

        Snapshot snapshot = SnapshotBuilder.build(1L, sources,
                allow("org.thoughtcrime.securesms", Tier.INTERRUPT));

        assertEquals(1, snapshot.items.size());
        assertEquals("a", snapshot.items.get(0).key);
    }

    @Test
    public void appliesTheTierFromTheAllowlist() {
        Snapshot snapshot = SnapshotBuilder.build(1L,
                Arrays.asList(source("a", "pkg", 100L)), allow("pkg", Tier.INTERRUPT));

        assertEquals(Tier.INTERRUPT, snapshot.items.get(0).tier);
    }

    @Test
    public void ordersNewestFirst() {
        List<SourceNotification> sources = Arrays.asList(
                source("old", "pkg", 100L),
                source("new", "pkg", 300L),
                source("mid", "pkg", 200L));

        Snapshot snapshot = SnapshotBuilder.build(1L, sources, allow("pkg", Tier.QUEUE));

        assertEquals("new", snapshot.items.get(0).key);
        assertEquals("mid", snapshot.items.get(1).key);
        assertEquals("old", snapshot.items.get(2).key);
    }

    @Test
    public void capsAtTheProtocolLimitKeepingTheNewest() {
        List<SourceNotification> sources = new ArrayList<SourceNotification>();
        for (int i = 0; i < Protocol.MAX_ITEMS + 10; i++) {
            sources.add(source("k" + i, "pkg", i));
        }

        Snapshot snapshot = SnapshotBuilder.build(1L, sources, allow("pkg", Tier.QUEUE));

        assertEquals(Protocol.MAX_ITEMS, snapshot.items.size());
        assertEquals("the newest must survive the cap",
                "k" + (Protocol.MAX_ITEMS + 9), snapshot.items.get(0).key);
    }

    @Test
    public void truncatesBodyText() {
        StringBuilder long_ = new StringBuilder();
        for (int i = 0; i < Protocol.MAX_TEXT_CHARS * 2; i++) {
            long_.append('x');
        }
        SourceNotification s = new SourceNotification("a", "pkg", "Signal", "title",
                long_.toString(), 100L, false);

        Snapshot snapshot = SnapshotBuilder.build(1L, Arrays.asList(s), allow("pkg", Tier.QUEUE));

        assertEquals(Protocol.MAX_TEXT_CHARS, snapshot.items.get(0).text.length());
    }

    @Test
    public void truncatesTitle() {
        StringBuilder long_ = new StringBuilder();
        for (int i = 0; i < Protocol.MAX_TITLE_CHARS * 2; i++) {
            long_.append('y');
        }
        SourceNotification s = new SourceNotification("a", "pkg", "Signal", long_.toString(),
                "body", 100L, false);

        Snapshot snapshot = SnapshotBuilder.build(1L, Arrays.asList(s), allow("pkg", Tier.QUEUE));

        assertEquals(Protocol.MAX_TITLE_CHARS, snapshot.items.get(0).title.length());
    }

    @Test
    public void leavesShortTextAlone() {
        Snapshot snapshot = SnapshotBuilder.build(1L,
                Arrays.asList(source("a", "pkg", 100L)), allow("pkg", Tier.QUEUE));

        assertEquals("hello", snapshot.items.get(0).text);
    }

    @Test
    public void dropsOngoingNotifications() {
        // Ongoing notifications are persistent status - a music player, a
        // navigation session, another app's foreground service. They are not
        // events, and they would permanently occupy queue slots.
        SourceNotification ongoing = new SourceNotification("a", "pkg", "Player", "Now playing",
                "a song", 100L, true);

        Snapshot snapshot = SnapshotBuilder.build(1L, Arrays.asList(ongoing),
                allow("pkg", Tier.QUEUE));

        assertTrue(snapshot.items.isEmpty());
    }

    @Test
    public void toleratesNullTitleAndText() {
        // Plenty of real notifications have no title, or no text, or neither.
        // NotificationItem forbids nulls, so the builder must normalise.
        SourceNotification s = new SourceNotification("a", "pkg", "Signal", null, null, 100L, false);

        NotificationItem item = SnapshotBuilder.build(1L, Arrays.asList(s),
                allow("pkg", Tier.QUEUE)).items.get(0);

        assertEquals("", item.title);
        assertEquals("", item.text);
    }

    @Test
    public void carriesTheSnapshotIdThrough() {
        assertEquals(99L, SnapshotBuilder.build(99L,
                new ArrayList<SourceNotification>(), new HashMap<String, Tier>()).snapshotId);
    }

    @Test
    public void anEmptyAllowlistProducesAnEmptySnapshot() {
        Snapshot snapshot = SnapshotBuilder.build(1L,
                Arrays.asList(source("a", "pkg", 100L)), new HashMap<String, Tier>());

        assertTrue(snapshot.items.isEmpty());
    }
}
