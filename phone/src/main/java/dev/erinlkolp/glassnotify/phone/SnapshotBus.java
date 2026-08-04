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
