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

