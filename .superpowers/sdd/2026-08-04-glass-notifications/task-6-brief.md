## Task 6: Card rendering and the queue screen

Everything visual lands here. Pure black and pure white only, sizes in `dp`, and the immersive flags that stop the status bar eating downward swipes. (§9)

**Files:**
- Create: `glass/src/main/java/dev/erinlkolp/glassnotify/glass/TouchSample.java`
- Create: `glass/src/main/java/dev/erinlkolp/glassnotify/glass/Swipe.java`
- Create: `glass/src/main/java/dev/erinlkolp/glassnotify/glass/SwipeDetector.java`
- Create: `glass/src/main/java/dev/erinlkolp/glassnotify/glass/CardRenderer.java`
- Create: `glass/src/main/java/dev/erinlkolp/glassnotify/glass/QueueActivity.java`
- Modify: `glass/src/main/AndroidManifest.xml`
- Test: `glass/src/test/java/dev/erinlkolp/glassnotify/glass/SwipeDetectorTest.java`

**Interfaces:**
- Consumes: `QueueCursor`, `SnapshotStore`, `NotificationItem`, `Tier`.
- Produces: `TouchSample(float x, float y, long timeMs)` with public final fields; `Swipe.NONE/TAP/FORWARD/BACK`; `SwipeDetector()`, `SwipeDetector.begin(TouchSample):void`, `SwipeDetector.move(TouchSample):void`, `SwipeDetector.end(TouchSample):Swipe`, `SwipeDetector.cancel():void`; `CardRenderer.interruptCard(Context, NotificationItem):View`, `CardRenderer.queueCard(Context, NotificationItem, int position, int total, boolean stale):View`, `CardRenderer.messageCard(Context, String):View`.

- [ ] **Step 1: Write the failing test**

```java
package dev.erinlkolp.glassnotify.glass;

import static org.junit.Assert.assertEquals;

import org.junit.Before;
import org.junit.Test;

public class SwipeDetectorTest {

    private SwipeDetector detector;

    @Before
    public void setUp() {
        detector = new SwipeDetector();
    }

    /** Drives a full down-move-up sequence and returns the verdict. */
    private Swipe gesture(float startX, float endX, float startY, float endY, long durationMs) {
        detector.begin(new TouchSample(startX, startY, 0L));
        detector.move(new TouchSample((startX + endX) / 2f, (startY + endY) / 2f, durationMs / 2));
        return detector.end(new TouchSample(endX, endY, durationMs));
    }

    @Test
    public void aShortStillTouchIsATap() {
        assertEquals(Swipe.TAP, gesture(300f, 302f, 100f, 101f, 90L));
    }

    @Test
    public void aLongStillTouchIsNotATap() {
        // A resting finger is not an intentional tap. Long-press has no meaning
        // in a read-only queue, so it resolves to nothing.
        assertEquals(Swipe.NONE, gesture(300f, 300f, 100f, 100f, 1200L));
    }

    @Test
    public void movingForwardAlongThePadIsForward() {
        assertEquals(Swipe.FORWARD, gesture(200f, 200f + SwipeDetector.SWIPE_MIN_DX + 10f, 100f, 100f, 250L));
    }

    @Test
    public void movingBackwardAlongThePadIsBack() {
        assertEquals(Swipe.BACK, gesture(400f, 400f - SwipeDetector.SWIPE_MIN_DX - 10f, 100f, 100f, 250L));
    }

    @Test
    public void movementBelowTheThresholdIsNotASwipe() {
        assertEquals(Swipe.TAP, gesture(200f, 200f + SwipeDetector.SWIPE_MIN_DX - 5f, 100f, 100f, 120L));
    }

    @Test
    public void aStronglyVerticalDragIsIgnored() {
        // The touchpad is anisotropic: 187 native vertical units are rescaled
        // onto 360px while 1366 horizontal units are squeezed into 640, so a
        // physically small vertical movement produces a large dy. Requiring
        // horizontal dominance keeps a sloppy horizontal swipe from being
        // rejected while a genuine vertical drag is not misread as paging.
        assertEquals(Swipe.NONE, gesture(200f, 210f, 40f, 250f, 250L));
    }

    @Test
    public void aDiagonalSwipeStillCountsIfHorizontalDominates() {
        assertEquals(Swipe.FORWARD,
                gesture(200f, 200f + SwipeDetector.SWIPE_MIN_DX + 40f, 100f, 130f, 250L));
    }

    @Test
    public void cancellingDiscardsTheGestureInProgress() {
        detector.begin(new TouchSample(200f, 100f, 0L));
        detector.move(new TouchSample(400f, 100f, 100L));
        detector.cancel();

        assertEquals("a cancelled gesture must not resolve", Swipe.NONE,
                detector.end(new TouchSample(400f, 100f, 200L)));
    }

    @Test
    public void endWithoutBeginIsNone() {
        assertEquals(Swipe.NONE, detector.end(new TouchSample(400f, 100f, 200L)));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :glass:testDebugUnitTest --tests '*SwipeDetectorTest*'`
