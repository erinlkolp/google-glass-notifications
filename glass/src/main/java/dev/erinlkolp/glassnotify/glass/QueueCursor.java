package dev.erinlkolp.glassnotify.glass;

/**
 * Tracks which queue item is on screen.
 *
 * Deliberately free of Android types so it can be unit tested on the host JVM.
 * The interesting behaviour is clamping: because the phone sends whole
 * snapshots rather than deltas, the list can shrink while the wearer is part
 * way through it, and that must never throw.
 */
public final class QueueCursor {

    private int index;
    private int size;

    /** Applies a new item count, clamping the current position into range. */
    public void setSize(int newSize) {
        if (newSize < 0) {
            throw new IllegalArgumentException("size must not be negative: " + newSize);
        }
        this.size = newSize;
        clamp();
    }

    public int index() {
        return index;
    }

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    /** Returns true if the position actually moved. */
    public boolean next() {
        if (index + 1 >= size) {
            return false;
        }
        index++;
        return true;
    }

    /** Returns true if the position actually moved. */
    public boolean previous() {
        if (index <= 0) {
            return false;
        }
        index--;
        return true;
    }

    private void clamp() {
        if (size == 0) {
            index = 0;
        } else if (index >= size) {
            index = size - 1;
        }
    }
}
