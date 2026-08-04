## Task 2: Framing

RFCOMM is a byte stream with no message boundaries. This is where "works on the bench, corrupts in the field" bugs live, so the tests here are deliberately adversarial. (§7.1)

**Files:**
- Create: `wire/src/main/java/dev/erinlkolp/glassnotify/wire/Frame.java`
- Create: `wire/src/main/java/dev/erinlkolp/glassnotify/wire/FrameCodec.java`
- Test: `wire/src/test/java/dev/erinlkolp/glassnotify/wire/ChunkedInputStream.java`
- Test: `wire/src/test/java/dev/erinlkolp/glassnotify/wire/FrameCodecTest.java`

**Interfaces:**
- Consumes: `Protocol.VERSION`, `Protocol.MAX_FRAME_BYTES`, `MessageType.*`, `ProtocolException`.
- Produces: `Frame(int version, int type, byte[] body)` with public final fields `version`, `type`, `body`; `FrameCodec.write(OutputStream out, int type, byte[] body):void`; `FrameCodec.read(InputStream in):Frame`.

- [ ] **Step 1: Write the test helper**

`ChunkedInputStream.java` — an `InputStream` that hands out at most `n` bytes per `read()` call, so tests can prove the reader reassembles frames correctly no matter how the transport fragments them.

```java
package dev.erinlkolp.glassnotify.wire;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Delivers at most maxChunk bytes per read() call. Real RFCOMM sockets
 * fragment arbitrarily; this makes that behaviour reproducible in a test.
 */
final class ChunkedInputStream extends InputStream {

    private final ByteArrayInputStream delegate;
    private final int maxChunk;

    ChunkedInputStream(byte[] data, int maxChunk) {
        if (maxChunk < 1) {
            throw new IllegalArgumentException("maxChunk must be >= 1");
        }
        this.delegate = new ByteArrayInputStream(data);
        this.maxChunk = maxChunk;
    }

    @Override
    public int read() throws IOException {
        return delegate.read();
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        return delegate.read(b, off, Math.min(len, maxChunk));
    }
}
```

- [ ] **Step 2: Write the failing tests**

```java
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
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./gradlew :wire:test --tests '*FrameCodecTest*'`
Expected: FAIL — `Frame` and `FrameCodec` do not exist.

- [ ] **Step 4: Write `Frame.java`**

```java
package dev.erinlkolp.glassnotify.wire;

/** One decoded frame: header fields plus the still-encoded body. */
public final class Frame {

    /** Protocol version claimed by the sender. May not match Protocol.VERSION. */
    public final int version;

    /** One of the MessageType constants. May be unrecognised. */
    public final int type;

    /** Type-specific payload, decoded by HelloCodec or SnapshotCodec. */
    public final byte[] body;

    public Frame(int version, int type, byte[] body) {
        if (body == null) {
            throw new NullPointerException("body");
        }
        this.version = version;
        this.type = type;
        this.body = body;
    }
}
```

- [ ] **Step 5: Write `FrameCodec.java`**

```java
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
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew :wire:test`
Expected: PASS, 24 tests total.

- [ ] **Step 7: Commit**

```bash
git add wire/
git commit -m "feat(wire): add length-prefixed framing

RFCOMM has no message boundaries, so framing is where field corruption
comes from. The tests sweep every possible split point of a frame,
concatenated frames, truncation mid-header and mid-body, and corrupt
lengths including 2GB and negative.

Length is validated before the body buffer is allocated, so a garbage
length field fails cleanly instead of becoming an OutOfMemoryError. An
unrecognised version is passed through rather than rejected, so the
service layer can report 'phone app out of date' specifically."
```

---

