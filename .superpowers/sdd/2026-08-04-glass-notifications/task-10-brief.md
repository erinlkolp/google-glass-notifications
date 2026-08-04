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

