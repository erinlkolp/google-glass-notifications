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
