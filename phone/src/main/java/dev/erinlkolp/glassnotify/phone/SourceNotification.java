package dev.erinlkolp.glassnotify.phone;

/**
 * A notification as observed on the phone, with every Android type already
 * stripped off.
 *
 * StatusBarNotification cannot be constructed in a host unit test, so mapping
 * happens once in SbnMapper and every decision after that operates on this.
 * Spec section 12.2.
 */
public final class SourceNotification {

    public final String key;
    public final String packageName;
    public final String appLabel;

    /** May be null - plenty of real notifications have no title. */
    public final String title;

    /** May be null. */
    public final String text;

    public final long postedAt;

    /** True for persistent status: media players, navigation, foreground services. */
    public final boolean ongoing;

    public SourceNotification(String key, String packageName, String appLabel,
            String title, String text, long postedAt, boolean ongoing) {
        if (key == null) {
            throw new NullPointerException("key");
        }
        if (packageName == null) {
            throw new NullPointerException("packageName");
        }
        if (appLabel == null) {
            throw new NullPointerException("appLabel");
        }
        this.key = key;
        this.packageName = packageName;
        this.appLabel = appLabel;
        this.title = title;
        this.text = text;
        this.postedAt = postedAt;
        this.ongoing = ongoing;
    }
}
