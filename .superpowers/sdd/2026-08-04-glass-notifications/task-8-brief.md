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

