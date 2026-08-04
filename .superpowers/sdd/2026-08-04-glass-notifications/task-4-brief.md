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

