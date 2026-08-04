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

