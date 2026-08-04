package dev.erinlkolp.glassnotify.phone;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Locale;
import java.util.Set;

/**
 * Shows what still needs granting, and links straight to each system screen.
 *
 * Three things must be true before anything works, and each fails silently in
 * its own way, so each gets its own visible line rather than one vague
 * "not working" state.
 */
public final class SetupActivity extends Activity {

    private TextView accessStatus;
    private TextView bondStatus;
    private TextView batteryStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        accessStatus = addRow(root, getString(R.string.grant_notification_access),
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        startActivity(new Intent(
                                "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
                    }
                });

        bondStatus = addRow(root, "Pair with Glass", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
            }
        });

        batteryStatus = addRow(root, getString(R.string.grant_battery_exemption),
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        requestBatteryExemption();
                    }
                });

        addRow(root, getString(R.string.configure_allowlist), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(SetupActivity.this, AllowlistActivity.class));
            }
        });

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        accessStatus.setText(hasNotificationAccess() ? "Granted" : "Not granted");
        bondStatus.setText(bondedGlassName() == null
                ? "No paired device named 'Glass'" : "Paired with " + bondedGlassName());
        batteryStatus.setText(isBatteryExempt() ? "Exempt" : "Not exempt");

        if (hasNotificationAccess()) {
            LinkClientService.start(this);
        }
    }

    /**
     * Reads the same colon-separated setting the system uses, rather than
     * inferring from whether our service has been bound - which is racy right
     * after a grant.
     */
    private boolean hasNotificationAccess() {
        String enabled = Settings.Secure.getString(getContentResolver(),
                "enabled_notification_listeners");
        if (enabled == null) {
            return false;
        }
        String target = getPackageName() + "/" + NotifyListenerService.class.getName();
        for (String entry : enabled.split(":")) {
            if (entry.equals(target)) {
                return true;
            }
        }
        return false;
    }

    private String bondedGlassName() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            return null;
        }
        Set<BluetoothDevice> bonded = adapter.getBondedDevices();
        if (bonded == null) {
            return null;
        }
        for (BluetoothDevice device : bonded) {
            String name = device.getName();
            if (name != null && name.toLowerCase(Locale.US).contains("glass")) {
                return name;
            }
        }
        return null;
    }

    private boolean isBatteryExempt() {
        PowerManager power = (PowerManager) getSystemService(Context.POWER_SERVICE);
        return power != null && power.isIgnoringBatteryOptimizations(getPackageName());
    }

    private void requestBatteryExemption() {
        // No SDK_INT guard: minSdk is 26, well above the API 23 this needs.
        Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
        intent.setData(Uri.parse("package:" + getPackageName()));
        startActivity(intent);
    }

    private TextView addRow(LinearLayout parent, String label, View.OnClickListener onClick) {
        TextView title = new TextView(this);
        title.setText(label);
        title.setTextSize(18f);
        parent.addView(title);

        TextView status = new TextView(this);
        status.setTextSize(14f);
        parent.addView(status);

        Button button = new Button(this);
        button.setText("Open");
        button.setOnClickListener(onClick);
        parent.addView(button);

        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (int) (24 * getResources().getDisplayMetrics().density)));
        parent.addView(spacer);

        return status;
    }
}
