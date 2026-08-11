# Glass Charge Alert Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** When Google Glass reaches 100% battery while plugged in, the paired LG V30 raises a notification saying Glass is charged.

**Architecture:** Adds the first Glass → phone message to a protocol that is currently one-directional. Glass gains a `StateWriter` thread that reports its battery state; the phone gains a `LinkReader` thread that consumes it and drives a small alert policy. Both new threads are deliberately isolated from the existing forward path — Glass's writer never reads, and the phone's reader never writes — so the single-writer discipline both link services depend on survives untouched.

**Tech Stack:** Java 8, Android Gradle Plugin, JUnit 4. Three Gradle modules: `wire` (pure Java, no Android), `glass` (minSdk/targetSdk 22), `phone` (minSdk 26, targetSdk 28).

**Design spec:** `docs/superpowers/specs/2026-08-10-glass-charge-alert-design.md`
**Branch:** `feat/glass-charge-alert`

## Global Constraints

- **Do not change `Protocol.VERSION`.** It stays at `1`. Spec §5.3 explains why at length; a bump makes old and new builds refuse each other instead of degrading silently.
- **Do not modify the forward path.** No changes to `SnapshotCodec`, `SnapshotBus`, `SnapshotBuilder`, `InterruptPolicy`, `InterruptOverlay`, `SnapshotStore`, or the existing snapshot/PING/HELLO write sequence in `LinkClientService.pump`.
- **The `wire` module must not import anything from `android.*`.** `NoAndroidImportsTest` enforces this and will fail the build.
- **Java 8 source/target, and the Glass app runs on API 22.** No `java.util.function`, no lambdas in `glass`, no `java.util.Optional`, no try-with-resources on non-`Closeable` types. Use anonymous inner classes, as the existing code does.
- **Brace every `if`, including single-statement bodies.** This matches every file in the codebase.
- **The regression gate is 102 existing tests staying green** — wire 45, glass 32, phone 25. Verified green on 2026-08-10 at commit `ec70ecc`.
- **Counting tests:** `./gradlew test` runs the Android modules once per build variant, so `glass` and `phone` each emit both a `testDebugUnitTest` and a `testReleaseUnitTest` results directory. Summing every `TEST-*.xml` reads 159, not 102. Count one variant.
- **Hard ordering constraint:** Task 4 (the phone's reader) must be committed before Task 6 (Glass's writer). A Glass that writes to a phone that never reads will eventually fill the socket buffer. It degrades safely by design, but there is no reason to ship that intermediate state.

---

## File Structure

**`wire` — new files**
- `GlassState.java` — the value type: battery level and on-power flag, with `equals` (the debounce in Task 5 depends on it).
- `GlassStateCodec.java` — two-byte body encode/decode.

**`wire` — modified**
- `MessageType.java` — adds `GLASS_STATE = 4` and a note that direction now matters.

**`phone` — new files**
- `LinkReader.java` — pure Java frame reader. No Android imports, so it unit-tests on the JVM.
- `ChargeAlertPolicy.java` — pure Java state machine. Decides SHOW / CANCEL / NONE.
- `ChargeAlerter.java` — the Android shell: owns the channel, posts and cancels.
- `res/drawable/ic_glass_charged.xml` — notification small icon.

**`phone` — modified**
- `LinkClientService.java` — starts the reader inside `pump()`. No other change.
- `res/values/strings.xml` — three new strings.

**`glass` — new files**
- `BatteryReading.java` — pure Java extras→`GlassState` normalisation. Unit-tested.
- `BatteryWatcher.java` — the Android shell: registers the receiver, debounces, notifies a listener.
- `StateWriter.java` — pure Java writer loop. No Android imports, so it unit-tests on the JVM.
- `DebugBatteryReceiver.java` — adb-driven fake battery state, debug builds only.

**`glass` — modified**
- `LinkServerService.java` — owns the watcher, starts a writer per connection.
- `AndroidManifest.xml` — registers `DebugBatteryReceiver`.

**Scripts and docs**
- `scripts/fake-battery.sh` — new.
- `README.md`, parent spec §7.4 — corrected.

---

## Task 1: The wire message

**Files:**
- Create: `wire/src/main/java/dev/erinlkolp/glassnotify/wire/GlassState.java`
- Create: `wire/src/main/java/dev/erinlkolp/glassnotify/wire/GlassStateCodec.java`
- Modify: `wire/src/main/java/dev/erinlkolp/glassnotify/wire/MessageType.java`
- Test: `wire/src/test/java/dev/erinlkolp/glassnotify/wire/GlassStateTest.java`
- Test: `wire/src/test/java/dev/erinlkolp/glassnotify/wire/GlassStateCodecTest.java`

**Interfaces:**
- Consumes: `Protocol`, `ProtocolException` (existing).
- Produces:
  - `MessageType.GLASS_STATE` — `public static final int`, value `4`.
  - `new GlassState(int batteryLevel, boolean onPower)` — throws `IllegalArgumentException` if level is outside 0–100. Public final fields `batteryLevel` and `onPower`. Implements `equals`/`hashCode`.
  - `GlassStateCodec.encode(GlassState) -> byte[]` (throws `IOException`)
  - `GlassStateCodec.decode(byte[]) -> GlassState` (throws `IOException`)

- [ ] **Step 1: Write the failing tests**

`wire/src/test/java/dev/erinlkolp/glassnotify/wire/GlassStateTest.java`:

```java
package dev.erinlkolp.glassnotify.wire;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

public class GlassStateTest {

    @Test
    public void keepsWhatItIsGiven() {
        GlassState state = new GlassState(72, true);
        assertEquals(72, state.batteryLevel);
        assertTrue(state.onPower);
    }

    @Test
    public void acceptsBothBounds() {
        assertEquals(0, new GlassState(0, false).batteryLevel);
        assertEquals(100, new GlassState(100, true).batteryLevel);
    }

    @Test
    public void rejectsNegativeLevel() {
        try {
            new GlassState(-1, false);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // The constructor is the only guard; a bad level must not travel.
        }
    }

    @Test
    public void rejectsLevelOverOneHundred() {
        try {
            new GlassState(101, false);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void equalsComparesBothFields() {
        assertEquals(new GlassState(100, true), new GlassState(100, true));
        assertNotEquals(new GlassState(100, true), new GlassState(100, false));
        assertNotEquals(new GlassState(99, true), new GlassState(100, true));
    }

    @Test
    public void equalStatesShareAHashCode() {
        assertEquals(new GlassState(55, true).hashCode(), new GlassState(55, true).hashCode());
    }

    @Test
    public void isNotEqualToOtherTypes() {
        assertFalse(new GlassState(50, false).equals("50"));
    }
}
```

`wire/src/test/java/dev/erinlkolp/glassnotify/wire/GlassStateCodecTest.java`:

