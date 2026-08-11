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

import java.io.IOException;
import java.io.InputStream;

import dev.erinlkolp.glassnotify.wire.Frame;
import dev.erinlkolp.glassnotify.wire.FrameCodec;
import dev.erinlkolp.glassnotify.wire.GlassState;
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
public final class LinkServerService extends Service implements BatteryWatcher.Listener {

    private static final String TAG = "GlassNotify";

    /**
     * How long to idle before re-arming the listener after a failure.
     *
     * A plain sleep rather than the phone's exponential Backoff: Glass is the
     * passive side, so there is no cost to re-arming promptly, and a growing
     * delay here would only make the phone's own retry look broken. The point
     * is purely to stop the loop spinning.
     */
    private static final long RETRY_DELAY_MS = 5_000L;

    /**
     * How long to wait for the state writer to notice the session ended.
     *
     * Tidiness, not correctness. A straggler holds a socket that acceptLoop's
     * finally has already closed, so the worst it can do is throw on its next
     * write and exit. The join just keeps threads from piling up across a run
     * of fast reconnects.
     */
    private static final long WRITER_JOIN_MS = 500L;

    /** Debug-only extras, see DebugBatteryReceiver. */
    private static final String EXTRA_DEBUG_LEVEL = "debug_level";
    private static final String EXTRA_DEBUG_PLUGGED = "debug_plugged";

    private volatile boolean running;
    private Thread acceptThread;
    private volatile BluetoothServerSocket serverSocket;

    /**
     * The socket of the connection currently being served, for the whole time
     * serve() is running; null otherwise. Closing it from onDestroy() is what
     * unblocks a FrameCodec.read() the accept thread is blocked in - the
     * listening serverSocket is already closed and gone by the time a
     * connection is being served, so it cannot do that job. Volatile because
     * it is written on the accept thread and read from the main thread with
     * no other happens-before edge between them.
     */
    private volatile BluetoothSocket connectedSocket;

    private final Handler main = new Handler(Looper.getMainLooper());
    private InterruptOverlay overlay;

    /** The last snapshot applied on this connection; null until one arrives. */
    private Snapshot lastApplied;

    private BatteryWatcher batteryWatcher;

    /**
     * The writer for the session currently being served, or null between
     * sessions. Volatile because onBatteryState runs on the main thread while
     * the accept thread publishes and clears it.
     */
    private volatile StateWriter stateWriter;

