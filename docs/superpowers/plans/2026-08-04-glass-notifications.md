# Glass Notifications Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Put notifications from a carried LG V30 onto Google Glass over classic Bluetooth, with a glanceable interrupt card and a scrollable read-only queue.

**Architecture:** Three Gradle modules. `wire` is pure JVM and owns the protocol — message model, `DataOutputStream` encoding, and stream framing — so both apps compile against one definition and cannot drift apart. `glass` runs an RFCOMM *server* and renders. `phone` runs a `NotificationListenerService`, does all filtering and tiering, and is the RFCOMM *client* that owns reconnection. State transfer is whole-snapshot, never deltas.

**Tech Stack:** Java 8, Gradle 8.9, AGP 8.7.0, JUnit 4.13.2, plain Android framework APIs only.

**Spec:** `docs/superpowers/specs/2026-08-04-glass-notifications-design.md`. Section references below (§n) point into it.

## Global Constraints

- **No AndroidX, anywhere.** `android.useAndroidX=false` is project-wide. Both apps use plain framework `Activity`/`View`/`Service`. No support libraries, no Jetpack, no `androidx.*` imports. (§4.4)
- **No Kotlin.** Java only, matching the gesture launcher.
- **Java 8 bytecode.** `sourceCompatibility`/`targetCompatibility = VERSION_1_8`; `options.release.set(8)` on pure-JVM modules only.
- **`wire` has zero `android.*` imports.** Enforced by Task 3's test. It is a `java-library`, not an Android module.
- **compileSdk 34** for both app modules.
- **`glass`: minSdk 22, targetSdk 22.** **`phone`: minSdk 26, targetSdk 28.**
- **Glass display palette: pure `#000000` and pure `#FFFFFF` only.** No greys, no gradients, no icons, no images. Mid-tones are invisible on see-through optics. (§9.1)
- **Glass text sizes are in `dp`, never `sp`** — the layout is fixed and must not reflow under a font-scale setting. (§9.1)
- **Protocol constants are fixed:** version `1`, max frame `65536` bytes, max items `20`, max text `240` chars, max title `80` chars.
- **RFCOMM service UUID:** `7d9313f0-110b-4d84-8daa-10389eba6b55`. Do not regenerate.
- **Base package:** `dev.erinlkolp.glassnotify`. Application IDs: `dev.erinlkolp.glassnotify.glass` and `dev.erinlkolp.glassnotify.phone`.
- **JDK:** 21 (`openjdk-21-jdk-headless`). The JRE-only package lacks `lib/ct.sym` and breaks `options.release`.
- **The d8 enum NPE does not apply to this project.** Neither module dexes manually. `Tier` is an ordinary enum. (§12.6)

## Device Facts

| | Glass | V30 |
|---|---|---|
| adb serial | `0123456789ABCDEF` | `VS9967edd915b` |
| Bluetooth MAC | `22:22:41:C5:E5:67` | `10:F1:F2:EE:90:8F` |
| BT name | `Glass 1` | `V30` |
| API | 22 | 28 |

Both are reachable now. **They are not yet bonded** — pair them manually before Task 13.

## File Structure

```
google-glass-notifications/
├── settings.gradle.kts          include :wire, :glass, :phone
├── build.gradle.kts             AGP 8.7.0 apply false
├── gradle.properties            android.useAndroidX=false
├── gradle/wrapper/              Gradle 8.9
│
├── wire/                        pure JVM (java-library)
│   └── .../wire/
│       ├── Protocol.java            constants: version, limits, UUID
│       ├── MessageType.java         HELLO / SNAPSHOT / PING codes
│       ├── ProtocolException.java   extends IOException
│       ├── Tier.java                enum INTERRUPT / QUEUE with wire codes
│       ├── NotificationItem.java    immutable item
│       ├── Snapshot.java            id + unmodifiable item list
│       ├── Hello.java               handshake payload
│       ├── Frame.java               version + type + body bytes
│       ├── FrameCodec.java          length-prefixed framing
│       ├── HelloCodec.java          HELLO body encode/decode
│       └── SnapshotCodec.java       SNAPSHOT body encode/decode
│
├── glass/                       Android app, API 22
│   └── .../glass/
│       ├── QueueCursor.java         pure: index + clamping
│       ├── SwipeDetector.java       pure: swipe/tap from samples
│       ├── TouchSample.java         pure: x, y, timestamp
│       ├── Swipe.java               pure: enum NONE/TAP/FORWARD/BACK
│       ├── SnapshotStore.java       current snapshot + disk cache + staleness
│       ├── PeerPin.java             trust-on-first-use MAC pin
│       ├── CardRenderer.java        builds the black/white card View tree
│       ├── QueueActivity.java       one-per-screen browsing
│       ├── InterruptOverlay.java    TYPE_SYSTEM_ALERT + wakelock
│       ├── LinkServerService.java   RFCOMM accept loop
│       ├── BootReceiver.java        starts the service at boot
│       └── DebugInjectReceiver.java fake feed for phone-free development
│
└── phone/                       Android app, API 28
    └── .../phone/
        ├── SourceNotification.java  pure: Android-free notification data
        ├── AllowRule.java           pure: package -> tier
        ├── SnapshotBuilder.java     pure: filter, tier, truncate, sort, cap
        ├── Backoff.java             pure: exponential delay sequence
        ├── AllowlistStore.java      SharedPreferences persistence
        ├── SbnMapper.java           StatusBarNotification -> SourceNotification
        ├── NotifyListenerService.java  NotificationListenerService
        ├── LinkClientService.java   foreground service, RFCOMM client
        ├── AclReceiver.java         ACTION_ACL_CONNECTED fast reconnect
        ├── SetupActivity.java       permission/grant/bond status
        └── AllowlistActivity.java   per-app tier configuration
```

**Boundary rationale.** Every file above the dotted line in each module is pure logic with no Android imports, so it is unit-testable on the host JVM at full speed. Android types are confined to the service/activity/receiver classes at the edges. `SnapshotBuilder` and `SbnMapper` are split for exactly this reason: `StatusBarNotification` cannot be constructed in a host test, so mapping happens in one thin class and every interesting decision happens in a pure one.

---

## Task 1: Project scaffold and `wire` model

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`
- Create: `gradle/wrapper/gradle-wrapper.properties`, `gradle/wrapper/gradle-wrapper.jar`, `gradlew`, `gradlew.bat`
- Create: `wire/build.gradle.kts`
- Create: `wire/src/main/java/dev/erinlkolp/glassnotify/wire/{Protocol,MessageType,ProtocolException,Tier,NotificationItem,Snapshot,Hello}.java`
- Test: `wire/src/test/java/dev/erinlkolp/glassnotify/wire/{TierTest,NotificationItemTest,SnapshotTest}.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `Protocol.VERSION:int`, `Protocol.MAX_FRAME_BYTES:int`, `Protocol.MAX_ITEMS:int`, `Protocol.MAX_TEXT_CHARS:int`, `Protocol.MAX_TITLE_CHARS:int`, `Protocol.SERVICE_UUID:java.util.UUID`, `Protocol.SERVICE_NAME:String`; `MessageType.HELLO/SNAPSHOT/PING:int`; `ProtocolException(String)`; `Tier.INTERRUPT/QUEUE`, `Tier.code:int`, `Tier.fromCode(int):Tier` (returns `null` if unknown); `NotificationItem(String key, String appLabel, String title, String text, long postedAt, Tier tier)` with public final fields of those names; `Snapshot(long snapshotId, List<NotificationItem> items)` with `Snapshot.snapshotId:long` and `Snapshot.items:List<NotificationItem>` (unmodifiable); `Hello(String deviceName, String deviceAddress)` with public final fields.

- [ ] **Step 1: Copy the Gradle wrapper from the reference project**

The gesture launcher already has a working wrapper on this machine. Copying it avoids a network bootstrap and guarantees the same Gradle version.

```bash
cd /home/ekolp/workspace/google-glass-notifications
mkdir -p gradle/wrapper
cp /home/ekolp/workspace/google-glass-gesture-launcher/gradlew .
cp /home/ekolp/workspace/google-glass-gesture-launcher/gradlew.bat .
cp /home/ekolp/workspace/google-glass-gesture-launcher/gradle/wrapper/gradle-wrapper.jar gradle/wrapper/
cp /home/ekolp/workspace/google-glass-gesture-launcher/gradle/wrapper/gradle-wrapper.properties gradle/wrapper/
chmod +x gradlew
grep distributionUrl gradle/wrapper/gradle-wrapper.properties
```

Expected: `gradle-8.9-bin.zip`

- [ ] **Step 2: Write the root build files**

`settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "glass-notifications"
include(":wire")
include(":glass")
include(":phone")
```

`build.gradle.kts`:

```kotlin
plugins {
    id("com.android.application") version "8.7.0" apply false
}
```

`gradle.properties`:

```properties
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
android.useAndroidX=false
org.gradle.parallel=true
```

`wire/build.gradle.kts`:

```kotlin
plugins { id("java-library") }

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

tasks.withType<JavaCompile>().configureEach { options.release.set(8) }

dependencies { testImplementation("junit:junit:4.13.2") }
```

- [ ] **Step 3: Write the failing tests**

`wire/src/test/java/dev/erinlkolp/glassnotify/wire/TierTest.java`:

```java
package dev.erinlkolp.glassnotify.wire;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class TierTest {

    @Test
    public void codesAreStableOnTheWire() {
        // These numbers are protocol, not implementation detail. Changing them
        // breaks compatibility with any already-installed build.
        assertEquals(1, Tier.INTERRUPT.code);
        assertEquals(2, Tier.QUEUE.code);
    }

    @Test
    public void roundTripsThroughItsCode() {
        for (Tier tier : Tier.values()) {
            assertEquals(tier, Tier.fromCode(tier.code));
        }
    }

    @Test
    public void unknownCodeReturnsNullRatherThanThrowing() {
        // Decoders turn this into a ProtocolException with useful context;
        // the enum itself stays free of IO concerns.
        assertNull(Tier.fromCode(0));
        assertNull(Tier.fromCode(99));
        assertNull(Tier.fromCode(-1));
    }
}
```

`wire/src/test/java/dev/erinlkolp/glassnotify/wire/NotificationItemTest.java`:

```java
package dev.erinlkolp.glassnotify.wire;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.fail;

import org.junit.Test;

public class NotificationItemTest {

    private static NotificationItem item(String key) {
        return new NotificationItem(key, "Signal", "Jordan Reyes", "are you still good for 7pm?",
                1785870000000L, Tier.INTERRUPT);
    }

    @Test
    public void exposesItsFields() {
        NotificationItem i = item("k1");
        assertEquals("k1", i.key);
        assertEquals("Signal", i.appLabel);
        assertEquals("Jordan Reyes", i.title);
        assertEquals("are you still good for 7pm?", i.text);
        assertEquals(1785870000000L, i.postedAt);
        assertEquals(Tier.INTERRUPT, i.tier);
    }

    @Test
    public void equalityIsByValue() {
        assertEquals(item("k1"), item("k1"));
        assertEquals(item("k1").hashCode(), item("k1").hashCode());
        assertNotEquals(item("k1"), item("k2"));
    }

    @Test
    public void rejectsNullKey() {
        try {
            new NotificationItem(null, "Signal", "t", "x", 1L, Tier.QUEUE);
            fail("expected NullPointerException");
        } catch (NullPointerException expected) {
            // A null key would break identity matching on the Glass side.
        }
    }

    @Test
    public void rejectsNullTier() {
        try {
            new NotificationItem("k", "Signal", "t", "x", 1L, null);
            fail("expected NullPointerException");
        } catch (NullPointerException expected) {
        }
    }
}
```

`wire/src/test/java/dev/erinlkolp/glassnotify/wire/SnapshotTest.java`:

```java
package dev.erinlkolp.glassnotify.wire;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class SnapshotTest {

    private static NotificationItem item(String key) {
        return new NotificationItem(key, "Signal", "t", "x", 1L, Tier.QUEUE);
    }

    @Test
    public void exposesIdAndItems() {
        Snapshot s = new Snapshot(7L, Arrays.asList(item("a"), item("b")));
        assertEquals(7L, s.snapshotId);
        assertEquals(2, s.items.size());
        assertEquals("a", s.items.get(0).key);
    }

    @Test
    public void itemsAreUnmodifiable() {
        Snapshot s = new Snapshot(1L, new ArrayList<NotificationItem>(Arrays.asList(item("a"))));
        try {
            s.items.add(item("b"));
            fail("expected UnsupportedOperationException");
        } catch (UnsupportedOperationException expected) {
        }
    }

    @Test
    public void defensivelyCopiesTheCallersList() {
        List<NotificationItem> source = new ArrayList<NotificationItem>();
        source.add(item("a"));
        Snapshot s = new Snapshot(1L, source);
        source.add(item("b"));
        assertEquals("later mutation of the caller's list must not leak in", 1, s.items.size());
    }

    @Test
    public void emptySnapshotIsLegal() {
        // "You have nothing waiting" is a real state the phone must be able to send.
        assertEquals(0, new Snapshot(1L, new ArrayList<NotificationItem>()).items.size());
    }
}
```

- [ ] **Step 4: Run the tests to verify they fail**

Run: `./gradlew :wire:test`
Expected: FAIL — compilation errors, `Tier`/`NotificationItem`/`Snapshot` do not exist.

- [ ] **Step 5: Write `Protocol.java`**

```java
package dev.erinlkolp.glassnotify.wire;

import java.util.UUID;

/** Wire-protocol constants shared by both apps. */
public final class Protocol {

    /** Bumped on any incompatible change to framing or message bodies. */
    public static final int VERSION = 1;

    /**
     * Frames larger than this are rejected before any allocation, so a corrupted
     * length field cannot be turned into an OutOfMemoryError.
     */
    public static final int MAX_FRAME_BYTES = 64 * 1024;

    /** Snapshots carry at most this many items. */
    public static final int MAX_ITEMS = 20;

    /** Body text is truncated to this many characters by the phone, before sending. */
    public static final int MAX_TEXT_CHARS = 240;

    /** Titles are truncated to this many characters by the phone, before sending. */
    public static final int MAX_TITLE_CHARS = 80;

    /** SDP service record name advertised by the Glass server socket. */
    public static final String SERVICE_NAME = "GlassNotify";

    /** Fixed for the life of the project. Regenerating it breaks installed builds. */
    public static final UUID SERVICE_UUID =
            UUID.fromString("7d9313f0-110b-4d84-8daa-10389eba6b55");

    private Protocol() {
    }
}
```

- [ ] **Step 6: Write `MessageType.java`, `ProtocolException.java`, `Tier.java`**

```java
package dev.erinlkolp.glassnotify.wire;

/** Frame type codes. */
public final class MessageType {

    public static final int HELLO = 1;
    public static final int SNAPSHOT = 2;
    public static final int PING = 3;

    private MessageType() {
    }
}
```

```java
package dev.erinlkolp.glassnotify.wire;

import java.io.IOException;

/**
 * Thrown when bytes on the wire violate the protocol. Extends IOException so
 * callers can treat it like any other stream failure: close the socket and
 * reconnect. Mid-stream resynchronisation is never attempted.
 */
public class ProtocolException extends IOException {

    private static final long serialVersionUID = 1L;

    public ProtocolException(String message) {
        super(message);
    }
}
```

```java
package dev.erinlkolp.glassnotify.wire;

/** How aggressively Glass should present a notification. Decided on the phone. */
public enum Tier {

    /** Wakes the Glass display briefly. */
    INTERRUPT(1),

    /** Lands silently; visible only when the queue is opened. */
    QUEUE(2);

    /** Stable on-the-wire code. Not the ordinal — reordering the enum must be safe. */
    public final int code;

    Tier(int code) {
        this.code = code;
    }

    /** Returns null for an unrecognised code; decoders convert that to a ProtocolException. */
    public static Tier fromCode(int code) {
        for (Tier tier : values()) {
            if (tier.code == code) {
                return tier;
            }
        }
        return null;
    }
}
```

- [ ] **Step 7: Write `NotificationItem.java`, `Snapshot.java`, `Hello.java`**

```java
package dev.erinlkolp.glassnotify.wire;

/** One notification, already filtered, tiered and truncated by the phone. */
public final class NotificationItem {

    /** StatusBarNotification.getKey() — stable identity across updates. */
    public final String key;

    /** Human-readable app name. Resolved on the phone; Glass never renders icons. */
    public final String appLabel;

    public final String title;
    public final String text;

    /** Epoch millis, from the phone's clock. */
    public final long postedAt;

    public final Tier tier;

    public NotificationItem(String key, String appLabel, String title, String text,
            long postedAt, Tier tier) {
        if (key == null) {
            throw new NullPointerException("key");
        }
        if (appLabel == null) {
            throw new NullPointerException("appLabel");
        }
        if (title == null) {
            throw new NullPointerException("title");
        }
        if (text == null) {
            throw new NullPointerException("text");
        }
        if (tier == null) {
            throw new NullPointerException("tier");
        }
        this.key = key;
        this.appLabel = appLabel;
        this.title = title;
        this.text = text;
        this.postedAt = postedAt;
        this.tier = tier;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof NotificationItem)) {
            return false;
        }
        NotificationItem other = (NotificationItem) o;
        return postedAt == other.postedAt
                && key.equals(other.key)
                && appLabel.equals(other.appLabel)
                && title.equals(other.title)
                && text.equals(other.text)
                && tier == other.tier;
    }

    @Override
    public int hashCode() {
        int result = key.hashCode();
        result = 31 * result + appLabel.hashCode();
        result = 31 * result + title.hashCode();
        result = 31 * result + text.hashCode();
        result = 31 * result + (int) (postedAt ^ (postedAt >>> 32));
        result = 31 * result + tier.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return "NotificationItem{" + appLabel + " / " + title + " / " + tier + "}";
    }
}
```

