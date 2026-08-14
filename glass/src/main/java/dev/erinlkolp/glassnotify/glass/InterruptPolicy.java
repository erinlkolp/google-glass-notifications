package dev.erinlkolp.glassnotify.glass;

import java.util.HashMap;
import java.util.Map;

import dev.erinlkolp.glassnotify.wire.NotificationItem;
import dev.erinlkolp.glassnotify.wire.Snapshot;

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
            if (!item.tier.interrupts()) {
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
