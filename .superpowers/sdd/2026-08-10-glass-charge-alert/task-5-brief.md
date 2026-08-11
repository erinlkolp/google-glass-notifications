## Task 5: Read the battery on Glass

**Files:**
- Create: `glass/src/main/java/dev/erinlkolp/glassnotify/glass/BatteryReading.java`
- Create: `glass/src/main/java/dev/erinlkolp/glassnotify/glass/BatteryWatcher.java`
- Test: `glass/src/test/java/dev/erinlkolp/glassnotify/glass/BatteryReadingTest.java`

**Interfaces:**
- Consumes: `GlassState` from Task 1.
- Produces:
  - `BatteryReading.fromExtras(int level, int scale, int plugged) -> GlassState` — static; returns `null` when the broadcast carries no usable level.
  - `interface BatteryWatcher.Listener { void onBatteryState(GlassState state); }`
  - `new BatteryWatcher(Listener listener)`
  - `BatteryWatcher.register(Context)` / `BatteryWatcher.unregister(Context)`
  - `BatteryWatcher.latest() -> GlassState` — may be null.

The split is deliberate: `BatteryReading` holds all the arithmetic and no Android types, so it tests on the JVM. `BatteryWatcher` is a thin `BroadcastReceiver` shell around it.

- [ ] **Step 1: Write the failing test**

Create `glass/src/test/java/dev/erinlkolp/glassnotify/glass/BatteryReadingTest.java`:

```java
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
```

- [ ] **Step 2: Run the test and verify it fails**

Run: `./gradlew :glass:testReleaseUnitTest --tests '*BatteryReadingTest*'`
Expected: FAIL — compilation error, `BatteryReading` does not exist.

- [ ] **Step 3: Write `BatteryReading`**

Create `glass/src/main/java/dev/erinlkolp/glassnotify/glass/BatteryReading.java`:

```java
package dev.erinlkolp.glassnotify.glass;

import dev.erinlkolp.glassnotify.wire.GlassState;

/**
 * Turns raw ACTION_BATTERY_CHANGED extras into a {@link GlassState}.
 *
 * Split out from {@link BatteryWatcher} so the arithmetic can be tested on the
 * JVM: the watcher is a BroadcastReceiver and drags android.content in with it.
 *
 * Every guard here exists so that nothing invalid reaches the GlassState
 * constructor, which throws IllegalArgumentException - and an unchecked throw
 * inside a BroadcastReceiver takes the process down.
 */
public final class BatteryReading {

    private BatteryReading() {
    }

    /**
     * @param level   EXTRA_LEVEL, or a negative value if the extra was absent
     * @param scale   EXTRA_SCALE, the value {@code level} is out of
     * @param plugged EXTRA_PLUGGED: 0 for none, or a BATTERY_PLUGGED_* constant
     * @return the state, or null if the broadcast carried no usable level
     */
    public static GlassState fromExtras(int level, int scale, int plugged) {
        if (level < 0 || scale <= 0) {
            return null;
        }

        int percent = (int) Math.round(level * 100.0d / scale);
        if (percent < 0) {
            percent = 0;
        }
        if (percent > 100) {
            percent = 100;
        }

        // Strictly positive, not "!= 0". getIntExtra's miss value is -1, and
        // reading that as plugged would leave Glass permanently claiming to be
        // on charge.
        return new GlassState(percent, plugged > 0);
    }
}
```

- [ ] **Step 4: Run the test and verify it passes**

Run: `./gradlew :glass:testReleaseUnitTest --tests '*BatteryReadingTest*'`
Expected: PASS, 9 tests.

- [ ] **Step 5: Write `BatteryWatcher`**

Create `glass/src/main/java/dev/erinlkolp/glassnotify/glass/BatteryWatcher.java`:

```java
package dev.erinlkolp.glassnotify.glass;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;

import dev.erinlkolp.glassnotify.wire.GlassState;

/**
 * Watches Glass's own battery and reports changes worth sending.
 *
 * ACTION_BATTERY_CHANGED cannot be declared in a manifest filter, so this
 * registers at runtime. It needs no permission.
 *
 * <h3>The debounce</h3>
 *
 * The broadcast is chatty - it fires on temperature and voltage movement, not
 * only on level - so forwarding every one would put a frame on the link every
 * few seconds for no gain. The listener is called only when the
 * (batteryLevel, onPower) pair actually differs from the last one published.
 * No timer, no interval to tune: Glass sitting at 100% overnight produces one
 * change and then silence.
 */
public final class BatteryWatcher extends BroadcastReceiver {

    /** Called on the main thread. Implementations must not block. */
    public interface Listener {
        void onBatteryState(GlassState state);
    }

    private final Listener listener;

    /**
     * Last published state. Volatile because the accept thread reads it via
     * {@link #latest()} when a connection opens, while onReceive writes it on
     * the main thread.
     */
    private volatile GlassState latest;

    public BatteryWatcher(Listener listener) {
        if (listener == null) {
            throw new NullPointerException("listener");
        }
        this.listener = listener;
    }

    /**
     * ACTION_BATTERY_CHANGED is sticky, so registerReceiver hands back the last
     * broadcast immediately. Feeding it straight through means {@link #latest}
     * is populated before this method returns, rather than being null until the
     * battery next moves - which on a device sitting at a stable 100% could be
     * a very long time.
     */
    public void register(Context context) {
        Intent sticky = context.registerReceiver(this,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (sticky != null) {
            onReceive(context, sticky);
        }
    }

    public void unregister(Context context) {
        try {
            context.unregisterReceiver(this);
        } catch (IllegalArgumentException notRegistered) {
            // Already gone. Nothing to undo.
        }
    }

    /** The most recent state, or null if no usable broadcast has arrived. */
    public GlassState latest() {
        return latest;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        GlassState state = BatteryReading.fromExtras(
                intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
                intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1),
                intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0));

        if (state == null || state.equals(latest)) {
            return;
        }

        latest = state;
        listener.onBatteryState(state);
    }
}
```

- [ ] **Step 6: Build and confirm the suite**

Run: `./gradlew test assembleDebug`
Expected: BUILD SUCCESSFUL. Totals: wire 58, glass 41, phone 42.

- [ ] **Step 7: Commit**

```bash
git add glass/src/main/java/dev/erinlkolp/glassnotify/glass/BatteryReading.java \
        glass/src/main/java/dev/erinlkolp/glassnotify/glass/BatteryWatcher.java \
        glass/src/test/java/dev/erinlkolp/glassnotify/glass/BatteryReadingTest.java
git commit -m "feat(glass): watch the battery

BatteryReading normalises the raw level/scale/plugged extras and guards
every way they can be nonsense, so nothing invalid reaches the GlassState
constructor inside a BroadcastReceiver. BatteryWatcher is the thin shell
around it.

The debounce is value equality on (level, onPower): ACTION_BATTERY_CHANGED
also fires on temperature and voltage, and forwarding those would put a
frame on the link every few seconds.

Nothing sends this yet.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