```java
package dev.erinlkolp.glassnotify.wire;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The complete notification state at a moment in time. Glass replaces whatever
 * it was holding with this; there are no deltas to reconcile. See spec section 6.
 */
public final class Snapshot {

    /** Monotonically increasing on the phone. Useful for logs; not used for ordering. */
    public final long snapshotId;

    /** Newest first, as ordered by the phone. Unmodifiable. */
    public final List<NotificationItem> items;

    public Snapshot(long snapshotId, List<NotificationItem> items) {
        if (items == null) {
            throw new NullPointerException("items");
        }
        this.snapshotId = snapshotId;
        this.items = Collections.unmodifiableList(new ArrayList<NotificationItem>(items));
    }

    @Override
    public String toString() {
        return "Snapshot{id=" + snapshotId + ", items=" + items.size() + "}";
    }
}
```

```java
package dev.erinlkolp.glassnotify.wire;

/** Handshake sent by the phone immediately after connecting. */
public final class Hello {

    public final String deviceName;
    public final String deviceAddress;

    public Hello(String deviceName, String deviceAddress) {
        if (deviceName == null) {
            throw new NullPointerException("deviceName");
        }
        if (deviceAddress == null) {
            throw new NullPointerException("deviceAddress");
        }
        this.deviceName = deviceName;
        this.deviceAddress = deviceAddress;
    }

    @Override
    public String toString() {
        return "Hello{" + deviceName + " " + deviceAddress + "}";
    }
}
```

- [ ] **Step 8: Run the tests to verify they pass**

Run: `./gradlew :wire:test`
Expected: PASS, 11 tests.

- [ ] **Step 9: Commit**

```bash
git add settings.gradle.kts build.gradle.kts gradle.properties gradlew gradlew.bat gradle/ wire/
git commit -m "feat(wire): add project scaffold and protocol model

Three-module Gradle project matching the gesture launcher's shape. The
wire module is a plain java-library with no android imports, so all
protocol logic is testable on the host JVM.

Tier carries an explicit wire code rather than relying on ordinal, so
reordering the enum stays safe. Snapshot defensively copies and wraps
its item list - the phone builds these on a background thread and Glass
holds them across a redraw, so shared mutable state would be a hazard."
```

---

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

## Task 3: Message bodies

**Files:**
- Create: `wire/src/main/java/dev/erinlkolp/glassnotify/wire/HelloCodec.java`
- Create: `wire/src/main/java/dev/erinlkolp/glassnotify/wire/SnapshotCodec.java`
- Test: `wire/src/test/java/dev/erinlkolp/glassnotify/wire/HelloCodecTest.java`
- Test: `wire/src/test/java/dev/erinlkolp/glassnotify/wire/SnapshotCodecTest.java`
- Test: `wire/src/test/java/dev/erinlkolp/glassnotify/wire/NoAndroidImportsTest.java`

**Interfaces:**
- Consumes: everything from Tasks 1 and 2.
- Produces: `HelloCodec.encode(Hello):byte[]`, `HelloCodec.decode(byte[]):Hello`, `SnapshotCodec.encode(Snapshot):byte[]`, `SnapshotCodec.decode(byte[]):Snapshot`.

- [ ] **Step 1: Write the failing tests**

```java
package dev.erinlkolp.glassnotify.wire;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.io.IOException;

import org.junit.Test;

public class HelloCodecTest {

    @Test
    public void roundTrips() throws IOException {
        Hello decoded = HelloCodec.decode(HelloCodec.encode(new Hello("V30", "10:F1:F2:EE:90:8F")));

        assertEquals("V30", decoded.deviceName);
        assertEquals("10:F1:F2:EE:90:8F", decoded.deviceAddress);
    }

    @Test
    public void handlesNonAsciiNames() throws IOException {
        // Bluetooth device names are user-editable and routinely contain emoji.
        Hello decoded = HelloCodec.decode(HelloCodec.encode(new Hello("Erin's über phone ✨", "AA:BB:CC:DD:EE:FF")));

        assertEquals("Erin's über phone ✨", decoded.deviceName);
    }

    @Test
    public void rejectsTruncatedInput() {
        try {
            HelloCodec.decode(new byte[] {0, 5, 65});
            fail("expected IOException");
        } catch (IOException expected) {
        }
    }
}
```

```java
package dev.erinlkolp.glassnotify.wire;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class SnapshotCodecTest {

    private static NotificationItem item(String key, Tier tier) {
        return new NotificationItem(key, "Signal", "Jordan Reyes",
                "are you still good for 7pm?", 1785870000000L, tier);
    }

    @Test
    public void roundTripsAPopulatedSnapshot() throws IOException {
        Snapshot original = new Snapshot(42L,
                Arrays.asList(item("a", Tier.INTERRUPT), item("b", Tier.QUEUE)));

        Snapshot decoded = SnapshotCodec.decode(SnapshotCodec.encode(original));

        assertEquals(42L, decoded.snapshotId);
        assertEquals(2, decoded.items.size());
        assertEquals(item("a", Tier.INTERRUPT), decoded.items.get(0));
        assertEquals(item("b", Tier.QUEUE), decoded.items.get(1));
    }

    @Test
    public void roundTripsAnEmptySnapshot() throws IOException {
        Snapshot decoded = SnapshotCodec.decode(
                SnapshotCodec.encode(new Snapshot(1L, new ArrayList<NotificationItem>())));

        assertEquals(0, decoded.items.size());
    }

    @Test
    public void preservesOrder() throws IOException {
        List<NotificationItem> items = new ArrayList<NotificationItem>();
        for (int i = 0; i < Protocol.MAX_ITEMS; i++) {
            items.add(item("key-" + i, Tier.QUEUE));
        }

        Snapshot decoded = SnapshotCodec.decode(SnapshotCodec.encode(new Snapshot(1L, items)));

        for (int i = 0; i < Protocol.MAX_ITEMS; i++) {
            assertEquals("key-" + i, decoded.items.get(i).key);
        }
    }

    @Test
    public void aFullSnapshotFitsComfortablyInOneFrame() throws IOException {
        // Spec section 6 claims roughly 3KB. If that assumption ever breaks,
        // the snapshot-per-change design needs revisiting, so assert it.
        List<NotificationItem> items = new ArrayList<NotificationItem>();
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < Protocol.MAX_TEXT_CHARS; i++) {
            text.append('x');
        }
        for (int i = 0; i < Protocol.MAX_ITEMS; i++) {
            items.add(new NotificationItem("key-" + i, "Signal", "Jordan Reyes",
                    text.toString(), 1785870000000L, Tier.QUEUE));
        }

        int size = SnapshotCodec.encode(new Snapshot(1L, items)).length;

        assertTrue("worst-case snapshot was " + size + " bytes", size < 8 * 1024);
        assertTrue(size < Protocol.MAX_FRAME_BYTES);
    }

    @Test
    public void rejectsAnItemCountOverTheCap() throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(raw);
        d.writeLong(1L);
        d.writeShort(Protocol.MAX_ITEMS + 1);

        try {
            SnapshotCodec.decode(raw.toByteArray());
            fail("expected ProtocolException");
        } catch (ProtocolException expected) {
        }
    }

    @Test
    public void rejectsANegativeItemCount() throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(raw);
        d.writeLong(1L);
        d.writeShort(-1);

        try {
            SnapshotCodec.decode(raw.toByteArray());
            fail("expected ProtocolException");
        } catch (ProtocolException expected) {
        }
    }

    @Test
    public void rejectsAnUnknownTierCode() throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(raw);
        d.writeLong(1L);
        d.writeShort(1);
        d.writeUTF("k");
        d.writeUTF("Signal");
        d.writeUTF("title");
        d.writeUTF("text");
        d.writeLong(1L);
        d.writeByte(77); // not a Tier

        try {
            SnapshotCodec.decode(raw.toByteArray());
            fail("expected ProtocolException");
        } catch (ProtocolException expected) {
        }
    }

    @Test
    public void refusesToEncodeMoreThanTheCap() {
        List<NotificationItem> items = new ArrayList<NotificationItem>();
        for (int i = 0; i < Protocol.MAX_ITEMS + 1; i++) {
            items.add(item("key-" + i, Tier.QUEUE));
        }

        try {
            SnapshotCodec.encode(new Snapshot(1L, items));
            fail("expected ProtocolException");
        } catch (IOException expected) {
            // The phone caps before building; this is the belt-and-braces check.
        }
    }
}
```

`NoAndroidImportsTest.java` — makes the module's central constraint executable rather than aspirational:

```java
package dev.erinlkolp.glassnotify.wire;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/**
 * The wire module must stay free of Android types so it can be tested on the
 * host JVM at full speed. A stray android.* import would compile fine here and
 * only fail much later, so check it directly.
 */
public class NoAndroidImportsTest {

    @Test
    public void noSourceFileImportsAndroid() throws IOException {
        File sourceRoot = new File("src/main/java");
        assertTrue("expected to run with the module as working directory", sourceRoot.isDirectory());

        List<String> offenders = new ArrayList<String>();
        collect(sourceRoot, offenders);

        if (!offenders.isEmpty()) {
            fail("android imports found in wire: " + offenders);
        }
    }

    private void collect(File dir, List<String> offenders) throws IOException {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                collect(child, offenders);
            } else if (child.getName().endsWith(".java")) {
                BufferedReader reader = new BufferedReader(new FileReader(child));
                try {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        String trimmed = line.trim();
                        if (trimmed.startsWith("import android.")
                                || trimmed.startsWith("import androidx.")) {
                            offenders.add(child.getName() + ": " + trimmed);
                        }
                    }
                } finally {
                    reader.close();
                }
            }
        }
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :wire:test`
Expected: FAIL — `HelloCodec` and `SnapshotCodec` do not exist. (`NoAndroidImportsTest` will already pass; that is fine.)

- [ ] **Step 3: Write `HelloCodec.java`**

```java
package dev.erinlkolp.glassnotify.wire;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/** Encodes and decodes the HELLO frame body. */
public final class HelloCodec {

    private HelloCodec() {
    }

    public static byte[] encode(Hello hello) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeUTF(hello.deviceName);
        out.writeUTF(hello.deviceAddress);
        out.flush();
        return bytes.toByteArray();
    }

    public static Hello decode(byte[] body) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(body));
        String name = in.readUTF();
        String address = in.readUTF();
        return new Hello(name, address);
    }
}
```

- [ ] **Step 4: Write `SnapshotCodec.java`**

```java
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
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :wire:test`
Expected: PASS, 36 tests total.

- [ ] **Step 6: Commit**

```bash
git add wire/
git commit -m "feat(wire): add HELLO and SNAPSHOT body codecs

Both use writeUTF, which handles non-ASCII device names and message text
without any escaping logic of our own.

Decoders validate the item count before allocating and reject unknown
tier codes rather than silently defaulting, so a protocol mismatch
surfaces as a clear error instead of a notification appearing in the
wrong tier.

Also asserts the worst-case full snapshot stays under 8KB. The
snapshot-per-change design in spec section 6 rests on that being cheap,
so it is worth failing loudly if it ever stops being true.

NoAndroidImportsTest makes the module's zero-Android-dependency rule
executable instead of aspirational."
```

---

## Task 4: `glass` scaffold and queue cursor

The cursor is small but carries the edge case that full-state snapshots make routine: the list can shrink underneath the reader. (§12.3)

**Files:**
- Create: `glass/build.gradle.kts`
- Create: `glass/src/main/AndroidManifest.xml`
- Create: `glass/src/main/res/values/strings.xml`
- Create: `glass/src/main/java/dev/erinlkolp/glassnotify/glass/QueueCursor.java`
- Test: `glass/src/test/java/dev/erinlkolp/glassnotify/glass/QueueCursorTest.java`

**Interfaces:**
- Consumes: `:wire` as a project dependency.
- Produces: `QueueCursor()` no-arg constructor; `setSize(int):void`; `index():int`; `size():int`; `next():boolean`; `previous():boolean`; `isEmpty():boolean`.

- [ ] **Step 1: Write `glass/build.gradle.kts`**

```kotlin
plugins { id("com.android.application") }

android {
    namespace = "dev.erinlkolp.glassnotify.glass"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.erinlkolp.glassnotify.glass"
        minSdk = 22
        targetSdk = 22
        versionCode = 1
        versionName = "0.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }
}

dependencies {
    implementation(project(":wire"))
    testImplementation("junit:junit:4.13.2")
}
```

- [ ] **Step 2: Write the manifest and strings**

`glass/src/main/AndroidManifest.xml` — permissions for everything the module will need across Tasks 5-8, declared now so later tasks only add components:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.BLUETOOTH" />
    <uses-permission android:name="android.permission.BLUETOOTH_ADMIN" />
    <uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
    <uses-permission android:name="android.permission.WAKE_LOCK" />
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />

    <application
        android:allowBackup="false"
        android:label="@string/app_name" />

</manifest>
```

`glass/src/main/res/values/strings.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">Notifications</string>
    <string name="empty_queue">Nothing waiting</string>
    <string name="stale_queue">Not connected</string>
    <string name="version_mismatch">Phone app out of date</string>
</resources>
```

- [ ] **Step 3: Write the failing test**

```java
package dev.erinlkolp.glassnotify.glass;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

public class QueueCursorTest {

    private QueueCursor cursor;

    @Before
    public void setUp() {
        cursor = new QueueCursor();
    }

    @Test
    public void startsEmpty() {
        assertTrue(cursor.isEmpty());
        assertEquals(0, cursor.size());
        assertEquals(0, cursor.index());
    }

    @Test
    public void movesForwardAndBackward() {
        cursor.setSize(3);

        assertEquals(0, cursor.index());
        assertTrue(cursor.next());
        assertEquals(1, cursor.index());
        assertTrue(cursor.next());
        assertEquals(2, cursor.index());
        assertTrue(cursor.previous());
        assertEquals(1, cursor.index());
    }

    @Test
    public void doesNotWrapAtEitherEnd() {
        // Wrapping on a head-mounted display is disorienting - you lose track
        // of whether you have seen everything. Stop at the ends instead.
        cursor.setSize(2);

        assertFalse(cursor.previous());
        assertEquals(0, cursor.index());

        cursor.next();
        assertFalse(cursor.next());
        assertEquals(1, cursor.index());
    }

    @Test
    public void clampsWhenTheListShrinksUnderTheReader() {
        // THE case that full-state snapshots make routine: reading item 5 of 7
        // when a snapshot arrives holding only 3. Spec section 12.3.
        cursor.setSize(7);
        cursor.next();
        cursor.next();
        cursor.next();
        cursor.next();
        assertEquals(4, cursor.index());

        cursor.setSize(3);

        assertEquals("must clamp to the last valid index, not throw", 2, cursor.index());
    }

    @Test
    public void clampsToZeroWhenEverythingIsDismissed() {
        cursor.setSize(5);
        cursor.next();
        cursor.next();

        cursor.setSize(0);

        assertEquals(0, cursor.index());
        assertTrue(cursor.isEmpty());
    }

    @Test
    public void holdsPositionWhenTheListGrows() {
        // New notifications arrive at the head, but the reader's position is
        // an index. Growing the list must not silently move what they are reading.
        cursor.setSize(3);
        cursor.next();
        assertEquals(1, cursor.index());

        cursor.setSize(9);

        assertEquals(1, cursor.index());
    }

    @Test
    public void navigationOnAnEmptyQueueIsANoOp() {
        assertFalse(cursor.next());
        assertFalse(cursor.previous());
        assertEquals(0, cursor.index());
    }

    @Test
    public void rejectsANegativeSize() {
        try {
            cursor.setSize(-1);
            org.junit.Assert.fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
        }
    }
}
```

- [ ] **Step 4: Run the test to verify it fails**

Run: `./gradlew :glass:testDebugUnitTest`
Expected: FAIL — `QueueCursor` does not exist.

- [ ] **Step 5: Write `QueueCursor.java`**

```java
package dev.erinlkolp.glassnotify.glass;

/**
 * Tracks which queue item is on screen.
 *
 * Deliberately free of Android types so it can be unit tested on the host JVM.
 * The interesting behaviour is clamping: because the phone sends whole
 * snapshots rather than deltas, the list can shrink while the wearer is part
 * way through it, and that must never throw.
 */
public final class QueueCursor {

    private int index;
    private int size;

    /** Applies a new item count, clamping the current position into range. */
    public void setSize(int newSize) {
        if (newSize < 0) {
            throw new IllegalArgumentException("size must not be negative: " + newSize);
        }
        this.size = newSize;
        clamp();
    }

    public int index() {
        return index;
    }

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    /** Returns true if the position actually moved. */
    public boolean next() {
        if (index + 1 >= size) {
            return false;
        }
        index++;
        return true;
    }

    /** Returns true if the position actually moved. */
    public boolean previous() {
        if (index <= 0) {
            return false;
        }
        index--;
        return true;
    }

    private void clamp() {
        if (size == 0) {
            index = 0;
        } else if (index >= size) {
            index = size - 1;
        }
    }
}
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `./gradlew :glass:testDebugUnitTest`
Expected: PASS, 8 tests.

- [ ] **Step 7: Verify the module assembles for the device**

Run: `./gradlew :glass:assembleDebug`
Expected: BUILD SUCCESSFUL. This proves the AGP setup, the `:wire` dependency, and the no-AndroidX configuration all work together before any Android code is written.

- [ ] **Step 8: Commit**

```bash
git add glass/
git commit -m "feat(glass): add module scaffold and queue cursor

QueueCursor holds no Android types, so it is unit tested on the host JVM.
Its real job is clamping: whole-snapshot transfer means the list can
shrink while the wearer is mid-queue, and reading item 5 of 7 when a
3-item snapshot lands must clamp rather than throw.

Deliberately does not wrap at either end - wrapping on a head-mounted
display makes it impossible to tell whether you have seen everything.

Growing the list holds the reader's index rather than chasing the newest
item, so an arriving notification does not yank what they are reading."
```

