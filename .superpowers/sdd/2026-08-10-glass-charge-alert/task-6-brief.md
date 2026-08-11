## Task 6: Send it

**Files:**
- Create: `glass/src/main/java/dev/erinlkolp/glassnotify/glass/StateWriter.java`
- Test: `glass/src/test/java/dev/erinlkolp/glassnotify/glass/StateWriterTest.java`
- Modify: `glass/src/main/java/dev/erinlkolp/glassnotify/glass/LinkServerService.java`

**Interfaces:**
- Consumes: `BatteryWatcher` (Task 5), `GlassState`, `GlassStateCodec`, `MessageType` (Task 1).
- Produces:
  - `new StateWriter(OutputStream out, GlassState initial)` — `initial` may be null. Implements `Runnable`.
  - `StateWriter.offer(GlassState state)` — never blocks; safe from any thread.
  - `StateWriter.stop()` — makes `run()` return.

Like `LinkReader`, `StateWriter` has no Android imports so it can be tested on the JVM. `LinkServerService` does the logging.

- [ ] **Step 1: Write the failing test**

Create `glass/src/test/java/dev/erinlkolp/glassnotify/glass/StateWriterTest.java`:

```java
package dev.erinlkolp.glassnotify.glass;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import org.junit.Test;

import dev.erinlkolp.glassnotify.wire.Frame;
import dev.erinlkolp.glassnotify.wire.FrameCodec;
import dev.erinlkolp.glassnotify.wire.GlassState;
import dev.erinlkolp.glassnotify.wire.GlassStateCodec;
import dev.erinlkolp.glassnotify.wire.MessageType;

public class StateWriterTest {

    /** One GLASS_STATE frame is 4 length bytes + version + type + 2 body. */
    private static final int FRAME_BYTES = 8;

    private final ByteArrayOutputStream sink = new ByteArrayOutputStream();

    /**
     * Waits for the writer to produce at least the given number of bytes.
     * ByteArrayOutputStream's methods are synchronized, so polling size() from
     * this thread is safe. A deadline rather than a fixed sleep keeps the test
     * fast when it passes and honest when it hangs.
     */
    private void awaitBytes(int count) throws Exception {
        long deadline = System.currentTimeMillis() + 2000L;
        while (sink.size() < count) {
            if (System.currentTimeMillis() > deadline) {
                fail("expected " + count + " bytes, only saw " + sink.size());
            }
            Thread.sleep(5L);
        }
    }

    private java.util.List<GlassState> decodeAll() throws Exception {
        java.util.List<GlassState> states = new java.util.ArrayList<GlassState>();
        ByteArrayInputStream in = new ByteArrayInputStream(sink.toByteArray());
        while (in.available() > 0) {
            Frame frame = FrameCodec.read(in);
            assertEquals(MessageType.GLASS_STATE, frame.type);
            states.add(GlassStateCodec.decode(frame.body));
        }
        return states;
    }

    @Test
    public void sendsTheInitialStateWithoutBeingAsked() throws Exception {
        // A connection must learn Glass's state immediately, not at the next
        // battery movement. This is what makes "alert on reconnect if still
        // charging" work at all.
        StateWriter writer = new StateWriter(sink, new GlassState(100, true));
        Thread thread = new Thread(writer);
        thread.start();

        awaitBytes(FRAME_BYTES);
        writer.stop();
        thread.join(2000L);

        assertFalse(thread.isAlive());
        assertEquals(1, decodeAll().size());
        assertEquals(new GlassState(100, true), decodeAll().get(0));
    }

    @Test
    public void sendsNothingUntilThereIsSomethingToSend() throws Exception {
        // A null initial state means no usable battery broadcast has arrived.
        StateWriter writer = new StateWriter(sink, null);
        Thread thread = new Thread(writer);
        thread.start();

        Thread.sleep(100L);
        assertEquals(0, sink.size());

        writer.offer(new GlassState(64, false));
        awaitBytes(FRAME_BYTES);
        writer.stop();
        thread.join(2000L);

        assertEquals(1, decodeAll().size());
        assertEquals(new GlassState(64, false), decodeAll().get(0));
    }

    @Test
    public void sendsEachOfferedState() throws Exception {
        StateWriter writer = new StateWriter(sink, null);
        Thread thread = new Thread(writer);
        thread.start();

        writer.offer(new GlassState(98, true));
        awaitBytes(FRAME_BYTES);
        writer.offer(new GlassState(100, true));
        awaitBytes(FRAME_BYTES * 2);

        writer.stop();
        thread.join(2000L);

        assertEquals(2, decodeAll().size());
        assertEquals(98, decodeAll().get(0).batteryLevel);
        assertEquals(100, decodeAll().get(1).batteryLevel);
    }

    @Test
    public void stopEndsTheThreadEvenWithNothingPending() throws Exception {
        StateWriter writer = new StateWriter(sink, null);
        Thread thread = new Thread(writer);
        thread.start();

        writer.stop();
        thread.join(2000L);

        assertFalse(thread.isAlive());
        assertEquals(0, sink.size());
    }

    @Test
    public void offerAfterStopIsHarmless() throws Exception {
        StateWriter writer = new StateWriter(sink, null);
        Thread thread = new Thread(writer);
        thread.start();
        writer.stop();
        thread.join(2000L);

        // The watcher fires on the main thread and can land after a session
        // has already been torn down. It must not throw.
        writer.offer(new GlassState(100, true));

        assertEquals(0, sink.size());
    }

    @Test
    public void aDeadStreamEndsTheThreadRatherThanThrowing() throws Exception {
        java.io.OutputStream broken = new java.io.OutputStream() {
            @Override
            public void write(int b) throws java.io.IOException {
                throw new java.io.IOException("socket closed");
            }
        };

        StateWriter writer = new StateWriter(broken, new GlassState(100, true));
        Thread thread = new Thread(writer);
        thread.start();
        thread.join(2000L);

        // run() must swallow it. An uncaught IOException on this thread would
        // be a crash with no handler.
        assertFalse(thread.isAlive());
    }
}
```

