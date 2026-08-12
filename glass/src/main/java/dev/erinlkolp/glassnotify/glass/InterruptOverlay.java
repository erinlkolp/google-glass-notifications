package dev.erinlkolp.glassnotify.glass;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import dev.erinlkolp.glassnotify.wire.NotificationItem;

/**
 * Draws the interrupt card over whatever is foregrounded.
 *
 * TYPE_SYSTEM_ALERT needs only the SYSTEM_ALERT_WINDOW permission, which is
 * granted at install time on API 22 - so unlike the gesture launcher's global
 * gestures, this needs no root and no app_process daemon.
 *
 * Showing a second card while one is up replaces it and restarts the timer
 * rather than queueing, so a chatty thread cannot pin the display on.
 */
public final class InterruptOverlay {

    private static final String TAG = "GlassNotify";

    /** Starting value; tune on hardware. Spec section 14. */
    public static final long DISPLAY_MS = 7_000L;

    private final Context context;
    private final WindowManager windowManager;
    private final PowerManager powerManager;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable dismissRunnable = new Runnable() {
        @Override
        public void run() {
            dismiss();
        }
    };

    private View currentView;
    private PowerManager.WakeLock wakeLock;

    public InterruptOverlay(Context context) {
        this.context = context.getApplicationContext();
        this.windowManager =
                (WindowManager) this.context.getSystemService(Context.WINDOW_SERVICE);
        this.powerManager =
                (PowerManager) this.context.getSystemService(Context.POWER_SERVICE);
    }

    /** Must be called on the main thread. */
    public void show(NotificationItem item) {
        dismiss();

        View card = CardRenderer.interruptCard(context, item);

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_SYSTEM_ERROR,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_FULLSCREEN,
                PixelFormat.OPAQUE);
        params.gravity = Gravity.TOP | Gravity.START;

        try {
            windowManager.addView(card, params);
            currentView = card;
        } catch (RuntimeException e) {
            // Never let a windowing failure kill the link service.
            Log.w(TAG, "could not add interrupt overlay", e);
            return;
        }

        acquireWakeLock();
        handler.removeCallbacks(dismissRunnable);
        handler.postDelayed(dismissRunnable, DISPLAY_MS);
    }

    /** Must be called on the main thread. Safe when nothing is showing. */
    public void dismiss() {
        handler.removeCallbacks(dismissRunnable);

        if (currentView != null) {
            try {
                windowManager.removeView(currentView);
            } catch (RuntimeException e) {
                Log.w(TAG, "could not remove interrupt overlay", e);
            }
            currentView = null;
        }
        releaseWakeLock();
    }

    private void acquireWakeLock() {
        releaseWakeLock();
        wakeLock = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "GlassNotify:interrupt");
        // Timeout is a backstop: if dismiss() is somehow never reached, the
        // lock still expires rather than holding the display on indefinitely.
        wakeLock.acquire(DISPLAY_MS + 1_000L);
    }

    private void releaseWakeLock() {
        if (wakeLock != null) {
            if (wakeLock.isHeld()) {
                wakeLock.release();
            }
            wakeLock = null;
        }
    }
}
