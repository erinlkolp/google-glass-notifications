package dev.erinlkolp.glassnotify.phone;

/**
 * Exponential reconnect delay, capped.
 *
 * Pure logic so the sequence is testable without waiting in real time.
 * Spec section 10.2.
 */
public final class Backoff {

    public static final long INITIAL_MS = 1_000L;
    public static final long MAX_MS = 60_000L;

    private long next = INITIAL_MS;

    /** Returns the delay to wait before the next attempt, then advances. */
    public long nextDelayMs() {
        long delay = next;
        if (next < MAX_MS) {
            // Double, but clamp before assigning so repeated calls cannot overflow.
            long doubled = next * 2;
            next = (doubled > MAX_MS || doubled < 0) ? MAX_MS : doubled;
        }
        return delay;
    }

    /** Called on a successful connection. */
    public void reset() {
        next = INITIAL_MS;
    }
}