---

## Task 5: Snapshot store and peer pinning

**Files:**
- Create: `glass/src/main/java/dev/erinlkolp/glassnotify/glass/SnapshotStore.java`
- Create: `glass/src/main/java/dev/erinlkolp/glassnotify/glass/PeerPin.java`
- Test: `glass/src/test/java/dev/erinlkolp/glassnotify/glass/StalenessTest.java`

**Interfaces:**
- Consumes: `Snapshot`, `SnapshotCodec`, `Protocol` from `:wire`.
- Produces: `SnapshotStore(File cacheFile)`; `current():Snapshot` (never null — returns an empty snapshot before anything arrives); `apply(Snapshot):void`; `lastUpdatedElapsedMs():long`; `markContact():void`; `isStale(long nowElapsedMs):boolean`; `load():void`; `SnapshotStore.STALE_AFTER_MS:long`; static `SnapshotStore.isStale(long lastContactElapsedMs, long nowElapsedMs):boolean`. `PeerPin(SharedPreferences prefs)`; `isAllowed(String address):boolean`; `pinIfUnset(String address):void`; `pinnedAddress():String`; `clear():void`.

- [ ] **Step 1: Write the failing test**

Only the staleness rule is pure logic; the rest touches `File` and `SharedPreferences` and is covered on hardware in Task 13.

```java
package dev.erinlkolp.glassnotify.glass;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class StalenessTest {

    @Test
    public void freshContactIsNotStale() {
        assertFalse(SnapshotStore.isStale(1_000L, 1_000L));
        assertFalse(SnapshotStore.isStale(1_000L, 5_000L));
    }

    @Test
    public void goesStaleAfterTheThreshold() {
        long lastContact = 1_000L;
        assertFalse(SnapshotStore.isStale(lastContact, lastContact + SnapshotStore.STALE_AFTER_MS - 1));
        assertTrue(SnapshotStore.isStale(lastContact, lastContact + SnapshotStore.STALE_AFTER_MS));
        assertTrue(SnapshotStore.isStale(lastContact, lastContact + 600_000L));
    }

    @Test
    public void aClockThatWentBackwardsIsNotTreatedAsStale() {
        // elapsedRealtime should never go backwards, but a bug that made it
        // appear to must not silently blank the queue.
        assertFalse(SnapshotStore.isStale(10_000L, 9_000L));
    }

    @Test
    public void neverContactedIsStale() {
        // Sentinel: nothing has ever arrived, so whatever is cached on disk
        // came from a previous boot and must be labelled.
        assertTrue(SnapshotStore.isStale(SnapshotStore.NEVER, 0L));
        assertTrue(SnapshotStore.isStale(SnapshotStore.NEVER, 500_000L));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :glass:testDebugUnitTest --tests '*StalenessTest*'`
Expected: FAIL — `SnapshotStore` does not exist.

- [ ] **Step 3: Write `SnapshotStore.java`**

```java
package dev.erinlkolp.glassnotify.glass;

import android.os.SystemClock;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import dev.erinlkolp.glassnotify.wire.NotificationItem;
import dev.erinlkolp.glassnotify.wire.Snapshot;
import dev.erinlkolp.glassnotify.wire.SnapshotCodec;

/**
 * Holds the current snapshot and mirrors it to disk.
 *
 * The cache exists so a Bluetooth dropout or a service restart still leaves
 * already-received notifications readable. Anything read back from disk is
 * stale by definition until the phone makes contact again.
 */
public final class SnapshotStore {

    private static final String TAG = "GlassNotify";

    /** Sentinel for "the phone has never made contact in this process". */
    public static final long NEVER = Long.MIN_VALUE;

    /** Spec section 7.3: PING every 10s, so 30s of silence means something is wrong. */
    public static final long STALE_AFTER_MS = 30_000L;

    private static final Snapshot EMPTY =
            new Snapshot(0L, new ArrayList<NotificationItem>());

    private final File cacheFile;

    private volatile Snapshot current = EMPTY;
    private volatile long lastContactElapsedMs = NEVER;

    public SnapshotStore(File cacheFile) {
        if (cacheFile == null) {
            throw new NullPointerException("cacheFile");
        }
        this.cacheFile = cacheFile;
    }

    /** Never null. Returns an empty snapshot before anything has arrived. */
    public Snapshot current() {
        return current;
    }

    /** Replaces the whole queue and persists it. */
    public void apply(Snapshot snapshot) {
        if (snapshot == null) {
            throw new NullPointerException("snapshot");
        }
        current = snapshot;
        markContact();
        persist(snapshot);
    }

    /** Records that the phone is alive, without changing the queue. Called on PING. */
    public void markContact() {
        lastContactElapsedMs = SystemClock.elapsedRealtime();
    }

    public boolean isStale() {
        return isStale(lastContactElapsedMs, SystemClock.elapsedRealtime());
    }

    /** Pure form, so the rule is testable without an Android runtime. */
    public static boolean isStale(long lastContactElapsedMs, long nowElapsedMs) {
        if (lastContactElapsedMs == NEVER) {
            return true;
        }
        long silence = nowElapsedMs - lastContactElapsedMs;
        if (silence < 0) {
            // Clock apparently moved backwards. Do not blank the queue over it.
            return false;
        }
        return silence >= STALE_AFTER_MS;
    }

    /** Restores the cached snapshot. Safe to call when no cache exists. */
    public void load() {
        if (!cacheFile.exists()) {
            return;
        }
        FileInputStream in = null;
        try {
            in = new FileInputStream(cacheFile);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                bytes.write(buffer, 0, read);
            }
            current = SnapshotCodec.decode(bytes.toByteArray());
            // Deliberately does NOT markContact: restored data is stale until
            // the phone actually connects.
        } catch (IOException e) {
            Log.w(TAG, "discarding unreadable snapshot cache", e);
            current = EMPTY;
        } finally {
            closeQuietly(in);
        }
    }

    private void persist(Snapshot snapshot) {
        FileOutputStream out = null;
        try {
            byte[] encoded = SnapshotCodec.encode(snapshot);
            out = new FileOutputStream(cacheFile);
            out.write(encoded);
            out.flush();
        } catch (IOException e) {
            // A failed cache write must never take down the live connection.
            Log.w(TAG, "could not persist snapshot cache", e);
        } finally {
            closeQuietly(out);
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignored) {
                // Nothing useful to do.
            }
        }
    }

    /** Convenience for the UI: the items of the current snapshot. */
    public List<NotificationItem> items() {
        return current.items;
    }
}
```

- [ ] **Step 4: Write `PeerPin.java`**

```java
package dev.erinlkolp.glassnotify.glass;

import android.content.SharedPreferences;

/**
 * Trust-on-first-use pinning for the phone's Bluetooth address.
 *
 * The server socket would otherwise accept a connection from anything in range
 * that knows the service UUID, which means a stranger could push text into the
 * wearer's field of view. The first device to connect is remembered; anything
 * else is refused.
 *
 * Spec section 11.1 requires a reset path, because Glass's own address is
 * regenerated on a /data wipe and a replacement phone has a different MAC.
 * `adb shell pm clear dev.erinlkolp.glassnotify.glass` clears this.
 */
public final class PeerPin {

    private static final String KEY_ADDRESS = "pinned_peer_address";

    private final SharedPreferences prefs;

    public PeerPin(SharedPreferences prefs) {
        if (prefs == null) {
            throw new NullPointerException("prefs");
        }
        this.prefs = prefs;
    }

    /** Null until something has connected. */
    public String pinnedAddress() {
        return prefs.getString(KEY_ADDRESS, null);
    }

    /** True if nothing is pinned yet, or the address matches what is. */
    public boolean isAllowed(String address) {
        if (address == null) {
            return false;
        }
        String pinned = pinnedAddress();
        return pinned == null || pinned.equalsIgnoreCase(address);
    }

    /** Records the address if none is pinned. Does nothing otherwise. */
    public void pinIfUnset(String address) {
        if (address == null) {
            throw new NullPointerException("address");
        }
        if (pinnedAddress() == null) {
            prefs.edit().putString(KEY_ADDRESS, address).commit();
        }
    }

    public void clear() {
        prefs.edit().remove(KEY_ADDRESS).commit();
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :glass:testDebugUnitTest`
Expected: PASS, 12 tests.

- [ ] **Step 6: Commit**

```bash
git add glass/
git commit -m "feat(glass): add snapshot cache and trust-on-first-use peer pin

The disk cache keeps already-received notifications readable through a
Bluetooth dropout or a service restart. load() deliberately does not
mark contact, so restored data reads as stale until the phone actually
reconnects - showing hours-old notifications as current is worse than
showing none.

A failed cache write is logged and swallowed rather than propagated;
losing the cache must never take down a live connection.

PeerPin refuses connections from anything but the first device seen, so
a stranger in range cannot push text into the wearer's field of view.
Spec section 11.1 requires a reset path and pm clear is it."
```

---

## Task 6: Card rendering and the queue screen

Everything visual lands here. Pure black and pure white only, sizes in `dp`, and the immersive flags that stop the status bar eating downward swipes. (§9)

**Files:**
- Create: `glass/src/main/java/dev/erinlkolp/glassnotify/glass/TouchSample.java`
- Create: `glass/src/main/java/dev/erinlkolp/glassnotify/glass/Swipe.java`
- Create: `glass/src/main/java/dev/erinlkolp/glassnotify/glass/SwipeDetector.java`
- Create: `glass/src/main/java/dev/erinlkolp/glassnotify/glass/CardRenderer.java`
- Create: `glass/src/main/java/dev/erinlkolp/glassnotify/glass/QueueActivity.java`
- Modify: `glass/src/main/AndroidManifest.xml`
- Test: `glass/src/test/java/dev/erinlkolp/glassnotify/glass/SwipeDetectorTest.java`

**Interfaces:**
- Consumes: `QueueCursor`, `SnapshotStore`, `NotificationItem`, `Tier`.
- Produces: `TouchSample(float x, float y, long timeMs)` with public final fields; `Swipe.NONE/TAP/FORWARD/BACK`; `SwipeDetector()`, `SwipeDetector.begin(TouchSample):void`, `SwipeDetector.move(TouchSample):void`, `SwipeDetector.end(TouchSample):Swipe`, `SwipeDetector.cancel():void`; `CardRenderer.interruptCard(Context, NotificationItem):View`, `CardRenderer.queueCard(Context, NotificationItem, int position, int total, boolean stale):View`, `CardRenderer.messageCard(Context, String):View`.

- [ ] **Step 1: Write the failing test**

```java
package dev.erinlkolp.glassnotify.glass;

import static org.junit.Assert.assertEquals;

import org.junit.Before;
import org.junit.Test;

public class SwipeDetectorTest {

    private SwipeDetector detector;

    @Before
    public void setUp() {
        detector = new SwipeDetector();
    }

    /** Drives a full down-move-up sequence and returns the verdict. */
    private Swipe gesture(float startX, float endX, float startY, float endY, long durationMs) {
        detector.begin(new TouchSample(startX, startY, 0L));
        detector.move(new TouchSample((startX + endX) / 2f, (startY + endY) / 2f, durationMs / 2));
        return detector.end(new TouchSample(endX, endY, durationMs));
    }

    @Test
    public void aShortStillTouchIsATap() {
        assertEquals(Swipe.TAP, gesture(300f, 302f, 100f, 101f, 90L));
    }

    @Test
    public void aLongStillTouchIsNotATap() {
        // A resting finger is not an intentional tap. Long-press has no meaning
        // in a read-only queue, so it resolves to nothing.
        assertEquals(Swipe.NONE, gesture(300f, 300f, 100f, 100f, 1200L));
    }

    @Test
    public void movingForwardAlongThePadIsForward() {
        assertEquals(Swipe.FORWARD, gesture(200f, 200f + SwipeDetector.SWIPE_MIN_DX + 10f, 100f, 100f, 250L));
    }

    @Test
    public void movingBackwardAlongThePadIsBack() {
        assertEquals(Swipe.BACK, gesture(400f, 400f - SwipeDetector.SWIPE_MIN_DX - 10f, 100f, 100f, 250L));
    }

    @Test
    public void movementBelowTheThresholdIsNotASwipe() {
        assertEquals(Swipe.TAP, gesture(200f, 200f + SwipeDetector.SWIPE_MIN_DX - 5f, 100f, 100f, 120L));
    }

    @Test
    public void aStronglyVerticalDragIsIgnored() {
        // The touchpad is anisotropic: 187 native vertical units are rescaled
        // onto 360px while 1366 horizontal units are squeezed into 640, so a
        // physically small vertical movement produces a large dy. Requiring
        // horizontal dominance keeps a sloppy horizontal swipe from being
        // rejected while a genuine vertical drag is not misread as paging.
        assertEquals(Swipe.NONE, gesture(200f, 210f, 40f, 250f, 250L));
    }

    @Test
    public void aDiagonalSwipeStillCountsIfHorizontalDominates() {
        assertEquals(Swipe.FORWARD,
                gesture(200f, 200f + SwipeDetector.SWIPE_MIN_DX + 40f, 100f, 130f, 250L));
    }

    @Test
    public void cancellingDiscardsTheGestureInProgress() {
        detector.begin(new TouchSample(200f, 100f, 0L));
        detector.move(new TouchSample(400f, 100f, 100L));
        detector.cancel();

        assertEquals("a cancelled gesture must not resolve", Swipe.NONE,
                detector.end(new TouchSample(400f, 100f, 200L)));
    }

    @Test
    public void endWithoutBeginIsNone() {
        assertEquals(Swipe.NONE, detector.end(new TouchSample(400f, 100f, 200L)));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :glass:testDebugUnitTest --tests '*SwipeDetectorTest*'`
Expected: FAIL — `SwipeDetector` does not exist.

- [ ] **Step 3: Write `TouchSample.java` and `Swipe.java`**

```java
package dev.erinlkolp.glassnotify.glass;

/** One touch position in view coordinates, with its event time. */
public final class TouchSample {

    public final float x;
    public final float y;
    public final long timeMs;

    public TouchSample(float x, float y, long timeMs) {
        this.x = x;
        this.y = y;
        this.timeMs = timeMs;
    }
}
```

```java
package dev.erinlkolp.glassnotify.glass;

/** What a completed touch resolved to. */
public enum Swipe {

    /** Nothing actionable. */
    NONE,

    /** A brief stationary touch. */
    TAP,

    /** Toward the front of the head - next item. */
    FORWARD,

    /** Toward the back of the head - previous item. */
    BACK
}
```

- [ ] **Step 4: Write `SwipeDetector.java`**

```java
package dev.erinlkolp.glassnotify.glass;

/**
 * Resolves a touch sequence into a paging gesture.
 *
 * Free of Android types so the decision logic is unit tested on the host JVM.
 * MotionEvent is adapted into TouchSample by QueueActivity.
 *
 * The thresholds below are in view coordinates (the 640x360 space the
 * framework rescales the pad onto) and are starting values to be tuned on
 * hardware. Note the pad is anisotropic - its native surface is 1366x187,
 * so horizontal travel is compressed by roughly 0.47 and vertical stretched
 * by roughly 1.93 on the way to view coordinates. That is why dominance is
 * tested as a ratio rather than by comparing raw dx to raw dy.
 */
public final class SwipeDetector {

    /** Minimum horizontal travel, in view coordinates, to count as a swipe. */
    public static final float SWIPE_MIN_DX = 60f;

    /** How much horizontal travel must exceed vertical for a swipe to register. */
    public static final float HORIZONTAL_DOMINANCE = 1.2f;

    /** Longest touch still eligible to be a tap. */
    public static final long TAP_MAX_MS = 400L;

    private boolean active;
    private TouchSample start;
    private TouchSample latest;

    public void begin(TouchSample sample) {
        active = true;
        start = sample;
        latest = sample;
    }

    public void move(TouchSample sample) {
        if (active) {
            latest = sample;
        }
    }

    /** Discards the gesture in progress, e.g. on ACTION_CANCEL. */
    public void cancel() {
        active = false;
        start = null;
        latest = null;
    }

    public Swipe end(TouchSample sample) {
        if (!active || start == null) {
            return Swipe.NONE;
        }
        TouchSample first = start;
        cancel();

        float dx = sample.x - first.x;
        float dy = sample.y - first.y;
        long duration = sample.timeMs - first.timeMs;

        float absDx = Math.abs(dx);
        float absDy = Math.abs(dy);

        if (absDx >= SWIPE_MIN_DX && absDx > absDy * HORIZONTAL_DOMINANCE) {
            return dx > 0 ? Swipe.FORWARD : Swipe.BACK;
        }

        // Not a swipe. A short, essentially stationary touch is a tap.
        if (duration <= TAP_MAX_MS && absDx < SWIPE_MIN_DX && absDy < SWIPE_MIN_DX) {
            return Swipe.TAP;
        }

        return Swipe.NONE;
    }
}
```

- [ ] **Step 5: Write `CardRenderer.java`**

