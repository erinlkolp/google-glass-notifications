## Task 7: The interrupt overlay

Draws over whatever is foregrounded and wakes the display briefly. The behaviour that matters most is storm collapsing: a chatty group thread must not pin the display on. (§10.1)

**Files:**
- Create: `glass/src/main/java/dev/erinlkolp/glassnotify/glass/InterruptPolicy.java`
- Create: `glass/src/main/java/dev/erinlkolp/glassnotify/glass/InterruptOverlay.java`
- Test: `glass/src/test/java/dev/erinlkolp/glassnotify/glass/InterruptPolicyTest.java`

**Interfaces:**
- Consumes: `CardRenderer.interruptCard`, `NotificationItem`, `Tier`, `Snapshot`.
- Produces: `InterruptPolicy()`; `InterruptPolicy.selectInterrupt(Snapshot previous, Snapshot next):NotificationItem` (static, returns `null` when nothing should interrupt); `InterruptOverlay(Context)`; `show(NotificationItem):void`; `dismiss():void`; `InterruptOverlay.DISPLAY_MS:long`.

- [ ] **Step 1: Write the failing test**

```java
package dev.erinlkolp.glassnotify.glass;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import dev.erinlkolp.glassnotify.wire.NotificationItem;
import dev.erinlkolp.glassnotify.wire.Snapshot;
import dev.erinlkolp.glassnotify.wire.Tier;

public class InterruptPolicyTest {

    private static NotificationItem item(String key, Tier tier, long postedAt) {
        return new NotificationItem(key, "Signal", "Jordan Reyes", "hello", postedAt, tier);
    }

    private static Snapshot snapshot(NotificationItem... items) {
        return new Snapshot(1L, Arrays.asList(items));
    }

    private static Snapshot empty() {
        return new Snapshot(0L, new ArrayList<NotificationItem>());
    }

    @Test
    public void aNewInterruptItemInterrupts() {
        NotificationItem incoming = item("a", Tier.INTERRUPT, 100L);

        assertEquals(incoming, InterruptPolicy.selectInterrupt(empty(), snapshot(incoming)));
    }

    @Test
    public void aNewQueueItemDoesNotInterrupt() {
        assertNull(InterruptPolicy.selectInterrupt(empty(), snapshot(item("a", Tier.QUEUE, 100L))));
    }

    @Test
    public void anItemAlreadySeenDoesNotInterruptAgain() {
        // Every change resends the whole queue, so an unchanged item appears in
        // snapshot after snapshot. Re-interrupting on each would be unusable.
        Snapshot previous = snapshot(item("a", Tier.INTERRUPT, 100L));
        Snapshot next = snapshot(item("a", Tier.INTERRUPT, 100L), item("b", Tier.QUEUE, 200L));

        assertNull(InterruptPolicy.selectInterrupt(previous, next));
    }

    @Test
    public void anUpdatedItemWithTheSameKeyInterruptsAgain() {
        // A messaging app reuses one key and rewrites the text as a thread
        // grows. A newer postedAt is a genuinely new message.
        Snapshot previous = snapshot(item("a", Tier.INTERRUPT, 100L));
        NotificationItem updated = item("a", Tier.INTERRUPT, 500L);

        assertEquals(updated, InterruptPolicy.selectInterrupt(previous, snapshot(updated)));
    }

    @Test
    public void collapsesAStormToTheNewestItem() {
        // Several arrive between snapshots. Show only the newest rather than
        // queueing five seconds each - that pins the display on and drains
        // the battery. Spec section 10.1.
        NotificationItem newest = item("c", Tier.INTERRUPT, 300L);
        Snapshot next = snapshot(newest, item("b", Tier.INTERRUPT, 200L), item("a", Tier.INTERRUPT, 100L));

        assertEquals(newest, InterruptPolicy.selectInterrupt(empty(), next));
    }

    @Test
    public void picksTheNewestRegardlessOfPositionInTheList() {
        NotificationItem newest = item("b", Tier.INTERRUPT, 900L);
        Snapshot next = snapshot(item("a", Tier.INTERRUPT, 100L), newest);

        assertEquals(newest, InterruptPolicy.selectInterrupt(empty(), next));
    }

    @Test
    public void anEmptySnapshotInterruptsNothing() {
        assertNull(InterruptPolicy.selectInterrupt(snapshot(item("a", Tier.INTERRUPT, 100L)), empty()));
    }

    @Test
    public void removalDoesNotInterrupt() {
        Snapshot previous = snapshot(item("a", Tier.INTERRUPT, 100L), item("b", Tier.INTERRUPT, 200L));
        Snapshot next = snapshot(item("a", Tier.INTERRUPT, 100L));

        assertNull(InterruptPolicy.selectInterrupt(previous, next));
    }

    @Test
    public void theFirstSnapshotAfterReconnectDoesNotReplayTheBacklog() {
        // On reconnect the phone sends everything it holds. Those are not new
        // events - interrupting for each would be a wall of cards.
        List<NotificationItem> backlog = new ArrayList<NotificationItem>();
        for (int i = 0; i < 5; i++) {
            backlog.add(item("k" + i, Tier.INTERRUPT, 100L + i));
        }

        assertNull(InterruptPolicy.selectInterrupt(null, new Snapshot(1L, backlog)));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :glass:testDebugUnitTest --tests '*InterruptPolicyTest*'`
Expected: FAIL — `InterruptPolicy` does not exist.

- [ ] **Step 3: Write `InterruptPolicy.java`**

