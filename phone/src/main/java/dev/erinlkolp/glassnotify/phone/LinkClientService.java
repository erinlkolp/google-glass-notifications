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
