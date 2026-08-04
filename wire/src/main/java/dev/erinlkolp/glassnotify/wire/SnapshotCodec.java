package dev.erinlkolp.glassnotify.wire;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Encodes and decodes the SNAPSHOT frame body.
 *
 * <pre>
 * int64   snapshotId
 * int16   itemCount
 * repeated itemCount times:
 *   utf   key
 *   utf   appLabel
 *   utf   title
 *   utf   text
 *   int64 postedAt
 *   uint8 tierCode
 * </pre>
 */
public final class SnapshotCodec {

    private SnapshotCodec() {
    }

    public static byte[] encode(Snapshot snapshot) throws IOException {
        if (snapshot.items.size() > Protocol.MAX_ITEMS) {
            throw new ProtocolException("snapshot holds " + snapshot.items.size()
                    + " items, over the cap of " + Protocol.MAX_ITEMS);
        }

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);

        out.writeLong(snapshot.snapshotId);
        out.writeShort(snapshot.items.size());
        for (NotificationItem item : snapshot.items) {
            out.writeUTF(item.key);
            out.writeUTF(item.appLabel);
            out.writeUTF(item.title);
            out.writeUTF(item.text);
            out.writeLong(item.postedAt);
            out.writeByte(item.tier.code);
        }

        out.flush();
        return bytes.toByteArray();
    }

    /**
     * Encodes, dropping the oldest items until the result fits in one frame.
     *
     * The per-field caps in {@link Protocol} should already make this
     * impossible, but "should" is not a guarantee against input the phone does
     * not control. If an oversized snapshot ever did reach the writer, the
     * failure mode without this is unrecoverable rather than merely lossy:
     * FrameCodec.write throws, the sender drops the link, reconnects, and the
     * handshake re-sends the identical snapshot - a reconnect loop that never
     * self-heals and presents as "phone connected, Glass blank". Losing the
     * tail of the queue is strictly better than that.
     *
     * Items are newest-first, so the oldest is the last one - which is also
     * the one the wearer is least likely to be looking for.
     */
    public static byte[] encodeWithinFrame(Snapshot snapshot) throws IOException {
        byte[] encoded = encode(snapshot);
        if (encoded.length <= FrameCodec.MAX_BODY_BYTES) {
            return encoded;
        }

        List<NotificationItem> items =
                new ArrayList<NotificationItem>(snapshot.items);
        while (!items.isEmpty()) {
            items.remove(items.size() - 1);
            encoded = encode(new Snapshot(snapshot.snapshotId, items));
            if (encoded.length <= FrameCodec.MAX_BODY_BYTES) {
                return encoded;
            }
        }
        // An empty snapshot is a fixed ten bytes, so the loop above always
        // returns before falling through. Kept so the compiler agrees.
        return encoded;
    }

    public static Snapshot decode(byte[] body) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(body));

        long snapshotId = in.readLong();
        int count = in.readShort();
        if (count < 0 || count > Protocol.MAX_ITEMS) {
            throw new ProtocolException("snapshot declares " + count
                    + " items, outside 0.." + Protocol.MAX_ITEMS);
        }

        List<NotificationItem> items = new ArrayList<NotificationItem>(count);
        for (int i = 0; i < count; i++) {
            String key = in.readUTF();
            String appLabel = in.readUTF();
            String title = in.readUTF();
            String text = in.readUTF();
            long postedAt = in.readLong();

            int tierCode = in.readUnsignedByte();
            Tier tier = Tier.fromCode(tierCode);
            if (tier == null) {
                throw new ProtocolException("unknown tier code " + tierCode + " at item " + i);
            }

            items.add(new NotificationItem(key, appLabel, title, text, postedAt, tier));
        }

        return new Snapshot(snapshotId, items);
    }
}