```java
package dev.erinlkolp.glassnotify.glass;

import java.util.HashMap;
import java.util.Map;

import dev.erinlkolp.glassnotify.wire.NotificationItem;
import dev.erinlkolp.glassnotify.wire.Snapshot;
import dev.erinlkolp.glassnotify.wire.Tier;

/**
 * Decides whether an incoming snapshot should light up the display, and with what.
 *
 * Whole-snapshot transfer means an unchanged notification arrives over and over,
 * so "is this new?" is a diff against the previous snapshot rather than a
 * property of the item. Free of Android types, so it is unit tested on the host.
 */
public final class InterruptPolicy {

    private InterruptPolicy() {
    }

    /**
     * Returns the single item to show, or null for nothing.
     *
     * @param previous the last snapshot applied, or null if this is the first
     *                 one of the connection - in which case nothing interrupts,
     *                 because a reconnect backlog is not a stream of new events
     */
    public static NotificationItem selectInterrupt(Snapshot previous, Snapshot next) {
        if (previous == null) {
            return null;
        }

        Map<String, Long> seen = new HashMap<String, Long>();
        for (NotificationItem item : previous.items) {
            seen.put(item.key, Long.valueOf(item.postedAt));
        }

        NotificationItem winner = null;
        for (NotificationItem item : next.items) {
            if (item.tier != Tier.INTERRUPT) {
                continue;
            }
            Long previouslyPostedAt = seen.get(item.key);
            boolean isNew = previouslyPostedAt == null
                    || item.postedAt > previouslyPostedAt.longValue();
            if (!isNew) {
                continue;
            }
            // Collapse a storm: keep only the newest.
            if (winner == null || item.postedAt > winner.postedAt) {
                winner = item;
            }
        }
        return winner;
    }
}
```

- [ ] **Step 4: Write `InterruptOverlay.java`**

```java
package dev.erinlkolp.glassnotify.glass;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import dev.erinlkolp.glassnotify.wire.NotificationItem;

/**
 * Draws the interrupt card over whatever is foregrounded.
 *
 * TYPE_SYSTEM_ALERT needs only the SYSTEM_ALERT_WINDOW permission, which is
 * granted at install time on API 22 - so unlike the gesture launcher's global
 * gestures, this needs no root and no app_process daemon.
 *
 * Showing a second card while one is up replaces it and restarts the timer
 * rather than queueing, so a chatty thread cannot pin the display on.
 */
public final class InterruptOverlay {

    private static final String TAG = "GlassNotify";

    /** Starting value; tune on hardware. Spec section 14. */
    public static final long DISPLAY_MS = 5_000L;

    private final Context context;
    private final WindowManager windowManager;
    private final PowerManager powerManager;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable dismissRunnable = new Runnable() {
        @Override
        public void run() {
            dismiss();
        }
    };

    private View currentView;
    private PowerManager.WakeLock wakeLock;

    public InterruptOverlay(Context context) {
        this.context = context.getApplicationContext();
        this.windowManager =
                (WindowManager) this.context.getSystemService(Context.WINDOW_SERVICE);
        this.powerManager =
                (PowerManager) this.context.getSystemService(Context.POWER_SERVICE);
    }

    /** Must be called on the main thread. */
    public void show(NotificationItem item) {
        dismiss();

        View card = CardRenderer.interruptCard(context, item);

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_FULLSCREEN,
                PixelFormat.OPAQUE);
        params.gravity = Gravity.TOP | Gravity.START;

        try {
            windowManager.addView(card, params);
            currentView = card;
        } catch (RuntimeException e) {
            // Never let a windowing failure kill the link service.
            Log.w(TAG, "could not add interrupt overlay", e);
            return;
        }

        acquireWakeLock();
        handler.removeCallbacks(dismissRunnable);
        handler.postDelayed(dismissRunnable, DISPLAY_MS);
    }

    /** Must be called on the main thread. Safe when nothing is showing. */
    public void dismiss() {
        handler.removeCallbacks(dismissRunnable);

        if (currentView != null) {
            try {
                windowManager.removeView(currentView);
            } catch (RuntimeException e) {
                Log.w(TAG, "could not remove interrupt overlay", e);
            }
            currentView = null;
        }
        releaseWakeLock();
    }

    private void acquireWakeLock() {
        releaseWakeLock();
        wakeLock = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "GlassNotify:interrupt");
        // Timeout is a backstop: if dismiss() is somehow never reached, the
        // lock still expires rather than holding the display on indefinitely.
        wakeLock.acquire(DISPLAY_MS + 1_000L);
    }

    private void releaseWakeLock() {
        if (wakeLock != null) {
            if (wakeLock.isHeld()) {
                wakeLock.release();
            }
            wakeLock = null;
        }
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :glass:testDebugUnitTest`
Expected: PASS, 30 tests.

- [ ] **Step 6: Commit**

```bash
git add glass/
git commit -m "feat(glass): add the interrupt overlay and its policy

InterruptPolicy diffs against the previous snapshot rather than looking
at the item alone. Whole-snapshot transfer means an unchanged
notification arrives repeatedly, so 'is this new' is only answerable as
a diff. Same key with a newer postedAt counts as new, which is how
messaging apps signal another message in a thread.

A storm collapses to the single newest item, and the first snapshot of
a connection interrupts for nothing at all - a reconnect backlog is not
a stream of new events and would otherwise be a wall of cards.

The overlay uses TYPE_SYSTEM_ALERT, an install-time permission on API
22, so no root or app_process daemon is needed. The wake lock is
acquired with a timeout as a backstop against a leaked display-on."
```

---

