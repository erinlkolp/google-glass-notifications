package dev.erinlkolp.glassnotify.phone;

import static org.junit.Assert.assertEquals;

import org.junit.Before;
import org.junit.Test;

import dev.erinlkolp.glassnotify.wire.GlassState;

public class ChargeAlertPolicyTest {

    private ChargeAlertPolicy policy;

    @Before
    public void setUp() {
        policy = new ChargeAlertPolicy();
    }

    private ChargeAlertPolicy.Action charging(int level) {
        return policy.onState(new GlassState(level, true));
    }

    private ChargeAlertPolicy.Action unplugged(int level) {
        return policy.onState(new GlassState(level, false));
    }

    @Test
    public void alertsWhenChargingCompletes() {
        assertEquals(ChargeAlertPolicy.Action.NONE, charging(98));
        assertEquals(ChargeAlertPolicy.Action.NONE, charging(99));
        assertEquals(ChargeAlertPolicy.Action.SHOW, charging(100));
    }

    @Test
    public void doesNotRepeatWhileItSitsOnTheCharger() {
        assertEquals(ChargeAlertPolicy.Action.SHOW, charging(100));
        assertEquals(ChargeAlertPolicy.Action.NONE, charging(100));
        assertEquals(ChargeAlertPolicy.Action.NONE, charging(100));
    }

    @Test
    public void doesNotRepeatWhenTheLinkDropsAndComesBackStillFull() {
        // Every reconnect re-sends current state. This is the case that makes
        // "alert on reconnect if still charging" safe rather than naggy.
        assertEquals(ChargeAlertPolicy.Action.SHOW, charging(100));
        assertEquals(ChargeAlertPolicy.Action.NONE, charging(100));
    }

    @Test
    public void clearsTheAlertOnUnplug() {
        assertEquals(ChargeAlertPolicy.Action.SHOW, charging(100));
        assertEquals(ChargeAlertPolicy.Action.CANCEL, unplugged(100));
    }

    @Test
    public void reArmsAfterUnplug() {
        assertEquals(ChargeAlertPolicy.Action.SHOW, charging(100));
        assertEquals(ChargeAlertPolicy.Action.CANCEL, unplugged(100));
        assertEquals(ChargeAlertPolicy.Action.NONE, unplugged(64));
        assertEquals(ChargeAlertPolicy.Action.NONE, charging(64));
        assertEquals(ChargeAlertPolicy.Action.SHOW, charging(100));
    }

    @Test
    public void doesNotCancelWhenNothingWasShown() {
        // Glass spends most of its life unplugged and not full. That must not
        // produce a stream of pointless cancels.
        assertEquals(ChargeAlertPolicy.Action.NONE, unplugged(80));
        assertEquals(ChargeAlertPolicy.Action.NONE, unplugged(79));
        assertEquals(ChargeAlertPolicy.Action.NONE, unplugged(78));
    }

    @Test
    public void doesNotAlertAtFullWithoutPower() {
        // A freshly unplugged, still-full Glass. The moment has passed.
        assertEquals(ChargeAlertPolicy.Action.NONE, unplugged(100));
    }

    @Test
    public void cancelsOnlyOnceForOneUnplug() {
        assertEquals(ChargeAlertPolicy.Action.SHOW, charging(100));
        assertEquals(ChargeAlertPolicy.Action.CANCEL, unplugged(100));
        assertEquals(ChargeAlertPolicy.Action.NONE, unplugged(99));
    }

    @Test
    public void aDismissedNotificationDoesNotComeBackByItself() {
        // shown tracks "we alerted", not "the notification is visible". If the
        // user swipes it away and the link then bounces, re-sending the same
        // state must stay silent - re-alerting is how an app earns a mute.
        assertEquals(ChargeAlertPolicy.Action.SHOW, charging(100));
        assertEquals(ChargeAlertPolicy.Action.NONE, charging(100));
        assertEquals(ChargeAlertPolicy.Action.NONE, charging(100));
    }
}
