package dev.erinlkolp.glassnotify.wire;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;

import org.junit.Test;

public class FrameCodecTest {

    private static byte[] framed(int type, byte[] body) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        FrameCodec.write(out, type, body);
        return out.toByteArray();
    }

    @Test
    public void roundTripsASimpleFrame() throws IOException {
        byte[] body = {1, 2, 3, 4, 5};
        Frame frame = FrameCodec.read(new ByteArrayInputStream(framed(MessageType.SNAPSHOT, body)));

        assertEquals(Protocol.VERSION, frame.version);
        assertEquals(MessageType.SNAPSHOT, frame.type);
        assertArrayEquals(body, frame.body);
    }

    @Test
    public void roundTripsAnEmptyBody() throws IOException {
        // PING has no payload.
        Frame frame = FrameCodec.read(new ByteArrayInputStream(framed(MessageType.PING, new byte[0])));

        assertEquals(MessageType.PING, frame.type);
        assertEquals(0, frame.body.length);
    }

    @Test
    public void headerLayoutIsExactlySpecified() throws IOException {
        // Guards the on-wire layout against accidental change: a 4-byte
        // big-endian length covering everything after it, then version, then type.
        byte[] encoded = framed(MessageType.HELLO, new byte[] {(byte) 0xAB});

        assertEquals(7, encoded.length);
        assertEquals(0, encoded[0]);
        assertEquals(0, encoded[1]);
        assertEquals(0, encoded[2]);
        assertEquals(3, encoded[3]); // version + type + 1 body byte
        assertEquals(Protocol.VERSION, encoded[4]);
        assertEquals(MessageType.HELLO, encoded[5]);
        assertEquals((byte) 0xAB, encoded[6]);
    }

    @Test
    public void reassemblesAFrameSplitAtEveryPossibleBoundary() throws IOException {
        byte[] body = new byte[64];
        for (int i = 0; i < body.length; i++) {
            body[i] = (byte) i;
        }
        byte[] encoded = framed(MessageType.SNAPSHOT, body);

        // A one-byte-at-a-time stream is the worst case; every larger chunk
        // size is a weaker version of the same test, so sweep them all.
        for (int chunk = 1; chunk <= encoded.length; chunk++) {
            Frame frame = FrameCodec.read(new ChunkedInputStream(encoded, chunk));
            assertArrayEquals("chunk size " + chunk, body, frame.body);
        }
    }

    @Test
    public void readsTwoFramesArrivingInOneBuffer() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        FrameCodec.write(out, MessageType.HELLO, new byte[] {9});
        FrameCodec.write(out, MessageType.PING, new byte[0]);

        ByteArrayInputStream in = new ByteArrayInputStream(out.toByteArray());
        assertEquals(MessageType.HELLO, FrameCodec.read(in).type);
        assertEquals(MessageType.PING, FrameCodec.read(in).type);
    }

    @Test
    public void readsFramesSplitAcrossReadsAndConcatenated() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        FrameCodec.write(out, MessageType.SNAPSHOT, new byte[] {1, 2, 3});
        FrameCodec.write(out, MessageType.SNAPSHOT, new byte[] {4, 5, 6});

        // Three bytes at a time straddles both frame boundaries.
        ChunkedInputStream in = new ChunkedInputStream(out.toByteArray(), 3);
        assertArrayEquals(new byte[] {1, 2, 3}, FrameCodec.read(in).body);
        assertArrayEquals(new byte[] {4, 5, 6}, FrameCodec.read(in).body);
    }

    @Test
    public void rejectsAnAbsurdLengthWithoutAllocating() throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(raw);
        d.writeInt(Integer.MAX_VALUE); // a corrupted length claiming 2GB
        d.writeByte(Protocol.VERSION);
        d.writeByte(MessageType.SNAPSHOT);

        try {
            FrameCodec.read(new ByteArrayInputStream(raw.toByteArray()));
            fail("expected ProtocolException");
        } catch (ProtocolException expected) {
            // Must fail on the length check, never by trying to allocate the buffer.
        }
    }

    @Test
    public void rejectsALengthTooSmallToHoldTheHeader() throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        new DataOutputStream(raw).writeInt(1); // needs at least 2: version + type

        try {
            FrameCodec.read(new ByteArrayInputStream(raw.toByteArray()));
            fail("expected ProtocolException");
        } catch (ProtocolException expected) {
        }
    }

    @Test
    public void rejectsANegativeLength() throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        new DataOutputStream(raw).writeInt(-1);

        try {
            FrameCodec.read(new ByteArrayInputStream(raw.toByteArray()));
            fail("expected ProtocolException");
        } catch (ProtocolException expected) {
        }
    }

    @Test
    public void throwsEofWhenTheStreamEndsMidHeader() {
        try {
            FrameCodec.read(new ByteArrayInputStream(new byte[] {0, 0}));
            fail("expected EOFException");
        } catch (IOException expected) {
            // The peer vanished. Callers close and reconnect either way.
        }
    }

    @Test
    public void throwsEofWhenTheStreamEndsMidBody() throws IOException {
        byte[] encoded = framed(MessageType.SNAPSHOT, new byte[] {1, 2, 3, 4});
        byte[] truncated = new byte[encoded.length - 2];
        System.arraycopy(encoded, 0, truncated, 0, truncated.length);

        try {
            FrameCodec.read(new ByteArrayInputStream(truncated));
            fail("expected EOFException");
        } catch (EOFException expected) {
        }
    }

    @Test
    public void refusesToWriteAnOversizedBody() {
        try {
            FrameCodec.write(new ByteArrayOutputStream(), MessageType.SNAPSHOT,
                    new byte[Protocol.MAX_FRAME_BYTES]);
            fail("expected ProtocolException");
        } catch (IOException expected) {
            // Catching it at the sender gives a far better diagnostic than
            // letting the receiver reject it after a round trip.
        }
    }

    @Test
    public void preservesAVersionItDoesNotRecognise() throws IOException {
        // The reader must surface a foreign version rather than rejecting it,
        // so the service layer can show "phone app out of date" instead of
        // a generic stream error.
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(raw);
        d.writeInt(2);
        d.writeByte(99);
        d.writeByte(MessageType.PING);

        assertEquals(99, FrameCodec.read(new ByteArrayInputStream(raw.toByteArray())).version);
    }
}
