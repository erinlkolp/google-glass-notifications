package dev.erinlkolp.glassnotify.glass;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Starts the link service at boot.
 *
 * Far simpler than the gesture launcher's boot problem, which needed an init
 * hook because it ran a root app_process daemon. This is an ordinary app uid,
 * so the standard receiver is enough. Spec section 10.1.
 */
public final class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            LinkServerService.start(context);
        }
    }
}
