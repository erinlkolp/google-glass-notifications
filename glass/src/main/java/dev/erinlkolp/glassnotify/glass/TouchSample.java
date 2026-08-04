package dev.erinlkolp.glassnotify.glass;

/** One touch position in view coordinates, with its event time. */
public final class TouchSample {

    public final float x;
    public final float y;
    public final long timeMs;

    public TouchSample(float x, float y, long timeMs) {
        this.x = x;
        this.y = y;
        this.timeMs = timeMs;
    }
}