```java
package dev.erinlkolp.glassnotify.glass;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

import dev.erinlkolp.glassnotify.wire.NotificationItem;

/**
 * Builds the card view trees.
 *
 * Everything is pure black and pure white. The prism is see-through, so black
 * is transparent and mid-tones wash out to nothing - there are deliberately no
 * icons, greys, borders or gradients anywhere in here.
 *
 * All sizes are dp, never sp: the layout is fixed at 320x180dp and must not
 * reflow under a user font-scale setting.
 */
public final class CardRenderer {

    private static final int FG = Color.WHITE;
    private static final int BG = Color.BLACK;

    /** The status bar window claims the top 38px; keep content clear of it. */
    private static final int PAD_TOP_DP = 26;
    private static final int PAD_SIDE_DP = 22;
    private static final int PAD_BOTTOM_DP = 18;

    private CardRenderer() {
    }

    /**
     * Glanceable headline: large sender, hard-truncated message, small app label.
     * Readable in under a second without focusing. Spec section 9.2.
     */
    public static View interruptCard(Context context, NotificationItem item) {
        LinearLayout root = column(context);

        root.addView(text(context, item.title, 27, true, 2));
        root.addView(spacer(context, 8));
        root.addView(text(context, item.text, 16, false, 1));

        FrameLayout frame = frame(context, root);
        TextView label = text(context, item.appLabel.toUpperCase(Locale.getDefault()), 12, false, 1);
        label.setLetterSpacing(0.18f);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM | Gravity.START;
        lp.leftMargin = dp(context, PAD_SIDE_DP);
        lp.bottomMargin = dp(context, PAD_BOTTOM_DP);
        frame.addView(label, lp);

        return frame;
    }

    /**
     * One queue entry: app label and position on top, sender, full body, age
     * at the bottom. This is where reading actually happens, so the body is
     * not truncated further. Spec section 9.3.
     */
    public static View queueCard(Context context, NotificationItem item,
            int position, int total, boolean stale) {
        LinearLayout root = column(context);

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);

        TextView app = text(context, item.appLabel.toUpperCase(Locale.getDefault()), 12, false, 1);
        app.setLetterSpacing(0.18f);
        LinearLayout.LayoutParams appLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        header.addView(app, appLp);

        TextView pos = text(context, position + " / " + total, 12, false, 1);
        pos.setLetterSpacing(0.1f);
        header.addView(pos);

        root.addView(header);
        root.addView(spacer(context, 8));
        root.addView(text(context, item.title, 20, true, 1));
        root.addView(spacer(context, 6));

        TextView body = text(context, item.text, 15, false, 4);
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(body, bodyLp);

        String footer = stale
                ? context.getString(R.string.stale_queue)
                : Ages.describe(context, item.postedAt, System.currentTimeMillis());
        TextView age = text(context, footer, 12, false, 1);
        age.setLetterSpacing(0.18f);
        root.addView(age);

        return frame(context, root);
    }

    /** Centred single message, for empty / stale / version-mismatch states. */
    public static View messageCard(Context context, String message) {
        LinearLayout root = column(context);
        root.setGravity(Gravity.CENTER);
        root.addView(text(context, message, 20, false, 2));
        return frame(context, root);
    }

    private static LinearLayout column(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(context, PAD_SIDE_DP), dp(context, PAD_TOP_DP),
                dp(context, PAD_SIDE_DP), dp(context, PAD_BOTTOM_DP));
        return layout;
    }

    private static FrameLayout frame(Context context, View content) {
        FrameLayout frame = new FrameLayout(context);
        frame.setBackgroundColor(BG);
        frame.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return frame;
    }

    private static TextView text(Context context, String value, int sizeDp,
            boolean bold, int maxLines) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextColor(FG);
        // COMPLEX_UNIT_DIP, not SP: fixed layout, must not reflow.
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, sizeDp);
        view.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        view.setMaxLines(maxLines);
        view.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return view;
    }

    private static View spacer(Context context, int heightDp) {
        View view = new View(context);
        view.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(context, heightDp)));
        return view;
    }

    private static int dp(Context context, int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics());
    }
}
```

- [ ] **Step 6: Write `Ages.java`**

```java
package dev.erinlkolp.glassnotify.glass;

import android.content.Context;

import java.util.Locale;

/** Renders a timestamp as the short, all-caps age string shown on queue cards. */
public final class Ages {

    private Ages() {
    }

    public static String describe(Context context, long postedAtMs, long nowMs) {
        long deltaMs = nowMs - postedAtMs;
        if (deltaMs < 0) {
            // The phone's clock is ahead of ours. Treat it as just-arrived.
            deltaMs = 0;
        }

        long minutes = deltaMs / 60_000L;
        if (minutes < 1) {
            return "JUST NOW";
        }
        if (minutes < 60) {
            return String.format(Locale.US, "%d MIN AGO", minutes);
        }
        long hours = minutes / 60;
        if (hours < 24) {
            return String.format(Locale.US, "%d HR AGO", hours);
        }
        return String.format(Locale.US, "%d DAY AGO", hours / 24);
    }
}
```

- [ ] **Step 7: Write `QueueActivity.java`**

```java
package dev.erinlkolp.glassnotify.glass;

import android.app.Activity;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

import java.util.List;

import dev.erinlkolp.glassnotify.wire.NotificationItem;

/**
 * Browses the queue, one notification per screen.
 *
 * Swipe forward/back pages, matching the gesture launcher's next/previous-app
 * idiom so there is no new muscle memory to build. Read-only by design: there
 * is no dismiss, and no action can be fired from here.
 */
public final class QueueActivity extends Activity {

    private final QueueCursor cursor = new QueueCursor();
    private final SwipeDetector detector = new SwipeDetector();

    private SnapshotStore store;
    private FrameLayout container;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        store = GlassNotify.store(this);

        container = new FrameLayout(this);
        setContentView(container);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        applyImmersiveFlags();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // The service may have applied snapshots while we were away.
        refresh();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            // Must be re-applied here as well as in onCreate, or the status bar
            // reclaims its touchable region and starts eating downward swipes.
            applyImmersiveFlags();
        }
    }

    /**
     * The StatusBar window claims touchableRegion [0,0][640,38]. Without
     * IMMERSIVE_STICKY, swipes near the top of the pad open the notification
     * shade instead of reaching this activity. LOW_PROFILE does not do this -
     * it only dims navigation icons. Spec section 9.4.
     */
    private void applyImmersiveFlags() {
        container.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        TouchSample sample = new TouchSample(event.getX(), event.getY(), event.getEventTime());
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                detector.begin(sample);
                return true;
            case MotionEvent.ACTION_MOVE:
                detector.move(sample);
                return true;
            case MotionEvent.ACTION_CANCEL:
                detector.cancel();
                return true;
            case MotionEvent.ACTION_UP:
                handle(detector.end(sample));
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    private void handle(Swipe swipe) {
        boolean moved = false;
        if (swipe == Swipe.FORWARD) {
            moved = cursor.next();
        } else if (swipe == Swipe.BACK) {
            moved = cursor.previous();
        }
        if (moved) {
            render();
        }
    }

    private void refresh() {
        cursor.setSize(store.items().size());
        render();
    }

    private void render() {
        container.removeAllViews();

        List<NotificationItem> items = store.items();
        if (items.isEmpty()) {
            container.addView(CardRenderer.messageCard(this, getString(R.string.empty_queue)));
            return;
        }

        int index = cursor.index();
        container.addView(CardRenderer.queueCard(this, items.get(index),
                index + 1, items.size(), store.isStale()));
    }
}
```

- [ ] **Step 8: Write `GlassNotify.java` (shared store accessor)**

```java
package dev.erinlkolp.glassnotify.glass;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;

/**
 * Process-wide singletons. The service and the activity must see the same
 * snapshot, and Glass is small enough that a holder like this beats wiring a
 * binder interface between two components in the same process.
 */
public final class GlassNotify {

    private static final String PREFS = "glassnotify";
    private static final String CACHE_FILE = "snapshot.bin";

    private static SnapshotStore store;
    private static PeerPin peerPin;

    private GlassNotify() {
    }

    public static synchronized SnapshotStore store(Context context) {
        if (store == null) {
            Context app = context.getApplicationContext();
            store = new SnapshotStore(new File(app.getFilesDir(), CACHE_FILE));
            store.load();
        }
        return store;
    }

    public static synchronized PeerPin peerPin(Context context) {
        if (peerPin == null) {
            SharedPreferences prefs = context.getApplicationContext()
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            peerPin = new PeerPin(prefs);
        }
        return peerPin;
    }
}
```

- [ ] **Step 9: Register the activity in the manifest**

Add inside `<application>` in `glass/src/main/AndroidManifest.xml`:

```xml
        <activity
            android:name=".QueueActivity"
            android:exported="true"
            android:label="@string/app_name"
            android:theme="@android:style/Theme.Black.NoTitleBar.Fullscreen">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
```

The `LAUNCHER` category is what makes it appear in the gesture launcher's app list — which is how the queue is opened, per §10.1.

- [ ] **Step 10: Run the tests and build**

Run: `./gradlew :glass:testDebugUnitTest :glass:assembleDebug`
Expected: PASS, 21 tests. BUILD SUCCESSFUL.

- [ ] **Step 11: Commit**

```bash
git add glass/
git commit -m "feat(glass): add card rendering and the queue screen

Pure black and pure white only, with sizes in dp rather than sp - the
prism is see-through so black is transparent and mid-tones vanish, and
the 320x180dp layout must not reflow under a font-scale setting.

IMMERSIVE_STICKY is applied in both onCreate and onWindowFocusChanged.
The status bar claims touchableRegion [0,0][640,38], so without it
swipes near the top of the pad open the notification shade instead of
reaching the activity. LOW_PROFILE does not help - it only dims icons.

SwipeDetector is Android-free so the decision logic is unit tested on
the host. It tests horizontal dominance as a ratio rather than comparing
raw dx to dy, because the pad is anisotropic: 1366x187 native rescaled
onto 640x360 compresses horizontal travel and stretches vertical."
```

---

## Task 7: The interrupt overlay

Draws over whatever is foregrounded and wakes the display briefly. The behaviour that matters most is storm collapsing: a chatty group thread must not pin the display on. (§10.1)

**Files:**
- Create: `glass/src/main/java/dev/erinlkolp/glassnotify/glass/InterruptPolicy.java`
- Create: `glass/src/main/java/dev/erinlkolp/glassnotify/glass/InterruptOverlay.java`
- Test: `glass/src/test/java/dev/erinlkolp/glassnotify/glass/InterruptPolicyTest.java`

**Interfaces:**
- Consumes: `CardRenderer.interruptCard`, `NotificationItem`, `Tier`, `Snapshot`.
- Produces: `InterruptPolicy()`; `InterruptPolicy.selectInterrupt(Snapshot previous, Snapshot next):NotificationItem` (static, returns `null` when nothing should interrupt); `InterruptOverlay(Context)`; `show(NotificationItem):void`; `dismiss():void`; `InterruptOverlay.DISPLAY_MS:long`.

- [ ] **Step 1: Write the failing test**

```java
package dev.erinlkolp.glassnotify.glass;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import dev.erinlkolp.glassnotify.wire.NotificationItem;
import dev.erinlkolp.glassnotify.wire.Snapshot;
import dev.erinlkolp.glassnotify.wire.Tier;

public class InterruptPolicyTest {

    private static NotificationItem item(String key, Tier tier, long postedAt) {
        return new NotificationItem(key, "Signal", "Jordan Reyes", "hello", postedAt, tier);
    }

    private static Snapshot snapshot(NotificationItem... items) {
        return new Snapshot(1L, Arrays.asList(items));
    }

    private static Snapshot empty() {
        return new Snapshot(0L, new ArrayList<NotificationItem>());
    }

    @Test
    public void aNewInterruptItemInterrupts() {
        NotificationItem incoming = item("a", Tier.INTERRUPT, 100L);

        assertEquals(incoming, InterruptPolicy.selectInterrupt(empty(), snapshot(incoming)));
    }

    @Test
    public void aNewQueueItemDoesNotInterrupt() {
        assertNull(InterruptPolicy.selectInterrupt(empty(), snapshot(item("a", Tier.QUEUE, 100L))));
    }

    @Test
    public void anItemAlreadySeenDoesNotInterruptAgain() {
        // Every change resends the whole queue, so an unchanged item appears in
        // snapshot after snapshot. Re-interrupting on each would be unusable.
        Snapshot previous = snapshot(item("a", Tier.INTERRUPT, 100L));
        Snapshot next = snapshot(item("a", Tier.INTERRUPT, 100L), item("b", Tier.QUEUE, 200L));

        assertNull(InterruptPolicy.selectInterrupt(previous, next));
    }

    @Test
    public void anUpdatedItemWithTheSameKeyInterruptsAgain() {
        // A messaging app reuses one key and rewrites the text as a thread
        // grows. A newer postedAt is a genuinely new message.
        Snapshot previous = snapshot(item("a", Tier.INTERRUPT, 100L));
        NotificationItem updated = item("a", Tier.INTERRUPT, 500L);

        assertEquals(updated, InterruptPolicy.selectInterrupt(previous, snapshot(updated)));
    }

    @Test
    public void collapsesAStormToTheNewestItem() {
        // Several arrive between snapshots. Show only the newest rather than
        // queueing five seconds each - that pins the display on and drains
        // the battery. Spec section 10.1.
        NotificationItem newest = item("c", Tier.INTERRUPT, 300L);
        Snapshot next = snapshot(newest, item("b", Tier.INTERRUPT, 200L), item("a", Tier.INTERRUPT, 100L));

        assertEquals(newest, InterruptPolicy.selectInterrupt(empty(), next));
    }

    @Test
    public void picksTheNewestRegardlessOfPositionInTheList() {
        NotificationItem newest = item("b", Tier.INTERRUPT, 900L);
        Snapshot next = snapshot(item("a", Tier.INTERRUPT, 100L), newest);

        assertEquals(newest, InterruptPolicy.selectInterrupt(empty(), next));
    }

    @Test
    public void anEmptySnapshotInterruptsNothing() {
        assertNull(InterruptPolicy.selectInterrupt(snapshot(item("a", Tier.INTERRUPT, 100L)), empty()));
    }

    @Test
    public void removalDoesNotInterrupt() {
        Snapshot previous = snapshot(item("a", Tier.INTERRUPT, 100L), item("b", Tier.INTERRUPT, 200L));
        Snapshot next = snapshot(item("a", Tier.INTERRUPT, 100L));

        assertNull(InterruptPolicy.selectInterrupt(previous, next));
    }

    @Test
    public void theFirstSnapshotAfterReconnectDoesNotReplayTheBacklog() {
        // On reconnect the phone sends everything it holds. Those are not new
        // events - interrupting for each would be a wall of cards.
        List<NotificationItem> backlog = new ArrayList<NotificationItem>();
        for (int i = 0; i < 5; i++) {
            backlog.add(item("k" + i, Tier.INTERRUPT, 100L + i));
        }

        assertNull(InterruptPolicy.selectInterrupt(null, new Snapshot(1L, backlog)));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :glass:testDebugUnitTest --tests '*InterruptPolicyTest*'`
Expected: FAIL — `InterruptPolicy` does not exist.

- [ ] **Step 3: Write `InterruptPolicy.java`**

```java
package dev.erinlkolp.glassnotify.glass;

import java.util.HashMap;
import java.util.Map;

import dev.erinlkolp.glassnotify.wire.NotificationItem;
import dev.erinlkolp.glassnotify.wire.Snapshot;
import dev.erinlkolp.glassnotify.wire.Tier;

/**
 * Decides whether an incoming snapshot should light up the display, and with what.
 *
 * Whole-snapshot transfer means an unchanged notification arrives over and over,
 * so "is this new?" is a diff against the previous snapshot rather than a
 * property of the item. Free of Android types, so it is unit tested on the host.
 */
public final class InterruptPolicy {

    private InterruptPolicy() {
    }

    /**
     * Returns the single item to show, or null for nothing.
     *
     * @param previous the last snapshot applied, or null if this is the first
     *                 one of the connection - in which case nothing interrupts,
     *                 because a reconnect backlog is not a stream of new events
     */
    public static NotificationItem selectInterrupt(Snapshot previous, Snapshot next) {
        if (previous == null) {
            return null;
        }

        Map<String, Long> seen = new HashMap<String, Long>();
        for (NotificationItem item : previous.items) {
            seen.put(item.key, Long.valueOf(item.postedAt));
        }

        NotificationItem winner = null;
        for (NotificationItem item : next.items) {
            if (item.tier != Tier.INTERRUPT) {
                continue;
            }
            Long previouslyPostedAt = seen.get(item.key);
            boolean isNew = previouslyPostedAt == null
                    || item.postedAt > previouslyPostedAt.longValue();
            if (!isNew) {
                continue;
            }
            // Collapse a storm: keep only the newest.
            if (winner == null || item.postedAt > winner.postedAt) {
                winner = item;
            }
        }
        return winner;
    }
}
```

- [ ] **Step 4: Write `InterruptOverlay.java`**