```java
package dev.erinlkolp.glassnotify.wire;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Test;

public class GlassStateCodecTest {

    @Test
    public void roundTripsWhilePlugged() throws Exception {
        GlassState original = new GlassState(100, true);
        assertEquals(original, GlassStateCodec.decode(GlassStateCodec.encode(original)));
    }

    @Test
    public void roundTripsWhileUnplugged() throws Exception {
        GlassState original = new GlassState(37, false);
        assertEquals(original, GlassStateCodec.decode(GlassStateCodec.encode(original)));
    }

    @Test
    public void roundTripsBothBounds() throws Exception {
        assertEquals(new GlassState(0, false),
                GlassStateCodec.decode(GlassStateCodec.encode(new GlassState(0, false))));
        assertEquals(new GlassState(100, true),
                GlassStateCodec.decode(GlassStateCodec.encode(new GlassState(100, true))));
    }

    @Test
    public void bodyIsTwoBytes() throws Exception {
        // Small enough that the write is effectively atomic at the RFCOMM
        // layer, which matters for a message sent from a thread that must
        // never stall. Spec section 5.2.
        assertEquals(2, GlassStateCodec.encode(new GlassState(100, true)).length);
    }

    @Test
    public void rejectsAnOutOfRangeLevelOnTheWire() {
        // A corrupt or hostile frame must raise ProtocolException - an
        // IOException the reader already handles - not IllegalArgumentException
        // escaping from the GlassState constructor.
        try {
            GlassStateCodec.decode(new byte[] {(byte) 200, 1});
            fail("expected ProtocolException");
        } catch (java.io.IOException expected) {
            assertEquals(ProtocolException.class, expected.getClass());
        }
    }

    @Test
    public void rejectsATruncatedBody() {
        try {
            GlassStateCodec.decode(new byte[] {50});
            fail("expected an IOException");
        } catch (java.io.IOException expected) {
        }
    }
}
```

- [ ] **Step 2: Run the tests and verify they fail**

Run: `./gradlew :wire:test --tests '*GlassState*'`
Expected: FAIL — compilation error, `GlassState` and `GlassStateCodec` do not exist.

- [ ] **Step 3: Add the message type**

Replace the body of `wire/src/main/java/dev/erinlkolp/glassnotify/wire/MessageType.java`:

```java
package dev.erinlkolp.glassnotify.wire;

/**
 * Frame type codes.
 *
 * Direction is part of a type's meaning. Types 1-3 are phone to Glass; type 4
 * is the only message that travels the other way. A receiver that sees a type
 * it does not expect on its side of the link should ignore it, which is what
 * the default branches in both link services already do.
 */
public final class MessageType {

    /** Phone to Glass. */
    public static final int HELLO = 1;

    /** Phone to Glass. */
    public static final int SNAPSHOT = 2;

    /** Phone to Glass. */
    public static final int PING = 3;

    /**
     * Glass to phone. Unsolicited battery state; see the charge-alert design,
     * section 5. This is the entire reverse channel - keep it that way.
     */
    public static final int GLASS_STATE = 4;

    private MessageType() {
    }
}
```

- [ ] **Step 4: Write `GlassState`**

Create `wire/src/main/java/dev/erinlkolp/glassnotify/wire/GlassState.java`:

```java
package dev.erinlkolp.glassnotify.wire;

/**
 * Glass's own battery state, reported to the phone.
 *
 * The only Glass to phone message in the protocol. Deliberately carries state
 * rather than an event: the phone decides what is worth alerting about, which
 * is what lets a full charge that happened while the link was down still be
 * noticed when it comes back - and lets one that has since been unplugged
 * correctly pass unremarked.
 */
public final class GlassState {

    /** Percentage, 0-100, already normalised from the raw level/scale pair. */
    public final int batteryLevel;

    /** True when Glass is plugged into any power source. */
    public final boolean onPower;

    public GlassState(int batteryLevel, boolean onPower) {
        if (batteryLevel < 0 || batteryLevel > 100) {
            throw new IllegalArgumentException(
                    "batteryLevel " + batteryLevel + " is outside 0..100");
        }
        this.batteryLevel = batteryLevel;
        this.onPower = onPower;
    }

    /**
     * Value equality, and load-bearing: the Glass-side watcher uses it to
     * decide whether a battery broadcast is worth sending at all.
     * ACTION_BATTERY_CHANGED also fires on temperature and voltage changes, so
     * without this the link would carry a frame every few seconds.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof GlassState)) {
            return false;
        }
        GlassState other = (GlassState) o;
        return batteryLevel == other.batteryLevel && onPower == other.onPower;
    }

    @Override
    public int hashCode() {
        return 31 * batteryLevel + (onPower ? 1 : 0);
    }

    @Override
    public String toString() {
        return "GlassState{" + batteryLevel + "%" + (onPower ? " on power" : "") + "}";
    }
}
```

- [ ] **Step 5: Write `GlassStateCodec`**

Create `wire/src/main/java/dev/erinlkolp/glassnotify/wire/GlassStateCodec.java`:

```java
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
```

- [ ] **Step 6: Run the tests and verify they pass**

Run: `./gradlew :wire:test --tests '*GlassState*'`
Expected: PASS, 13 tests.

- [ ] **Step 7: Run the full suite to confirm nothing regressed**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL. `wire` is now 58 tests; `glass` 32 and `phone` 25 are unchanged.

- [ ] **Step 8: Commit**

```bash
git add wire/src/main/java/dev/erinlkolp/glassnotify/wire/GlassState.java \
        wire/src/main/java/dev/erinlkolp/glassnotify/wire/GlassStateCodec.java \
        wire/src/main/java/dev/erinlkolp/glassnotify/wire/MessageType.java \
        wire/src/test/java/dev/erinlkolp/glassnotify/wire/GlassStateTest.java \
        wire/src/test/java/dev/erinlkolp/glassnotify/wire/GlassStateCodecTest.java
git commit -m "feat(wire): add the GLASS_STATE message

The first Glass -> phone message in the protocol. Carries battery level
and whether Glass is on power, in a two-byte body.

Protocol.VERSION deliberately does not move. Unknown frame types are
already ignored on both sides, so an old build paired with a new one goes
quiet rather than refusing the link - which is what a version bump would
do instead.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

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

## Task 3: The alert policy

**Files:**
- Create: `phone/src/main/java/dev/erinlkolp/glassnotify/phone/ChargeAlertPolicy.java`
- Test: `phone/src/test/java/dev/erinlkolp/glassnotify/phone/ChargeAlertPolicyTest.java`

**Interfaces:**
- Consumes: `GlassState` from Task 1.
- Produces:
  - `enum ChargeAlertPolicy.Action { SHOW, CANCEL, NONE }`
  - `new ChargeAlertPolicy()`
  - `ChargeAlertPolicy.onState(GlassState) -> Action`
  - `ChargeAlertPolicy.FULL_LEVEL` — package-private `static final int`, value `100`.

- [ ] **Step 1: Write the failing test**

Create `phone/src/test/java/dev/erinlkolp/glassnotify/phone/ChargeAlertPolicyTest.java`:

```java
package dev.erinlkolp.glassnotify.phone;

