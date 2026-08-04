package dev.erinlkolp.glassnotify.phone;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import dev.erinlkolp.glassnotify.wire.NotificationItem;
import dev.erinlkolp.glassnotify.wire.Protocol;
import dev.erinlkolp.glassnotify.wire.Snapshot;
import dev.erinlkolp.glassnotify.wire.Tier;

/**
 * Turns observed notifications into the snapshot sent to Glass.
 *
 * Every product decision lives here - what is shown, at what tier, in what
 * order, and how much text survives - and none of it touches an Android type,
 * so all of it is unit tested on the host JVM.
 *
 * Filtering here rather than on Glass is the largest battery lever in the
 * system: screened-out notifications never reach the radio. Spec section 8.
 */
public final class SnapshotBuilder {

    private SnapshotBuilder() {
    }

    public static Snapshot build(long snapshotId, List<SourceNotification> sources,
            Map<String, Tier> allowlist) {

        List<SourceNotification> eligible = new ArrayList<SourceNotification>();
        for (SourceNotification source : sources) {
            if (source.ongoing) {
                // Persistent status, not an event. Would occupy a slot forever.
                continue;
            }
            if (!allowlist.containsKey(source.packageName)) {
                continue;
            }
            eligible.add(source);
        }

        Collections.sort(eligible, new Comparator<SourceNotification>() {
            @Override
            public int compare(SourceNotification a, SourceNotification b) {
                // Newest first. Compare rather than subtract: the difference of
                // two epoch-milli longs can overflow an int.
                if (a.postedAt == b.postedAt) {
                    return a.key.compareTo(b.key); // stable, so ordering is deterministic
                }
                return a.postedAt > b.postedAt ? -1 : 1;
            }
        });

        List<NotificationItem> items = new ArrayList<NotificationItem>();
        for (SourceNotification source : eligible) {
            if (items.size() >= Protocol.MAX_ITEMS) {
                break;
            }
            items.add(new NotificationItem(
                    // key and appLabel are as app-controlled as the body text
                    // is: getKey()'s tag component and the label from the app's
                    // manifest are both unbounded. Untruncated, twenty items
                    // can push the snapshot past MAX_FRAME_BYTES, which the
                    // link cannot recover from on its own.
                    truncate(source.key, Protocol.MAX_KEY_CHARS),
                    truncate(source.appLabel, Protocol.MAX_APP_LABEL_CHARS),
                    truncate(source.title, Protocol.MAX_TITLE_CHARS),
                    truncate(source.text, Protocol.MAX_TEXT_CHARS),
                    source.postedAt,
                    allowlist.get(source.packageName)));
        }

        return new Snapshot(snapshotId, items);
    }

    /** Null-safe truncation. NotificationItem forbids nulls, so normalise here. */
    private static String truncate(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        if (value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, maxChars);
    }
}
