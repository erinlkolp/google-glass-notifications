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

    @Test
    public void aFullSnapshotFitsComfortablyInOneFrame() throws IOException {
        // Spec section 6 claims roughly 3KB. If that assumption ever breaks,
        // the snapshot-per-change design needs revisiting, so assert it.
        List<NotificationItem> items = new ArrayList<NotificationItem>();
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < Protocol.MAX_TEXT_CHARS; i++) {
            text.append('x');
        }
        for (int i = 0; i < Protocol.MAX_ITEMS; i++) {
            items.add(new NotificationItem("key-" + i, "Signal", "Jordan Reyes",
                    text.toString(), 1785870000000L, Tier.QUEUE));
        }

        int size = SnapshotCodec.encode(new Snapshot(1L, items)).length;

        assertTrue("worst-case snapshot was " + size + " bytes", size < 8 * 1024);
        assertTrue(size < Protocol.MAX_FRAME_BYTES);
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
