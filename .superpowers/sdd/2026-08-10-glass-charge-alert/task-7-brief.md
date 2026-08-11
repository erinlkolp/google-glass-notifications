## Task 7: Fake the battery over adb

**Files:**
- Create: `glass/src/main/java/dev/erinlkolp/glassnotify/glass/DebugBatteryReceiver.java`
- Create: `scripts/fake-battery.sh`
- Modify: `glass/src/main/AndroidManifest.xml`
- Modify: `glass/src/main/java/dev/erinlkolp/glassnotify/glass/LinkServerService.java`

**Interfaces:**
- Consumes: `BatteryReading` (Task 5), `LinkServerService.onBatteryState` (Task 6).
- Produces: broadcast action `dev.erinlkolp.glassnotify.DEBUG_BATTERY` with int extra `level` and boolean extra `plugged`.

Charging Glass to 100% takes over an hour. Without this, none of the Task 9 checks get run honestly.

- [ ] **Step 1: Accept a synthetic state in `LinkServerService`**

Add these constants beside the others:

```java
    /** Debug-only extras, see DebugBatteryReceiver. */
    private static final String EXTRA_DEBUG_LEVEL = "debug_level";
    private static final String EXTRA_DEBUG_PLUGGED = "debug_plugged";
```

Then in `onStartCommand`, before the `if (!running)` block:

```java
        if (intent != null && intent.hasExtra(EXTRA_DEBUG_LEVEL)) {
            // Straight into the same path a real broadcast takes, so what is
            // being exercised is the real writer, not a shortcut round it.
            GlassState fake = BatteryReading.fromExtras(
                    intent.getIntExtra(EXTRA_DEBUG_LEVEL, 100), 100,
                    intent.getBooleanExtra(EXTRA_DEBUG_PLUGGED, true) ? 1 : 0);
            if (fake != null) {
                Log.i(TAG, "debug: battery " + fake);
                onBatteryState(fake);
            }
        }
```

- [ ] **Step 2: Write the receiver**

Create `glass/src/main/java/dev/erinlkolp/glassnotify/glass/DebugBatteryReceiver.java`:

```java
package dev.erinlkolp.glassnotify.glass;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Fake battery state, so the charge alert can be exercised without waiting
 * over an hour for a real charge.
 *
 *   adb shell am broadcast -a dev.erinlkolp.glassnotify.DEBUG_BATTERY \
 *     --ei level 100 --ez plugged true
 *
 * Routed through the service rather than acting directly, so the frame really
 * does travel the live socket via StateWriter. A shortcut that posted the
 * notification some other way would test nothing worth testing.
 */
public final class DebugBatteryReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!BuildConfig.DEBUG) {
            // Never allow synthetic battery state into a non-debug build.
            return;
        }

        Intent toService = new Intent(context, LinkServerService.class);
        toService.putExtra("debug_level", intent.getIntExtra("level", 100));
        toService.putExtra("debug_plugged", intent.getBooleanExtra("plugged", true));
        context.startService(toService);
    }
}
```

- [ ] **Step 3: Register it in the manifest**

In `glass/src/main/AndroidManifest.xml`, add beside the existing `DebugInjectReceiver`:

```xml
        <receiver
            android:name=".DebugBatteryReceiver"
            android:exported="true">
            <intent-filter>
                <action android:name="dev.erinlkolp.glassnotify.DEBUG_BATTERY" />
            </intent-filter>
        </receiver>
```

- [ ] **Step 4: Write the script**

Create `scripts/fake-battery.sh` and `chmod +x` it. The remote-quoting helper is copied from `fake-notify.sh` for the same reason documented there:

```bash
#!/usr/bin/env bash
# Injects a synthetic battery state into the Glass app, so the phone's charge
# alert can be exercised without waiting out a real charge.
#
#   scripts/fake-battery.sh 100 true    # full, on the charger  -> alert
#   scripts/fake-battery.sh 100 false   # unplugged             -> alert clears
#   scripts/fake-battery.sh 64 true     # charging              -> nothing
set -euo pipefail

SERIAL="${GLASS_SERIAL:-0123456789ABCDEF}"
ACTION=dev.erinlkolp.glassnotify.DEBUG_BATTERY

# `adb shell` joins its arguments with spaces and hands the result to the
# DEVICE's shell, where our local quoting no longer exists. See the same note
# in fake-notify.sh for what goes wrong without this.
remote() {
  local quoted=""
  local arg
  for arg in "$@"; do
    quoted="$quoted '$(printf '%s' "$arg" | sed "s/'/'\\\\''/g")'"
  done
  adb -s "$SERIAL" shell "$quoted"
}

LEVEL="${1:-100}"
PLUGGED="${2:-true}"

remote am broadcast -a "$ACTION" \
  --ei level "$LEVEL" \
  --ez plugged "$PLUGGED"
```

- [ ] **Step 5: Build and confirm the suite**

Run: `./gradlew test assembleDebug`
Expected: BUILD SUCCESSFUL, totals unchanged from Task 6 — wire 58, glass 47, phone 42.

- [ ] **Step 6: Commit**

```bash
chmod +x scripts/fake-battery.sh
git add glass/src/main/java/dev/erinlkolp/glassnotify/glass/DebugBatteryReceiver.java \
        glass/src/main/java/dev/erinlkolp/glassnotify/glass/LinkServerService.java \
        glass/src/main/AndroidManifest.xml \
        scripts/fake-battery.sh
git commit -m "feat(glass): inject fake battery state for testing

A real charge takes over an hour, which is long enough that the hardware
checks would get skipped or faked. This drives the same path a real
broadcast takes, so the frame genuinely crosses the live socket.

Debug builds only, matching DebugInjectReceiver.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

