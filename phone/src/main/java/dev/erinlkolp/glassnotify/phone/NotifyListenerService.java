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
        LinkClientService.start(this);
    }

    /**
     * Notification access was revoked, or the system is unbinding us.
     *
     * Without this the link service outlives its only source of snapshots and
     * carries on as a foreground service, pinging Glass every 10s and holding
     * a frozen queue on screen that the wearer has no way of telling is dead.
     */
    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        Log.i(TAG, "listener disconnected, stopping the link");
        LinkClientService.stop(this);
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
