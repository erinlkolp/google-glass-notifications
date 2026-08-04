package dev.erinlkolp.glassnotify.wire;

/** One notification, already filtered, tiered and truncated by the phone. */
public final class NotificationItem {

    /** StatusBarNotification.getKey() — stable identity across updates. */
    public final String key;

    /** Human-readable app name. Resolved on the phone; Glass never renders icons. */
    public final String appLabel;

    public final String title;
    public final String text;

    /** Epoch millis, from the phone's clock. */
    public final long postedAt;

    public final Tier tier;

    public NotificationItem(String key, String appLabel, String title, String text,
            long postedAt, Tier tier) {
        if (key == null) {
            throw new NullPointerException("key");
        }
        if (appLabel == null) {
            throw new NullPointerException("appLabel");
        }
        if (title == null) {
            throw new NullPointerException("title");
        }
        if (text == null) {
            throw new NullPointerException("text");
        }
        if (tier == null) {
            throw new NullPointerException("tier");
        }
        this.key = key;
        this.appLabel = appLabel;
        this.title = title;
        this.text = text;
        this.postedAt = postedAt;
        this.tier = tier;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof NotificationItem)) {
            return false;
        }
        NotificationItem other = (NotificationItem) o;
        return postedAt == other.postedAt
                && key.equals(other.key)
                && appLabel.equals(other.appLabel)
                && title.equals(other.title)
                && text.equals(other.text)
                && tier == other.tier;
    }

    @Override
    public int hashCode() {
        int result = key.hashCode();
        result = 31 * result + appLabel.hashCode();
        result = 31 * result + title.hashCode();
        result = 31 * result + text.hashCode();
        result = 31 * result + (int) (postedAt ^ (postedAt >>> 32));
        result = 31 * result + tier.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return "NotificationItem{" + appLabel + " / " + title + " / " + tier + "}";
    }
}
