package dev.erinlkolp.glassnotify.glass;

/**
 * Renders the alert tone as PCM.
 *
 * Free of Android types, so the maths is unit tested on the host - which
 * matters more here than it looks, because a plausible implementation of the
 * sweep is silently wrong. See render().
 */
public final class ChirpTone {

    public static final int SAMPLE_RATE = 44100;
    public static final int START_HZ = 800;
    public static final int END_HZ = 2400;
    public static final int DURATION_MS = 150;

    private ChirpTone() {
    }

    /** Chosen by ear from candidate tones during the spike; a starting value to tune on hardware. Spec section 6.1. */
    public static short[] renderDefault() {
        return render(START_HZ, END_HZ, DURATION_MS, SAMPLE_RATE);
    }

    /**
     * A sine sweep from startHz to endHz with a raised-cosine attack and decay.
     *
     * Phase is accumulated per sample rather than computed as 2*PI*f(i)*i/rate,
     * which looks equivalent and is not: with a varying f, the latter jumps
     * rather than sweeps.
     */
    public static short[] render(int startHz, int endHz, int ms, int sampleRate) {
        int frames = (int) ((long) sampleRate * ms / 1000L);
        if (frames <= 0) {
            return new short[0];
        }

        short[] pcm = new short[frames];

        // Guarded against zero so a burst shorter than the nominal ramp still
        // renders instead of dividing by zero.
        int ramp = Math.max(1, Math.min(frames / 4, sampleRate / 200));
        double phase = 0.0;

        for (int i = 0; i < frames; i++) {
            double progress = frames == 1 ? 0.0 : (double) i / (frames - 1);
            double frequency = startHz + (endHz - startHz) * progress;
            phase += 2.0 * Math.PI * frequency / sampleRate;

            double envelope = 1.0;
            if (i < ramp) {
                envelope = 0.5 * (1.0 - Math.cos(Math.PI * i / ramp));
            } else if (i >= frames - ramp) {
                envelope = 0.5 * (1.0 - Math.cos(Math.PI * (frames - 1 - i) / ramp));
            }

            pcm[i] = (short) (Math.sin(phase) * envelope * Short.MAX_VALUE);
        }

        return pcm;
    }
}