Expected: FAIL — `SwipeDetector` does not exist.

- [ ] **Step 3: Write `TouchSample.java` and `Swipe.java`**

```java
package dev.erinlkolp.glassnotify.glass;

/** One touch position in view coordinates, with its event time. */
public final class TouchSample {

    public final float x;
    public final float y;
    public final long timeMs;

    public TouchSample(float x, float y, long timeMs) {
        this.x = x;
        this.y = y;
        this.timeMs = timeMs;
    }
}
```

```java
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
```

- [ ] **Step 4: Write `SwipeDetector.java`**

```java
package dev.erinlkolp.glassnotify.glass;

/**
 * Resolves a touch sequence into a paging gesture.
 *
 * Free of Android types so the decision logic is unit tested on the host JVM.
 * MotionEvent is adapted into TouchSample by QueueActivity.
 *
 * The thresholds below are in view coordinates (the 640x360 space the
 * framework rescales the pad onto) and are starting values to be tuned on
 * hardware. Note the pad is anisotropic - its native surface is 1366x187,
 * so horizontal travel is compressed by roughly 0.47 and vertical stretched
 * by roughly 1.93 on the way to view coordinates. That is why dominance is
 * tested as a ratio rather than by comparing raw dx to raw dy.
 */
public final class SwipeDetector {

    /** Minimum horizontal travel, in view coordinates, to count as a swipe. */
    public static final float SWIPE_MIN_DX = 60f;

    /** How much horizontal travel must exceed vertical for a swipe to register. */
    public static final float HORIZONTAL_DOMINANCE = 1.2f;

    /** Longest touch still eligible to be a tap. */
    public static final long TAP_MAX_MS = 400L;

    private boolean active;
    private TouchSample start;
    private TouchSample latest;

    public void begin(TouchSample sample) {
        active = true;
        start = sample;
        latest = sample;
    }

    public void move(TouchSample sample) {
        if (active) {
            latest = sample;
        }
    }

    /** Discards the gesture in progress, e.g. on ACTION_CANCEL. */
    public void cancel() {
        active = false;
        start = null;
        latest = null;
    }

    public Swipe end(TouchSample sample) {
        if (!active || start == null) {
            return Swipe.NONE;
        }
        TouchSample first = start;
        cancel();

        float dx = sample.x - first.x;
        float dy = sample.y - first.y;
        long duration = sample.timeMs - first.timeMs;

        float absDx = Math.abs(dx);
        float absDy = Math.abs(dy);

        if (absDx >= SWIPE_MIN_DX && absDx > absDy * HORIZONTAL_DOMINANCE) {
            return dx > 0 ? Swipe.FORWARD : Swipe.BACK;
        }

        // Not a swipe. A short, essentially stationary touch is a tap.
        if (duration <= TAP_MAX_MS && absDx < SWIPE_MIN_DX && absDy < SWIPE_MIN_DX) {
            return Swipe.TAP;
        }

        return Swipe.NONE;
    }
}
```

- [ ] **Step 5: Write `CardRenderer.java`**

