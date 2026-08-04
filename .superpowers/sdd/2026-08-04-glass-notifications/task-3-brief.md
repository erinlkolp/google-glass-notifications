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

