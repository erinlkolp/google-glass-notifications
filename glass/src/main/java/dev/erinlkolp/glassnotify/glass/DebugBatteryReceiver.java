package dev.erinlkolp.glassnotify.glass;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Fake battery state, so the charge alert can be exercised without waiting
 * over an hour for a real charge.
 *
 *   adb shell am broadcast -a dev.erinlkolp.glassnotify.DEBUG_BATTERY \
 *     --ei level 100 --ez plugged true
 *
 * Routed through the service rather than acting directly, so the frame really
 * does travel the live socket via StateWriter. A shortcut that posted the
 * notification some other way would test nothing worth testing.
 */
public final class DebugBatteryReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!BuildConfig.DEBUG) {
            // Never allow synthetic battery state into a non-debug build.
            return;
        }

        Intent toService = new Intent(context, LinkServerService.class);
        toService.putExtra("debug_level", intent.getIntExtra("level", 100));
        toService.putExtra("debug_plugged", intent.getBooleanExtra("plugged", true));
        context.startService(toService);
    }
}
