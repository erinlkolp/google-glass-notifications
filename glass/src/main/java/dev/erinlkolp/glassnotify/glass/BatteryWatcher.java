package dev.erinlkolp.glassnotify.glass;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;

import dev.erinlkolp.glassnotify.wire.GlassState;

/**
 * Watches Glass's own battery and reports changes worth sending.
 *
 * ACTION_BATTERY_CHANGED cannot be declared in a manifest filter, so this
 * registers at runtime. It needs no permission.
 *
 * <h3>The debounce</h3>
 *
 * The broadcast is chatty - it fires on temperature and voltage movement, not
 * only on level - so forwarding every one would put a frame on the link every
 * few seconds for no gain. The listener is called only when the
 * (batteryLevel, onPower) pair actually differs from the last one published.
 * No timer, no interval to tune: Glass sitting at 100% overnight produces one
 * change and then silence.
 */
public final class BatteryWatcher extends BroadcastReceiver {

    /** Called on the main thread. Implementations must not block. */
    public interface Listener {
        void onBatteryState(GlassState state);
    }

    private final Listener listener;

    /**
     * Last published state. Volatile because the accept thread reads it via
     * {@link #latest()} when a connection opens, while onReceive writes it on
     * the main thread.
     */
    private volatile GlassState latest;

    public BatteryWatcher(Listener listener) {
        if (listener == null) {
            throw new NullPointerException("listener");
        }
        this.listener = listener;
    }

    /**
     * ACTION_BATTERY_CHANGED is sticky, so registerReceiver hands back the last
     * broadcast immediately. Feeding it straight through means {@link #latest}
     * is populated before this method returns, rather than being null until the
     * battery next moves - which on a device sitting at a stable 100% could be
     * a very long time.
     */
    public void register(Context context) {
        Intent sticky = context.registerReceiver(this,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (sticky != null) {
            onReceive(context, sticky);
        }
    }

    public void unregister(Context context) {
        try {
            context.unregisterReceiver(this);
        } catch (IllegalArgumentException notRegistered) {
            // Already gone. Nothing to undo.
        }
    }

    /** The most recent state, or null if no usable broadcast has arrived. */
    public GlassState latest() {
        return latest;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        GlassState state = BatteryReading.fromExtras(
                intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
                intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1),
                intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0));

        if (state == null || state.equals(latest)) {
            return;
        }

        latest = state;
        listener.onBatteryState(state);
    }
}