import static org.junit.Assert.assertEquals;

import org.junit.Before;
import org.junit.Test;

import dev.erinlkolp.glassnotify.wire.GlassState;

public class ChargeAlertPolicyTest {

    private ChargeAlertPolicy policy;

    @Before
    public void setUp() {
        policy = new ChargeAlertPolicy();
    }

    private ChargeAlertPolicy.Action charging(int level) {
        return policy.onState(new GlassState(level, true));
    }

    private ChargeAlertPolicy.Action unplugged(int level) {
        return policy.onState(new GlassState(level, false));
    }

    @Test
    public void alertsWhenChargingCompletes() {
        assertEquals(ChargeAlertPolicy.Action.NONE, charging(98));
        assertEquals(ChargeAlertPolicy.Action.NONE, charging(99));
        assertEquals(ChargeAlertPolicy.Action.SHOW, charging(100));
    }

    @Test
    public void doesNotRepeatWhileItSitsOnTheCharger() {
        assertEquals(ChargeAlertPolicy.Action.SHOW, charging(100));
        assertEquals(ChargeAlertPolicy.Action.NONE, charging(100));
        assertEquals(ChargeAlertPolicy.Action.NONE, charging(100));
    }

    @Test
    public void doesNotRepeatWhenTheLinkDropsAndComesBackStillFull() {
        // Every reconnect re-sends current state. This is the case that makes
        // "alert on reconnect if still charging" safe rather than naggy.
        assertEquals(ChargeAlertPolicy.Action.SHOW, charging(100));
        assertEquals(ChargeAlertPolicy.Action.NONE, charging(100));
    }

    @Test
    public void clearsTheAlertOnUnplug() {
        assertEquals(ChargeAlertPolicy.Action.SHOW, charging(100));
        assertEquals(ChargeAlertPolicy.Action.CANCEL, unplugged(100));
    }

    @Test
    public void reArmsAfterUnplug() {
        assertEquals(ChargeAlertPolicy.Action.SHOW, charging(100));
        assertEquals(ChargeAlertPolicy.Action.CANCEL, unplugged(100));
        assertEquals(ChargeAlertPolicy.Action.NONE, unplugged(64));
        assertEquals(ChargeAlertPolicy.Action.NONE, charging(64));
        assertEquals(ChargeAlertPolicy.Action.SHOW, charging(100));
    }

    @Test
    public void doesNotCancelWhenNothingWasShown() {
        // Glass spends most of its life unplugged and not full. That must not
        // produce a stream of pointless cancels.
        assertEquals(ChargeAlertPolicy.Action.NONE, unplugged(80));
        assertEquals(ChargeAlertPolicy.Action.NONE, unplugged(79));
        assertEquals(ChargeAlertPolicy.Action.NONE, unplugged(78));
    }

    @Test
    public void doesNotAlertAtFullWithoutPower() {
        // A freshly unplugged, still-full Glass. The moment has passed.
        assertEquals(ChargeAlertPolicy.Action.NONE, unplugged(100));
    }

    @Test
    public void cancelsOnlyOnceForOneUnplug() {
        assertEquals(ChargeAlertPolicy.Action.SHOW, charging(100));
        assertEquals(ChargeAlertPolicy.Action.CANCEL, unplugged(100));
        assertEquals(ChargeAlertPolicy.Action.NONE, unplugged(99));
    }

    @Test
    public void aDismissedNotificationDoesNotComeBackByItself() {
        // shown tracks "we alerted", not "the notification is visible". If the
        // user swipes it away and the link then bounces, re-sending the same
        // state must stay silent - re-alerting is how an app earns a mute.
        assertEquals(ChargeAlertPolicy.Action.SHOW, charging(100));
        assertEquals(ChargeAlertPolicy.Action.NONE, charging(100));
        assertEquals(ChargeAlertPolicy.Action.NONE, charging(100));
    }
}
```

- [ ] **Step 2: Run the test and verify it fails**

Run: `./gradlew :phone:testReleaseUnitTest --tests '*ChargeAlertPolicyTest*'`
Expected: FAIL — compilation error, `ChargeAlertPolicy` does not exist.

- [ ] **Step 3: Write `ChargeAlertPolicy`**

Create `phone/src/main/java/dev/erinlkolp/glassnotify/phone/ChargeAlertPolicy.java`:

```java
package dev.erinlkolp.glassnotify.phone;

import dev.erinlkolp.glassnotify.wire.GlassState;

/**
 * Decides whether a battery state from Glass is worth telling the wearer about.
 *
 * Glass sends state, not events, so this receives the same state repeatedly -
 * on every reconnect, and whenever the level or power flag moves. One boolean
 * turns that stream into at most one alert per charge:
 *
 * <ul>
 *   <li><b>Reconnect while still full.</b> Already alerted, so nothing fires.
 *       This is what makes it safe to alert on reconnect at all, which is how
 *       a charge that completed while the phone was out of range still gets
 *       noticed.</li>
 *   <li><b>Notification dismissed by hand, then the link bounces.</b> Still
 *       silent. The flag tracks <em>we alerted</em>, not <em>it is visible</em>
 *       - tracking visibility would re-alert on every reconnect.</li>
 *   <li><b>Unplug re-arms.</b> The alert is cancelled and the next charge
 *       announces itself normally.</li>
 * </ul>
 *
 * Known edge, accepted: if the app restarts while Glass sits plugged in at
 * 100%, the flag starts false and one further alert fires. Persisting it to
 * disk was judged disproportionate - a fresh session arguably should
 * re-announce.
 *
 * Not thread-safe. {@link ChargeAlerter} calls it on the main thread only.
 */
public final class ChargeAlertPolicy {

    public enum Action {
        /** Post the charged notification. */
        SHOW,
        /** Remove it. */
        CANCEL,
        /** Do nothing at all. */
        NONE
    }

    /**
     * The level that counts as charged.
     *
     * Deliberately not BATTERY_STATUS_FULL. That value is firmware-specific -
     * some builds latch it well before the cell is topped off and others never
     * emit it - and this ROM's behaviour is unmeasured. Charge-alert design,
     * section 4.
     */
    static final int FULL_LEVEL = 100;

    /** Whether an alert has been raised for the charge currently in progress. */
    private boolean shown;

