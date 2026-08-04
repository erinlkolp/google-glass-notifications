package dev.erinlkolp.glassnotify.wire;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The complete notification state at a moment in time. Glass replaces whatever
 * it was holding with this; there are no deltas to reconcile. See spec section 6.
 */
public final class Snapshot {

    /** Monotonically increasing on the phone. Useful for logs; not used for ordering. */
    public final long snapshotId;

    /** Newest first, as ordered by the phone. Unmodifiable. */
    public final List<NotificationItem> items;

    public Snapshot(long snapshotId, List<NotificationItem> items) {
        if (items == null) {
            throw new NullPointerException("items");
        }
        this.snapshotId = snapshotId;
        this.items = Collections.unmodifiableList(new ArrayList<NotificationItem>(items));
    }

    @Override
    public String toString() {
        return "Snapshot{id=" + snapshotId + ", items=" + items.size() + "}";
    }
}
