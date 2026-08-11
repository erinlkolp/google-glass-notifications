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
    <string name="charged_text">100%% — ready to go</string>
```

The doubled `%%` is required: a single `%` in an Android string resource is treated as a format specifier and fails the build with "Multiple substitutions specified in non-positional format".

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