    public Action onState(GlassState state) {
        if (!state.onPower) {
            if (shown) {
                shown = false;
                return Action.CANCEL;
            }
            return Action.NONE;
        }

        if (state.batteryLevel >= FULL_LEVEL && !shown) {
            shown = true;
            return Action.SHOW;
        }

        return Action.NONE;
    }
}
```

- [ ] **Step 4: Run the test and verify it passes**

Run: `./gradlew :phone:testReleaseUnitTest --tests '*ChargeAlertPolicyTest*'`
Expected: PASS, 9 tests.

- [ ] **Step 5: Commit**

```bash
git add phone/src/main/java/dev/erinlkolp/glassnotify/phone/ChargeAlertPolicy.java \
        phone/src/test/java/dev/erinlkolp/glassnotify/phone/ChargeAlertPolicyTest.java
git commit -m "feat(phone): decide when a full charge is worth an alert

One boolean turns a repeating state stream into at most one alert per
charge, covering reconnect-while-full, a hand-dismissed notification, and
re-arming on unplug.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 4: Post the notification, and wire the reader in

**Files:**
- Create: `phone/src/main/java/dev/erinlkolp/glassnotify/phone/ChargeAlerter.java`
- Create: `phone/src/main/res/drawable/ic_glass_charged.xml`
- Modify: `phone/src/main/res/values/strings.xml`
- Modify: `phone/src/main/java/dev/erinlkolp/glassnotify/phone/LinkClientService.java`

**Interfaces:**
- Consumes: `LinkReader` (Task 2), `ChargeAlertPolicy` (Task 3), `GlassState` (Task 1).
- Produces:
  - `new ChargeAlerter(Context context)` — creates the channel.
  - `ChargeAlerter.onGlassState(GlassState state)` — main thread only.

This is the task that completes the phone half. **It must be committed before Task 6**, per the ordering constraint.

- [ ] **Step 1: Add the strings**

In `phone/src/main/res/values/strings.xml`, add three entries inside `<resources>`:

```xml
    <string name="channel_charge">Glass charged</string>
    <string name="charged_title">Glass is charged</string>
    <string name="charged_text" formatted="false">100% — ready to go</string>
```

A lone `%` in an Android string resource is normally treated as a format specifier by aapt's validation, so `formatted="false"` is required to tell the compiler this string is not a format string and a bare `%` is allowed. Do **not** "fix" this by doubling it to `%%` instead: `ChargeAlerter.build()` retrieves this string with the single-argument `context.getString(int)`, which never runs `String.format` and therefore never collapses `%%` back down — it renders the literal two characters, producing `100%% — ready to go` on screen. Escape collapsing only happens through the varargs `getString(int, Object...)` overload. Because that overload is not used here, `formatted="false"` plus a single `%` is the only combination that both compiles and renders correctly.

- [ ] **Step 2: Add the notification icon**

Create `phone/src/main/res/drawable/ic_glass_charged.xml`. A vector rather than a platform drawable: the `android.R.drawable.stat_sys_battery*` names are internal, not public API, and guessing one costs a build failure. A notification small icon must be a white silhouette on transparency — the system tints it.

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="#FFFFFFFF"
        android:pathData="M10,2h4v2h-4z M7,5h10v16h-10z" />
</vector>
```

- [ ] **Step 3: Write `ChargeAlerter`**

Create `phone/src/main/java/dev/erinlkolp/glassnotify/phone/ChargeAlerter.java`:

```java
package dev.erinlkolp.glassnotify.phone;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;

import dev.erinlkolp.glassnotify.wire.GlassState;

/**
 * Raises and clears the "Glass is charged" notification.
 *
 * Its own channel, separate from the link status channel. That one is
 * IMPORTANCE_MIN on purpose - it is an always-present service notification the
 * wearer should never notice - and this one has to be audible, so they cannot
 * share. IMPORTANCE_DEFAULT rather than HIGH: a finished charge is worth a
 * sound, not a heads-up window over whatever you were doing.
 *
 * Main thread only. {@link ChargeAlertPolicy} holds mutable state with no
 * synchronisation, and confining every call to one thread is cheaper than
 * locking it.
 */
public final class ChargeAlerter implements LinkReader.Listener {

    private static final String CHANNEL_ID = "glass_charge";

    /** 1 belongs to the foreground service notification. */
    private static final int NOTIFICATION_ID = 2;

    private final Context context;
    private final ChargeAlertPolicy policy = new ChargeAlertPolicy();

    public ChargeAlerter(Context context) {
        this.context = context.getApplicationContext();
        createChannel();
    }

    @Override
    public void onGlassState(GlassState state) {
        switch (policy.onState(state)) {
            case SHOW:
                manager().notify(NOTIFICATION_ID, build());
                break;
            case CANCEL:
                manager().cancel(NOTIFICATION_ID);
                break;
            default:
                break;
        }
    }

    private Notification build() {
        return new Notification.Builder(context, CHANNEL_ID)
                .setContentTitle(context.getString(R.string.charged_title))
                .setContentText(context.getString(R.string.charged_text))
                .setSmallIcon(R.drawable.ic_glass_charged)
                .setAutoCancel(true)
                // Belt and braces. The policy already guarantees we do not
                // re-post while an alert stands, so this should never be the
                // thing that keeps it quiet - but if that guarantee ever
                // breaks, the failure is a silent update rather than a device
                // that chirps on every reconnect.
                .setOnlyAlertOnce(true)
                .build();
    }

    private void createChannel() {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                context.getString(R.string.channel_charge),
                NotificationManager.IMPORTANCE_DEFAULT);
        channel.setShowBadge(false);
        manager().createNotificationChannel(channel);
    }

    private NotificationManager manager() {
        return (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
    }
}
```

- [ ] **Step 4: Wire the reader into `LinkClientService`**

Four edits to `phone/src/main/java/dev/erinlkolp/glassnotify/phone/LinkClientService.java`. Change nothing else in this file.

**4a.** Add imports beside the existing ones:

```java
import android.os.Handler;
import android.os.Looper;
```

**4b.** Add two fields next to the other private fields:

```java
    /** Posts the charged alert. Touched only from the main thread. */
    private ChargeAlerter alerter;

    private final Handler main = new Handler(Looper.getMainLooper());
```

**4c.** In `onCreate()`, construct the alerter after `createChannel()`:

```java
    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        alerter = new ChargeAlerter(this);
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.status_connecting)));
        SnapshotBus.get().setListener(this);
    }
```

**4d.** In `pump()`, start the reader immediately after the HELLO write. Insert this block between the `writeFrame(connected, MessageType.HELLO, ...)` call and the `clearPendingSnapshot()` call:

```java
        // The reverse channel. A separate thread because this one must stay
        // free to write, and a reader because Glass now reports its own
        // battery state. It is deliberately fire-and-forget: no join, no
        // reference kept, no effect on this method's control flow. When the
        // session ends, connectLoop's finally closes the socket, the blocking
        // read throws, and the thread ends itself.
        //
        // Nothing here may write. See LinkReader's class comment - the
        // single-writer guarantee this whole class is built on depends on it.
        //
        // getInputStream() is called out here rather than inside run(): it
        // throws IOException, which cannot be declared on Runnable.run(). Out
        // here the exception lands in pump's existing throws clause and the
        // retry loop treats it like any other connection failure.
        final InputStream reverse = connected.getInputStream();
        Thread reader = new Thread(new Runnable() {
            @Override
            public void run() {
                new LinkReader(reverse, new LinkReader.Listener() {
                    @Override
                    public void onGlassState(final GlassState state) {
                        main.post(new Runnable() {
                            @Override
                            public void run() {
                                alerter.onGlassState(state);
                            }
                        });
                    }
                }).run();
                Log.i(TAG, "reverse channel ended");
            }
        }, "glassnotify-reader");
        reader.start();