    public static void start(Context context) {
        context.startService(new Intent(context, LinkServerService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        overlay = GlassNotify.overlay(this);
        GlassNotify.store(this);
        batteryWatcher = new BatteryWatcher(this);
        batteryWatcher.register(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.hasExtra(EXTRA_DEBUG_LEVEL)) {
            // Straight into the same path a real broadcast takes, so what is
            // being exercised is the real writer, not a shortcut round it.
            GlassState fake = BatteryReading.fromExtras(
                    intent.getIntExtra(EXTRA_DEBUG_LEVEL, 100), 100,
                    intent.getBooleanExtra(EXTRA_DEBUG_PLUGGED, true) ? 1 : 0);
            if (fake != null) {
                Log.i(TAG, "debug: battery " + fake);
                onBatteryState(fake);
            }
        }
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
        batteryWatcher.unregister(this);
        closeServerSocket();
        closeConnectedSocket();
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

    /**
     * Called on the main thread by BatteryWatcher, already debounced.
     *
     * Hands off and returns. It never touches a socket, so a phone that has
     * walked out of range cannot stall the main thread here.
     */
    @Override
    public void onBatteryState(GlassState state) {
        StateWriter writer = stateWriter;
        if (writer != null) {
            writer.offer(state);
        }
    }

    private void acceptLoop() {
        while (running) {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null || !adapter.isEnabled()) {
                // Bluetooth is off. Idle rather than spinning a retry loop.
                sleepQuietly(RETRY_DELAY_MS);
                continue;
            }

            try {
                serverSocket = adapter.listenUsingRfcommWithServiceRecord(
                        Protocol.SERVICE_NAME, Protocol.SERVICE_UUID);
            } catch (IOException e) {
                Log.w(TAG, "could not open server socket", e);
                sleepQuietly(RETRY_DELAY_MS);
                continue;
            }

            boolean acceptFailed = false;
            BluetoothSocket socket = null;
            try {
                socket = serverSocket.accept();
                closeServerSocket();
                connectedSocket = socket;
                serve(socket);
            } catch (IOException e) {
                acceptFailed = true;
                if (running) {
                    Log.w(TAG, "accept failed", e);
                }
            } finally {
                closeConnectedSocket();
                closeServerSocket();
            }

            if (acceptFailed && running) {
                // The listen path already backed off; this one did not, so an
                // accept() that failed immediately fell straight back to the
                // top and spun listen/accept/close plus a log line as fast as
                // the CPU allowed - on the device with the smallest battery in
                // the system. Reachable during a Bluetooth adapter toggle,
                // where isEnabled() above still passes and accept() then
                // fails. Sleeping after the finally rather than inside the
                // catch keeps the sockets closed while we wait.
                sleepQuietly(RETRY_DELAY_MS);
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

        StateWriter writer = null;
        Thread writerThread = null;
        try {
            writer = new StateWriter(socket.getOutputStream(), batteryWatcher.latest());
        } catch (IOException e) {
            // No return here: the forward path (phone -> Glass) does not
            // depend on this stream at all, and the phone has already shown
            // "Connected" by the time this failure surfaces - it discovers
            // nothing is wrong until its own next write. Aborting the whole
            // session over a reverse channel the wearer never asked to see
            // would drop an entire session of notifications and repeat on
            // every reconnect if the condition persists, which is exactly
            // the new failure mode spec section 3.2 rules out. So: log it
            // and fall through into the read loop with no writer. writer and
            // writerThread stay null, stateWriter is never published for
            // this session, and onBatteryState already drops states when
            // stateWriter is null - no extra handling needed below.
            Log.w(TAG, "no output stream for the reverse channel, serving without it", e);
        }
        if (writer != null) {
            writerThread = new Thread(writer, "glassnotify-state");
            // Publish before start(), not after: onBatteryState (main
            // thread) and this thread race to see stateWriter. If start()
            // ran first, a battery tick landing in that window would find
            // stateWriter still null and drop the update. BatteryWatcher
            // only re-broadcasts on change (its debounce), so a dropped
            // update is not merely late - once the reading has settled,
            // nothing ever resends it, and the session goes without an
            // alert until the link happens to drop and reconnect, possibly
            // hours later. offer() is thread-safe and a not-yet-started
            // thread simply reads the newer pending value on its first pass
            // through the loop, so publishing first costs nothing and closes
            // the window.
            stateWriter = writer;
            writerThread.start();
        }

        try {
            InputStream in = socket.getInputStream();
            while (running) {
                Frame frame = FrameCodec.read(in);

                if (frame.version != Protocol.VERSION) {
                    Log.w(TAG, "protocol version " + frame.version
                            + " from phone, expected " + Protocol.VERSION);
                    // A state, not a message. A ~3.5s Toast on a see-through
                    // prism is one the wearer is very likely looking away
                    // from, and a mismatch that goes unseen looks exactly like
                    // the app being broken. Spec section 7.1.
                    GlassNotify.store(this).setVersionMismatch(true);
                    return;
                }

                dispatch(frame);
            }
        } catch (IOException e) {
            // Includes ProtocolException. Either way: close and go back to
            // accept(). Mid-stream resync is never attempted.
            Log.i(TAG, "connection ended: " + e.getMessage());
        } finally {
            // Clear the field first, so a battery change landing during
            // teardown finds nothing rather than offering to a writer that is
            // already stopping.
            stateWriter = null;
            // Both null together when the output stream failed above and the
            // session ran with no reverse channel at all - nothing to stop
            // or join in that case.
            if (writer != null) {
                writer.stop();
                try {
                    writerThread.join(WRITER_JOIN_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            Log.i(TAG, "reverse channel ended");
        }
    }

    private void dispatch(Frame frame) throws IOException {
        switch (frame.type) {
            case MessageType.HELLO: {
                Hello hello = HelloCodec.decode(frame.body);
                Log.i(TAG, "hello from " + hello.deviceName + " " + hello.deviceAddress);
                // A HELLO that got this far carried a version we understand,
                // which is the successful handshake that clears a mismatch
                // left over from a previous connection.
                GlassNotify.store(this).setVersionMismatch(false);
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

    private void closeConnectedSocket() {
        BluetoothSocket socket = connectedSocket;
        connectedSocket = null;
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
