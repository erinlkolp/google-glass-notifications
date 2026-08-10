package dev.erinlkolp.glassnotify.glass;

import dev.erinlkolp.glassnotify.wire.GlassState;

/**
 * Turns raw ACTION_BATTERY_CHANGED extras into a {@link GlassState}.
 *
 * Split out from {@link BatteryWatcher} so the arithmetic can be tested on the
 * JVM: the watcher is a BroadcastReceiver and drags android.content in with it.
 *
 * Every guard here exists so that nothing invalid reaches the GlassState
 * constructor, which throws IllegalArgumentException - and an unchecked throw
 * inside a BroadcastReceiver takes the process down.
 */
public final class BatteryReading {

    private BatteryReading() {
    }

    /**
     * @param level   EXTRA_LEVEL, or a negative value if the extra was absent
     * @param scale   EXTRA_SCALE, the value {@code level} is out of
     * @param plugged EXTRA_PLUGGED: 0 for none, or a BATTERY_PLUGGED_* constant
     * @return the state, or null if the broadcast carried no usable level
     */
    public static GlassState fromExtras(int level, int scale, int plugged) {
        if (level < 0 || scale <= 0) {
            return null;
        }

        int percent = (int) Math.round(level * 100.0d / scale);
        if (percent < 0) {
            percent = 0;
        }
        if (percent > 100) {
            percent = 100;
        }

        // Strictly positive, not "!= 0". getIntExtra's miss value is -1, and
        // reading that as plugged would leave Glass permanently claiming to be
        // on charge.
        return new GlassState(percent, plugged > 0);
    }
}