```

This needs two more imports alongside those from step 4a:

```java
import java.io.InputStream;
import dev.erinlkolp.glassnotify.wire.GlassState;
```

Also change the signature of `pump` from `private void pump(BluetoothSocket connected)` to `private void pump(final BluetoothSocket connected)`. Java 8 would infer effectively-final, but the codebase targets source 8 with anonymous inner classes throughout, and the explicit `final` matches the surrounding style.

- [ ] **Step 5: Build and confirm the whole suite is still green**

Run: `./gradlew test assembleDebug`
Expected: BUILD SUCCESSFUL. Test totals: wire 58, glass 32, phone 42.

If `glass` moved off 32, something outside this task's scope changed — stop and find out why.

- [ ] **Step 6: Commit**

```bash
git add phone/src/main/java/dev/erinlkolp/glassnotify/phone/ChargeAlerter.java \
        phone/src/main/java/dev/erinlkolp/glassnotify/phone/LinkClientService.java \
        phone/src/main/res/drawable/ic_glass_charged.xml \
        phone/src/main/res/values/strings.xml
git commit -m "feat(phone): alert when Glass finishes charging

Starts a reader thread per session and posts a notification on its own
IMPORTANCE_DEFAULT channel. The link status channel stays IMPORTANCE_MIN
and untouched.

The reader is fire-and-forget and never writes, so the single-writer
discipline in this service is unchanged. Glass does not send GLASS_STATE
yet, so this is inert until the Glass side lands - which is the intended
order: a writer must never arrive before its reader.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 5: Read the battery on Glass

**Files:**
- Create: `glass/src/main/java/dev/erinlkolp/glassnotify/glass/BatteryReading.java`
- Create: `glass/src/main/java/dev/erinlkolp/glassnotify/glass/BatteryWatcher.java`
- Test: `glass/src/test/java/dev/erinlkolp/glassnotify/glass/BatteryReadingTest.java`

**Interfaces:**
- Consumes: `GlassState` from Task 1.
- Produces:
  - `BatteryReading.fromExtras(int level, int scale, int plugged) -> GlassState` — static; returns `null` when the broadcast carries no usable level.
  - `interface BatteryWatcher.Listener { void onBatteryState(GlassState state); }`
  - `new BatteryWatcher(Listener listener)`
  - `BatteryWatcher.register(Context)` / `BatteryWatcher.unregister(Context)`
  - `BatteryWatcher.latest() -> GlassState` — may be null.

The split is deliberate: `BatteryReading` holds all the arithmetic and no Android types, so it tests on the JVM. `BatteryWatcher` is a thin `BroadcastReceiver` shell around it.

- [ ] **Step 1: Write the failing test**

Create `glass/src/test/java/dev/erinlkolp/glassnotify/glass/BatteryReadingTest.java`:

```java
package dev.erinlkolp.glassnotify.glass;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import dev.erinlkolp.glassnotify.wire.GlassState;

public class BatteryReadingTest {

    @Test
    public void readsAPlainPercentage() {
        GlassState state = BatteryReading.fromExtras(72, 100, 0);
        assertEquals(72, state.batteryLevel);
        assertFalse(state.onPower);
    }

    @Test
    public void normalisesAScaleThatIsNotOneHundred() {
        // EXTRA_SCALE is whatever the driver says it is. Assuming 100 is a
        // classic way to report 50% as 50 on a device whose scale is 200.
        assertEquals(25, BatteryReading.fromExtras(50, 200, 0).batteryLevel);
        assertEquals(100, BatteryReading.fromExtras(255, 255, 1).batteryLevel);
    }

    @Test
    public void roundsRatherThanTruncates() {
        // 2/3 is 66.67%. Truncation would report 66.
        assertEquals(67, BatteryReading.fromExtras(2, 3, 0).batteryLevel);
    }

    @Test
    public void treatsAnyPowerSourceAsCharging() {
        // BATTERY_PLUGGED_AC = 1, USB = 2, WIRELESS = 4.
        assertTrue(BatteryReading.fromExtras(50, 100, 1).onPower);
        assertTrue(BatteryReading.fromExtras(50, 100, 2).onPower);
        assertTrue(BatteryReading.fromExtras(50, 100, 4).onPower);
        assertFalse(BatteryReading.fromExtras(50, 100, 0).onPower);
    }

    @Test
    public void treatsAMissingPluggedExtraAsUnplugged() {
        // getIntExtra's miss value is -1 in the caller. Anything that is not a
        // positive plug type must read as unplugged, or a device that omits
        // the extra would look permanently on charge.
        assertFalse(BatteryReading.fromExtras(50, 100, -1).onPower);
    }

    @Test
    public void rejectsAMissingLevel() {
        assertNull(BatteryReading.fromExtras(-1, 100, 0));
    }

    @Test
    public void rejectsAnUnusableScale() {
        // Guards the division. A scale of 0 would be an ArithmeticException.
        assertNull(BatteryReading.fromExtras(50, 0, 0));
        assertNull(BatteryReading.fromExtras(50, -1, 0));
    }

    @Test
    public void clampsALevelAboveItsOwnScale() {
        // Nonsense from the driver must not reach the GlassState constructor,
        // which would throw IllegalArgumentException inside a BroadcastReceiver.
        assertEquals(100, BatteryReading.fromExtras(150, 100, 1).batteryLevel);
    }

    @Test
    public void reportsAFullBatteryAsOneHundred() {
        GlassState state = BatteryReading.fromExtras(100, 100, 1);
        assertEquals(100, state.batteryLevel);
        assertTrue(state.onPower);
    }
}
```

- [ ] **Step 2: Run the test and verify it fails**

Run: `./gradlew :glass:testReleaseUnitTest --tests '*BatteryReadingTest*'`
Expected: FAIL — compilation error, `BatteryReading` does not exist.

- [ ] **Step 3: Write `BatteryReading`**

Create `glass/src/main/java/dev/erinlkolp/glassnotify/glass/BatteryReading.java`:

