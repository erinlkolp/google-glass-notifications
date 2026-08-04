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
import android.os.SystemClock;
import android.util.Log;

import java.io.IOException;
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
 *
 * <h3>Threading</h3>
 *
 * There is exactly one writer. The worker thread owns the socket outright and
 * is the only code in this class that calls {@link FrameCodec#write} - HELLO,
 * SNAPSHOT and PING all leave from it, in that one thread, in order. Nothing
 * else may touch the output stream.
 *
 * Everything else is a handoff. {@link #onSnapshot} runs on the main thread
 * (SnapshotBus delivers on the main looper) and does nothing but raise a flag
 * and signal the worker; it never touches a socket and never blocks. The
 * worker then re-reads {@link SnapshotBus#latest()}, which is why coalescing
 * is free: several snapshots arriving before the worker wakes collapse into
 * the newest one, with no queue to bound.
 *
 * This shape exists because the two obvious alternatives are both broken.
 * Writing from the bus callback puts a blocking RFCOMM write on the UI thread,
 * where a stalled write waits out the ACL supervision timeout and ANRs - which
 * takes NotifyListenerService down with it. And sharing a socket field between
 * the connect path and the write path lets a write land on a socket still
 * inside connect(), whose error handling then closes the very socket the
 * worker is trying to establish.
 */
public final class LinkClientService extends Service implements SnapshotBus.Listener {

    private static final String TAG = "GlassNotify";
    private static final String CHANNEL_ID = "glass_link";
    private static final int NOTIFICATION_ID = 1;

    private static final byte[] NO_BODY = new byte[0];

    /** Spec section 7.3. */
    private static final long PING_INTERVAL_MS = 10_000L;

    /** How long to idle when there is no bonded Glass to connect to at all. */
    private static final long NO_DEVICE_RETRY_MS = 10_000L;

    /**
     * How many PINGs a session must complete before the backoff is reset.
     *
     * A bare connect() is not proof of a working session. Glass accepts the
     * socket before it has checked either the MAC pin or the protocol version,
     * and closes it immediately afterwards in both cases. Resetting on
     * connect() therefore reads those two failures as success and pins the
     * retry interval at Backoff.INITIAL_MS forever: connect, reset, HELLO,
     * Glass closes, PING fails, ~1s wait, repeat - at full duty cycle, in
     * exactly the situations the exponential backoff exists for.
     *
     * A PING that completes without an IOException means Glass held the link
     * for at least PING_INTERVAL_MS, which neither of those paths does. One is
     * enough; asking for more only delays recovery from a genuine dropout.
     */
    private static final int HEALTHY_SESSION_PINGS = 1;

    private final Backoff backoff = new Backoff();

    private volatile boolean running;
    private Thread worker;

    /**
     * The socket currently being handed to {@code connect()}.
     *
     * Written by the worker immediately before the blocking connect and
     * cleared once it returns. Read only by {@link #onDestroy}, which closes
     * it - that is the only way to make a blocking connect() return, and
     * without it the worker stays unkillable until Glass answers or the stack
     * gives up.
     *
     * Deliberately separate from {@link #connectedSocket}: nothing may write a
     * frame to this one, because it is not connected yet.
     */
    private volatile BluetoothSocket connectingSocket;

    /**
     * The live, connected socket, published only after {@code connect()} has
     * returned successfully and cleared as soon as the session ends.
     *
     * This is the only socket the write path may touch, and only the worker
     * thread writes to it. {@link #onDestroy} may close it, which is how a
     * write stalled on a dead ACL link is aborted rather than waited out.
     */
    private volatile BluetoothSocket connectedSocket;

    /** Guards {@link #wakeRequested} and {@link #snapshotPending}. */
    private final Object wakeLock = new Object();

    /** Set when something wants an immediate retry, e.g. ACL_CONNECTED. */
    private boolean wakeRequested; // guarded by wakeLock

    /** Set when a newer snapshot is waiting to be sent. */
    private boolean snapshotPending; // guarded by wakeLock

    public static void start(Context context) {
        context.startService(new Intent(context, LinkClientService.class));
    }

    /** Tears the link down. Used when the source of snapshots goes away. */
    public static void stop(Context context) {
        context.stopService(new Intent(context, LinkClientService.class));
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
                wakeRequested = true;
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
        // Ordering matters. running is cleared first so that the worker, which
        // re-checks it after publishing each socket field, sees the shutdown
        // even if both closes below happen to land in the gap between the
        // worker publishing a socket and actually using it.
        running = false;
        SnapshotBus.get().setListener(null);
        // Abort whatever the worker is parked in: a blocking connect(), or a
        // write stalled on an ACL link that is no longer there.
        closeQuietly(connectingSocket);
        closeQuietly(connectedSocket);
        // And wake a worker parked in a backoff or an inter-ping wait.
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
                waitForWake(NO_DEVICE_RETRY_MS);
                continue;
            }

            BluetoothSocket attempt = null;
            try {
                attempt = glass.createRfcommSocketToServiceRecord(Protocol.SERVICE_UUID);
                // Discovery is expensive and interferes with connecting.
                BluetoothAdapter.getDefaultAdapter().cancelDiscovery();

                // Published before connect() so onDestroy() can close it and
                // make the blocking connect return. Nothing writes to it here.
                connectingSocket = attempt;
                attempt.connect();

                // Publish before clearing, so there is never an instant where
                // neither field names this socket and onDestroy() would close
                // nothing.
                connectedSocket = attempt;
                connectingSocket = null;

                if (!running) {
                    // onDestroy() ran while connect() was blocking. Don't send
                    // Glass a live handshake and snapshot from a service that
                    // is going away. Any onDestroy() that raced past the two
                    // closes above is caught here, because it clears running
                    // before it closes anything.
                    return;
                }

                // Note: no backoff.reset() here. See HEALTHY_SESSION_PINGS -
                // the reset happens in pump(), once the session has proven it
                // is more than an accept() Glass is about to hang up on.
                status(R.string.status_connected);
                pump(attempt);
            } catch (IOException e) {
                Log.i(TAG, "connect failed: " + e.getMessage());
            } finally {
                connectedSocket = null;
                connectingSocket = null;
                closeQuietly(attempt);
            }

            if (running) {
                status(R.string.status_connecting);
                waitForWake(backoff.nextDelayMs());
            }
        }
    }

    /**
     * Sends the handshake, an immediate snapshot, then snapshots and
     * heartbeats until the link dies.
     *
     * Runs on the worker thread and is the only place frames are written.
     * Every IOException propagates out to the retry loop: there is no read
     * side on this end, so a failed write is the only liveness signal there
     * is, and swallowing one would leave the phone reporting Connected to a
     * socket that has been dead for hours.
     */
    private void pump(BluetoothSocket connected) throws IOException {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        writeFrame(connected, MessageType.HELLO,
                HelloCodec.encode(new Hello(
                        adapter.getName() == null ? "phone" : adapter.getName(),
                        adapter.getAddress() == null ? "" : adapter.getAddress())));

        // Glass has whatever it cached from last time; replace it immediately.
        // Anything flagged while we were connecting is subsumed by this, since
        // latest() is by definition at least as new.
        clearPendingSnapshot();
        writeSnapshot(connected, SnapshotBus.get().latest());

        long nextPingAt = SystemClock.elapsedRealtime() + PING_INTERVAL_MS;
        int successfulPings = 0;

        while (running) {
            boolean snapshotDue =
                    awaitWork(nextPingAt - SystemClock.elapsedRealtime());
            if (!running) {
                return;
            }

            if (snapshotDue) {
                writeSnapshot(connected, SnapshotBus.get().latest());
            }

            if (SystemClock.elapsedRealtime() >= nextPingAt) {
                writeFrame(connected, MessageType.PING, NO_BODY);
                nextPingAt = SystemClock.elapsedRealtime() + PING_INTERVAL_MS;

                if (successfulPings < HEALTHY_SESSION_PINGS
                        && ++successfulPings == HEALTHY_SESSION_PINGS) {
                    // The session is real, not an accept() Glass refused.
                    backoff.reset();
                }
            }
        }
    }

    /**
     * Called by SnapshotBus on the main thread.
     *
     * Does not send. A blocking RFCOMM write here would sit on the UI thread
     * until the ACL supervision timeout when Glass walks out of range, which
     * is an ANR, and an ANR kill takes NotifyListenerService with it. So this
     * only raises a flag and signals the worker, which re-reads
     * SnapshotBus.latest() - so two snapshots arriving before the worker picks
     * one up collapse into the newer with no queue to grow.
     */
    @Override
    public void onSnapshot(Snapshot snapshot) {
        synchronized (wakeLock) {
            snapshotPending = true;
            wakeLock.notifyAll();
        }
    }

    private void writeSnapshot(BluetoothSocket connected, Snapshot snapshot) throws IOException {
        // encodeWithinFrame, not encode: an oversized snapshot must lose its
        // oldest items rather than throw, because the reconnect that follows
        // would re-send the identical snapshot and wedge the link for good.
        writeFrame(connected, MessageType.SNAPSHOT, SnapshotCodec.encodeWithinFrame(snapshot));
    }

    /**
     * The single write choke point, reached only from the worker thread.
     *
     * There is deliberately no lock here. A lock would only be needed if two
     * threads could write, and the whole point of the handoff in
     * {@link #onSnapshot} is that they cannot. Reintroducing one would be a
     * sign that a second writer has crept back in.
     */
    private void writeFrame(BluetoothSocket target, int type, byte[] body) throws IOException {
        FrameCodec.write(target.getOutputStream(), type, body);
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

    /**
     * Sleeps between connection attempts, but returns early if wake() is
     * called - including a wake() that arrived just before this method was
     * entered, e.g. while status() was busy on a Binder call to
     * NotificationManager. Without the flag, that notifyAll() would land with
     * nobody waiting and be lost, forcing the full stale backoff to run out
     * regardless.
     *
     * Deliberately not shortened by a pending snapshot - and that takes a
     * condition loop, not just the absence of a snapshotPending check.
     * {@link #onSnapshot} signals this same monitor, and {@code
     * Object.wait(long)} returns identically on a timeout and on a notify, so a
     * bare {@code wait(ms)} cannot tell the two apart: it falls through on the
     * first notification to arrive. That hands the reconnect cadence to the
     * notification stream - every post or removal becomes an RFCOMM connect
     * attempt - and the exponential backoff never engages at all.
     *
     * So the loop below re-checks a condition rather than trusting the return.
     * Only {@link #wakeRequested} (a real {@code wake()}) or {@code !running}
     * (teardown) ends the wait early, and both are latched flags, so neither can
     * be missed by arriving a moment before the wait is entered. Every other
     * wakeup recomputes the time still owed and parks again.
     *
     * The deadline is on {@link SystemClock#elapsedRealtime}, not
     * {@code System.currentTimeMillis()}, so a clock adjustment mid-wait can
     * neither stretch the remaining time nor collapse it to zero.
     */
    private void waitForWake(long ms) {
        long deadline = SystemClock.elapsedRealtime() + ms;
        synchronized (wakeLock) {
            while (running && !wakeRequested) {
                long remaining = deadline - SystemClock.elapsedRealtime();
                if (remaining <= 0) {
                    break;
                }
                // wait(0) waits forever; the check above is what rules it out.
                try {
                    wakeLock.wait(remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            wakeRequested = false;
        }
    }

    /**
     * Parks the worker until the next PING is due, a snapshot needs sending,
     * or the service is going away. Returns true if a snapshot is waiting,
     * consuming the flag.
     *
     * No deadline loop here, unlike {@link #waitForWake}. {@link #pump} tracks
     * an absolute {@code nextPingAt} and re-enters this method with whatever
     * time is left, so an early return costs one extra pass round a loop that
     * then finds nothing to do - it cannot shorten the heartbeat interval.
     *
     * Both flags are consumed on the way out. {@code wakeRequested} means "cut
     * the connect backoff short", and inside a live session there is no backoff
     * to cut; leaving it set would carry it past the end of this session into
     * the first {@link #waitForWake} after the link drops, which would then
     * return instantly and skip a whole backoff interval. Consuming it here is
     * what the pre-single-writer wait did, and it keeps the flag meaning one
     * thing: nobody has acted on the wake yet.
     */
    private boolean awaitWork(long ms) {
        synchronized (wakeLock) {
            if (running && !snapshotPending && ms > 0) {
                try {
                    wakeLock.wait(ms);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            wakeRequested = false;
            boolean pending = snapshotPending;
            snapshotPending = false;
            return pending;
        }
    }

    /** Clears any pending flag without acting on it. */
    private void clearPendingSnapshot() {
        synchronized (wakeLock) {
            snapshotPending = false;
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
