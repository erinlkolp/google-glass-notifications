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

