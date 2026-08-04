package dev.erinlkolp.glassnotify.glass;

import static org.junit.Assert.assertEquals;

import org.junit.Before;
import org.junit.Test;

public class SwipeDetectorTest {

    private SwipeDetector detector;

    @Before
    public void setUp() {
        detector = new SwipeDetector();
    }

    /** Drives a full down-move-up sequence and returns the verdict. */
    private Swipe gesture(float startX, float endX, float startY, float endY, long durationMs) {
        detector.begin(new TouchSample(startX, startY, 0L));
        detector.move(new TouchSample((startX + endX) / 2f, (startY + endY) / 2f, durationMs / 2));
        return detector.end(new TouchSample(endX, endY, durationMs));
    }

    @Test
    public void aShortStillTouchIsATap() {
        assertEquals(Swipe.TAP, gesture(300f, 302f, 100f, 101f, 90L));
    }

    @Test
    public void aLongStillTouchIsNotATap() {
        // A resting finger is not an intentional tap. Long-press has no meaning
        // in a read-only queue, so it resolves to nothing.
        assertEquals(Swipe.NONE, gesture(300f, 300f, 100f, 100f, 1200L));
    }

    @Test
    public void movingForwardAlongThePadIsForward() {
        assertEquals(Swipe.FORWARD, gesture(200f, 200f + SwipeDetector.SWIPE_MIN_DX + 10f, 100f, 100f, 250L));
    }

    @Test
    public void movingBackwardAlongThePadIsBack() {
        assertEquals(Swipe.BACK, gesture(400f, 400f - SwipeDetector.SWIPE_MIN_DX - 10f, 100f, 100f, 250L));
    }

    @Test
    public void movementBelowTheThresholdIsNotASwipe() {
        assertEquals(Swipe.TAP, gesture(200f, 200f + SwipeDetector.SWIPE_MIN_DX - 5f, 100f, 100f, 120L));
    }

    @Test
    public void aStronglyVerticalDragIsIgnored() {
        // The touchpad is anisotropic: 187 native vertical units are rescaled
        // onto 360px while 1366 horizontal units are squeezed into 640, so a
        // physically small vertical movement produces a large dy. Requiring
        // horizontal dominance keeps a sloppy horizontal swipe from being
        // rejected while a genuine vertical drag is not misread as paging.
        assertEquals(Swipe.NONE, gesture(200f, 210f, 40f, 250f, 250L));
    }

    @Test
    public void aDiagonalSwipeStillCountsIfHorizontalDominates() {
        assertEquals(Swipe.FORWARD,
                gesture(200f, 200f + SwipeDetector.SWIPE_MIN_DX + 40f, 100f, 130f, 250L));
    }

    @Test
    public void cancellingDiscardsTheGestureInProgress() {
        detector.begin(new TouchSample(200f, 100f, 0L));
        detector.move(new TouchSample(400f, 100f, 100L));
        detector.cancel();

        assertEquals("a cancelled gesture must not resolve", Swipe.NONE,
                detector.end(new TouchSample(400f, 100f, 200L)));
    }

    @Test
    public void endWithoutBeginIsNone() {
        assertEquals(Swipe.NONE, detector.end(new TouchSample(400f, 100f, 200L)));
    }
}
