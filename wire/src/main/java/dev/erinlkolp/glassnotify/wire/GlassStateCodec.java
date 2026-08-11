package dev.erinlkolp.glassnotify.wire;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Encodes and decodes the GLASS_STATE frame body.
 *
 * <pre>
 * uint8   batteryLevel   0-100
 * uint8   onPower        0 or 1
 * </pre>
 */
public final class GlassStateCodec {

    private GlassStateCodec() {
    }

    public static byte[] encode(GlassState state) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeByte(state.batteryLevel);
        out.writeBoolean(state.onPower);
        out.flush();
        return bytes.toByteArray();
    }

    public static GlassState decode(byte[] body) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(body));
        int level = in.readUnsignedByte();
        boolean onPower = in.readBoolean();

        // readUnsignedByte cannot go negative, so only the ceiling needs a
        // check. Raised as ProtocolException rather than letting the
        // GlassState constructor throw IllegalArgumentException: the reader
        // treats IOException as "this session is over" and would let an
        // unchecked exception kill its thread with no handler. Same reasoning
        // as the unknown-tier guard in SnapshotCodec.
        if (level > 100) {
            throw new ProtocolException("battery level " + level + " is outside 0..100");
        }
        return new GlassState(level, onPower);
    }
}
