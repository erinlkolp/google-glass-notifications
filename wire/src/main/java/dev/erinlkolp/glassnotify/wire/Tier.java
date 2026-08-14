package dev.erinlkolp.glassnotify.wire;

/** How aggressively Glass should present a notification. Decided on the phone. */
public enum Tier {

    /** Wakes the Glass display briefly. */
    INTERRUPT(1),

    /** Lands silently; visible only when the queue is opened. */
    QUEUE(2),

    /** Wakes the Glass display and plays a short tone. */
    INTERRUPT_CHIRP(3);

    /** Stable on-the-wire code. Not the ordinal — reordering the enum must be safe. */
    public final int code;

    Tier(int code) {
        this.code = code;
    }

    /** True for tiers that light up the prism. */
    public boolean interrupts() {
        return this != QUEUE;
    }

    /** True for tiers that also make a sound. */
    public boolean chirps() {
        return this == INTERRUPT_CHIRP;
    }

    /** Returns null for an unrecognised code; decoders convert that to a ProtocolException. */
    public static Tier fromCode(int code) {
        for (Tier tier : values()) {
            if (tier.code == code) {
                return tier;
            }
        }
        return null;
    }
}
