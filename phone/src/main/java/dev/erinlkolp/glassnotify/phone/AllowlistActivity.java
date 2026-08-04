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