```java
package dev.erinlkolp.glassnotify.glass;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

import dev.erinlkolp.glassnotify.wire.NotificationItem;

/**
 * Builds the card view trees.
 *
 * Everything is pure black and pure white. The prism is see-through, so black
 * is transparent and mid-tones wash out to nothing - there are deliberately no
 * icons, greys, borders or gradients anywhere in here.
 *
 * All sizes are dp, never sp: the layout is fixed at 320x180dp and must not
 * reflow under a user font-scale setting.
 */
public final class CardRenderer {

    private static final int FG = Color.WHITE;
    private static final int BG = Color.BLACK;

    /** The status bar window claims the top 38px; keep content clear of it. */
    private static final int PAD_TOP_DP = 26;
    private static final int PAD_SIDE_DP = 22;
    private static final int PAD_BOTTOM_DP = 18;

    private CardRenderer() {
    }

    /**
     * Glanceable headline: large sender, hard-truncated message, small app label.
     * Readable in under a second without focusing. Spec section 9.2.
     */
    public static View interruptCard(Context context, NotificationItem item) {
        LinearLayout root = column(context);

        root.addView(text(context, item.title, 27, true, 2));
        root.addView(spacer(context, 8));
        root.addView(text(context, item.text, 16, false, 1));

        FrameLayout frame = frame(context, root);
        TextView label = text(context, item.appLabel.toUpperCase(Locale.getDefault()), 12, false, 1);
        label.setLetterSpacing(0.18f);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM | Gravity.START;
        lp.leftMargin = dp(context, PAD_SIDE_DP);
        lp.bottomMargin = dp(context, PAD_BOTTOM_DP);
        frame.addView(label, lp);

        return frame;
    }

    /**
     * One queue entry: app label and position on top, sender, full body, age
     * at the bottom. This is where reading actually happens, so the body is
     * not truncated further. Spec section 9.3.
     */
    public static View queueCard(Context context, NotificationItem item,
            int position, int total, boolean stale) {
        LinearLayout root = column(context);

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);

        TextView app = text(context, item.appLabel.toUpperCase(Locale.getDefault()), 12, false, 1);
        app.setLetterSpacing(0.18f);
        LinearLayout.LayoutParams appLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        header.addView(app, appLp);

        TextView pos = text(context, position + " / " + total, 12, false, 1);
        pos.setLetterSpacing(0.1f);
        header.addView(pos);

        root.addView(header);
        root.addView(spacer(context, 8));
        root.addView(text(context, item.title, 20, true, 1));
        root.addView(spacer(context, 6));

        TextView body = text(context, item.text, 15, false, 4);
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(body, bodyLp);

        String footer = stale
                ? context.getString(R.string.stale_queue)
                : Ages.describe(context, item.postedAt, System.currentTimeMillis());
        TextView age = text(context, footer, 12, false, 1);
        age.setLetterSpacing(0.18f);
        root.addView(age);

        return frame(context, root);
    }

    /** Centred single message, for empty / stale / version-mismatch states. */
    public static View messageCard(Context context, String message) {
        LinearLayout root = column(context);
        root.setGravity(Gravity.CENTER);
        root.addView(text(context, message, 20, false, 2));
        return frame(context, root);
    }

    private static LinearLayout column(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(context, PAD_SIDE_DP), dp(context, PAD_TOP_DP),
                dp(context, PAD_SIDE_DP), dp(context, PAD_BOTTOM_DP));
        return layout;
    }

    private static FrameLayout frame(Context context, View content) {
        FrameLayout frame = new FrameLayout(context);
        frame.setBackgroundColor(BG);
        frame.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return frame;
    }

    private static TextView text(Context context, String value, int sizeDp,
            boolean bold, int maxLines) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextColor(FG);
        // COMPLEX_UNIT_DIP, not SP: fixed layout, must not reflow.
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, sizeDp);
        view.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        view.setMaxLines(maxLines);
        view.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return view;
    }

    private static View spacer(Context context, int heightDp) {
        View view = new View(context);
        view.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(context, heightDp)));
        return view;
    }

    private static int dp(Context context, int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics());
    }
}
```

- [ ] **Step 6: Write `Ages.java`**

```java
package dev.erinlkolp.glassnotify.glass;

import android.content.Context;

import java.util.Locale;

/** Renders a timestamp as the short, all-caps age string shown on queue cards. */
public final class Ages {

    private Ages() {
    }

    public static String describe(Context context, long postedAtMs, long nowMs) {
        long deltaMs = nowMs - postedAtMs;
        if (deltaMs < 0) {
            // The phone's clock is ahead of ours. Treat it as just-arrived.
            deltaMs = 0;
        }

        long minutes = deltaMs / 60_000L;
        if (minutes < 1) {
            return "JUST NOW";
        }
        if (minutes < 60) {
            return String.format(Locale.US, "%d MIN AGO", minutes);
        }
        long hours = minutes / 60;
        if (hours < 24) {
            return String.format(Locale.US, "%d HR AGO", hours);
        }
        return String.format(Locale.US, "%d DAY AGO", hours / 24);
    }
}
```

- [ ] **Step 7: Write `QueueActivity.java`**