```java
package dev.erinlkolp.glassnotify.glass;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import dev.erinlkolp.glassnotify.wire.NotificationItem;

/**
 * Draws the interrupt card over whatever is foregrounded.
 *
 * TYPE_SYSTEM_ALERT needs only the SYSTEM_ALERT_WINDOW permission, which is
 * granted at install time on API 22 - so unlike the gesture launcher's global
 * gestures, this needs no root and no app_process daemon.
 *
 * Showing a second card while one is up replaces it and restarts the timer
 * rather than queueing, so a chatty thread cannot pin the display on.
 */
public final class InterruptOverlay {

    private static final String TAG = "GlassNotify";

    /** Starting value; tune on hardware. Spec section 14. */
    public static final long DISPLAY_MS = 5_000L;

    private final Context context;
    private final WindowManager windowManager;
    private final PowerManager powerManager;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable dismissRunnable = new Runnable() {
        @Override
        public void run() {
            dismiss();
        }
    };

    private View currentView;
    private PowerManager.WakeLock wakeLock;

    public InterruptOverlay(Context context) {
        this.context = context.getApplicationContext();
        this.windowManager =
                (WindowManager) this.context.getSystemService(Context.WINDOW_SERVICE);
        this.powerManager =
                (PowerManager) this.context.getSystemService(Context.POWER_SERVICE);
    }

    /** Must be called on the main thread. */
    public void show(NotificationItem item) {
        dismiss();

        View card = CardRenderer.interruptCard(context, item);

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_FULLSCREEN,
                PixelFormat.OPAQUE);
        params.gravity = Gravity.TOP | Gravity.START;

        try {
            windowManager.addView(card, params);
            currentView = card;
        } catch (RuntimeException e) {
            // Never let a windowing failure kill the link service.
            Log.w(TAG, "could not add interrupt overlay", e);
            return;
        }

        acquireWakeLock();
        handler.removeCallbacks(dismissRunnable);
        handler.postDelayed(dismissRunnable, DISPLAY_MS);
    }

    /** Must be called on the main thread. Safe when nothing is showing. */
    public void dismiss() {
        handler.removeCallbacks(dismissRunnable);

        if (currentView != null) {
            try {
                windowManager.removeView(currentView);
            } catch (RuntimeException e) {
                Log.w(TAG, "could not remove interrupt overlay", e);
            }
            currentView = null;
        }
        releaseWakeLock();
    }

    private void acquireWakeLock() {
        releaseWakeLock();
        wakeLock = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "GlassNotify:interrupt");
        // Timeout is a backstop: if dismiss() is somehow never reached, the
        // lock still expires rather than holding the display on indefinitely.
        wakeLock.acquire(DISPLAY_MS + 1_000L);
    }

    private void releaseWakeLock() {
        if (wakeLock != null) {
            if (wakeLock.isHeld()) {
                wakeLock.release();
            }
            wakeLock = null;
        }
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :glass:testDebugUnitTest`
Expected: PASS, 30 tests.

- [ ] **Step 6: Commit**

```bash
git add glass/
git commit -m "feat(glass): add the interrupt overlay and its policy

InterruptPolicy diffs against the previous snapshot rather than looking
at the item alone. Whole-snapshot transfer means an unchanged
notification arrives repeatedly, so 'is this new' is only answerable as
a diff. Same key with a newer postedAt counts as new, which is how
messaging apps signal another message in a thread.

A storm collapses to the single newest item, and the first snapshot of
a connection interrupts for nothing at all - a reconnect backlog is not
a stream of new events and would otherwise be a wall of cards.

The overlay uses TYPE_SYSTEM_ALERT, an install-time permission on API
22, so no root or app_process daemon is needed. The wake lock is
acquired with a timeout as a backstop against a leaked display-on."
```

---

## Task 8: RFCOMM server, boot, and the fake feed

Completes the Glass app. At the end of this task the whole UI is exercisable from `adb` with no phone in existence. (§12.4)

**Files:**
- Create: `glass/src/main/java/dev/erinlkolp/glassnotify/glass/LinkServerService.java`
- Create: `glass/src/main/java/dev/erinlkolp/glassnotify/glass/BootReceiver.java`
- Create: `glass/src/main/java/dev/erinlkolp/glassnotify/glass/DebugInjectReceiver.java`
- Create: `scripts/fake-notify.sh`
- Modify: `glass/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: `GlassNotify.store`, `GlassNotify.peerPin`, `InterruptPolicy`, `InterruptOverlay`, `FrameCodec`, `SnapshotCodec`, `HelloCodec`, `Protocol`, `MessageType`.
- Produces: `LinkServerService.start(Context):void` (static helper); the broadcast action `dev.erinlkolp.glassnotify.DEBUG_INJECT`.

- [ ] **Step 1: Write `LinkServerService.java`**

```java
package dev.erinlkolp.glassnotify.glass;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;

import dev.erinlkolp.glassnotify.wire.Frame;
import dev.erinlkolp.glassnotify.wire.FrameCodec;
import dev.erinlkolp.glassnotify.wire.Hello;
import dev.erinlkolp.glassnotify.wire.HelloCodec;
import dev.erinlkolp.glassnotify.wire.MessageType;
import dev.erinlkolp.glassnotify.wire.Protocol;
import dev.erinlkolp.glassnotify.wire.Snapshot;
import dev.erinlkolp.glassnotify.wire.SnapshotCodec;

/**
 * Accepts the phone's RFCOMM connection and applies whatever it sends.
 *
 * Glass is the server because reconnection means an indefinite backoff loop,
 * which belongs on the device with the larger battery. Blocking in accept()
 * costs nothing here. Spec section 5.
 */
public final class LinkServerService extends Service {

    private static final String TAG = "GlassNotify";

    private volatile boolean running;
    private Thread acceptThread;
    private BluetoothServerSocket serverSocket;

    private final Handler main = new Handler(Looper.getMainLooper());
    private InterruptOverlay overlay;

    /** The last snapshot applied on this connection; null until one arrives. */
    private Snapshot lastApplied;

    public static void start(Context context) {
        context.startService(new Intent(context, LinkServerService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        overlay = new InterruptOverlay(this);
        GlassNotify.store(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!running) {
            running = true;
            acceptThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    acceptLoop();
                }
            }, "glassnotify-accept");
            acceptThread.start();
        }
        // Restart if the system kills us: this service is the whole point of the app.
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        closeServerSocket();
        super.onDestroy();
        main.post(new Runnable() {
            @Override
            public void run() {
                overlay.dismiss();
            }
        });
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void acceptLoop() {
        while (running) {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null || !adapter.isEnabled()) {
                // Bluetooth is off. Idle rather than spinning a retry loop.
                sleepQuietly(5_000L);
                continue;
            }

            try {
                serverSocket = adapter.listenUsingRfcommWithServiceRecord(
                        Protocol.SERVICE_NAME, Protocol.SERVICE_UUID);
            } catch (IOException e) {
                Log.w(TAG, "could not open server socket", e);
                sleepQuietly(5_000L);
                continue;
            }

            BluetoothSocket socket = null;
            try {
                socket = serverSocket.accept();
                closeServerSocket();
                serve(socket);
            } catch (IOException e) {
                if (running) {
                    Log.w(TAG, "accept failed", e);
                }
            } finally {
                closeQuietly(socket);
                closeServerSocket();
            }
        }
    }

    private void serve(BluetoothSocket socket) {
        BluetoothDevice remote = socket.getRemoteDevice();
        String address = remote == null ? null : remote.getAddress();

        PeerPin pin = GlassNotify.peerPin(this);
        if (!pin.isAllowed(address)) {
            Log.w(TAG, "refusing connection from unpinned device " + address);
            return;
        }
        pin.pinIfUnset(address);

        Log.i(TAG, "connected to " + address);
        lastApplied = null;

        try {
            InputStream in = socket.getInputStream();
            while (running) {
                Frame frame = FrameCodec.read(in);

                if (frame.version != Protocol.VERSION) {
                    Log.w(TAG, "protocol version " + frame.version
                            + " from phone, expected " + Protocol.VERSION);
                    showMessage(getString(R.string.version_mismatch));
                    return;
                }

                dispatch(frame);
            }
        } catch (IOException e) {
            // Includes ProtocolException. Either way: close and go back to
            // accept(). Mid-stream resync is never attempted.
            Log.i(TAG, "connection ended: " + e.getMessage());
        }
    }

    private void dispatch(Frame frame) throws IOException {
        switch (frame.type) {
            case MessageType.HELLO: {
                Hello hello = HelloCodec.decode(frame.body);
                Log.i(TAG, "hello from " + hello.deviceName + " " + hello.deviceAddress);
                GlassNotify.store(this).markContact();
                break;
            }
            case MessageType.PING: {
                GlassNotify.store(this).markContact();
                break;
            }
            case MessageType.SNAPSHOT: {
                applySnapshot(SnapshotCodec.decode(frame.body));
                break;
            }
            default:
                // Unknown types are ignored so a newer phone can add messages
                // without breaking an older Glass build.
                Log.i(TAG, "ignoring unknown frame type " + frame.type);
        }
    }

    private void applySnapshot(final Snapshot snapshot) {
        final Snapshot previous = lastApplied;
        GlassNotify.store(this).apply(snapshot);
        lastApplied = snapshot;

        main.post(new Runnable() {
            @Override
            public void run() {
                dev.erinlkolp.glassnotify.wire.NotificationItem interrupt =
                        InterruptPolicy.selectInterrupt(previous, snapshot);
                if (interrupt != null) {
                    overlay.show(interrupt);
                }
            }
        });
    }

    private void showMessage(final String message) {
        main.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(LinkServerService.this, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void closeServerSocket() {
        BluetoothServerSocket socket = serverSocket;
        serverSocket = null;
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static void closeQuietly(BluetoothSocket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
```

- [ ] **Step 2: Write `BootReceiver.java`**

```java
package dev.erinlkolp.glassnotify.glass;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Starts the link service at boot.
 *
 * Far simpler than the gesture launcher's boot problem, which needed an init
 * hook because it ran a root app_process daemon. This is an ordinary app uid,
 * so the standard receiver is enough. Spec section 10.1.
 */
public final class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            LinkServerService.start(context);
        }
    }
}
```

- [ ] **Step 3: Write `DebugInjectReceiver.java`**

```java
package dev.erinlkolp.glassnotify.glass;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

import dev.erinlkolp.glassnotify.wire.NotificationItem;
import dev.erinlkolp.glassnotify.wire.Protocol;
import dev.erinlkolp.glassnotify.wire.Snapshot;
import dev.erinlkolp.glassnotify.wire.Tier;

/**
 * Fake feed, so the whole Glass UI can be developed and demoed before the
 * phone app exists. Spec section 12.4.
 *
 * Injecting is additive, newest-first, mirroring what the phone will send:
 *
 *   adb shell am broadcast -a dev.erinlkolp.glassnotify.DEBUG_INJECT \
 *     --es app Signal --es title "Jordan Reyes" \
 *     --es text "are you still good for 7pm?" --es tier INTERRUPT
 *
 *   adb shell am broadcast -a dev.erinlkolp.glassnotify.DEBUG_INJECT --ez clear true
 */
public final class DebugInjectReceiver extends BroadcastReceiver {

    private static final String TAG = "GlassNotify";

    private static long sequence;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!BuildConfig.DEBUG) {
            // Never allow synthetic notifications into a non-debug build.
            return;
        }

        SnapshotStore store = GlassNotify.store(context);
        Snapshot previous = store.current();

        if (intent.getBooleanExtra("clear", false)) {
            store.apply(new Snapshot(++sequence, new ArrayList<NotificationItem>()));
            Log.i(TAG, "debug: queue cleared");
            notifyUi(context, previous, store.current());
            return;
        }

        String app = valueOr(intent.getStringExtra("app"), "Signal");
        String title = valueOr(intent.getStringExtra("title"), "Jordan Reyes");
        String text = valueOr(intent.getStringExtra("text"), "are you still good for 7pm?");
        String tierName = valueOr(intent.getStringExtra("tier"), "QUEUE");

        Tier tier;
        try {
            tier = Tier.valueOf(tierName.toUpperCase(java.util.Locale.US));
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "unknown tier '" + tierName + "', defaulting to QUEUE");
            tier = Tier.QUEUE;
        }

        List<NotificationItem> items = new ArrayList<NotificationItem>();
        items.add(new NotificationItem("debug-" + (++sequence), app, title, text,
                System.currentTimeMillis(), tier));
        for (NotificationItem existing : previous.items) {
            if (items.size() >= Protocol.MAX_ITEMS) {
                break;
            }
            items.add(existing);
        }

        Snapshot next = new Snapshot(sequence, items);
        store.apply(next);
        Log.i(TAG, "debug: injected " + tier + " item, queue now " + items.size());

        notifyUi(context, previous, next);
    }

    /** Runs the same interrupt path the real link service uses. */
    private void notifyUi(final Context context, final Snapshot previous, final Snapshot next) {
        final InterruptOverlay overlay = new InterruptOverlay(context);
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                NotificationItem interrupt = InterruptPolicy.selectInterrupt(previous, next);
                if (interrupt != null) {
                    overlay.show(interrupt);
                }
            }
        });
    }

    private static String valueOr(String value, String fallback) {
        return value == null ? fallback : value;
    }
}
```

- [ ] **Step 4: Register the components in the manifest**

Add inside `<application>`:

```xml
        <service
            android:name=".LinkServerService"
            android:exported="false" />

        <receiver
            android:name=".BootReceiver"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.BOOT_COMPLETED" />
            </intent-filter>
        </receiver>

        <receiver
            android:name=".DebugInjectReceiver"
            android:exported="true">
            <intent-filter>
                <action android:name="dev.erinlkolp.glassnotify.DEBUG_INJECT" />
            </intent-filter>
        </receiver>
```

- [ ] **Step 5: Start the service from the activity**

So the app works after a fresh install without waiting for a reboot. In `QueueActivity.onCreate`, after `store = GlassNotify.store(this);` add:

```java
        LinkServerService.start(this);
```

- [ ] **Step 6: Write `scripts/fake-notify.sh`**

```bash
#!/usr/bin/env bash
# Injects a synthetic notification into the Glass app, so the UI can be
# exercised without the phone. See spec section 12.4.
#
#   scripts/fake-notify.sh "Signal" "Jordan Reyes" "are you still good for 7pm?" INTERRUPT
#   scripts/fake-notify.sh --clear
set -euo pipefail

SERIAL="${GLASS_SERIAL:-0123456789ABCDEF}"
ACTION=dev.erinlkolp.glassnotify.DEBUG_INJECT

if [[ "${1:-}" == "--clear" ]]; then
  adb -s "$SERIAL" shell am broadcast -a "$ACTION" --ez clear true
  exit 0
fi

APP="${1:-Signal}"
TITLE="${2:-Jordan Reyes}"
TEXT="${3:-are you still good for 7pm?}"
TIER="${4:-QUEUE}"

adb -s "$SERIAL" shell am broadcast -a "$ACTION" \
  --es app "$APP" \
  --es title "$TITLE" \
  --es text "$TEXT" \
  --es tier "$TIER"
```

Then: `chmod +x scripts/fake-notify.sh`

- [ ] **Step 7: Build, install, and exercise the UI end to end**

```bash
./gradlew :glass:assembleDebug
adb -s 0123456789ABCDEF install -r glass/build/outputs/apk/debug/glass-debug.apk
adb -s 0123456789ABCDEF shell am start -n dev.erinlkolp.glassnotify.glass/.QueueActivity
```

Expected on the prism: **"Nothing waiting"** in white on transparent.

```bash
scripts/fake-notify.sh "Signal" "Jordan Reyes" "are you still good for 7pm? i can move it later" QUEUE
scripts/fake-notify.sh "Calendar" "Standup" "starts in 10 minutes" QUEUE
scripts/fake-notify.sh "Signal" "Ana Whitfield" "lunch?" INTERRUPT
```

Expected: the first two land silently; the third wakes the display with the glanceable card for ~5s. Opening the queue shows `1 / 3` and swiping pages through.

**Real-finger testing is mandatory here.** `adb shell input tap/swipe` injects below the window manager, bypasses touchable regions entirely, and cannot do multitouch — on the gesture launcher it produced 40 green tests while two real bugs were live. Page through the queue with an actual finger and confirm downward swipes near the top of the pad do *not* open the notification shade. (§12.5)

- [ ] **Step 8: Commit**

```bash
git add glass/ scripts/
git commit -m "feat(glass): add RFCOMM server, boot receiver, and fake feed

Glass listens and the phone connects, because reconnection is an
indefinite backoff loop that belongs on the device with the larger
battery. Any IOException - including ProtocolException - closes the
socket and returns to accept(); mid-stream resync is never attempted.

Connections from an unpinned device are refused before a single frame
is read. A version mismatch reports 'phone app out of date' rather than
failing as a generic stream error. Unknown frame types are ignored so a
newer phone can add messages without breaking an older Glass build.

The debug receiver makes the whole UI exercisable from adb with no phone
in existence, and is inert unless BuildConfig.DEBUG."
```

---

## Task 9: `phone` scaffold and the snapshot builder

Every filtering, tiering, truncation and ordering decision lives here, in a class with no Android types. (§8, §12.2)

**Files:**
- Create: `phone/build.gradle.kts`
- Create: `phone/src/main/AndroidManifest.xml`
- Create: `phone/src/main/res/values/strings.xml`
- Create: `phone/src/main/java/dev/erinlkolp/glassnotify/phone/SourceNotification.java`
- Create: `phone/src/main/java/dev/erinlkolp/glassnotify/phone/AllowRule.java`
- Create: `phone/src/main/java/dev/erinlkolp/glassnotify/phone/SnapshotBuilder.java`
- Test: `phone/src/test/java/dev/erinlkolp/glassnotify/phone/SnapshotBuilderTest.java`

**Interfaces:**
- Consumes: `:wire`.
- Produces: `SourceNotification(String key, String packageName, String appLabel, String title, String text, long postedAt, boolean ongoing)` with public final fields of those names; `AllowRule(String packageName, Tier tier)` with public final fields; `SnapshotBuilder.build(long snapshotId, List<SourceNotification> sources, Map<String, Tier> allowlist):Snapshot` (static).

- [ ] **Step 1: Write `phone/build.gradle.kts`, manifest and strings**

```kotlin
plugins { id("com.android.application") }

android {
    namespace = "dev.erinlkolp.glassnotify.phone"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.erinlkolp.glassnotify.phone"
        minSdk = 26
        targetSdk = 28
        versionCode = 1
        versionName = "0.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }
}

dependencies {
    implementation(project(":wire"))
    testImplementation("junit:junit:4.13.2")
}
```

`phone/src/main/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.BLUETOOTH" />
    <uses-permission android:name="android.permission.BLUETOOTH_ADMIN" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
    <uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />

    <application
        android:allowBackup="false"
        android:label="@string/app_name" />

