package dev.erinlkolp.glassnotify.wire;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UTFDataFormatException;
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
     *
     * Every attempt is inside the loop, including the first. Being oversized is
     * not the only way {@link #encode} can fail: a single field over 65535 UTF-8
     * bytes raises {@code UTFDataFormatException} from {@code writeUTF}, and
     * more than {@link Protocol#MAX_ITEMS} items raises {@link
     * ProtocolException}. Both are {@code IOException}s, so an unguarded first
     * attempt would throw one straight at the writer - and that lands in exactly
     * the unrecoverable loop this method exists to prevent, since the reconnect
     * re-sends the identical snapshot. {@code SnapshotBuilder} caps every field
     * and the item count, so none of this is reachable today; it is the second
     * layer, and a second layer with a hole in it is not one.
     */
    public static byte[] encodeWithinFrame(Snapshot snapshot) throws IOException {
        List<NotificationItem> items =
                new ArrayList<NotificationItem>(snapshot.items);
        while (true) {
            try {
                byte[] encoded = encode(new Snapshot(snapshot.snapshotId, items));
                if (encoded.length <= FrameCodec.MAX_BODY_BYTES) {
                    return encoded;
                }
            } catch (ProtocolException tooMany) {
                // Over MAX_ITEMS. Dropping the tail is the same remedy.
            } catch (UTFDataFormatException tooLong) {
                // One field past writeUTF's own 65535-byte ceiling.
            }
            if (items.isEmpty()) {
                // An empty snapshot is a fixed ten bytes and has no strings to
                // overflow, so it always encodes and always fits: this is
                // unreachable. Kept so the loop provably terminates rather
                // than indexing off the end of an empty list.
                throw new ProtocolException(
                        "snapshot " + snapshot.snapshotId + " will not encode even when empty");
            }
            items.remove(items.size() - 1);
        }
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
