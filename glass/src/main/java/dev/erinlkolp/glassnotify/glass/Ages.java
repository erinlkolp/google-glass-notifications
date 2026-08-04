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