</manifest>
```

`phone/src/main/res/values/strings.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">Glass Notifications</string>
    <string name="channel_link">Glass link</string>
    <string name="status_connected">Connected to Glass</string>
    <string name="status_connecting">Connecting to Glass…</string>
    <string name="status_no_bluetooth">Bluetooth is off</string>
    <string name="status_not_bonded">Glass is not paired</string>
    <string name="grant_notification_access">Grant notification access</string>
    <string name="grant_battery_exemption">Allow running in the background</string>
    <string name="configure_allowlist">Choose which apps to show</string>
</resources>
```

- [ ] **Step 2: Write the failing test**

```java
package dev.erinlkolp.glassnotify.phone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import dev.erinlkolp.glassnotify.wire.NotificationItem;
import dev.erinlkolp.glassnotify.wire.Protocol;
import dev.erinlkolp.glassnotify.wire.Snapshot;
import dev.erinlkolp.glassnotify.wire.Tier;

public class SnapshotBuilderTest {

    private static SourceNotification source(String key, String pkg, long postedAt) {
        return new SourceNotification(key, pkg, "Signal", "Jordan Reyes", "hello", postedAt, false);
    }

    private static Map<String, Tier> allow(String pkg, Tier tier) {
        Map<String, Tier> map = new HashMap<String, Tier>();
        map.put(pkg, tier);
        return map;
    }

    @Test
    public void dropsAnythingNotOnTheAllowlist() {
        // Filtering here is the biggest battery lever in the system: screened
        // notifications never reach the radio at all. Spec section 8.
        List<SourceNotification> sources = Arrays.asList(
                source("a", "org.thoughtcrime.securesms", 100L),
                source("b", "com.example.spam", 200L));

        Snapshot snapshot = SnapshotBuilder.build(1L, sources,
                allow("org.thoughtcrime.securesms", Tier.INTERRUPT));

        assertEquals(1, snapshot.items.size());
        assertEquals("a", snapshot.items.get(0).key);
    }

    @Test
    public void appliesTheTierFromTheAllowlist() {
        Snapshot snapshot = SnapshotBuilder.build(1L,
                Arrays.asList(source("a", "pkg", 100L)), allow("pkg", Tier.INTERRUPT));

        assertEquals(Tier.INTERRUPT, snapshot.items.get(0).tier);
    }

    @Test
    public void ordersNewestFirst() {
        List<SourceNotification> sources = Arrays.asList(
                source("old", "pkg", 100L),
                source("new", "pkg", 300L),
                source("mid", "pkg", 200L));

        Snapshot snapshot = SnapshotBuilder.build(1L, sources, allow("pkg", Tier.QUEUE));

        assertEquals("new", snapshot.items.get(0).key);
        assertEquals("mid", snapshot.items.get(1).key);
        assertEquals("old", snapshot.items.get(2).key);
    }

    @Test
    public void capsAtTheProtocolLimitKeepingTheNewest() {
        List<SourceNotification> sources = new ArrayList<SourceNotification>();
        for (int i = 0; i < Protocol.MAX_ITEMS + 10; i++) {
            sources.add(source("k" + i, "pkg", i));
        }

        Snapshot snapshot = SnapshotBuilder.build(1L, sources, allow("pkg", Tier.QUEUE));

        assertEquals(Protocol.MAX_ITEMS, snapshot.items.size());
        assertEquals("the newest must survive the cap",
                "k" + (Protocol.MAX_ITEMS + 9), snapshot.items.get(0).key);
    }

    @Test
    public void truncatesBodyText() {
        StringBuilder long_ = new StringBuilder();
        for (int i = 0; i < Protocol.MAX_TEXT_CHARS * 2; i++) {
            long_.append('x');
        }
        SourceNotification s = new SourceNotification("a", "pkg", "Signal", "title",
                long_.toString(), 100L, false);

        Snapshot snapshot = SnapshotBuilder.build(1L, Arrays.asList(s), allow("pkg", Tier.QUEUE));

        assertEquals(Protocol.MAX_TEXT_CHARS, snapshot.items.get(0).text.length());
    }

    @Test
    public void truncatesTitle() {
        StringBuilder long_ = new StringBuilder();
        for (int i = 0; i < Protocol.MAX_TITLE_CHARS * 2; i++) {
            long_.append('y');
        }
        SourceNotification s = new SourceNotification("a", "pkg", "Signal", long_.toString(),
                "body", 100L, false);

        Snapshot snapshot = SnapshotBuilder.build(1L, Arrays.asList(s), allow("pkg", Tier.QUEUE));

        assertEquals(Protocol.MAX_TITLE_CHARS, snapshot.items.get(0).title.length());
    }

    @Test
    public void leavesShortTextAlone() {
        Snapshot snapshot = SnapshotBuilder.build(1L,
                Arrays.asList(source("a", "pkg", 100L)), allow("pkg", Tier.QUEUE));

        assertEquals("hello", snapshot.items.get(0).text);
    }

    @Test
    public void dropsOngoingNotifications() {
        // Ongoing notifications are persistent status - a music player, a
        // navigation session, another app's foreground service. They are not
        // events, and they would permanently occupy queue slots.
        SourceNotification ongoing = new SourceNotification("a", "pkg", "Player", "Now playing",
                "a song", 100L, true);

        Snapshot snapshot = SnapshotBuilder.build(1L, Arrays.asList(ongoing),
                allow("pkg", Tier.QUEUE));

        assertTrue(snapshot.items.isEmpty());
    }

    @Test
    public void toleratesNullTitleAndText() {
        // Plenty of real notifications have no title, or no text, or neither.
        // NotificationItem forbids nulls, so the builder must normalise.
        SourceNotification s = new SourceNotification("a", "pkg", "Signal", null, null, 100L, false);

        NotificationItem item = SnapshotBuilder.build(1L, Arrays.asList(s),
                allow("pkg", Tier.QUEUE)).items.get(0);

        assertEquals("", item.title);
        assertEquals("", item.text);
    }

    @Test
    public void carriesTheSnapshotIdThrough() {
        assertEquals(99L, SnapshotBuilder.build(99L,
                new ArrayList<SourceNotification>(), new HashMap<String, Tier>()).snapshotId);
    }

