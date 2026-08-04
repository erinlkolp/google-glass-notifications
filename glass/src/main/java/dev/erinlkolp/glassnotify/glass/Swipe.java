package dev.erinlkolp.glassnotify.glass;

/** What a completed touch resolved to. */
public enum Swipe {

    /** Nothing actionable. */
    NONE,

    /** A brief stationary touch. */
    TAP,

    /** Toward the front of the head - next item. */
    FORWARD,

    /** Toward the back of the head - previous item. */
    BACK
}