```java
package dev.erinlkolp.glassnotify.glass;

import dev.erinlkolp.glassnotify.wire.GlassState;

/**
 * Turns raw ACTION_BATTERY_CHANGED extras into a {@link GlassState}.
 *
 * Split out from {@link BatteryWatcher} so the arithmetic can be tested on the
 * JVM: the watcher is a BroadcastReceiver and drags android.content in with it.
 *
 * Every guard here exists so that nothing invalid reaches the GlassState
 * constructor, which throws IllegalArgumentException - and an unchecked throw
 * inside a BroadcastReceiver takes the process down.
 */
public final class BatteryReading {

    private BatteryReading() {
    }

    /**
     * @param level   EXTRA_LEVEL, or a negative value if the extra was absent
     * @param scale   EXTRA_SCALE, the value {@code level} is out of
     * @param plugged EXTRA_PLUGGED: 0 for none, or a BATTERY_PLUGGED_* constant
     * @return the state, or null if the broadcast carried no usable level
     */
    public static GlassState fromExtras(int level, int scale, int plugged) {
        if (level < 0 || scale <= 0) {
            return null;
        }

        int percent = (int) Math.round(level * 100.0d / scale);
        if (percent < 0) {
            percent = 0;
        }
        if (percent > 100) {
            percent = 100;
        }

        // Strictly positive, not "!= 0". getIntExtra's miss value is -1, and
        // reading that as plugged would leave Glass permanently claiming to be
        // on charge.
        return new GlassState(percent, plugged > 0);
    }
}
```

- [ ] **Step 4: Run the test and verify it passes**

Run: `./gradlew :glass:testReleaseUnitTest --tests '*BatteryReadingTest*'`
Expected: PASS, 9 tests.

- [ ] **Step 5: Write `BatteryWatcher`**

Create `glass/src/main/java/dev/erinlkolp/glassnotify/glass/BatteryWatcher.java`:

```java
package dev.erinlkolp.glassnotify.glass;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;

import dev.erinlkolp.glassnotify.wire.GlassState;

/**
 * Watches Glass's own battery and reports changes worth sending.
 *
 * ACTION_BATTERY_CHANGED cannot be declared in a manifest filter, so this
 * registers at runtime. It needs no permission.
 *
 * <h3>The debounce</h3>
 *
 * The broadcast is chatty - it fires on temperature and voltage movement, not
 * only on level - so forwarding every one would put a frame on the link every
 * few seconds for no gain. The listener is called only when the
 * (batteryLevel, onPower) pair actually differs from the last one published.
 * No timer, no interval to tune: Glass sitting at 100% overnight produces one
 * change and then silence.
 */
public final class BatteryWatcher extends BroadcastReceiver {

    /** Called on the main thread. Implementations must not block. */
    public interface Listener {
        void onBatteryState(GlassState state);
    }

    private final Listener listener;

    /**
     * Last published state. Volatile because the accept thread reads it via
     * {@link #latest()} when a connection opens, while onReceive writes it on
     * the main thread.
     */
    private volatile GlassState latest;

    public BatteryWatcher(Listener listener) {
        if (listener == null) {
            throw new NullPointerException("listener");
        }
        this.listener = listener;
    }

    /**
     * ACTION_BATTERY_CHANGED is sticky, so registerReceiver hands back the last
     * broadcast immediately. Feeding it straight through means {@link #latest}
     * is populated before this method returns, rather than being null until the
     * battery next moves - which on a device sitting at a stable 100% could be
     * a very long time.
     */
    public void register(Context context) {
        Intent sticky = context.registerReceiver(this,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (sticky != null) {
            onReceive(context, sticky);
        }
    }

    public void unregister(Context context) {
        try {
            context.unregisterReceiver(this);
        } catch (IllegalArgumentException notRegistered) {
            // Already gone. Nothing to undo.
        }
    }

    /** The most recent state, or null if no usable broadcast has arrived. */
    public GlassState latest() {
        return latest;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        GlassState state = BatteryReading.fromExtras(
                intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
                intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1),
                intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0));

        if (state == null || state.equals(latest)) {
            return;
        }

        latest = state;
        listener.onBatteryState(state);
    }
}
```

- [ ] **Step 6: Build and confirm the suite**

Run: `./gradlew test assembleDebug`
Expected: BUILD SUCCESSFUL. Totals: wire 58, glass 41, phone 42.

- [ ] **Step 7: Commit**

```bash
git add glass/src/main/java/dev/erinlkolp/glassnotify/glass/BatteryReading.java \
        glass/src/main/java/dev/erinlkolp/glassnotify/glass/BatteryWatcher.java \
        glass/src/test/java/dev/erinlkolp/glassnotify/glass/BatteryReadingTest.java
git commit -m "feat(glass): watch the battery

BatteryReading normalises the raw level/scale/plugged extras and guards
every way they can be nonsense, so nothing invalid reaches the GlassState
constructor inside a BroadcastReceiver. BatteryWatcher is the thin shell
around it.

The debounce is value equality on (level, onPower): ACTION_BATTERY_CHANGED
also fires on temperature and voltage, and forwarding those would put a
frame on the link every few seconds.

Nothing sends this yet.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

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

## Task 7: Fake the battery over adb

**Files:**
- Create: `glass/src/main/java/dev/erinlkolp/glassnotify/glass/DebugBatteryReceiver.java`
- Create: `scripts/fake-battery.sh`
- Modify: `glass/src/main/AndroidManifest.xml`
- Modify: `glass/src/main/java/dev/erinlkolp/glassnotify/glass/LinkServerService.java`

**Interfaces:**
- Consumes: `BatteryReading` (Task 5), `LinkServerService.onBatteryState` (Task 6).
- Produces: broadcast action `dev.erinlkolp.glassnotify.DEBUG_BATTERY` with int extra `level` and boolean extra `plugged`.

Charging Glass to 100% takes over an hour. Without this, none of the Task 9 checks get run honestly.

- [ ] **Step 1: Accept a synthetic state in `LinkServerService`**

Add these constants beside the others:

```java
    /** Debug-only extras, see DebugBatteryReceiver. */
    private static final String EXTRA_DEBUG_LEVEL = "debug_level";
    private static final String EXTRA_DEBUG_PLUGGED = "debug_plugged";
```

Then in `onStartCommand`, before the `if (!running)` block:

```java
        if (intent != null && intent.hasExtra(EXTRA_DEBUG_LEVEL)) {
            // Straight into the same path a real broadcast takes, so what is
            // being exercised is the real writer, not a shortcut round it.
            GlassState fake = BatteryReading.fromExtras(
                    intent.getIntExtra(EXTRA_DEBUG_LEVEL, 100), 100,
                    intent.getBooleanExtra(EXTRA_DEBUG_PLUGGED, true) ? 1 : 0);
            if (fake != null) {
                Log.i(TAG, "debug: battery " + fake);
                onBatteryState(fake);
            }
        }
