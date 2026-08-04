package dev.erinlkolp.glassnotify.phone;

import android.app.Notification;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;

/**
 * The single place StatusBarNotification is touched.
 *
 * Everything downstream operates on SourceNotification, which has no Android
 * types and is therefore testable on the host. Spec section 12.2.
 */
public final class SbnMapper {

    private SbnMapper() {
    }

    public static SourceNotification map(StatusBarNotification sbn, PackageManager packages) {
        Notification notification = sbn.getNotification();
        Bundle extras = notification == null ? null : notification.extras;

        String title = extras == null ? null : charSequence(extras, Notification.EXTRA_TITLE);
        String text = extras == null ? null : charSequence(extras, Notification.EXTRA_TEXT);

        if (text == null && extras != null) {
            // Big-text style puts the body here instead.
            text = charSequence(extras, Notification.EXTRA_BIG_TEXT);
        }

        boolean ongoing = notification != null
                && (notification.flags & Notification.FLAG_ONGOING_EVENT) != 0;

        return new SourceNotification(
                sbn.getKey(),
                sbn.getPackageName(),
                appLabel(sbn.getPackageName(), packages),
                title,
                text,
                sbn.getPostTime(),
                ongoing);
    }

    private static String charSequence(Bundle extras, String key) {
        CharSequence value = extras.getCharSequence(key);
        return value == null ? null : value.toString();
    }

    /** Falls back to the package name, which is ugly but never wrong. */
    private static String appLabel(String packageName, PackageManager packages) {
        try {
            ApplicationInfo info = packages.getApplicationInfo(packageName, 0);
            CharSequence label = packages.getApplicationLabel(info);
            return label == null ? packageName : label.toString();
        } catch (PackageManager.NameNotFoundException e) {
            return packageName;
        }
    }
}
