## Task 2: The phone's frame reader

**Files:**
- Create: `phone/src/main/java/dev/erinlkolp/glassnotify/phone/LinkReader.java`
- Test: `phone/src/test/java/dev/erinlkolp/glassnotify/phone/LinkReaderTest.java`
- Test: `phone/src/test/java/dev/erinlkolp/glassnotify/phone/ChunkedInputStream.java`

**Interfaces:**
- Consumes: `FrameCodec`, `Frame`, `MessageType`, `Protocol`, `GlassState`, `GlassStateCodec` from Task 1.
- Produces:
  - `interface LinkReader.Listener { void onGlassState(GlassState state); }`
  - `new LinkReader(InputStream in, Listener listener)` — implements `Runnable`.
  - `LinkReader.run()` — reads until the stream ends or anything goes wrong, then returns. Never throws.

`LinkReader` has **no Android imports and does no logging**. Logging would pull in `android.util.Log`, which throws "not mocked" under plain JUnit and would cost us the ability to test this on the JVM. The caller in Task 4 logs when the thread ends.

- [ ] **Step 1: Write the test helper**

Create `phone/src/test/java/dev/erinlkolp/glassnotify/phone/ChunkedInputStream.java`. This mirrors the helper already in `wire`'s test source set, which is package-private there and so not visible here:

```java
package dev.erinlkolp.glassnotify.phone;

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

- [ ] **Step 2: Write the failing test**

Create `phone/src/test/java/dev/erinlkolp/glassnotify/phone/LinkReaderTest.java`:

```java
package dev.erinlkolp.glassnotify.phone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import dev.erinlkolp.glassnotify.wire.FrameCodec;
import dev.erinlkolp.glassnotify.wire.GlassState;
import dev.erinlkolp.glassnotify.wire.GlassStateCodec;
import dev.erinlkolp.glassnotify.wire.MessageType;

public class LinkReaderTest {

    private final List<GlassState> seen = new ArrayList<GlassState>();

    private final LinkReader.Listener collector = new LinkReader.Listener() {
        @Override
        public void onGlassState(GlassState state) {
            seen.add(state);
        }
    };

    private static byte[] framed(GlassState... states) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (GlassState state : states) {
            FrameCodec.write(bytes, MessageType.GLASS_STATE, GlassStateCodec.encode(state));
        }
        return bytes.toByteArray();
    }

    private void readAll(InputStream in) {
        new LinkReader(in, collector).run();
    }

    @Test
    public void deliversASingleState() throws Exception {
        readAll(new ByteArrayInputStream(framed(new GlassState(100, true))));
        assertEquals(1, seen.size());
        assertEquals(new GlassState(100, true), seen.get(0));
    }

    @Test
    public void deliversEveryStateInOrder() throws Exception {
        readAll(new ByteArrayInputStream(
                framed(new GlassState(98, true), new GlassState(99, true),
                        new GlassState(100, true))));
        assertEquals(3, seen.size());
        assertEquals(98, seen.get(0).batteryLevel);
        assertEquals(99, seen.get(1).batteryLevel);
        assertEquals(100, seen.get(2).batteryLevel);
    }

    @Test
    public void survivesAStreamThatFragmentsEveryByte() throws Exception {
        // A real RFCOMM socket splits wherever it likes. One byte per read is
        // the worst case FrameCodec has to cope with.
        readAll(new ChunkedInputStream(
                framed(new GlassState(100, true), new GlassState(42, false)), 1));
        assertEquals(2, seen.size());
        assertEquals(new GlassState(42, false), seen.get(1));
    }

    @Test
    public void ignoresFrameTypesItDoesNotKnow() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        FrameCodec.write(bytes, MessageType.PING, new byte[0]);
        FrameCodec.write(bytes, 99, new byte[] {1, 2, 3});
        FrameCodec.write(bytes, MessageType.GLASS_STATE,
                GlassStateCodec.encode(new GlassState(100, true)));

        readAll(new ByteArrayInputStream(bytes.toByteArray()));

        // Skipped cleanly rather than desynchronising the stream: the state
        // frame behind them still arrives.
        assertEquals(1, seen.size());
        assertEquals(100, seen.get(0).batteryLevel);
    }

    @Test
    public void stopsOnAnUnknownProtocolVersion() throws Exception {
        byte[] good = framed(new GlassState(100, true));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        // A frame whose version byte is not ours, hand-assembled: length 2,
        // version 99, type GLASS_STATE, then a valid frame behind it.
        bytes.write(new byte[] {0, 0, 0, 4, 99, (byte) MessageType.GLASS_STATE, 100, 1});
        bytes.write(good);

        readAll(new ByteArrayInputStream(bytes.toByteArray()));

        // Nothing delivered, and it did not press on into the frame behind.
        assertTrue(seen.isEmpty());
    }

    @Test
    public void returnsQuietlyOnATruncatedStream() throws Exception {
        byte[] full = framed(new GlassState(100, true));
        byte[] cut = new byte[full.length - 1];
        System.arraycopy(full, 0, cut, 0, cut.length);

        // The contract is that run() never throws - the caller's only job is
        // to notice the thread ended.
        readAll(new ByteArrayInputStream(cut));

        assertTrue(seen.isEmpty());
    }

    @Test
    public void returnsQuietlyOnAnEmptyStream() {
        readAll(new ByteArrayInputStream(new byte[0]));
        assertTrue(seen.isEmpty());
    }

    @Test
    public void returnsQuietlyOnACorruptStateBody() throws Exception {
        // Length 3 so the body is one byte too long for a state, and the level
        // byte is out of range. GlassStateCodec raises ProtocolException.
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(new byte[] {0, 0, 0, 4, 1, (byte) MessageType.GLASS_STATE, (byte) 200, 1});

        readAll(new ByteArrayInputStream(bytes.toByteArray()));

        assertTrue(seen.isEmpty());
    }
}
```

- [ ] **Step 3: Run the test and verify it fails**

Run: `./gradlew :phone:testReleaseUnitTest --tests '*LinkReaderTest*'`
Expected: FAIL — compilation error, `LinkReader` does not exist.

- [ ] **Step 4: Write `LinkReader`**

Create `phone/src/main/java/dev/erinlkolp/glassnotify/phone/LinkReader.java`:

```java
package dev.erinlkolp.glassnotify.phone;