    @Test
    public void anEmptyAllowlistProducesAnEmptySnapshot() {
        Snapshot snapshot = SnapshotBuilder.build(1L,
                Arrays.asList(source("a", "pkg", 100L)), new HashMap<String, Tier>());

        assertTrue(snapshot.items.isEmpty());
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `./gradlew :phone:testDebugUnitTest`
Expected: FAIL — `SourceNotification` and `SnapshotBuilder` do not exist.

- [ ] **Step 4: Write `SourceNotification.java` and `AllowRule.java`**

```java
package dev.erinlkolp.glassnotify.phone;

/**
 * A notification as observed on the phone, with every Android type already
 * stripped off.
 *
 * StatusBarNotification cannot be constructed in a host unit test, so mapping
 * happens once in SbnMapper and every decision after that operates on this.
 * Spec section 12.2.
 */
public final class SourceNotification {

    public final String key;
    public final String packageName;
    public final String appLabel;

    /** May be null - plenty of real notifications have no title. */
    public final String title;

    /** May be null. */
    public final String text;

    public final long postedAt;

    /** True for persistent status: media players, navigation, foreground services. */
    public final boolean ongoing;

    public SourceNotification(String key, String packageName, String appLabel,
            String title, String text, long postedAt, boolean ongoing) {
        if (key == null) {
            throw new NullPointerException("key");
        }
        if (packageName == null) {
            throw new NullPointerException("packageName");
        }
        if (appLabel == null) {
            throw new NullPointerException("appLabel");
        }
        this.key = key;
        this.packageName = packageName;
        this.appLabel = appLabel;
        this.title = title;
        this.text = text;
        this.postedAt = postedAt;
        this.ongoing = ongoing;
    }
}
```

```java
package dev.erinlkolp.glassnotify.phone;

import dev.erinlkolp.glassnotify.wire.Tier;

/** One allowlist entry: this package, shown at this tier. */
public final class AllowRule {

    public final String packageName;
    public final Tier tier;

    public AllowRule(String packageName, Tier tier) {
        if (packageName == null) {
            throw new NullPointerException("packageName");
        }
        if (tier == null) {
            throw new NullPointerException("tier");
        }
        this.packageName = packageName;
        this.tier = tier;
    }
}
```

- [ ] **Step 5: Write `SnapshotBuilder.java`**

```java
package dev.erinlkolp.glassnotify.phone;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import dev.erinlkolp.glassnotify.wire.NotificationItem;
import dev.erinlkolp.glassnotify.wire.Protocol;
import dev.erinlkolp.glassnotify.wire.Snapshot;
import dev.erinlkolp.glassnotify.wire.Tier;

/**
 * Turns observed notifications into the snapshot sent to Glass.
 *
 * Every product decision lives here - what is shown, at what tier, in what
 * order, and how much text survives - and none of it touches an Android type,
 * so all of it is unit tested on the host JVM.
 *
 * Filtering here rather than on Glass is the largest battery lever in the
 * system: screened-out notifications never reach the radio. Spec section 8.
 */
public final class SnapshotBuilder {

    private SnapshotBuilder() {
    }

    public static Snapshot build(long snapshotId, List<SourceNotification> sources,
            Map<String, Tier> allowlist) {

        List<SourceNotification> eligible = new ArrayList<SourceNotification>();
        for (SourceNotification source : sources) {
            if (source.ongoing) {
                // Persistent status, not an event. Would occupy a slot forever.
                continue;
            }
            if (!allowlist.containsKey(source.packageName)) {
                continue;
            }
            eligible.add(source);
        }

        Collections.sort(eligible, new Comparator<SourceNotification>() {
            @Override
            public int compare(SourceNotification a, SourceNotification b) {
                // Newest first. Compare rather than subtract: the difference of
                // two epoch-milli longs can overflow an int.
                if (a.postedAt == b.postedAt) {
                    return a.key.compareTo(b.key); // stable, so ordering is deterministic
                }
                return a.postedAt > b.postedAt ? -1 : 1;
            }
        });

        List<NotificationItem> items = new ArrayList<NotificationItem>();
        for (SourceNotification source : eligible) {
            if (items.size() >= Protocol.MAX_ITEMS) {
                break;
            }
            items.add(new NotificationItem(
                    source.key,
                    source.appLabel,
                    truncate(source.title, Protocol.MAX_TITLE_CHARS),
                    truncate(source.text, Protocol.MAX_TEXT_CHARS),
                    source.postedAt,
                    allowlist.get(source.packageName)));
        }

        return new Snapshot(snapshotId, items);
    }

    /** Null-safe truncation. NotificationItem forbids nulls, so normalise here. */
    private static String truncate(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        if (value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, maxChars);
    }
}
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew :phone:testDebugUnitTest :phone:assembleDebug`
Expected: PASS, 11 tests. BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add phone/
git commit -m "feat(phone): add module scaffold and the snapshot builder

Every product decision - what is shown, at what tier, in what order, how
much text survives - lives in one class with no Android types, so all of
it is unit tested on the host JVM.

Filtering happens here rather than on Glass because screened-out
notifications then never reach the radio, which is the largest battery
lever in the system.

Drops ongoing notifications: media players, navigation and other apps'
foreground services are persistent status, not events, and would occupy
queue slots permanently. Normalises null titles and text, which are
common in the wild and which NotificationItem forbids."
```

---

## Task 10: Notification listener and allowlist storage

**Files:**
- Create: `phone/src/main/java/dev/erinlkolp/glassnotify/phone/AllowlistStore.java`
- Create: `phone/src/main/java/dev/erinlkolp/glassnotify/phone/SbnMapper.java`
- Create: `phone/src/main/java/dev/erinlkolp/glassnotify/phone/NotifyListenerService.java`
- Create: `phone/src/main/java/dev/erinlkolp/glassnotify/phone/SnapshotBus.java`
- Modify: `phone/src/main/AndroidManifest.xml`
- Test: `phone/src/test/java/dev/erinlkolp/glassnotify/phone/AllowlistCodecTest.java`

**Interfaces:**
- Consumes: `SnapshotBuilder`, `SourceNotification`, `Tier`.
- Produces: `AllowlistStore(SharedPreferences)`, `rules():Map<String, Tier>`, `put(String pkg, Tier):void`, `remove(String pkg):void`; static `AllowlistStore.encode(Map<String, Tier>):Set<String>` and `AllowlistStore.decode(Set<String>):Map<String, Tier>`; `SbnMapper.map(StatusBarNotification, PackageManager):SourceNotification`; `SnapshotBus.latest():Snapshot`, `SnapshotBus.publish(Snapshot):void`, `SnapshotBus.setListener(SnapshotBus.Listener):void`, interface `SnapshotBus.Listener { void onSnapshot(Snapshot); }`; `SnapshotBus.DEBOUNCE_MS:long`.

- [ ] **Step 1: Write the failing test**

The encoding of the allowlist into a `SharedPreferences` string set is the only pure part; the rest is exercised on hardware in Task 13.

```java
package dev.erinlkolp.glassnotify.phone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

import dev.erinlkolp.glassnotify.wire.Tier;

public class AllowlistCodecTest {

    @Test
    public void roundTripsRules() {
        Map<String, Tier> rules = new HashMap<String, Tier>();
        rules.put("org.thoughtcrime.securesms", Tier.INTERRUPT);
        rules.put("com.slack", Tier.QUEUE);

        Map<String, Tier> decoded = AllowlistStore.decode(AllowlistStore.encode(rules));

        assertEquals(2, decoded.size());
        assertEquals(Tier.INTERRUPT, decoded.get("org.thoughtcrime.securesms"));
        assertEquals(Tier.QUEUE, decoded.get("com.slack"));
    }

    @Test
    public void roundTripsAnEmptyMap() {
        assertTrue(AllowlistStore.decode(AllowlistStore.encode(new HashMap<String, Tier>())).isEmpty());
    }

    @Test
    public void skipsMalformedEntriesRatherThanThrowing() {
        // A hand-edited or half-migrated preference must not crash the listener
        // service on boot.
        Set<String> raw = new HashSet<String>();
        raw.add("org.thoughtcrime.securesms|1");
        raw.add("garbage-with-no-separator");
        raw.add("com.example|999");
        raw.add("|1");

        Map<String, Tier> decoded = AllowlistStore.decode(raw);

        assertEquals(1, decoded.size());
        assertEquals(Tier.INTERRUPT, decoded.get("org.thoughtcrime.securesms"));
    }

    @Test
    public void toleratesNullFromSharedPreferences() {
        assertTrue(AllowlistStore.decode(null).isEmpty());
    }

    @Test
    public void packageNamesContainingTheSeparatorDoNotCorruptTheSet() {
        // Package names cannot contain '|', but proving the split is anchored
        // to the last separator costs nothing and documents the assumption.
        Map<String, Tier> rules = new HashMap<String, Tier>();
        rules.put("com.example.app", Tier.QUEUE);

        assertEquals(Tier.QUEUE,
                AllowlistStore.decode(AllowlistStore.encode(rules)).get("com.example.app"));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :phone:testDebugUnitTest --tests '*AllowlistCodecTest*'`
Expected: FAIL — `AllowlistStore` does not exist.

- [ ] **Step 3: Write `AllowlistStore.java`**

```java
package dev.erinlkolp.glassnotify.phone;

import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import dev.erinlkolp.glassnotify.wire.Tier;

/**
 * Persists which packages are shown, and at what tier.
 *
 * Stored as a SharedPreferences string set of "packageName|tierCode" so the
 * encoding is inspectable and testable without an Android runtime.
 */
public final class AllowlistStore {

    private static final String KEY_RULES = "allowlist_rules";
    private static final char SEPARATOR = '|';

    private final SharedPreferences prefs;

    public AllowlistStore(SharedPreferences prefs) {
        if (prefs == null) {
            throw new NullPointerException("prefs");
        }
        this.prefs = prefs;
    }

    public Map<String, Tier> rules() {
        return decode(prefs.getStringSet(KEY_RULES, null));
    }

    public void put(String packageName, Tier tier) {
        Map<String, Tier> rules = rules();
        rules.put(packageName, tier);
        save(rules);
    }

    public void remove(String packageName) {
        Map<String, Tier> rules = rules();
        rules.remove(packageName);
        save(rules);
    }

    private void save(Map<String, Tier> rules) {
        prefs.edit().putStringSet(KEY_RULES, encode(rules)).apply();
    }

    static Set<String> encode(Map<String, Tier> rules) {
        Set<String> encoded = new HashSet<String>();
        for (Map.Entry<String, Tier> entry : rules.entrySet()) {
            encoded.add(entry.getKey() + SEPARATOR + entry.getValue().code);
        }
        return encoded;
    }

    static Map<String, Tier> decode(Set<String> raw) {
        Map<String, Tier> rules = new HashMap<String, Tier>();
        if (raw == null) {
            return rules;
        }
        for (String entry : raw) {
            int split = entry.lastIndexOf(SEPARATOR);
            if (split <= 0 || split == entry.length() - 1) {
                // No separator, or nothing on one side of it. Skip rather than
                // throw: a corrupt preference must not stop the service booting.
                continue;
            }
            String packageName = entry.substring(0, split);
            Tier tier;
            try {
                tier = Tier.fromCode(Integer.parseInt(entry.substring(split + 1)));
            } catch (NumberFormatException e) {
                continue;
            }
            if (tier != null) {
                rules.put(packageName, tier);
            }
        }
        return rules;
    }
}
```

- [ ] **Step 4: Write `SnapshotBus.java`**

```java
package dev.erinlkolp.glassnotify.phone;

import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;

import dev.erinlkolp.glassnotify.wire.NotificationItem;
import dev.erinlkolp.glassnotify.wire.Snapshot;

/**
 * Carries snapshots from the listener service to the link service, debouncing
 * on the way.
 *
 * A single group message can fire several onNotificationPosted callbacks in a
 * burst. Coalescing them into one snapshot is the second big battery lever
 * after phone-side filtering. Spec section 10.2.
 */
public final class SnapshotBus {

    /** Spec section 10.2. */
    public static final long DEBOUNCE_MS = 500L;

    public interface Listener {
        void onSnapshot(Snapshot snapshot);
    }

    private static final SnapshotBus INSTANCE = new SnapshotBus();

    private final Handler handler = new Handler(Looper.getMainLooper());

    private volatile Snapshot latest =
            new Snapshot(0L, new ArrayList<NotificationItem>());
    private volatile Listener listener;
    private boolean pending;

    private final Runnable deliver = new Runnable() {
        @Override
        public void run() {
            pending = false;
            Listener target = listener;
            if (target != null) {
                target.onSnapshot(latest);
            }
        }
    };

    private SnapshotBus() {
    }

    public static SnapshotBus get() {
        return INSTANCE;
    }

    /** Never null. The link service sends this on connect. */
    public Snapshot latest() {
        return latest;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** Replaces the current snapshot and schedules a debounced delivery. */
    public void publish(Snapshot snapshot) {
        latest = snapshot;
        if (!pending) {
            pending = true;
            handler.postDelayed(deliver, DEBOUNCE_MS);
        }
    }
}
```

- [ ] **Step 5: Write `SbnMapper.java`**

```java
package dev.erinlkolp.glassnotify.phone;

import android.app.Notification;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;

/**
 * The single place StatusBarNotification is touched.
 *
 * Everything downstream operates on SourceNotification, which has no Android
 * types and is therefore testable on the host. Spec section 12.2.
 */
public final class SbnMapper {

    private SbnMapper() {
    }

    public static SourceNotification map(StatusBarNotification sbn, PackageManager packages) {
        Notification notification = sbn.getNotification();
        Bundle extras = notification == null ? null : notification.extras;

        String title = extras == null ? null : charSequence(extras, Notification.EXTRA_TITLE);
        String text = extras == null ? null : charSequence(extras, Notification.EXTRA_TEXT);

        if (text == null && extras != null) {
            // Big-text style puts the body here instead.
            text = charSequence(extras, Notification.EXTRA_BIG_TEXT);
        }

        boolean ongoing = notification != null
                && (notification.flags & Notification.FLAG_ONGOING_EVENT) != 0;

        return new SourceNotification(
                sbn.getKey(),
                sbn.getPackageName(),
                appLabel(sbn.getPackageName(), packages),
                title,
                text,
                sbn.getPostTime(),
                ongoing);
    }

    private static String charSequence(Bundle extras, String key) {
        CharSequence value = extras.getCharSequence(key);
        return value == null ? null : value.toString();
    }

    /** Falls back to the package name, which is ugly but never wrong. */
    private static String appLabel(String packageName, PackageManager packages) {
        try {
            ApplicationInfo info = packages.getApplicationInfo(packageName, 0);
            CharSequence label = packages.getApplicationLabel(info);
            return label == null ? packageName : label.toString();
        } catch (PackageManager.NameNotFoundException e) {
            return packageName;
        }
    }
}
```

- [ ] **Step 6: Write `NotifyListenerService.java`**

```java
package dev.erinlkolp.glassnotify.phone;

import android.content.Context;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import dev.erinlkolp.glassnotify.wire.Snapshot;
import dev.erinlkolp.glassnotify.wire.Tier;

/**
 * Observes system notifications and republishes the whole current state.
 *
 * Bound and revived by the system automatically once notification access is
 * granted. Every callback rebuilds the entire snapshot rather than tracking
 * deltas, which is what makes the transport idempotent. Spec section 6.
 */
public final class NotifyListenerService extends NotificationListenerService {

    private static final String TAG = "GlassNotify";

    private long sequence;

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        // getActiveNotifications is only valid once connected, so this is the
        // first point at which a complete snapshot can be built.
        republish();
        // Task 11 adds the LinkClientService.start(this) call here, once that
        // class exists. Publishing to the bus is useful on its own until then.
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        republish();
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        // Removals matter as much as posts: without them the Glass queue rots
        // within a day. Spec section 6.
        republish();
    }

    private void republish() {
        StatusBarNotification[] active;
        try {
            active = getActiveNotifications();
        } catch (SecurityException e) {
            // Access was revoked underneath us.
            Log.w(TAG, "notification access unavailable", e);
            return;
        }
        if (active == null) {
            return;
        }

        List<SourceNotification> sources = new ArrayList<SourceNotification>(active.length);
        for (StatusBarNotification sbn : active) {
            sources.add(SbnMapper.map(sbn, getPackageManager()));
        }

        Map<String, Tier> rules = new AllowlistStore(
                getSharedPreferences(GlassNotifyPrefs.NAME, Context.MODE_PRIVATE)).rules();

        Snapshot snapshot = SnapshotBuilder.build(++sequence, sources, rules);
        SnapshotBus.get().publish(snapshot);
    }
}
```

- [ ] **Step 7: Write `GlassNotifyPrefs.java`**

```java
package dev.erinlkolp.glassnotify.phone;

/** Single source of truth for the SharedPreferences file name. */
public final class GlassNotifyPrefs {

    public static final String NAME = "glassnotify";

    private GlassNotifyPrefs() {
    }
}
```

- [ ] **Step 8: Register the listener in the manifest**

Add inside `<application>`:

```xml
        <service
            android:name=".NotifyListenerService"
            android:exported="true"
            android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE">
            <intent-filter>
                <action android:name="android.service.notification.NotificationListenerService" />
            </intent-filter>
        </service>
```

- [ ] **Step 9: Run the tests to verify they pass**

Run: `./gradlew :phone:testDebugUnitTest`
Expected: PASS, 16 tests.

Also run `./gradlew :phone:assembleDebug` and expect BUILD SUCCESSFUL. This task deliberately does not reference `LinkClientService`, which does not exist yet — Task 11 adds that call. The module must compile standalone at the end of every task.

- [ ] **Step 10: Commit**

```bash
git add phone/
git commit -m "feat(phone): add notification listener, allowlist and snapshot bus

Every listener callback rebuilds the complete snapshot rather than
tracking deltas - that is what makes the transport idempotent, and it
means removals are handled by construction rather than as a special
case. Without removals the Glass queue would rot within a day.

SnapshotBus debounces by 500ms because one group message fires several
onNotificationPosted callbacks in a burst; coalescing them is the second
battery lever after phone-side filtering.

The allowlist decoder skips malformed entries instead of throwing, so a
corrupt preference cannot stop the listener service from starting."
```

---

## Task 11: RFCOMM client, backoff, and fast reconnect

**Files:**
- Create: `phone/src/main/java/dev/erinlkolp/glassnotify/phone/Backoff.java`
- Create: `phone/src/main/java/dev/erinlkolp/glassnotify/phone/LinkClientService.java`
- Create: `phone/src/main/java/dev/erinlkolp/glassnotify/phone/AclReceiver.java`
- Modify: `phone/src/main/AndroidManifest.xml`
- Modify: `phone/src/main/java/dev/erinlkolp/glassnotify/phone/NotifyListenerService.java` (restore the `LinkClientService.start` call)
- Test: `phone/src/test/java/dev/erinlkolp/glassnotify/phone/BackoffTest.java`

**Interfaces:**
- Consumes: `SnapshotBus`, `FrameCodec`, `SnapshotCodec`, `HelloCodec`, `Protocol`, `MessageType`.
- Produces: `Backoff()`, `nextDelayMs():long`, `reset():void`, `Backoff.INITIAL_MS`, `Backoff.MAX_MS`; `LinkClientService.start(Context):void`, `LinkClientService.wake(Context):void`.

- [ ] **Step 1: Write the failing test**

```java
package dev.erinlkolp.glassnotify.phone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

public class BackoffTest {

    private Backoff backoff;

    @Before
    public void setUp() {
        backoff = new Backoff();
    }

    @Test
    public void startsShort() {
        // The common case is Glass momentarily out of range. Waiting a minute
        // for the first retry would make that feel broken.
        assertEquals(Backoff.INITIAL_MS, backoff.nextDelayMs());
    }

    @Test
    public void doublesEachTime() {
        assertEquals(Backoff.INITIAL_MS, backoff.nextDelayMs());
        assertEquals(Backoff.INITIAL_MS * 2, backoff.nextDelayMs());
        assertEquals(Backoff.INITIAL_MS * 4, backoff.nextDelayMs());
        assertEquals(Backoff.INITIAL_MS * 8, backoff.nextDelayMs());
    }

    @Test
    public void capsAtTheCeiling() {
        for (int i = 0; i < 100; i++) {
            assertTrue(backoff.nextDelayMs() <= Backoff.MAX_MS);
        }
        assertEquals(Backoff.MAX_MS, backoff.nextDelayMs());
    }

    @Test
    public void neverOverflows() {
        // 100 doublings would overflow a long if implemented naively.
        for (int i = 0; i < 100; i++) {
            assertTrue("delay went negative at attempt " + i, backoff.nextDelayMs() > 0);
        }
    }

    @Test
    public void resetReturnsToTheStart() {
        backoff.nextDelayMs();
        backoff.nextDelayMs();
        backoff.nextDelayMs();

        backoff.reset();

        assertEquals(Backoff.INITIAL_MS, backoff.nextDelayMs());
    }

    @Test
    public void ceilingIsSixtySeconds() {
        // Spec section 10.2 fixes this; a longer ceiling makes walking back
        // into range feel dead.
        assertEquals(60_000L, Backoff.MAX_MS);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :phone:testDebugUnitTest --tests '*BackoffTest*'`
Expected: FAIL — `Backoff` does not exist.

- [ ] **Step 3: Write `Backoff.java`**

```java
package dev.erinlkolp.glassnotify.phone;

/**
 * Exponential reconnect delay, capped.
 *
 * Pure logic so the sequence is testable without waiting in real time.
 * Spec section 10.2.
 */
public final class Backoff {

    public static final long INITIAL_MS = 1_000L;
    public static final long MAX_MS = 60_000L;

    private long next = INITIAL_MS;

    /** Returns the delay to wait before the next attempt, then advances. */
    public long nextDelayMs() {
        long delay = next;
        if (next < MAX_MS) {
            // Double, but clamp before assigning so repeated calls cannot overflow.
            long doubled = next * 2;
            next = (doubled > MAX_MS || doubled < 0) ? MAX_MS : doubled;
        }
        return delay;
    }

    /** Called on a successful connection. */
    public void reset() {
        next = INITIAL_MS;
    }
}
```

- [ ] **Step 4: Write `LinkClientService.java`**

```java
package dev.erinlkolp.glassnotify.phone;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Set;

import dev.erinlkolp.glassnotify.wire.FrameCodec;
import dev.erinlkolp.glassnotify.wire.Hello;
import dev.erinlkolp.glassnotify.wire.HelloCodec;
import dev.erinlkolp.glassnotify.wire.MessageType;
import dev.erinlkolp.glassnotify.wire.Protocol;
import dev.erinlkolp.glassnotify.wire.Snapshot;
import dev.erinlkolp.glassnotify.wire.SnapshotCodec;

/**
 * Owns the RFCOMM connection to Glass and the reconnect loop.
 *
 * A foreground service because targetSdk 28 forbids indefinite background
 * execution, and because this must survive Doze and App Standby. The phone
 * owns retry rather than Glass, since a backoff loop belongs on the device
 * with the larger battery. Spec section 5.
 */
public final class LinkClientService extends Service implements SnapshotBus.Listener {

    private static final String TAG = "GlassNotify";
    private static final String CHANNEL_ID = "glass_link";
    private static final int NOTIFICATION_ID = 1;

    /** Spec section 7.3. */
    private static final long PING_INTERVAL_MS = 10_000L;

    private final Backoff backoff = new Backoff();
    private final Object socketLock = new Object();

    private volatile boolean running;
    private Thread worker;
    private BluetoothSocket socket;

    /** Set when something wants an immediate retry, e.g. ACL_CONNECTED. */
    private final Object wakeLock = new Object();

    public static void start(Context context) {
        context.startService(new Intent(context, LinkClientService.class));
    }

    /** Cuts the current backoff short. Called when Glass comes into range. */
    public static void wake(Context context) {
        Intent intent = new Intent(context, LinkClientService.class);
        intent.putExtra("wake", true);
        context.startService(intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.status_connecting)));
        SnapshotBus.get().setListener(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getBooleanExtra("wake", false)) {
            backoff.reset();
            synchronized (wakeLock) {
                wakeLock.notifyAll();
            }
        }
        if (!running) {
            running = true;
            worker = new Thread(new Runnable() {
                @Override
                public void run() {
                    connectLoop();
                }
            }, "glassnotify-link");
            worker.start();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        SnapshotBus.get().setListener(null);
        closeSocket();
        synchronized (wakeLock) {
            wakeLock.notifyAll();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void connectLoop() {
        while (running) {
            BluetoothDevice glass = findBondedGlass();
            if (glass == null) {
                status(BluetoothAdapter.getDefaultAdapter() == null
                        || !BluetoothAdapter.getDefaultAdapter().isEnabled()
                        ? R.string.status_no_bluetooth
                        : R.string.status_not_bonded);
                waitFor(10_000L);
                continue;
            }

            BluetoothSocket attempt = null;
            try {
                attempt = glass.createRfcommSocketToServiceRecord(Protocol.SERVICE_UUID);
                // Discovery is expensive and interferes with connecting.
                BluetoothAdapter.getDefaultAdapter().cancelDiscovery();
                attempt.connect();

                synchronized (socketLock) {
                    socket = attempt;
                }
                backoff.reset();
                status(R.string.status_connected);
                pump(attempt);
            } catch (IOException e) {
                Log.i(TAG, "connect failed: " + e.getMessage());
            } finally {
                closeQuietly(attempt);
                synchronized (socketLock) {
                    socket = null;
                }
            }

            if (running) {
                status(R.string.status_connecting);
                waitFor(backoff.nextDelayMs());
            }
        }
    }

    /** Sends the handshake, an immediate snapshot, then heartbeats until the link dies. */
    private void pump(BluetoothSocket connected) throws IOException {
        OutputStream out = connected.getOutputStream();

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        FrameCodec.write(out, MessageType.HELLO,
                HelloCodec.encode(new Hello(
                        adapter.getName() == null ? "phone" : adapter.getName(),
                        adapter.getAddress() == null ? "" : adapter.getAddress())));

        // Glass has whatever it cached from last time; replace it immediately.
        send(SnapshotBus.get().latest());

        while (running) {
            waitFor(PING_INTERVAL_MS);
            if (!running) {
                return;
            }
            // A write failure is how a half-dead socket is discovered - there
            // is no read side to notice EOF on. Throws out to the retry loop.
            FrameCodec.write(out, MessageType.PING, new byte[0]);
        }
    }

    @Override
    public void onSnapshot(Snapshot snapshot) {
        send(snapshot);
    }

    private void send(Snapshot snapshot) {
        BluetoothSocket current;
        synchronized (socketLock) {
            current = socket;
        }
        if (current == null) {
            return; // Not connected. The next connection sends the latest anyway.
        }
        try {
            FrameCodec.write(current.getOutputStream(), MessageType.SNAPSHOT,
                    SnapshotCodec.encode(snapshot));
        } catch (IOException e) {
            Log.i(TAG, "send failed, dropping link: " + e.getMessage());
            closeSocket(); // Unblocks the worker so it can back off and retry.
        }
    }

    private BluetoothDevice findBondedGlass() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            return null;
        }
        Set<BluetoothDevice> bonded = adapter.getBondedDevices();
        if (bonded == null) {
            return null;
        }
        for (BluetoothDevice device : bonded) {
            String name = device.getName();
            if (name != null && name.toLowerCase(java.util.Locale.US).contains("glass")) {
                return device;
            }
        }
        return null;
    }

    /** Sleeps, but returns early if wake() is called. */
    private void waitFor(long ms) {
        synchronized (wakeLock) {
            try {
                wakeLock.wait(ms);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void closeSocket() {
        synchronized (socketLock) {
            closeQuietly(socket);
            socket = null;
        }
    }

    private static void closeQuietly(BluetoothSocket s) {
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void status(int stringRes) {
        NotificationManager manager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        manager.notify(NOTIFICATION_ID, buildNotification(getString(stringRes)));
    }

    private void createChannel() {
        NotificationManager manager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                getString(R.string.channel_link), NotificationManager.IMPORTANCE_MIN);
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    private Notification buildNotification(String text) {
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setOngoing(true)
                .build();
    }
}
```

- [ ] **Step 5: Write `AclReceiver.java`**

```java
package dev.erinlkolp.glassnotify.phone;

import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.util.Locale;

/**
 * Cuts the reconnect backoff short when Glass comes into range.
 *
 * Without this, walking back to your desk can mean waiting out a full 60
 * second interval before notifications resume, which feels broken even though
 * it is working. Spec section 10.2.
 */
public final class AclReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!BluetoothDevice.ACTION_ACL_CONNECTED.equals(intent.getAction())) {
            return;
        }
        BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
        if (device == null) {
            return;
        }
        String name = device.getName();
        if (name != null && name.toLowerCase(Locale.US).contains("glass")) {
            LinkClientService.wake(context);
        }
    }
}
```

- [ ] **Step 6: Restore the listener's start call and register components**

In `NotifyListenerService.onListenerConnected`, ensure this line is present and uncommented:

```java
        LinkClientService.start(this);
```

Add inside `<application>` in `phone/src/main/AndroidManifest.xml`:

```xml
        <service
            android:name=".LinkClientService"
            android:exported="false" />

        <receiver
            android:name=".AclReceiver"
            android:exported="true">
            <intent-filter>
                <action android:name="android.bluetooth.device.action.ACL_CONNECTED" />
            </intent-filter>
        </receiver>
```

- [ ] **Step 7: Run the tests and build**

Run: `./gradlew :phone:testDebugUnitTest :phone:assembleDebug`
Expected: PASS, 22 tests. BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add phone/
git commit -m "feat(phone): add RFCOMM client, backoff and fast reconnect

A foreground service, because targetSdk 28 forbids indefinite background
execution and the link must survive Doze and App Standby.

The backoff wait is a monitor wait rather than a sleep, so ACL_CONNECTED
can cut it short the moment Glass comes into range - otherwise walking
back to your desk means waiting out a full 60 seconds before
notifications resume, which feels broken even though it is working.

PING doubles as liveness detection for the sender. There is no read side
on this end, so a failed write is the only way a half-dead RFCOMM socket
gets noticed. Backoff clamps before assigning so repeated doubling
cannot overflow into a negative delay."
```

---

## Task 12: Setup and allowlist screens

**Files:**
- Create: `phone/src/main/java/dev/erinlkolp/glassnotify/phone/SetupActivity.java`
- Create: `phone/src/main/java/dev/erinlkolp/glassnotify/phone/AllowlistActivity.java`
- Modify: `phone/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: `AllowlistStore`, `GlassNotifyPrefs`, `LinkClientService`, `Tier`.
- Produces: nothing consumed by later tasks.

- [ ] **Step 1: Write `SetupActivity.java`**

```java
package dev.erinlkolp.glassnotify.phone;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Locale;
import java.util.Set;

/**
 * Shows what still needs granting, and links straight to each system screen.
 *
 * Three things must be true before anything works, and each fails silently in
 * its own way, so each gets its own visible line rather than one vague
 * "not working" state.
 */
public final class SetupActivity extends Activity {

    private TextView accessStatus;
    private TextView bondStatus;
    private TextView batteryStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        accessStatus = addRow(root, getString(R.string.grant_notification_access),
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        startActivity(new Intent(
                                "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
                    }
                });

        bondStatus = addRow(root, "Pair with Glass", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
            }
        });

        batteryStatus = addRow(root, getString(R.string.grant_battery_exemption),
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        requestBatteryExemption();
                    }
                });

        addRow(root, getString(R.string.configure_allowlist), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(SetupActivity.this, AllowlistActivity.class));
            }
        });

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        accessStatus.setText(hasNotificationAccess() ? "Granted" : "Not granted");
        bondStatus.setText(bondedGlassName() == null
                ? "No paired device named 'Glass'" : "Paired with " + bondedGlassName());
        batteryStatus.setText(isBatteryExempt() ? "Exempt" : "Not exempt");

        if (hasNotificationAccess()) {
            LinkClientService.start(this);
        }
    }

    /**
     * Reads the same colon-separated setting the system uses, rather than
     * inferring from whether our service has been bound - which is racy right
     * after a grant.
     */
    private boolean hasNotificationAccess() {
        String enabled = Settings.Secure.getString(getContentResolver(),
                "enabled_notification_listeners");
        if (enabled == null) {
            return false;
        }
        String target = getPackageName() + "/" + NotifyListenerService.class.getName();
        for (String entry : enabled.split(":")) {
            if (entry.equals(target)) {
                return true;
            }
        }
        return false;
    }

    private String bondedGlassName() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            return null;
        }
        Set<BluetoothDevice> bonded = adapter.getBondedDevices();
        if (bonded == null) {
            return null;
        }
        for (BluetoothDevice device : bonded) {
            String name = device.getName();
            if (name != null && name.toLowerCase(Locale.US).contains("glass")) {
                return name;
            }
        }
        return null;
    }

    private boolean isBatteryExempt() {
        PowerManager power = (PowerManager) getSystemService(Context.POWER_SERVICE);
        return power != null && power.isIgnoringBatteryOptimizations(getPackageName());
    }

    private void requestBatteryExemption() {
        // No SDK_INT guard: minSdk is 26, well above the API 23 this needs.
        Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
        intent.setData(Uri.parse("package:" + getPackageName()));
        startActivity(intent);
    }

    private TextView addRow(LinearLayout parent, String label, View.OnClickListener onClick) {
        TextView title = new TextView(this);
        title.setText(label);
        title.setTextSize(18f);
        parent.addView(title);

        TextView status = new TextView(this);
        status.setTextSize(14f);
        parent.addView(status);

        Button button = new Button(this);
        button.setText("Open");
        button.setOnClickListener(onClick);
        parent.addView(button);

        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (int) (24 * getResources().getDisplayMetrics().density)));
        parent.addView(spacer);

        return status;
    }
}
```

- [ ] **Step 2: Write `AllowlistActivity.java`**

```java
package dev.erinlkolp.glassnotify.phone;