```

- [ ] **Step 2: Write the receiver**

Create `glass/src/main/java/dev/erinlkolp/glassnotify/glass/DebugBatteryReceiver.java`:

```java
package dev.erinlkolp.glassnotify.glass;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Fake battery state, so the charge alert can be exercised without waiting
 * over an hour for a real charge.
 *
 *   adb shell am broadcast -a dev.erinlkolp.glassnotify.DEBUG_BATTERY \
 *     --ei level 100 --ez plugged true
 *
 * Routed through the service rather than acting directly, so the frame really
 * does travel the live socket via StateWriter. A shortcut that posted the
 * notification some other way would test nothing worth testing.
 */
public final class DebugBatteryReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!BuildConfig.DEBUG) {
            // Never allow synthetic battery state into a non-debug build.
            return;
        }

        Intent toService = new Intent(context, LinkServerService.class);
        toService.putExtra("debug_level", intent.getIntExtra("level", 100));
        toService.putExtra("debug_plugged", intent.getBooleanExtra("plugged", true));
        context.startService(toService);
    }
}
```

- [ ] **Step 3: Register it in the manifest**

In `glass/src/main/AndroidManifest.xml`, add beside the existing `DebugInjectReceiver`:

```xml
        <receiver
            android:name=".DebugBatteryReceiver"
            android:exported="true">
            <intent-filter>
                <action android:name="dev.erinlkolp.glassnotify.DEBUG_BATTERY" />
            </intent-filter>
        </receiver>
```

- [ ] **Step 4: Write the script**

Create `scripts/fake-battery.sh` and `chmod +x` it. The remote-quoting helper is copied from `fake-notify.sh` for the same reason documented there:

```bash
#!/usr/bin/env bash
# Injects a synthetic battery state into the Glass app, so the phone's charge
# alert can be exercised without waiting out a real charge.
#
#   scripts/fake-battery.sh 100 true    # full, on the charger  -> alert
#   scripts/fake-battery.sh 100 false   # unplugged             -> alert clears
#   scripts/fake-battery.sh 64 true     # charging              -> nothing
set -euo pipefail

SERIAL="${GLASS_SERIAL:-0123456789ABCDEF}"
ACTION=dev.erinlkolp.glassnotify.DEBUG_BATTERY

# `adb shell` joins its arguments with spaces and hands the result to the
# DEVICE's shell, where our local quoting no longer exists. See the same note
# in fake-notify.sh for what goes wrong without this.
remote() {
  local quoted=""
  local arg
  for arg in "$@"; do
    quoted="$quoted '$(printf '%s' "$arg" | sed "s/'/'\\\\''/g")'"
  done
  adb -s "$SERIAL" shell "$quoted"
}

LEVEL="${1:-100}"
PLUGGED="${2:-true}"

remote am broadcast -a "$ACTION" \
  --ei level "$LEVEL" \
  --ez plugged "$PLUGGED"
```

- [ ] **Step 5: Build and confirm the suite**

Run: `./gradlew test assembleDebug`
Expected: BUILD SUCCESSFUL, totals unchanged from Task 6 — wire 58, glass 47, phone 42.

- [ ] **Step 6: Commit**

```bash
chmod +x scripts/fake-battery.sh
git add glass/src/main/java/dev/erinlkolp/glassnotify/glass/DebugBatteryReceiver.java \
        glass/src/main/java/dev/erinlkolp/glassnotify/glass/LinkServerService.java \
        glass/src/main/AndroidManifest.xml \
        scripts/fake-battery.sh
git commit -m "feat(glass): inject fake battery state for testing

A real charge takes over an hour, which is long enough that the hardware
checks would get skipped or faked. This drives the same path a real
broadcast takes, so the frame genuinely crosses the live socket.

Debug builds only, matching DebugInjectReceiver.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 8: Correct the documentation

**Files:**
- Modify: `README.md` (lines 28, 191, 205, and section 13)
- Modify: `docs/superpowers/specs/2026-08-04-glass-notifications-design.md` (§7.4)

Four statements in the docs now say the opposite of what the code does. Read each one in place before editing — the line numbers are from commit `ec70ecc` and will have shifted.

- [ ] **Step 1: Fix README §1**

At `README.md:28`, the bullet currently reads:

```markdown
- **Glass is read-only.** It displays and scrolls a queue. It never dismisses, replies to, or acts
  on a notification — there is no reverse channel at all (see [section 5](#5-how-the-protocol-works)).
```

Replace with:

```markdown
- **Glass is read-only.** It displays and scrolls a queue. It never dismisses, replies to, or acts
  on a notification. The one thing it does send back is its own battery state, so the phone can tell
  you when Glass has finished charging — see [section 5](#5-how-the-protocol-works).
```

- [ ] **Step 2: Fix README §5**

Change the heading at `README.md:191` from `### The three message types` to `### The four message types`, and add an entry after the `PING` description:

```markdown
**`GLASS_STATE`** — Glass → phone, unsolicited. Glass's own battery level and whether it is plugged
in. Sent when a connection opens and whenever the level or power state actually changes. This is the
only message that travels this direction, and the phone acts on exactly one thing in it: reaching
100% while on power, which raises a "Glass is charged" notification.
```

- [ ] **Step 3: Fix the reverse-channel paragraph**

At `README.md:205`, replace the paragraph beginning "There is deliberately **no reverse channel and no acknowledgement**" with:

```markdown
There is deliberately **no acknowledgement**. With full-state snapshots there is nothing to
acknowledge: a lost frame is superseded by the next one.

The reverse channel is exactly one message wide, and should stay that way. Glass volunteers its
battery state and nothing else — it never asks the phone for anything, never confirms receipt, and
never acts on a notification. Adding a second Glass → phone message is a real protocol change and
should be argued on its own merits, not waved through because a channel already exists.
```

- [ ] **Step 4: Fix the spec cross-reference**

At `README.md:356` the migration guide says the protocol "has no reverse channel (spec §7.4) — so the phone genuinely cannot learn". Read the surrounding sentence in place. It is about the phone being unable to discover something from Glass during setup, which is still true — the reverse channel carries battery state only. Change just the parenthetical to `(spec §7.4 — the one reverse message carries battery state only)` and leave the surrounding argument intact.

- [ ] **Step 5: Rewrite parent spec §7.4**

In `docs/superpowers/specs/2026-08-04-glass-notifications-design.md`, replace section 7.4:

```markdown
### 7.4 The reverse channel is one message wide

There is no `ACK`. With full-state snapshots there is nothing to acknowledge: a lost frame is
superseded by the next snapshot. There is no request/response either — neither side ever asks the
other for anything.

The single exception, added 2026-08-10, is `GLASS_STATE`: Glass volunteers its own battery level and
power state so the phone can raise an alert when charging completes. It is unsolicited, carries no
reply, and does not make Glass any less read-only with respect to notifications. See
`2026-08-10-glass-charge-alert-design.md` §5.4.
```

- [ ] **Step 6: Add the tuned value**

In README section 13 ("Tuned values"), add a row to the existing table matching its format:

