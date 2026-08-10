package dev.erinlkolp.glassnotify.phone;

import dev.erinlkolp.glassnotify.wire.GlassState;

/**
 * Decides whether a battery state from Glass is worth telling the wearer about.
 *
 * Glass sends state, not events, so this receives the same state repeatedly -
 * on every reconnect, and whenever the level or power flag moves. One boolean
 * turns that stream into at most one alert per charge:
 *
 * <ul>
 *   <li><b>Reconnect while still full.</b> Already alerted, so nothing fires.
 *       This is what makes it safe to alert on reconnect at all, which is how
 *       a charge that completed while the phone was out of range still gets
 *       noticed.</li>
 *   <li><b>Notification dismissed by hand, then the link bounces.</b> Still
 *       silent. The flag tracks <em>we alerted</em>, not <em>it is visible</em>
 *       - tracking visibility would re-alert on every reconnect.</li>
 *   <li><b>Unplug re-arms.</b> The alert is cancelled and the next charge
 *       announces itself normally.</li>
 * </ul>
 *
 * Known edge, accepted: if the app restarts while Glass sits plugged in at
 * 100%, the flag starts false and one further alert fires. Persisting it to
 * disk was judged disproportionate - a fresh session arguably should
 * re-announce.
 *
 * Not thread-safe. {@link ChargeAlerter} calls it on the main thread only.
 */
public final class ChargeAlertPolicy {

    public enum Action {
        /** Post the charged notification. */
        SHOW,
        /** Remove it. */
        CANCEL,
        /** Do nothing at all. */
        NONE
    }

    /**
     * The level that counts as charged.
     *
     * Deliberately not BATTERY_STATUS_FULL. That value is firmware-specific -
     * some builds latch it well before the cell is topped off and others never
     * emit it - and this ROM's behaviour is unmeasured. Charge-alert design,
     * section 4.
     */
    static final int FULL_LEVEL = 100;

    /** Whether an alert has been raised for the charge currently in progress. */
    private boolean shown;

    public Action onState(GlassState state) {
        if (!state.onPower) {
            if (shown) {
                shown = false;
                return Action.CANCEL;
            }
            return Action.NONE;
        }

        if (state.batteryLevel >= FULL_LEVEL && !shown) {
            shown = true;
            return Action.SHOW;
        }

        return Action.NONE;
    }
}