```java
package dev.erinlkolp.glassnotify.glass;

import android.app.Activity;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

import java.util.List;

import dev.erinlkolp.glassnotify.wire.NotificationItem;

/**
 * Browses the queue, one notification per screen.
 *
 * Swipe forward/back pages, matching the gesture launcher's next/previous-app
 * idiom so there is no new muscle memory to build. Read-only by design: there
 * is no dismiss, and no action can be fired from here.
 */
public final class QueueActivity extends Activity {

    private final QueueCursor cursor = new QueueCursor();
    private final SwipeDetector detector = new SwipeDetector();

    private SnapshotStore store;
    private FrameLayout container;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        store = GlassNotify.store(this);

        container = new FrameLayout(this);
        setContentView(container);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        applyImmersiveFlags();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // The service may have applied snapshots while we were away.
        refresh();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            // Must be re-applied here as well as in onCreate, or the status bar
            // reclaims its touchable region and starts eating downward swipes.
            applyImmersiveFlags();
        }
    }

    /**
     * The StatusBar window claims touchableRegion [0,0][640,38]. Without
     * IMMERSIVE_STICKY, swipes near the top of the pad open the notification
     * shade instead of reaching this activity. LOW_PROFILE does not do this -
     * it only dims navigation icons. Spec section 9.4.
     */
    private void applyImmersiveFlags() {
        container.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        TouchSample sample = new TouchSample(event.getX(), event.getY(), event.getEventTime());
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                detector.begin(sample);
                return true;
            case MotionEvent.ACTION_MOVE:
                detector.move(sample);
                return true;
            case MotionEvent.ACTION_CANCEL:
                detector.cancel();
                return true;
            case MotionEvent.ACTION_UP:
                handle(detector.end(sample));
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    private void handle(Swipe swipe) {
        boolean moved = false;
        if (swipe == Swipe.FORWARD) {
            moved = cursor.next();
        } else if (swipe == Swipe.BACK) {
            moved = cursor.previous();
        }
        if (moved) {
            render();
        }
    }

    private void refresh() {
        cursor.setSize(store.items().size());
        render();
    }

    private void render() {
        container.removeAllViews();

        List<NotificationItem> items = store.items();
        if (items.isEmpty()) {
            container.addView(CardRenderer.messageCard(this, getString(R.string.empty_queue)));
            return;
        }

        int index = cursor.index();
        container.addView(CardRenderer.queueCard(this, items.get(index),
                index + 1, items.size(), store.isStale()));
    }
}
```

- [ ] **Step 8: Write `GlassNotify.java` (shared store accessor)**

```java
package dev.erinlkolp.glassnotify.glass;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;

/**
 * Process-wide singletons. The service and the activity must see the same
 * snapshot, and Glass is small enough that a holder like this beats wiring a
 * binder interface between two components in the same process.
 */
public final class GlassNotify {

    private static final String PREFS = "glassnotify";
    private static final String CACHE_FILE = "snapshot.bin";

    private static SnapshotStore store;
    private static PeerPin peerPin;

    private GlassNotify() {
    }

    public static synchronized SnapshotStore store(Context context) {
        if (store == null) {
            Context app = context.getApplicationContext();
            store = new SnapshotStore(new File(app.getFilesDir(), CACHE_FILE));
            store.load();
        }
        return store;
    }

    public static synchronized PeerPin peerPin(Context context) {
        if (peerPin == null) {
            SharedPreferences prefs = context.getApplicationContext()
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            peerPin = new PeerPin(prefs);
        }
        return peerPin;
    }
}
```

- [ ] **Step 9: Register the activity in the manifest**

Add inside `<application>` in `glass/src/main/AndroidManifest.xml`:

```xml
        <activity
            android:name=".QueueActivity"
            android:exported="true"
            android:label="@string/app_name"
            android:theme="@android:style/Theme.Black.NoTitleBar.Fullscreen">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
```

The `LAUNCHER` category is what makes it appear in the gesture launcher's app list — which is how the queue is opened, per §10.1.

- [ ] **Step 10: Run the tests and build**

Run: `./gradlew :glass:testDebugUnitTest :glass:assembleDebug`
Expected: PASS, 21 tests. BUILD SUCCESSFUL.

- [ ] **Step 11: Commit**

```bash
git add glass/
git commit -m "feat(glass): add card rendering and the queue screen

Pure black and pure white only, with sizes in dp rather than sp - the
prism is see-through so black is transparent and mid-tones vanish, and
the 320x180dp layout must not reflow under a font-scale setting.

IMMERSIVE_STICKY is applied in both onCreate and onWindowFocusChanged.
The status bar claims touchableRegion [0,0][640,38], so without it
swipes near the top of the pad open the notification shade instead of
reaching the activity. LOW_PROFILE does not help - it only dims icons.

SwipeDetector is Android-free so the decision logic is unit tested on
the host. It tests horizontal dominance as a ratio rather than comparing
raw dx to dy, because the pad is anisotropic: 1366x187 native rescaled
onto 640x360 compresses horizontal travel and stretches vertical."
```

---

