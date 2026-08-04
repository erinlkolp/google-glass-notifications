package dev.erinlkolp.glassnotify.glass;

import android.os.Handler;
import android.os.Looper;
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

    /**
     * Notified when what is on screen would change.
     *
     * A plain listener rather than a broadcast or an observer library: both
     * ends live in the same process, and this has exactly one subscriber.
     */
    public interface Listener {
        /** Always delivered on the main thread. */
        void onStoreChanged();
    }

    private final File cacheFile;

    private volatile Snapshot current = EMPTY;
    private volatile long lastContactElapsedMs = NEVER;
    private volatile boolean versionMismatch;

    /** Created with the first listener, so the store stays constructible off-device. */
    private Handler mainHandler; // guarded by this
    private volatile Listener listener;

    public SnapshotStore(File cacheFile) {
        if (cacheFile == null) {
            throw new NullPointerException("cacheFile");
        }
        this.cacheFile = cacheFile;
    }

    /**
     * Registers the one listener told about changes, or clears it with null.
     *
     * The store is a process-wide singleton, so a listener left registered
     * outlives whatever registered it. The activity registers in onResume and
     * clears in onPause, which is what keeps this from leaking it.
     */
    public synchronized void setListener(Listener listener) {
        if (listener != null && mainHandler == null) {
            mainHandler = new Handler(Looper.getMainLooper());
        }
        this.listener = listener;
    }

    /**
     * Fans a change out on the main thread.
     *
     * apply() runs on the accept thread, and the listener redraws a view
     * hierarchy, so the hop is mandatory rather than a nicety.
     */
    private void notifyChanged() {
        Handler handler;
        synchronized (this) {
            if (listener == null) {
                return;
            }
            handler = mainHandler;
        }
        handler.post(new Runnable() {
            @Override
            public void run() {
                // Re-read rather than capture: the activity may have paused
                // between the post and the delivery, and this Runnable holds
                // only the store, so nothing is kept alive by it.
                Listener target = listener;
                if (target != null) {
                    target.onStoreChanged();
                }
            }
        });
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
        // Without this the queue screen shows the previous item until the next
        // swipe - and if the cursor is already at the end of the list, that
        // swipe does not move and never redraws either.
        notifyChanged();
    }

    /**
     * True when the phone last spoke a protocol version this build cannot read.
     *
     * Spec section 7.1 requires an explicit state, not a message: on a
     * see-through prism the wearer is very likely looking away for the few
     * seconds a Toast is up, and a mismatch that scrolls past unseen is
     * indistinguishable from the app being broken. Loud failure beats
     * mysterious silence, so this sticks until a good handshake clears it.
     *
     * In-memory rather than on disk deliberately: it describes the phone
     * currently on the other end of the link, not anything about this device.
     */
    public boolean isVersionMismatch() {
        return versionMismatch;
    }

    /** Set on a foreign protocol version; cleared by a successful handshake. */
    public void setVersionMismatch(boolean mismatch) {
        if (versionMismatch == mismatch) {
            return;
        }
        versionMismatch = mismatch;
        notifyChanged();
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
