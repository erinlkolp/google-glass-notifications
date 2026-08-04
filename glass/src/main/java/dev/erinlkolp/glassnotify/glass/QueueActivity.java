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
        LinkServerService.start(this);

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
        cursor.setSize(store.items().size());
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
        render();
    }

    private void render() {
        container.removeAllViews();

        // Capture once: current is volatile and items is immutable, so this
        // local cannot change under us even if the service swaps the snapshot.
        List<NotificationItem> items = store.items();
        cursor.setSize(items.size());

        if (items.isEmpty()) {
            container.addView(CardRenderer.messageCard(this, getString(R.string.empty_queue)));
            return;
        }

        int index = cursor.index();
        container.addView(CardRenderer.queueCard(this, items.get(index),
                index + 1, items.size(), store.isStale()));
    }
}
