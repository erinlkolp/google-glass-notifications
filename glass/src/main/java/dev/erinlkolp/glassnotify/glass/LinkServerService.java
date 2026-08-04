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