- [ ] **Step 2: Run the test and verify it fails**

Run: `./gradlew :glass:testReleaseUnitTest --tests '*StateWriterTest*'`
Expected: FAIL — compilation error, `StateWriter` does not exist.

- [ ] **Step 3: Write `StateWriter`**

Create `glass/src/main/java/dev/erinlkolp/glassnotify/glass/StateWriter.java`:

```java
package dev.erinlkolp.glassnotify.glass;

import java.io.IOException;
import java.io.OutputStream;

import dev.erinlkolp.glassnotify.wire.FrameCodec;
import dev.erinlkolp.glassnotify.wire.GlassState;
import dev.erinlkolp.glassnotify.wire.GlassStateCodec;
import dev.erinlkolp.glassnotify.wire.MessageType;

/**
 * Writes Glass's battery state to the phone, on its own thread.
 *
 * <h3>Why a separate thread</h3>
 *
 * The accept thread spends its life blocked in FrameCodec.read, so it cannot
 * also write. More importantly it must not: if the phone on the other end is
 * an older build with no reader, its receive buffer eventually fills and a
 * write here blocks forever. On this thread that is harmless - snapshots keep
 * arriving and rendering exactly as before. On the accept thread it would stop
 * the prism.
 *
 * <h3>Single writer</h3>
 *
 * This is the only code on Glass that writes to the socket, mirroring the
 * discipline LinkClientService documents on the phone. The accept thread reads
 * and never writes; this thread writes and never reads.
 *
 * <h3>Coalescing</h3>
 *
 * {@link #offer} overwrites rather than queues, so several changes arriving
 * before the writer wakes collapse into the newest. State is idempotent - only
 * the current value ever mattered - so there is nothing to lose and no queue to
 * bound.
 *
 * No android.util.Log, so this is testable on the JVM. LinkServerService logs
 * when the thread ends.
 */
public final class StateWriter implements Runnable {

    private final OutputStream out;

    private final Object lock = new Object();

    /** The state waiting to be written, or null if there is none. */
    private GlassState pending; // guarded by lock

    private boolean stopped; // guarded by lock

    /**
     * @param initial the state to send as soon as the thread starts, or null if
     *                none is known yet. Sending on connect rather than only on
     *                change is what lets the phone notice a charge that
     *                finished while the link was down.
     */
    public StateWriter(OutputStream out, GlassState initial) {
        if (out == null) {
            throw new NullPointerException("out");
        }
        this.out = out;
        this.pending = initial;
    }

    /** Never blocks. Safe from any thread, including the main thread. */
    public void offer(GlassState state) {
        synchronized (lock) {
            pending = state;
            lock.notifyAll();
        }
    }

    /** Makes {@link #run} return. Idempotent. */
    public void stop() {
        synchronized (lock) {
            stopped = true;
            lock.notifyAll();
        }
    }

    @Override
    public void run() {
        try {
            while (true) {
                GlassState next;
                synchronized (lock) {
                    while (!stopped && pending == null) {
                        lock.wait();
                    }
                    if (stopped) {
                        return;
                    }
                    next = pending;
                    pending = null;
                }
                // Outside the lock: a blocked write must not also block offer().
                FrameCodec.write(out, MessageType.GLASS_STATE, GlassStateCodec.encode(next));
            }
        } catch (IOException e) {
            // The session is over. The accept thread finds out independently,
            // by its own read failing.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
```

- [ ] **Step 4: Run the test and verify it passes**

Run: `./gradlew :glass:testReleaseUnitTest --tests '*StateWriterTest*'`
Expected: PASS, 6 tests.

- [ ] **Step 5: Wire it into `LinkServerService`**

