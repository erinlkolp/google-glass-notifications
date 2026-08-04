package dev.erinlkolp.glassnotify.glass;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;

/**
 * Process-wide singletons. The service and the activity must see the same
 * snapshot, and Glass is small enough that a holder like this beats wiring a
 * binder interface between two components in the same process.
 */
public final class GlassNotify {

    private static final String PREFS = "glassnotify";
    private static final String CACHE_FILE = "snapshot.bin";

    private static SnapshotStore store;
    private static PeerPin peerPin;

    private GlassNotify() {
    }

    public static synchronized SnapshotStore store(Context context) {
        if (store == null) {
            Context app = context.getApplicationContext();
            store = new SnapshotStore(new File(app.getFilesDir(), CACHE_FILE));
            store.load();
        }
        return store;
    }

    public static synchronized PeerPin peerPin(Context context) {
        if (peerPin == null) {
            SharedPreferences prefs = context.getApplicationContext()
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            peerPin = new PeerPin(prefs);
        }
        return peerPin;
    }
}