import android.app.Activity;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import dev.erinlkolp.glassnotify.wire.Tier;

/**
 * Per-app tier configuration.
 *
 * Tapping an app cycles it OFF -> QUEUE -> INTERRUPT -> OFF. A three-state
 * cycle on one row beats a checkbox plus a separate tier control, and the
 * whole list is short enough that scanning it is fine.
 */
public final class AllowlistActivity extends Activity {

    private AllowlistStore store;
    private final List<ApplicationInfo> apps = new ArrayList<ApplicationInfo>();
    private ArrayAdapter<ApplicationInfo> adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        store = new AllowlistStore(getSharedPreferences(GlassNotifyPrefs.NAME, Context.MODE_PRIVATE));
        loadApps();

        adapter = new ArrayAdapter<ApplicationInfo>(this, 0, apps) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView row = (TextView) convertView;
                if (row == null) {
                    row = new TextView(AllowlistActivity.this);
                    int pad = (int) (12 * getResources().getDisplayMetrics().density);
                    row.setPadding(pad, pad, pad, pad);
                    row.setTextSize(16f);
                }
                ApplicationInfo info = getItem(position);
                Tier tier = store.rules().get(info.packageName);
                row.setText(label(info) + "\n" + describe(tier));
                return row;
            }
        };

        ListView list = new ListView(this);
        list.setAdapter(adapter);
        list.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(android.widget.AdapterView<?> parent, View view,
                    int position, long id) {
                cycle(apps.get(position).packageName);
                adapter.notifyDataSetChanged();
            }
        });
        setContentView(list);
    }

    private void cycle(String packageName) {
        Map<String, Tier> rules = store.rules();
        Tier current = rules.get(packageName);
        if (current == null) {
            store.put(packageName, Tier.QUEUE);
        } else if (current == Tier.QUEUE) {
            store.put(packageName, Tier.INTERRUPT);
        } else {
            store.remove(packageName);
        }
    }

    private static String describe(Tier tier) {
        if (tier == null) {
            return "Not shown";
        }
        return tier == Tier.INTERRUPT ? "Interrupts" : "Queued silently";
    }

    private String label(ApplicationInfo info) {
        CharSequence label = getPackageManager().getApplicationLabel(info);
        return label == null ? info.packageName : label.toString();
    }

    /** Only apps that can actually produce notifications the user recognises. */
    private void loadApps() {
        PackageManager packages = getPackageManager();
        for (ApplicationInfo info : packages.getInstalledApplications(0)) {
            // Launchable apps only: system packages without a launcher entry
            // would bury the list in noise.
            if (packages.getLaunchIntentForPackage(info.packageName) != null) {
                apps.add(info);
            }
        }
        Collections.sort(apps, new Comparator<ApplicationInfo>() {
            @Override
            public int compare(ApplicationInfo a, ApplicationInfo b) {
                return label(a).compareToIgnoreCase(label(b));
            }
        });
    }
}
```

- [ ] **Step 3: Register both activities**

Add inside `<application>`:

```xml
        <activity
            android:name=".SetupActivity"
            android:exported="true"
            android:label="@string/app_name">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <activity
            android:name=".AllowlistActivity"
            android:exported="false"
            android:label="@string/configure_allowlist" />
```

- [ ] **Step 4: Build and install**

```bash
./gradlew :phone:assembleDebug
adb -s VS9967edd915b install -r phone/build/outputs/apk/debug/phone-debug.apk
adb -s VS9967edd915b shell am start -n dev.erinlkolp.glassnotify.phone/.SetupActivity
```

Expected: the setup screen lists four rows, each with a live status line. Notification access and battery exemption will both read as not granted.

- [ ] **Step 5: Commit**

```bash
git add phone/
git commit -m "feat(phone): add setup and allowlist screens

Three things must be true before anything works - notification access,
a bond with Glass, and a battery-optimisation exemption - and each fails
silently in a different way. Each gets its own status line linking
straight to the relevant system screen rather than one vague
'not working' state.

Notification access is read from the enabled_notification_listeners
secure setting rather than inferred from whether our service has been
bound, which is racy immediately after a grant.

The allowlist cycles each app OFF -> QUEUE -> INTERRUPT on tap, and
lists only launchable packages so system noise stays out of it."
```

---

## Task 13: Hardware bring-up

Nothing here is unit-testable. This is the task where the two devices meet.

**Files:**
- Create: `README.md`
- Modify: `docs/superpowers/specs/2026-08-04-glass-notifications-design.md` (record tuned values)

**Interfaces:**
- Consumes: everything.
- Produces: nothing.

- [ ] **Step 1: Pair the two devices**

This is manual and one-time; the apps never attempt programmatic pairing. (§11)

```bash
# Confirm Glass's Bluetooth is on and discoverable
adb -s 0123456789ABCDEF shell dumpsys bluetooth_manager | grep -iE "^ *(enabled|state|name):"
```

On the V30: Settings → Connected devices → Bluetooth → pair with **Glass 1**. Then verify from the phone side:

```bash
adb -s VS9967edd915b shell dumpsys bluetooth_manager | grep -iA4 "Bonded devices"
```

Expected: `22:22:41:C5:E5:67` appears.

- [ ] **Step 2: Grant notification access and the battery exemption**

Open the phone app's setup screen and use each row's button. Verify:

```bash
adb -s VS9967edd915b shell settings get secure enabled_notification_listeners
```

Expected: the output contains `dev.erinlkolp.glassnotify.phone/dev.erinlkolp.glassnotify.phone.NotifyListenerService`.

- [ ] **Step 3: Configure an allowlist**

In the phone app, set at least one app to QUEUE and one to INTERRUPT. Pick something you can trigger on demand.

- [ ] **Step 4: Verify the link comes up**

```bash
adb -s 0123456789ABCDEF logcat -c
adb -s VS9967edd915b logcat -c
adb -s 0123456789ABCDEF logcat -s GlassNotify &
adb -s VS9967edd915b logcat -s GlassNotify &
```

Expected on Glass: `connected to 10:F1:F2:EE:90:8F` and `hello from V30`.
Expected on the phone: the persistent notification reads "Connected to Glass".

- [ ] **Step 5: Verify a real notification end to end**

Send yourself a message in an app configured as INTERRUPT.

Expected: the glanceable card appears on the prism within a second or two and clears after ~5s. Opening the queue from the launcher shows it.

Then dismiss it on the phone. Expected: it disappears from Glass's queue on the next snapshot.

- [ ] **Step 6: Verify reconnection**

```bash
# Kill the link and confirm it comes back
adb -s VS9967edd915b shell am force-stop dev.erinlkolp.glassnotify.phone
```

Expected: Glass's queue goes stale after ~30s. Reopening the phone app restores the link and the queue.

Then walk out of Bluetooth range for a minute and come back. Expected: reconnection within a few seconds of returning, not a full 60-second wait — that is `AclReceiver` doing its job.

- [ ] **Step 7: Real-finger touch testing**

**Mandatory, and not substitutable with automation.** `adb shell input tap/swipe` injects below the window manager, bypasses touchable regions entirely, and cannot inject multitouch at all. On the gesture launcher it produced 40 green tests while two real bugs were live. (§12.5)

With an actual finger, on the actual device:

- Page forward and back through a queue of several items. Confirm it stops at both ends rather than wrapping.
- Swipe near the **top** of the touchpad. Confirm the notification shade does **not** open — this is the `IMMERSIVE_STICKY` fix working.
- Confirm a horizontal swipe registers reliably, and a deliberate vertical drag does not page.
- Confirm the interrupt card is legible outdoors, or against a bright background.

Note any thresholds that feel wrong. `SwipeDetector.SWIPE_MIN_DX`, `HORIZONTAL_DOMINANCE` and `TAP_MAX_MS` are starting values chosen on reasoning, not measurement.

- [ ] **Step 8: Verify boot persistence**

```bash
adb -s 0123456789ABCDEF reboot
# wait for it to come back
adb -s 0123456789ABCDEF wait-for-device
adb -s 0123456789ABCDEF logcat -s GlassNotify
```

Expected: the service starts without launching the app, and the phone reconnects on its own.

Also confirm the pinned MAC survived, and that the reset path works:

```bash
adb -s 0123456789ABCDEF shell run-as dev.erinlkolp.glassnotify.glass cat shared_prefs/glassnotify.xml
```

- [ ] **Step 9: Tune and record**

Update §14 of the spec with the values you actually settled on: interrupt display duration, ping interval, staleness threshold, and the three swipe thresholds. Replace "starting value to be tuned on hardware" with the measured figure and a sentence on why.

- [ ] **Step 10: Write a detailed `README.md`**

This is the document Erin will actually reach for months from now, so it must
stand alone — someone should be able to rebuild, reflash, and debug this
project from the README without reading the spec or this plan. Write it fully;
do not abbreviate sections with "see the spec".

**Required sections, in this order:**

1. **What it is and what it is not.** One paragraph. State plainly that it
   mirrors a *carried second Android phone*, not the iPhone, and that iMessage
   will never appear. Link the spec for the reasoning.
2. **Why not the iPhone.** Short, with the measured probe output
   (`getBluetoothLeAdvertiser = NULL`, `isPeripheralModeSupported = false`) and
   one sentence on why that kills ANCS. This is the question future-Erin will
   ask first, and the answer must not require re-deriving it.
3. **Hardware.** The device table from the plan's *Device Facts* — adb serials,
   Bluetooth MACs, BT names, API levels — plus a note that Glass's MAC is a
   generated locally-administered address, not the factory one, and is
   regenerated by a `/data` wipe (spec §5.1).
4. **Architecture.** The three modules, one paragraph each, saying what lives
   where and *why the boundary is there* — in particular why `wire` has no
   Android imports and why filtering happens on the phone.
5. **How the protocol works.** The frame layout diagram, the three message
   types, and one paragraph on why it is whole snapshots rather than deltas.
6. **Prerequisites.** JDK 21 (`openjdk-21-jdk-headless`, *not* the JRE package —
   say why), `ANDROID_HOME`, and the udev note for LG (`1004`).
7. **Build and install.** Exact commands for both APKs, both serials.
8. **First-run setup.** The complete ordered checklist: pair the devices,
   grant notification access, grant the battery exemption, configure the
   allowlist. Include the `adb` command to verify each one.
9. **Developing the Glass UI without the phone.** `scripts/fake-notify.sh`
   usage with worked examples for both tiers and `--clear`.
10. **Testing.** How to run the unit tests, what they cover, and the
    real-finger requirement with the reason (`adb shell input` injects below
    the window manager — 40 green tests hid two live bugs on the previous
    project).
11. **Troubleshooting.** A table of concrete symptoms and causes, at minimum:
    nothing appears on Glass; queue shows stale; "phone app out of date"; Glass
    refuses the connection after a reflash; V30 not visible to adb (USB mode is
    orthogonal to USB debugging — watch for interface `255/66/1`, not a PID
    change); RSA prompt never appears (screen must be unlocked).
12. **Recovery.** Clearing the pinned MAC, and clearing `/data/dalvik-cache`
    when testing failure paths.
13. **Tuned values.** The table of settled timing and threshold constants from
    Step 9, with where each lives in the source.

- [ ] **Step 11: Commit**

```bash
git add README.md docs/
git commit -m "docs: add README and record hardware-tuned values

Replaces the placeholder timing and threshold values in spec section 14
with the figures actually settled on during bring-up."
```

---

## Self-Review

Checked after writing, against the spec.

**Spec coverage.** Every section maps to a task: §4 architecture → Tasks 1, 4, 9; §5 transport topology → Tasks 8, 11; §5.1 generated MAC → Task 5 (`PeerPin`) and Task 13 Step 8; §6 snapshots → Tasks 1, 3, 10; §7 protocol → Tasks 1–3; §8 tiering → Task 9; §9 display → Tasks 6, 7; §10 lifecycle → Tasks 8, 11; §11 failure handling → Tasks 5, 8, 11; §11.1 pinning and reset → Task 5, plus the README recovery section; §12 testing → distributed, with §12.4's fake feed in Task 8 and §12.5's real-finger requirement in Task 13 Step 7.

**Two fixes applied during review:**

1. `Ages.java` and `GlassNotify.java` (Task 6) and `GlassNotifyPrefs.java` (Task 10) appear as implementation steps but were missing from their tasks' **Files** lists. Corrected below.
2. `R.string.status_*` are used by `LinkClientService` in Task 11 but declared in Task 9's `strings.xml`. That ordering is correct — noting it so a reviewer of Task 11 alone does not flag them as undefined.

**Corrected file lists:**
- Task 6 also creates `glass/.../Ages.java` and `glass/.../GlassNotify.java`.
- Task 10 also creates `phone/.../GlassNotifyPrefs.java`.

**Type consistency.** `SnapshotStore.isStale` exists in both instance and static form and both are used. `InterruptPolicy.selectInterrupt` takes `(Snapshot previous, Snapshot next)` everywhere, with `null` previous meaning first-of-connection. `AllowlistStore.encode`/`decode` are package-private static and tested from the same package. `Tier.fromCode` returns `null` rather than throwing, and every call site handles that.

**Known deferred item.** `SnapshotBus` is a singleton reached via `get()`, while `GlassNotify` on the Glass side uses static accessors taking a `Context`. The asymmetry is deliberate — the phone's bus needs no `Context` — but if a third consumer appears, unify them.