Five edits to `glass/src/main/java/dev/erinlkolp/glassnotify/glass/LinkServerService.java`.

**5a.** Add the import:

```java
import dev.erinlkolp.glassnotify.wire.GlassState;
```

**5b.** Change the class declaration to implement the listener:

```java
public final class LinkServerService extends Service implements BatteryWatcher.Listener {
```

**5c.** Add two fields beside the existing ones:

```java
    private BatteryWatcher batteryWatcher;

    /**
     * The writer for the session currently being served, or null between
     * sessions. Volatile because onBatteryState runs on the main thread while
     * the accept thread publishes and clears it.
     */
    private volatile StateWriter stateWriter;
```

**5d.** Register and unregister the watcher in the existing lifecycle methods:

```java
    @Override
    public void onCreate() {
        super.onCreate();
        overlay = GlassNotify.overlay(this);
        GlassNotify.store(this);
        batteryWatcher = new BatteryWatcher(this);
        batteryWatcher.register(this);
    }
```

In `onDestroy()`, unregister before the existing teardown:

```java
    @Override
    public void onDestroy() {
        running = false;
        batteryWatcher.unregister(this);
        closeServerSocket();
        closeConnectedSocket();
        super.onDestroy();
        main.post(new Runnable() {
            @Override
            public void run() {
                overlay.dismiss();
            }
        });
    }
```

**5e.** Add the listener method:

```java
    /**
     * Called on the main thread by BatteryWatcher, already debounced.
     *
     * Hands off and returns. It never touches a socket, so a phone that has
     * walked out of range cannot stall the main thread here.
     */
    @Override
    public void onBatteryState(GlassState state) {
        StateWriter writer = stateWriter;
        if (writer != null) {
            writer.offer(state);
        }
    }
```

**5f.** Start the writer inside `serve()`. Replace the body from the `Log.i(TAG, "connected to " + address);` line to the end of the method with:

```java
        Log.i(TAG, "connected to " + address);
        lastApplied = null;

        StateWriter writer;
        Thread writerThread;
        try {
            writer = new StateWriter(socket.getOutputStream(), batteryWatcher.latest());
        } catch (IOException e) {
            Log.w(TAG, "no output stream for the reverse channel", e);
            return;
        }
        writerThread = new Thread(writer, "glassnotify-state");
        writerThread.start();
        stateWriter = writer;

        try {
            InputStream in = socket.getInputStream();
            while (running) {
                Frame frame = FrameCodec.read(in);

                if (frame.version != Protocol.VERSION) {
                    Log.w(TAG, "protocol version " + frame.version
                            + " from phone, expected " + Protocol.VERSION);
                    // A state, not a message. A ~3.5s Toast on a see-through
                    // prism is one the wearer is very likely looking away
                    // from, and a mismatch that goes unseen looks exactly like
                    // the app being broken. Spec section 7.1.
                    GlassNotify.store(this).setVersionMismatch(true);
                    return;
                }

                dispatch(frame);
            }
        } catch (IOException e) {
            // Includes ProtocolException. Either way: close and go back to
            // accept(). Mid-stream resync is never attempted.
            Log.i(TAG, "connection ended: " + e.getMessage());
        } finally {
            // Clear the field first, so a battery change landing during
            // teardown finds nothing rather than offering to a writer that is
            // already stopping.
            stateWriter = null;
            writer.stop();
            try {
                writerThread.join(WRITER_JOIN_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            Log.i(TAG, "reverse channel ended");
        }
```

The two `return` statements inside the try now run the `finally`, which is exactly what we want.

**5g.** Add the constant beside `RETRY_DELAY_MS`:

```java
    /**
     * How long to wait for the state writer to notice the session ended.
     *
     * Tidiness, not correctness. A straggler holds a socket that acceptLoop's
     * finally has already closed, so the worst it can do is throw on its next
     * write and exit. The join just keeps threads from piling up across a run
     * of fast reconnects.
     */
    private static final long WRITER_JOIN_MS = 500L;
```

- [ ] **Step 6: Build and confirm the suite**

Run: `./gradlew test assembleDebug`
Expected: BUILD SUCCESSFUL. Totals: wire 58, glass 47, phone 42.

- [ ] **Step 7: Commit**

```bash
git add glass/src/main/java/dev/erinlkolp/glassnotify/glass/StateWriter.java \
        glass/src/main/java/dev/erinlkolp/glassnotify/glass/LinkServerService.java \
        glass/src/test/java/dev/erinlkolp/glassnotify/glass/StateWriterTest.java
git commit -m "feat(glass): report battery state to the phone

StateWriter runs on its own thread, sends the current state on connect and
on every debounced change, and coalesces by overwriting rather than
queueing.

Separate from the accept thread on purpose: against an older phone with no
reader, the receive buffer eventually fills and the write blocks forever.
Here that is inert. On the accept thread it would stop the prism.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

