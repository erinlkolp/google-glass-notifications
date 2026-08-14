package dev.erinlkolp.glassnotify.glass;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ChirpToneTest {

    /** Counts positive-going zero crossings, which is proportional to frequency. */
    private static int upwardCrossings(short[] pcm, int from, int to) {
        int crossings = 0;
        for (int i = from + 1; i < to; i++) {
            if (pcm[i - 1] <= 0 && pcm[i] > 0) {
                crossings++;
            }
        }
        return crossings;
    }

    @Test
    public void frameCountMatchesTheRequestedDuration() {
        assertEquals(6615, ChirpTone.render(800, 2400, 150, 44100).length);
        assertEquals(44100, ChirpTone.render(800, 2400, 1000, 44100).length);
    }

    @Test
    public void theEnvelopeSilencesBothEdges() {
        // A burst that starts or ends mid-cycle clicks, and against the skull
        // the click is more noticeable than the tone.
        short[] pcm = ChirpTone.renderDefault();

        assertEquals(0, pcm[0]);
        assertEquals(0, pcm[pcm.length - 1]);
    }

    @Test
    public void theToneReachesUsefulAmplitudeWithoutClipping() {
        short[] pcm = ChirpTone.renderDefault();

        int peak = 0;
        for (int i = 0; i < pcm.length; i++) {
            peak = Math.max(peak, Math.abs(pcm[i]));
        }

        assertTrue("peak " + peak + " should be loud enough to hear",
                peak > (int) (0.8 * Short.MAX_VALUE));
        assertTrue("peak " + peak + " must not clip", peak <= Short.MAX_VALUE);
    }

    @Test
    public void theSweepAccumulatesPhaseRatherThanRecomputingIt() {
        // Total cycles are the integral of frequency over time: with phase
        // accumulated per sample that is the mean frequency, 1600 Hz over
        // 0.15 s = 240 cycles. Computing phase as 2*PI*f(i)*i/rate instead
        // sweeps at twice the slope and lands near 360, so this count is what
        // separates the correct implementation from the plausible wrong one.
        short[] pcm = ChirpTone.renderDefault();

        int cycles = upwardCrossings(pcm, 0, pcm.length);

        assertTrue("expected about 240 cycles, got " + cycles,
                cycles >= 237 && cycles <= 243);
    }

    @Test
    public void aVeryShortBurstDoesNotDivideByZeroInTheRampGuard() {
        short[] pcm = ChirpTone.render(800, 2400, 1, 44100);

        assertEquals(44, pcm.length);
    }

    @Test
    public void aZeroLengthRequestYieldsNoSamplesRatherThanThrowing() {
        assertEquals(0, ChirpTone.render(800, 2400, 0, 44100).length);
    }
}
