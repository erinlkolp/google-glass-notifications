package dev.erinlkolp.glassnotify.phone;

import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.util.Locale;

/**
 * Cuts the reconnect backoff short when Glass comes into range.
 *
 * Without this, walking back to your desk can mean waiting out a full 60
 * second interval before notifications resume, which feels broken even though
 * it is working. Spec section 10.2.
 */
public final class AclReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!BluetoothDevice.ACTION_ACL_CONNECTED.equals(intent.getAction())) {
            return;
        }
        BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
        if (device == null) {
            return;
        }
        String name = device.getName();
        if (name != null && name.toLowerCase(Locale.US).contains("glass")) {
            LinkClientService.wake(context);
        }
    }
}
