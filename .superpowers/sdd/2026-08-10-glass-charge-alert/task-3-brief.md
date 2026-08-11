## Task 3: The alert policy

**Files:**
- Create: `phone/src/main/java/dev/erinlkolp/glassnotify/phone/ChargeAlertPolicy.java`
- Test: `phone/src/test/java/dev/erinlkolp/glassnotify/phone/ChargeAlertPolicyTest.java`

**Interfaces:**
- Consumes: `GlassState` from Task 1.
- Produces:
  - `enum ChargeAlertPolicy.Action { SHOW, CANCEL, NONE }`
  - `new ChargeAlertPolicy()`
  - `ChargeAlertPolicy.onState(GlassState) -> Action`
  - `ChargeAlertPolicy.FULL_LEVEL` — package-private `static final int`, value `100`.

- [ ] **Step 1: Write the failing test**

Create `phone/src/test/java/dev/erinlkolp/glassnotify/phone/ChargeAlertPolicyTest.java`:

```java
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
```

- [ ] **Step 2: Run the test and verify it fails**

Run: `./gradlew :phone:testReleaseUnitTest --tests '*ChargeAlertPolicyTest*'`
Expected: FAIL — compilation error, `ChargeAlertPolicy` does not exist.

- [ ] **Step 3: Write `ChargeAlertPolicy`**

Create `phone/src/main/java/dev/erinlkolp/glassnotify/phone/ChargeAlertPolicy.java`:

```java
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
```

- [ ] **Step 4: Run the test and verify it passes**

Run: `./gradlew :phone:testReleaseUnitTest --tests '*ChargeAlertPolicyTest*'`
Expected: PASS, 9 tests.

- [ ] **Step 5: Commit**

```bash
git add phone/src/main/java/dev/erinlkolp/glassnotify/phone/ChargeAlertPolicy.java \
        phone/src/test/java/dev/erinlkolp/glassnotify/phone/ChargeAlertPolicyTest.java
git commit -m "feat(phone): decide when a full charge is worth an alert

One boolean turns a repeating state stream into at most one alert per
charge, covering reconnect-while-full, a hand-dismissed notification, and
re-arming on unplug.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

