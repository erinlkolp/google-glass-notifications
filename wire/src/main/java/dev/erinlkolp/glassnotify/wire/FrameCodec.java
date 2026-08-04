package dev.erinlkolp.glassnotify.wire;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Length-prefixed framing over a raw byte stream.
 *
 * <pre>
 * uint32  length    big-endian, counts every byte after this field
 * uint8   version
 * uint8   type
 * ...     body
 * </pre>
 *
 * DataInputStream's readInt and readFully already loop over short reads, which
 * is what makes this correct against a socket that fragments arbitrarily.
 */
public final class FrameCodec {

    /** version + type. */
    private static final int HEADER_AFTER_LENGTH = 2;

    private FrameCodec() {
    }

    public static void write(OutputStream out, int type, byte[] body) throws IOException {
        int length = body.length + HEADER_AFTER_LENGTH;
        if (length > Protocol.MAX_FRAME_BYTES) {
            throw new ProtocolException("frame of " + length
                    + " bytes exceeds the " + Protocol.MAX_FRAME_BYTES + " byte limit");
        }
        DataOutputStream data = new DataOutputStream(out);
        data.writeInt(length);
        data.writeByte(Protocol.VERSION);
        data.writeByte(type);
        data.write(body);
        data.flush();
    }

    public static Frame read(InputStream in) throws IOException {
        DataInputStream data = new DataInputStream(in);

        int length = data.readInt();
        // Validate before allocating: a corrupt length must not become an OOM.
        if (length < HEADER_AFTER_LENGTH || length > Protocol.MAX_FRAME_BYTES) {
            throw new ProtocolException("declared frame length " + length + " is out of range");
        }

        int version = data.readUnsignedByte();
        int type = data.readUnsignedByte();

        byte[] body = new byte[length - HEADER_AFTER_LENGTH];
        data.readFully(body);

        return new Frame(version, type, body);
    }
}