import java.io.IOException;
import java.io.InputStream;

import dev.erinlkolp.glassnotify.wire.Frame;
import dev.erinlkolp.glassnotify.wire.FrameCodec;
import dev.erinlkolp.glassnotify.wire.GlassStateCodec;
import dev.erinlkolp.glassnotify.wire.MessageType;
import dev.erinlkolp.glassnotify.wire.Protocol;

/**
 * Reads the reverse channel: Glass to phone.
 *
 * <h3>What this may touch</h3>
 *
 * The input stream, and nothing else. Not the output stream, not
 * {@code wakeLock}, not {@code backoff}, not {@code connectedSocket}.
 * {@link LinkClientService} is built on there being exactly one writer, and
 * this class exists alongside that guarantee rather than inside it. A reply
 * sent from here would break the invariant its whole threading design rests
 * on. If you find yourself wanting to write from this class, add a flag the
 * worker thread reads instead - that is the pattern {@code onSnapshot} already
 * uses.
 *
 * <h3>Failure handling</h3>
 *
 * {@link #run} never throws and never escalates. A dead socket, a malformed
 * body, a version we do not know: log nothing, deliver nothing, return. It does
 * not tear the link down or disturb the backoff, because the PING loop is the
 * only liveness authority on this end and two of them would fight. The worst
 * case is that the reverse channel goes quiet for one session while
 * notifications keep flowing normally, which is the correct trade.
 *
 * <h3>Why there is no logging in here</h3>
 *
 * {@code android.util.Log} throws "not mocked" under plain JUnit, and keeping
 * this class free of Android lets the whole reader be tested on the JVM against
 * a fragmented stream. The caller logs when the thread ends.
 */
public final class LinkReader implements Runnable {

    /** Called on the reader thread. Implementations must not block. */
    public interface Listener {
        void onGlassState(dev.erinlkolp.glassnotify.wire.GlassState state);
    }

    private final InputStream in;
    private final Listener listener;

    public LinkReader(InputStream in, Listener listener) {
        if (in == null) {
            throw new NullPointerException("in");
        }
        if (listener == null) {
            throw new NullPointerException("listener");
        }
        this.in = in;
        this.listener = listener;
    }

    @Override
    public void run() {
        try {
            while (true) {
                Frame frame = FrameCodec.read(in);

                if (frame.version != Protocol.VERSION) {
                    // Unlike Glass, the phone does not surface this. Glass
                    // shows a mismatch because the wearer would otherwise see
                    // a blank prism and assume the app is broken; here the
                    // forward path is still working and there is nothing to
                    // explain.
                    return;
                }

                if (frame.type == MessageType.GLASS_STATE) {
                    listener.onGlassState(GlassStateCodec.decode(frame.body));
                }
                // Anything else is ignored, so a newer Glass can add messages
                // without breaking an older phone.
            }
        } catch (IOException e) {
            // Includes ProtocolException. Either way the session is over.
        }
    }
}
```

- [ ] **Step 5: Run the test and verify it passes**

Run: `./gradlew :phone:testReleaseUnitTest --tests '*LinkReaderTest*'`
Expected: PASS, 8 tests.

- [ ] **Step 6: Commit**

```bash
git add phone/src/main/java/dev/erinlkolp/glassnotify/phone/LinkReader.java \
        phone/src/test/java/dev/erinlkolp/glassnotify/phone/LinkReaderTest.java \
        phone/src/test/java/dev/erinlkolp/glassnotify/phone/ChunkedInputStream.java
git commit -m "feat(phone): read the reverse channel

LinkReader consumes Glass -> phone frames and hands GLASS_STATE to a
listener. Nothing is wired to it yet.

It is forbidden to touch anything but the input stream, and it is silent
by design: no android.util.Log, so the whole reader - including a stream
fragmented one byte at a time - is testable on the JVM.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

