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
