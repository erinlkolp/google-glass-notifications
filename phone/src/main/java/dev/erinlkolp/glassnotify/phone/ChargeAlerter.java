package dev.erinlkolp.glassnotify.phone;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;

import dev.erinlkolp.glassnotify.wire.GlassState;

/**
 * Raises and clears the "Glass is charged" notification.
 *
 * Its own channel, separate from the link status channel. That one is
 * IMPORTANCE_MIN on purpose - it is an always-present service notification the
 * wearer should never notice - and this one has to be audible, so they cannot
 * share. IMPORTANCE_DEFAULT rather than HIGH: a finished charge is worth a
 * sound, not a heads-up window over whatever you were doing.
 *
 * Main thread only. {@link ChargeAlertPolicy} holds mutable state with no
 * synchronisation, and confining every call to one thread is cheaper than
 * locking it.
 */
public final class ChargeAlerter implements LinkReader.Listener {

    private static final String CHANNEL_ID = "glass_charge";

    /** 1 belongs to the foreground service notification. */
    private static final int NOTIFICATION_ID = 2;

    private final Context context;
    private final ChargeAlertPolicy policy = new ChargeAlertPolicy();

    public ChargeAlerter(Context context) {
        this.context = context.getApplicationContext();
        createChannel();
    }

    @Override
    public void onGlassState(GlassState state) {
        switch (policy.onState(state)) {
            case SHOW:
                manager().notify(NOTIFICATION_ID, build());
                break;
            case CANCEL:
                manager().cancel(NOTIFICATION_ID);
                break;
            default:
                break;
        }
    }

    private Notification build() {
        return new Notification.Builder(context, CHANNEL_ID)
                .setContentTitle(context.getString(R.string.charged_title))
                .setContentText(context.getString(R.string.charged_text))
                .setSmallIcon(R.drawable.ic_glass_charged)
                .setAutoCancel(true)
                // Belt and braces. The policy already guarantees we do not
                // re-post while an alert stands, so this should never be the
                // thing that keeps it quiet - but if that guarantee ever
                // breaks, the failure is a silent update rather than a device
                // that chirps on every reconnect.
                .setOnlyAlertOnce(true)
                .build();
    }

    private void createChannel() {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                context.getString(R.string.channel_charge),
                NotificationManager.IMPORTANCE_DEFAULT);
        channel.setShowBadge(false);
        manager().createNotificationChannel(channel);
    }

    private NotificationManager manager() {
        return (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
    }
}
