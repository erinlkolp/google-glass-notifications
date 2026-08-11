package dev.erinlkolp.glassnotify.wire;

/**
 * Glass's own battery state, reported to the phone.
 *
 * The only Glass to phone message in the protocol. Deliberately carries state
 * rather than an event: the phone decides what is worth alerting about, which
 * is what lets a full charge that happened while the link was down still be
 * noticed when it comes back - and lets one that has since been unplugged
 * correctly pass unremarked.
 */
public final class GlassState {

    /** Percentage, 0-100, already normalised from the raw level/scale pair. */
    public final int batteryLevel;

    /** True when Glass is plugged into any power source. */
    public final boolean onPower;

    public GlassState(int batteryLevel, boolean onPower) {
        if (batteryLevel < 0 || batteryLevel > 100) {
            throw new IllegalArgumentException(
                    "batteryLevel " + batteryLevel + " is outside 0..100");
        }
        this.batteryLevel = batteryLevel;
        this.onPower = onPower;
    }

    /**
     * Value equality, and load-bearing: the Glass-side watcher uses it to
     * decide whether a battery broadcast is worth sending at all.
     * ACTION_BATTERY_CHANGED also fires on temperature and voltage changes, so
     * without this the link would carry a frame every few seconds.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof GlassState)) {
            return false;
        }
        GlassState other = (GlassState) o;
        return batteryLevel == other.batteryLevel && onPower == other.onPower;
    }

    @Override
    public int hashCode() {
        return 31 * batteryLevel + (onPower ? 1 : 0);
    }

    @Override
    public String toString() {
        return "GlassState{" + batteryLevel + "%" + (onPower ? " on power" : "") + "}";
    }
}
