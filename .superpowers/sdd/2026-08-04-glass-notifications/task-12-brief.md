## Task 12: Setup and allowlist screens

**Files:**
- Create: `phone/src/main/java/dev/erinlkolp/glassnotify/phone/SetupActivity.java`
- Create: `phone/src/main/java/dev/erinlkolp/glassnotify/phone/AllowlistActivity.java`
- Modify: `phone/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: `AllowlistStore`, `GlassNotifyPrefs`, `LinkClientService`, `Tier`.
- Produces: nothing consumed by later tasks.

- [ ] **Step 1: Write `SetupActivity.java`**

```java
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
```

- [ ] **Step 2: Write `AllowlistActivity.java`**

```java
package dev.erinlkolp.glassnotify.phone;

import android.app.Activity;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import dev.erinlkolp.glassnotify.wire.Tier;

/**
 * Per-app tier configuration.
 *
 * Tapping an app cycles it OFF -> QUEUE -> INTERRUPT -> OFF. A three-state
 * cycle on one row beats a checkbox plus a separate tier control, and the
 * whole list is short enough that scanning it is fine.
 */
public final class AllowlistActivity extends Activity {

    private AllowlistStore store;
    private final List<ApplicationInfo> apps = new ArrayList<ApplicationInfo>();
    private ArrayAdapter<ApplicationInfo> adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        store = new AllowlistStore(getSharedPreferences(GlassNotifyPrefs.NAME, Context.MODE_PRIVATE));
        loadApps();

        adapter = new ArrayAdapter<ApplicationInfo>(this, 0, apps) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView row = (TextView) convertView;
                if (row == null) {
                    row = new TextView(AllowlistActivity.this);
                    int pad = (int) (12 * getResources().getDisplayMetrics().density);
                    row.setPadding(pad, pad, pad, pad);
                    row.setTextSize(16f);
                }
                ApplicationInfo info = getItem(position);
                Tier tier = store.rules().get(info.packageName);
                row.setText(label(info) + "\n" + describe(tier));
                return row;
            }
        };

        ListView list = new ListView(this);
        list.setAdapter(adapter);
        list.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(android.widget.AdapterView<?> parent, View view,
                    int position, long id) {
                cycle(apps.get(position).packageName);
                adapter.notifyDataSetChanged();
            }
        });
        setContentView(list);
    }

    private void cycle(String packageName) {
        Map<String, Tier> rules = store.rules();
        Tier current = rules.get(packageName);
        if (current == null) {
            store.put(packageName, Tier.QUEUE);
        } else if (current == Tier.QUEUE) {
            store.put(packageName, Tier.INTERRUPT);
        } else {
            store.remove(packageName);
        }
    }

    private static String describe(Tier tier) {
        if (tier == null) {
            return "Not shown";
        }
        return tier == Tier.INTERRUPT ? "Interrupts" : "Queued silently";
    }

    private String label(ApplicationInfo info) {
        CharSequence label = getPackageManager().getApplicationLabel(info);
        return label == null ? info.packageName : label.toString();
    }

    /** Only apps that can actually produce notifications the user recognises. */
    private void loadApps() {
        PackageManager packages = getPackageManager();
        for (ApplicationInfo info : packages.getInstalledApplications(0)) {
            // Launchable apps only: system packages without a launcher entry
            // would bury the list in noise.
            if (packages.getLaunchIntentForPackage(info.packageName) != null) {
                apps.add(info);
            }
        }
        Collections.sort(apps, new Comparator<ApplicationInfo>() {
            @Override
            public int compare(ApplicationInfo a, ApplicationInfo b) {
                return label(a).compareToIgnoreCase(label(b));
            }
        });
    }
}
```

- [ ] **Step 3: Register both activities**

Add inside `<application>`:

```xml
        <activity
            android:name=".SetupActivity"
            android:exported="true"
            android:label="@string/app_name">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <activity
            android:name=".AllowlistActivity"
            android:exported="false"
            android:label="@string/configure_allowlist" />
```

- [ ] **Step 4: Build and install**

```bash
./gradlew :phone:assembleDebug
adb -s VS9967edd915b install -r phone/build/outputs/apk/debug/phone-debug.apk
adb -s VS9967edd915b shell am start -n dev.erinlkolp.glassnotify.phone/.SetupActivity
```

Expected: the setup screen lists four rows, each with a live status line. Notification access and battery exemption will both read as not granted.

- [ ] **Step 5: Commit**

```bash
git add phone/
git commit -m "feat(phone): add setup and allowlist screens

Three things must be true before anything works - notification access,
a bond with Glass, and a battery-optimisation exemption - and each fails
silently in a different way. Each gets its own status line linking
straight to the relevant system screen rather than one vague
'not working' state.

Notification access is read from the enabled_notification_listeners
secure setting rather than inferred from whether our service has been
bound, which is racy immediately after a grant.

The allowlist cycles each app OFF -> QUEUE -> INTERRUPT on tap, and
lists only launchable packages so system noise stays out of it."
```

---

