package dev.erinlkolp.glassnotify.glass;

/**
 * Resolves a touch sequence into a paging gesture.
 *
 * Free of Android types so the decision logic is unit tested on the host JVM.
 * MotionEvent is adapted into TouchSample by QueueActivity.
 *
 * The thresholds below are in view coordinates (the 640x360 space the
 * framework rescales the pad onto) and are starting values to be tuned on
 * hardware. Note the pad is anisotropic - its native surface is 1366x187,
 * so horizontal travel is compressed by roughly 0.47 and vertical stretched
 * by roughly 1.93 on the way to view coordinates. That is why dominance is
 * tested as a ratio rather than by comparing raw dx to raw dy.
 */
public final class SwipeDetector {

    /** Minimum horizontal travel, in view coordinates, to count as a swipe. */
    public static final float SWIPE_MIN_DX = 60f;

    /** How much horizontal travel must exceed vertical for a swipe to register. */
    public static final float HORIZONTAL_DOMINANCE = 1.2f;

    /** Longest touch still eligible to be a tap. */
    public static final long TAP_MAX_MS = 400L;

    private boolean active;
    private TouchSample start;
    private TouchSample latest;

    public void begin(TouchSample sample) {
        active = true;
        start = sample;
        latest = sample;
    }

    public void move(TouchSample sample) {
        if (active) {
            latest = sample;
        }
    }

    /** Discards the gesture in progress, e.g. on ACTION_CANCEL. */
    public void cancel() {
        active = false;
        start = null;
        latest = null;
    }

    public Swipe end(TouchSample sample) {
        if (!active || start == null) {
            return Swipe.NONE;
        }
        TouchSample first = start;
        cancel();

        float dx = sample.x - first.x;
        float dy = sample.y - first.y;
        long duration = sample.timeMs - first.timeMs;

        float absDx = Math.abs(dx);
        float absDy = Math.abs(dy);

        if (absDx >= SWIPE_MIN_DX && absDx > absDy * HORIZONTAL_DOMINANCE) {
            return dx > 0 ? Swipe.FORWARD : Swipe.BACK;
        }

        // Not a swipe. A short, essentially stationary touch is a tap.
        if (duration <= TAP_MAX_MS && absDx < SWIPE_MIN_DX && absDy < SWIPE_MIN_DX) {
            return Swipe.TAP;
        }

        return Swipe.NONE;
    }
}
