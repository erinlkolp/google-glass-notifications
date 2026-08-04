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
