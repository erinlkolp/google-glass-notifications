package dev.erinlkolp.glassnotify.wire;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class SnapshotCodecTest {

    private static NotificationItem item(String key, Tier tier) {
        return new NotificationItem(key, "Signal", "Jordan Reyes",
                "are you still good for 7pm?", 1785870000000L, tier);
    }

    @Test
    public void roundTripsAPopulatedSnapshot() throws IOException {
        Snapshot original = new Snapshot(42L,
                Arrays.asList(item("a", Tier.INTERRUPT), item("b", Tier.QUEUE)));

        Snapshot decoded = SnapshotCodec.decode(SnapshotCodec.encode(original));

        assertEquals(42L, decoded.snapshotId);
        assertEquals(2, decoded.items.size());
        assertEquals(item("a", Tier.INTERRUPT), decoded.items.get(0));
        assertEquals(item("b", Tier.QUEUE), decoded.items.get(1));
    }

    @Test
    public void roundTripsAnEmptySnapshot() throws IOException {
        Snapshot decoded = SnapshotCodec.decode(
                SnapshotCodec.encode(new Snapshot(1L, new ArrayList<NotificationItem>())));

        assertEquals(0, decoded.items.size());
    }

    @Test
    public void preservesOrder() throws IOException {
        List<NotificationItem> items = new ArrayList<NotificationItem>();
        for (int i = 0; i < Protocol.MAX_ITEMS; i++) {
            items.add(item("key-" + i, Tier.QUEUE));
        }

        Snapshot decoded = SnapshotCodec.decode(SnapshotCodec.encode(new Snapshot(1L, items)));

        for (int i = 0; i < Protocol.MAX_ITEMS; i++) {
            assertEquals("key-" + i, decoded.items.get(i).key);
        }
    }

    /** A string of exactly {@code length} copies of {@code c}. */
    private static String repeat(char c, int length) {
        StringBuilder builder = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            builder.append(c);
        }
        return builder.toString();
    }

    /** MAX_ITEMS items with every single field at its cap. */
    private static Snapshot worstCase(char fill) {
        List<NotificationItem> items = new ArrayList<NotificationItem>();
        for (int i = 0; i < Protocol.MAX_ITEMS; i++) {
            items.add(new NotificationItem(
                    repeat(fill, Protocol.MAX_KEY_CHARS),
                    repeat(fill, Protocol.MAX_APP_LABEL_CHARS),
                    repeat(fill, Protocol.MAX_TITLE_CHARS),
                    repeat(fill, Protocol.MAX_TEXT_CHARS),
                    1785870000000L, Tier.QUEUE));
        }
        return new Snapshot(1L, items);
    }

    @Test
    public void aFullSnapshotFitsComfortablyInOneFrame() throws IOException {
        // Every field at its cap, not just the body: key and appLabel come
        // from app-controlled strings, so a test that pins them at "key-3" and
        // "Signal" proves nothing about the real ceiling. If this assumption
        // ever breaks, the snapshot-per-change design needs revisiting.
        int size = SnapshotCodec.encode(worstCase('x')).length;

        assertTrue("worst-case snapshot was " + size + " bytes", size < 16 * 1024);
        assertTrue(size <= FrameCodec.MAX_BODY_BYTES);
    }

    @Test
    public void aFullSnapshotOfMultiByteTextStillFitsInOneFrame() throws IOException {
        // writeUTF spends three bytes on a BMP character outside Latin-1, so
        // the caps are measured in chars but the frame limit is in bytes. A
        // queue full of CJK is the genuine worst case.
        // Escaped rather than literal so the test does not depend on the
        // compiler's source encoding.
        int size = SnapshotCodec.encode(worstCase('\u4e2d')).length;

        assertTrue("multi-byte worst case was " + size + " bytes",
                size <= FrameCodec.MAX_BODY_BYTES);
    }

    @Test
    public void encodeWithinFrameLeavesAFittingSnapshotAlone() throws IOException {
        Snapshot original = worstCase('x');

        Snapshot decoded = SnapshotCodec.decode(SnapshotCodec.encodeWithinFrame(original));

        assertEquals(Protocol.MAX_ITEMS, decoded.items.size());
    }

    @Test
    public void encodeWithinFrameDropsTheOldestItemsUntilItFits() throws IOException {
        // Bypasses the phone's per-field caps deliberately: this is the guard
        // that stops an oversized snapshot becoming a permanent reconnect loop.
        String huge = repeat('x', 40_000);
        List<NotificationItem> items = new ArrayList<NotificationItem>();
        for (int i = 0; i < 4; i++) {
            items.add(new NotificationItem("key-" + i, "Signal", "Jordan Reyes",
                    huge, 1785870000000L, Tier.QUEUE));
        }

        byte[] encoded = SnapshotCodec.encodeWithinFrame(new Snapshot(7L, items));

        assertTrue(encoded.length <= FrameCodec.MAX_BODY_BYTES);
        Snapshot decoded = SnapshotCodec.decode(encoded);
        assertEquals(7L, decoded.snapshotId);
        // Newest survive, oldest go: only "key-0" fits at 40KB apiece.
        assertEquals(1, decoded.items.size());
        assertEquals("key-0", decoded.items.get(0).key);
    }

    @Test
    public void encodeWithinFrameSurvivesASingleUnsendableItem() throws IOException {
        // Even one item can be too big. Dropping to empty beats throwing:
        // Glass showing "Nothing waiting" is recoverable, a wedged link is not.
        List<NotificationItem> items = new ArrayList<NotificationItem>();
        // writeUTF itself caps a single string at 65535 bytes, so it takes two
        // large fields to push one item past the frame limit on its own.
        items.add(new NotificationItem("k", "Signal", repeat('x', 1_000),
                repeat('x', 65_000), 1785870000000L, Tier.QUEUE));

        byte[] encoded = SnapshotCodec.encodeWithinFrame(new Snapshot(1L, items));

        assertTrue(encoded.length <= FrameCodec.MAX_BODY_BYTES);
        assertEquals(0, SnapshotCodec.decode(encoded).items.size());
    }

    @Test
    public void encodeWithinFrameDegradesRatherThanThrowingOverTheItemCap() throws IOException {
        // encode() rejects more than MAX_ITEMS with a ProtocolException. That
        // used to escape encodeWithinFrame, because the first encode() call sat
        // outside the degradation loop - an IOException at the writer, which
        // drops the link, reconnects, and re-sends the identical snapshot.
        // Exactly the unrecoverable loop this method exists to prevent.
        List<NotificationItem> items = new ArrayList<NotificationItem>();
        for (int i = 0; i < Protocol.MAX_ITEMS + 5; i++) {
            items.add(item("key-" + i, Tier.QUEUE));
        }

        Snapshot decoded = SnapshotCodec.decode(
                SnapshotCodec.encodeWithinFrame(new Snapshot(9L, items)));

        assertEquals(9L, decoded.snapshotId);
        assertEquals(Protocol.MAX_ITEMS, decoded.items.size());
        // Newest survive, oldest go - the same tail-first rule as the size path.
        assertEquals("key-0", decoded.items.get(0).key);
        assertEquals("key-" + (Protocol.MAX_ITEMS - 1),
                decoded.items.get(Protocol.MAX_ITEMS - 1).key);
    }

    @Test
    public void encodeWithinFrameDegradesRatherThanThrowingOnAnUnencodableField()
            throws IOException {
        // writeUTF has its own 65535-byte ceiling and raises
        // UTFDataFormatException, not an oversized byte[], so the length check
        // alone never sees this one. It has to be caught, not measured.
        List<NotificationItem> items = new ArrayList<NotificationItem>();
        items.add(item("keep-me", Tier.QUEUE));
        items.add(new NotificationItem("too-big", "Signal", "Jordan Reyes",
                repeat('x', 70_000), 1785870000000L, Tier.QUEUE));

        byte[] encoded = SnapshotCodec.encodeWithinFrame(new Snapshot(11L, items));

        assertTrue(encoded.length <= FrameCodec.MAX_BODY_BYTES);
        Snapshot decoded = SnapshotCodec.decode(encoded);
        assertEquals(1, decoded.items.size());
        assertEquals("keep-me", decoded.items.get(0).key);
    }

    @Test
    public void encodeWithinFrameDropsToEmptyWhenTheNewestItemIsUnencodable()
            throws IOException {
        // Nothing is salvageable here: the offending item is the newest, so
        // degradation runs all the way to empty. Glass showing "Nothing
        // waiting" is recoverable; a wedged link is not.
        List<NotificationItem> items = new ArrayList<NotificationItem>();
        items.add(new NotificationItem("too-big", "Signal", repeat('x', 70_000),
                "text", 1785870000000L, Tier.QUEUE));

        byte[] encoded = SnapshotCodec.encodeWithinFrame(new Snapshot(12L, items));

        assertEquals(0, SnapshotCodec.decode(encoded).items.size());
    }

    @Test
    public void rejectsAnItemCountOverTheCap() throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(raw);
        d.writeLong(1L);
        d.writeShort(Protocol.MAX_ITEMS + 1);

        try {
            SnapshotCodec.decode(raw.toByteArray());
            fail("expected ProtocolException");
        } catch (ProtocolException expected) {
        }
    }

    @Test
    public void rejectsANegativeItemCount() throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(raw);
        d.writeLong(1L);
        d.writeShort(-1);

        try {
            SnapshotCodec.decode(raw.toByteArray());
            fail("expected ProtocolException");
        } catch (ProtocolException expected) {
        }
    }

    @Test
    public void rejectsAnUnknownTierCode() throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(raw);
        d.writeLong(1L);
        d.writeShort(1);
        d.writeUTF("k");
        d.writeUTF("Signal");
        d.writeUTF("title");
        d.writeUTF("text");
        d.writeLong(1L);
        d.writeByte(77); // not a Tier

        try {
            SnapshotCodec.decode(raw.toByteArray());
            fail("expected ProtocolException");
        } catch (ProtocolException expected) {
        }
    }

    @Test
    public void refusesToEncodeMoreThanTheCap() {
        List<NotificationItem> items = new ArrayList<NotificationItem>();
        for (int i = 0; i < Protocol.MAX_ITEMS + 1; i++) {
            items.add(item("key-" + i, Tier.QUEUE));
        }

        try {
            SnapshotCodec.encode(new Snapshot(1L, items));
            fail("expected ProtocolException");
        } catch (IOException expected) {
            // The phone caps before building; this is the belt-and-braces check.
        }
    }
}