```markdown
| Full-charge threshold | 100% while on power | `ChargeAlertPolicy.FULL_LEVEL` |
```

- [ ] **Step 7: Verify no stale claims remain**

Run: `grep -rn "no reverse channel\|three message types\|never sends anything back" README.md docs/`
Expected: no matches outside `2026-08-10-glass-charge-alert-design.md`, where §5.4 quotes the old wording deliberately while explaining what replaced it.

- [ ] **Step 8: Commit**

```bash
git add README.md docs/superpowers/specs/2026-08-04-glass-notifications-design.md
git commit -m "docs: record the reverse channel

Four places said Glass never sends anything back. Corrected, and the
parent spec's 7.4 is rewritten rather than deleted - its reasoning about
acknowledgements and deltas still holds, and the boundary it draws is
worth keeping now that there is exactly one exception to it.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 9: Verify on hardware

**Files:** none — this task changes no code.

**Device access:** both devices were confirmed attached over adb on 2026-08-10 at the serials the
scripts already default to — Glass `0123456789ABCDEF`, V30 `VS9967edd915b`. No `GLASS_SERIAL` or
`PHONE_SERIAL` override is needed.

Checks 1–5 are fully automatable from the command line. Checks 6 and 7 need Bluetooth toggled on the
V30, which is a screen tap. Check 8 needs Glass physically put on a charger and about an hour. So an
agent can get through most of this alone, then hand over — do not report the task complete with 6, 7
and 8 outstanding, and do not claim an alert appeared on the V30's screen without someone having
looked at it.

Install both APKs first:

```bash
./gradlew assembleDebug
adb -s "${GLASS_SERIAL:-0123456789ABCDEF}" install -r glass/build/outputs/apk/debug/glass-debug.apk
adb -s "${PHONE_SERIAL:-VS9967edd915b}" install -r phone/build/outputs/apk/debug/phone-debug.apk
```

- [ ] **Check 1: The forward path still works**

Before testing anything new, confirm nothing broke. Send a notification and watch it reach the prism:

```bash
scripts/fake-notify.sh "Signal" "Jordan Reyes" "still good for 7pm?" INTERRUPT
```

Expected: the card appears on the prism as it always has. **If this fails, stop.** Everything below is secondary to this still working.

- [ ] **Check 2: A full charge raises the alert**

With both devices connected:

```bash
scripts/fake-battery.sh 100 true
```

Expected: "Glass is charged / 100% — ready to go" appears on the V30, with sound.

- [ ] **Check 3: It does not nag**

```bash
scripts/fake-battery.sh 100 true
scripts/fake-battery.sh 100 true
```

Expected: nothing further. No repeat sound, no second notification.

- [ ] **Check 4: Unplugging clears it**

```bash
scripts/fake-battery.sh 100 false
```

Expected: the notification disappears from the V30.

- [ ] **Check 5: It re-arms**

```bash
scripts/fake-battery.sh 64 true
scripts/fake-battery.sh 100 true
```

Expected: the alert fires again, with sound.

- [ ] **Check 6: A charge completed out of range is noticed on reconnect**

This is the one that justifies the state-based design, and the only check the fake script cannot fully shortcut.

1. Turn Bluetooth off on the V30.
2. `scripts/fake-battery.sh 100 true`
3. Turn Bluetooth back on and wait for the phone's status notification to read "Connected to Glass".

Expected: the alert appears once the link is back.

- [ ] **Check 7: A charge that has since ended stays quiet**

1. Turn Bluetooth off on the V30.
2. `scripts/fake-battery.sh 100 true`
3. `scripts/fake-battery.sh 100 false`
4. Turn Bluetooth back on and wait for reconnection.

Expected: **no alert.** Glass is no longer on power, so the moment has passed. This is the behaviour that makes alerting on reconnect safe.

- [ ] **Check 8: A real charge, end to end**

Everything above used synthetic state. Run the real thing once: put Glass on a charger below 100% with the V30 nearby, and wait.

Expected: the alert arrives on its own. If it does not, the most likely cause is the ROM reporting a scale other than 100 or never reaching a level of exactly 100 — check with `adb -s "$GLASS_SERIAL" shell dumpsys battery` and compare `level` and `scale` against what `ChargeAlertPolicy.FULL_LEVEL` expects.

- [ ] **Check 9: Record the result**

Append the outcome to the spec's §8.1 checklist, note the observed `level`/`scale` values from Check 8 in README section 13, and commit.

---

## Self-Review

**Spec coverage:**

| Spec section | Task |
|---|---|
| §4 trigger definition | 3 (`FULL_LEVEL`, with the rationale in the doc comment) |
| §5.1 `GLASS_STATE = 4` | 1 |
| §5.2 `GlassState` + codec | 1 |
| §5.3 `VERSION` stays 1 | 1 (commit message and `MessageType` comment); Global Constraints |
| §5.4 one message wide | 8 (README and parent spec §7.4) |
| §6.1 `BatteryWatcher` + tuple debounce | 5 |
| §6.2 `StateWriter`, single-writer, lifecycle | 6 |
| §7.1 `LinkReader`, forbidden touches, inert failure | 2, 4 |
| §7.2 `ChargeAlertPolicy` | 3 |
| §7.3 channel, ID 2, icon | 4 |
| §8 unit tests | 1, 2, 3, 5, 6 |
| §8.1 hardware checks | 9 |
| §8.2 debug injection | 7 |
| §9 documentation | 8 |

No gaps.

**Placeholder scan:** none. Every code step carries complete source. The one item the spec left open — the notification icon — is resolved in Task 4 Step 2 with a concrete vector rather than deferred, because the platform battery drawable names are internal and would fail the build.

**Type consistency, checked across tasks:**

- `GlassState.batteryLevel` / `.onPower` — used identically in Tasks 1, 2, 3, 5, 6.
- `LinkReader.Listener.onGlassState(GlassState)` — declared Task 2, implemented by `ChargeAlerter` (Task 4) and by the anonymous class in `LinkClientService` (Task 4).
- `BatteryWatcher.Listener.onBatteryState(GlassState)` — declared Task 5, implemented by `LinkServerService` (Task 6), called again by the debug path (Task 7).
- `ChargeAlertPolicy.Action` — three constants, produced Task 3, consumed in the `switch` in Task 4.
- `StateWriter.offer` / `.stop` — declared Task 6, called from `LinkServerService.onBatteryState` and the `serve()` finally in the same task.
- `BatteryReading.fromExtras(int, int, int)` — declared Task 5, called by `BatteryWatcher` (Task 5) and the debug path (Task 7), both passing scale 100.

**Test count arithmetic:** wire 45 → 58 (+13, Task 1). glass 32 → 41 (+9, Task 5) → 47 (+6, Task 6). phone 25 → 33 (+8, Task 2) → 42 (+9, Task 3). Final: **147 tests**, of which the original 102 must all still pass.
