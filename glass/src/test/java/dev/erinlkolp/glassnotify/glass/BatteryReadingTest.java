package dev.erinlkolp.glassnotify.glass;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import dev.erinlkolp.glassnotify.wire.GlassState;

public class BatteryReadingTest {

    @Test
    public void readsAPlainPercentage() {
        GlassState state = BatteryReading.fromExtras(72, 100, 0);
        assertEquals(72, state.batteryLevel);
        assertFalse(state.onPower);
    }

    @Test
    public void normalisesAScaleThatIsNotOneHundred() {
        // EXTRA_SCALE is whatever the driver says it is. Assuming 100 is a
        // classic way to report 50% as 50 on a device whose scale is 200.
        assertEquals(25, BatteryReading.fromExtras(50, 200, 0).batteryLevel);
        assertEquals(100, BatteryReading.fromExtras(255, 255, 1).batteryLevel);
    }

    @Test
    public void roundsRatherThanTruncates() {
        // 2/3 is 66.67%. Truncation would report 66.
        assertEquals(67, BatteryReading.fromExtras(2, 3, 0).batteryLevel);
    }

    @Test
    public void treatsAnyPowerSourceAsCharging() {
        // BATTERY_PLUGGED_AC = 1, USB = 2, WIRELESS = 4.
        assertTrue(BatteryReading.fromExtras(50, 100, 1).onPower);
        assertTrue(BatteryReading.fromExtras(50, 100, 2).onPower);
        assertTrue(BatteryReading.fromExtras(50, 100, 4).onPower);
        assertFalse(BatteryReading.fromExtras(50, 100, 0).onPower);
    }

    @Test
    public void treatsAMissingPluggedExtraAsUnplugged() {
        // getIntExtra's miss value is -1 in the caller. Anything that is not a
        // positive plug type must read as unplugged, or a device that omits
        // the extra would look permanently on charge.
        assertFalse(BatteryReading.fromExtras(50, 100, -1).onPower);
    }

    @Test
    public void rejectsAMissingLevel() {
        assertNull(BatteryReading.fromExtras(-1, 100, 0));
    }

    @Test
    public void rejectsAnUnusableScale() {
        // Guards the division. A scale of 0 would be an ArithmeticException.
        assertNull(BatteryReading.fromExtras(50, 0, 0));
        assertNull(BatteryReading.fromExtras(50, -1, 0));
    }

    @Test
    public void clampsALevelAboveItsOwnScale() {
        // Nonsense from the driver must not reach the GlassState constructor,
        // which would throw IllegalArgumentException inside a BroadcastReceiver.
        assertEquals(100, BatteryReading.fromExtras(150, 100, 1).batteryLevel);
    }

    @Test
    public void reportsAFullBatteryAsOneHundred() {
        GlassState state = BatteryReading.fromExtras(100, 100, 1);
        assertEquals(100, state.batteryLevel);
        assertTrue(state.onPower);
    }
}
