# Task 12 Report: Setup and allowlist screens

## What I implemented

Transcribed the brief's code exactly, no changes:

1. **`phone/src/main/java/dev/erinlkolp/glassnotify/phone/SetupActivity.java`** (new)
   - `Activity` (framework, not AppCompat) that builds its UI programmatically in
     `onCreate` (`LinearLayout` inside a `ScrollView`), with four rows: notification
     access, Bluetooth pairing, battery exemption, and a link into `AllowlistActivity`.
   - `onResume` calls `refresh()`, which re-reads all three prerequisite states each
     time the screen is shown and starts `LinkClientService` if notification access
     is granted.
   - `hasNotificationAccess()` reads the `enabled_notification_listeners` secure
     setting and does a colon-split exact match against
     `getPackageName() + "/" + NotifyListenerService.class.getName()` — does not
     infer from binding state.
   - `bondedGlassName()` scans `BluetoothAdapter.getBondedDevices()` for a name
     containing "glass" (case-insensitive).
   - `isBatteryExempt()` uses `PowerManager.isIgnoringBatteryOptimizations`.
   - `requestBatteryExemption()` fires `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
     with no `SDK_INT` guard, as directed (minSdk 26 > required API 23).

2. **`phone/src/main/java/dev/erinlkolp/glassnotify/phone/AllowlistActivity.java`** (new)
   - `Activity` showing a `ListView` of installed launchable apps
     (`getLaunchIntentForPackage(...) != null`), sorted by display label.
   - Custom `ArrayAdapter` renders each row as `label + "\n" + describe(tier)`.
   - Tapping a row calls `cycle(packageName)`: `null → QUEUE → INTERRUPT → null`
     (remove), using `AllowlistStore.rules()/put()/remove()`.
   - Uses `dev.erinlkolp.glassnotify.wire.Tier` and
     `getSharedPreferences(GlassNotifyPrefs.NAME, Context.MODE_PRIVATE)`.

3. **`phone/src/main/AndroidManifest.xml`** (modified)
   - Added `<activity android:name=".SetupActivity">` with the `MAIN`/`LAUNCHER`
     intent filter, `exported="true"`, labeled `@string/app_name`.
   - Added `<activity android:name=".AllowlistActivity" android:exported="false"
     android:label="@string/configure_allowlist" />`.
   - Both inserted inside the existing `<application>` element, alongside
     `NotifyListenerService`, `LinkClientService`, and `AclReceiver`.

No string resources needed adding — all referenced strings
(`grant_notification_access`, `grant_battery_exemption`, `configure_allowlist`,
`app_name`) already existed in `phone/src/main/res/values/strings.xml`.

## What I tested and results

- `./gradlew :phone:testDebugUnitTest :phone:assembleDebug` — **BUILD SUCCESSFUL**.
  - Unit test XML results summed to **22 tests**, matching the expected regression
    count (no new unit tests added or removed, per the brief — both new classes are
    framework-bound with no Robolectric in this project).
  - `phone-debug.apk` produced at `phone/build/outputs/apk/debug/phone-debug.apk`.
  - Only compiler warnings were the usual Java 8 source/target obsolescence notices
    from JDK 21, plus one "uses or overrides a deprecated API" note in
    `SetupActivity.java` (this is the framework `Intent(String action)` /
    `startActivity` pattern already used elsewhere in the codebase — not something
    the brief asked to avoid).

## Steps NOT verified (need hardware)

Per the task instructions, the following from Step 4 were skipped because no
device is attached (confirmed no `adb` device in this environment):
- `adb -s VS9967edd915b install -r phone/build/outputs/apk/debug/phone-debug.apk`
- `adb -s VS9967edd915b shell am start -n dev.erinlkolp.glassnotify.phone/.SetupActivity`
- The expected on-device behavior ("the setup screen lists four rows, each with a
  live status line... notification access and battery exemption will both read as
  not granted") was **not observed** — only `:phone:assembleDebug` was run and
  confirmed to succeed.

## Files changed

- `phone/src/main/java/dev/erinlkolp/glassnotify/phone/SetupActivity.java` (new)
- `phone/src/main/java/dev/erinlkolp/glassnotify/phone/AllowlistActivity.java` (new)
- `phone/src/main/AndroidManifest.xml` (modified — two `<activity>` elements added)

Commit: `8f11b37` — "feat(phone): add setup and allowlist screens" (message
transcribed verbatim from the brief's Step 5).

## Self-review

- **Completeness against brief:** all five steps done except the two device-only
  commands in Step 4, which require hardware not available here.
- **Deliberate behaviours — none softened:**
  1. Notification access read from `enabled_notification_listeners` via exact
     colon-split match — kept as-is, no shortcut through service-bound state.
  2. Each of the three prerequisites has its own status `TextView` and its own
     `Button` routed to a distinct system `Intent` — preserved.
  3. Allowlist cycle is `null → QUEUE → INTERRUPT → remove` exactly as written —
     not turned into a checkbox or simplified.
  4. `loadApps()` filters strictly on `getLaunchIntentForPackage(...) != null` —
     no system-package inclusion added.
- **AndroidX check:** grepped both new files for `androidx` — none found. Both
  extend the plain framework `Activity` and use only `android.widget.*` /
  `android.view.*` types, consistent with the Java-only / no-AndroidX constraint.
- **Java 8 / no `var`:** confirmed no `var` usage, no post-8 API calls introduced;
  the only warnings are javac's generic obsolescence notices for `-source 8
  -target 8`, unrelated to this task's code.
- **Package placement:** both files are in
  `dev.erinlkolp.glassnotify.phone`, matching existing sibling classes.

## Concerns

None. The implementation is a verbatim transcription of the brief; the only gap
is the explicitly-expected, hardware-dependent install/launch verification, which
was called out in advance as unavailable in this environment and not faked.

---

## Fix report: build output noise (review follow-up)

**Finding addressed:** the review flagged non-pristine build output — two distinct
warning classes, one being AGP's own diagnostic about compiling Java 8 source/
target under JDK 21, the other javac's own `[options]` lint warnings, plus a
deprecated-API note attributed to `BluetoothAdapter.getDefaultAdapter()`.

**Erin's ruling:** suppress the first class only, via exactly one property line in
`gradle.properties` with a comment explaining the Java 8 constraint is deliberate
(Glass runs API 22). Do not touch `getDefaultAdapter()` anywhere — it appears at
7 call sites across 3 files in both modules, including the two files with a
previously-fixed write race and accept-thread lifecycle leak, and churning that
code for an informational note is not a trade being made. The remaining
deprecated-API note is accepted as known residue for the whole-branch review.

### Change made

`/home/ekolp/workspace/google-glass-notifications/gradle.properties`, one line
plus a three-line comment added directly under `android.useAndroidX=false`:

```properties
android.useAndroidX=false
# Java 8 source/target is deliberate and load-bearing: the Glass device runs
# API 22, which caps us there. JDK 21's warning about compiling for source/
# target 8 is noise about a decision that is not going to change.
android.javaCompile.suppressSourceTargetDeprecationWarning=true
```

No Java files were touched. No `BluetoothAdapter.getDefaultAdapter()` call sites
were changed.

### Verification — clean build

Commands run, in order:

```
./gradlew clean
./gradlew :glass:assembleDebug :phone:assembleDebug :wire:test :glass:testDebugUnitTest :phone:testDebugUnitTest
```

`clean`: `BUILD SUCCESSFUL in 529ms` (3 tasks, all executed — confirms nothing was
cached going into the next invocation).

Main run: `BUILD SUCCESSFUL in 1s`, 77 actionable tasks, 77 executed (all fresh,
none up-to-date — warnings below are genuine re-emissions, not stale output).

Test totals held steady versus the pre-fix baseline:
- `:wire:test` — 36 tests
- `:glass:testDebugUnitTest` — 31 tests
- `:phone:testDebugUnitTest` — 22 tests (unchanged from the original Task 12 run)

APKs produced:
- `glass/build/outputs/apk/debug/glass-debug.apk`
- `phone/build/outputs/apk/debug/phone-debug.apk`

### Warnings: before vs. after

Counted directly from the clean-build log
(`/tmp/claude-1000/-home-ekolp-workspace-google-glass-notifications/c6381c2f-f121-4b02-96ad-e418939bef6a/scratchpad/task12-fix-build.log`):

| Line | Occurrences after fix |
|---|---|
| `Java compiler version 21 has deprecated support for compiling with source/target version 8.` | **0** |
| `warning: [options] source value 8 is obsolete and will be removed in a future release` | **6** |
| `warning: [options] target value 8 is obsolete and will be removed in a future release` | **6** |
| `Note: Some input files use or override a deprecated API.` | **2** |

**What this means, stated explicitly:**

- The `android.javaCompile.suppressSourceTargetDeprecationWarning=true` property
  is confirmed to be the correct property name for AGP 8.7.0 (verified in
  `build.gradle.kts`: `id("com.android.application") version "8.7.0"`). It works —
  but it only suppresses **AGP's own added diagnostic paragraph** (the
  "Java compiler version 21 has deprecated support..." block, together with its
  "Try one of the following options" numbered list and the suggestion to set this
  very property). That block is now fully gone from both `:phone` and `:glass`
  compile tasks.
- It does **not** suppress the plain javac `[options]` lint warnings
  (`source value 8 is obsolete...` / `target value 8 is obsolete...`). Those are
  emitted directly by javac itself whenever `-source 8 -target 8` is passed under
  a newer JDK, independent of AGP's messaging layer. They appear 6 times each —
  once per compile task across the three modules
  (`wire:compileJava`, `wire:compileTestJava`, `phone:compileDebugJavaWithJavac`,
  `phone:compileDebugUnitTestJavaWithJavac`, `glass:compileDebugJavaWithJavac`,
  `glass:compileDebugUnitTestJavaWithJavac`). Notably they also appear for
  `:wire`, a plain `java-library` module that AGP does not touch at all — direct
  proof these come from javac, not from Android's build pipeline, and thus sit
  entirely outside what the ruling's property can reach.
- The deprecated-API note (`Note: Some input files use or override a deprecated
  API.`) remains, exactly as expected/accepted, attributable to
  `BluetoothAdapter.getDefaultAdapter()` per the reviewer's finding — appears for
  `:phone:compileDebugJavaWithJavac` and `:glass:compileDebugJavaWithJavac` (2
  occurrences), which is where those call sites live.

**Deviation from the stated expected end state, reported rather than papered
over:** the ruling described the expected end state as "the `source value 8` /
`target value 8` / `Java compiler version 21` lines are gone." Only the
`Java compiler version 21` line is actually gone; the `source value 8` /
`target value 8` javac lines persist, for the structural reason above (they are
javac's native lint output, not an AGP message the named property controls).
Eliminating them would require a different mechanism — e.g. `-Xlint:-options`
compiler args, or a Java toolchain pinned to an older JDK — neither of which was
authorized by the ruling ("exactly one line... No Java changes at all"), so no
further change was made. I did not treat this as license to guess at an
alternative property name or add compiler args on my own initiative; flagging it
here for Erin/the coordinator to decide whether the residual javac warnings are
also accepted residue (like the deprecated-API note) or need a follow-up ruling.

### Self-review of this fix

- Exactly one property line plus its comment was added; no other lines in
  `gradle.properties` were touched.
- No Java source files were modified in this fix round.
- No `BluetoothAdapter.getDefaultAdapter()` call site was touched, in `phone` or
  `glass`.
- Verification used a genuine `clean` beforehand, confirmed by `77 executed` with
  zero `UP-TO-DATE` tasks in the main run — the warning counts reflect real
  recompilation, not stale/cached output.
- Test counts (36 / 31 / 22) are unchanged from before this fix, confirming no
  regression was introduced by the `gradle.properties` change.

### Concern carried forward

The named property does not fully deliver the literal expected end state as
described (two of three warning lines persist, for a structural reason rooted in
javac itself rather than AGP). The fix as authorized was applied precisely and
verified; whether the residual `source value 8` / `target value 8` javac warnings
also count as accepted residue, or whether a further-scoped fix is wanted, is a
decision for Erin/the coordinator — not one I made unilaterally.

---

## Fix report round 2: residual javac `[options]` warnings

**Finding:** round 1 confirmed (independently, by the coordinator) that the
remaining `warning: [options] source value 8 is obsolete...` /
`target value 8 is obsolete...` lines come from javac itself, not from AGP's
messaging layer — proven by their appearance even in `:wire`, a plain
`java-library` module AGP never touches. The `gradle.properties` property from
round 1 cannot reach them; a compiler-args change is required instead.

**Remedy directed:** add `-Xlint:-options` to the `JavaCompile` compiler args of
all three Java-compiling modules — `wire`, `glass`, `phone` — the exact
suppression javac's own warning text names. Explicitly authorized: touching only
compiler args in the three build scripts, with a short comment on each. Explicitly
forbidden: touching `BluetoothAdapter.getDefaultAdapter()`, changing source/
target level or toolchain, adding `-Werror` or other lint config, or removing the
round-1 `gradle.properties` property.

### Changes made

`/home/ekolp/workspace/google-glass-notifications/wire/build.gradle.kts` — added
`-Xlint:-options` to the existing `tasks.withType<JavaCompile>()` block (which
already set `options.release.set(8)`):

```kotlin
// Source/target 8 is pinned for the life of the project - the Glass device is
// API 22 - so javac's "obsolete" advice to move off it is not actionable.
tasks.withType<JavaCompile>().configureEach {
    options.release.set(8)
    options.compilerArgs.add("-Xlint:-options")
}
```

`/home/ekolp/workspace/google-glass-notifications/glass/build.gradle.kts` and
`/home/ekolp/workspace/google-glass-notifications/phone/build.gradle.kts` — each
had this new block appended at the top level, after the `dependencies { }` block,
outside `android { }` (neither module had a `tasks.withType<JavaCompile>` block
before this):

```kotlin
// Source/target 8 is pinned for the life of the project - the Glass device is
// API 22 - so javac's "obsolete" advice to move off it is not actionable.
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Xlint:-options")
}
```

No Java source files were touched. `BluetoothAdapter.getDefaultAdapter()` was not
touched. Source/target level, toolchain, and `options.release` were not changed.
No `-Werror` or other lint configuration was added. The round-1
`android.javaCompile.suppressSourceTargetDeprecationWarning=true` property in
`gradle.properties` was left in place, untouched.

### Verification — clean build

Commands run, in order:

```
./gradlew clean
./gradlew :wire:test :glass:testDebugUnitTest :phone:testDebugUnitTest :glass:assembleDebug :phone:assembleDebug
```

`clean`: `BUILD SUCCESSFUL in 2s` (3 tasks, all executed).

Main run: `BUILD SUCCESSFUL in 1s`, 77 actionable tasks, 77 executed (none
up-to-date — a genuine re-emission, not stale/cached output). Full log captured at
`/tmp/claude-1000/-home-ekolp-workspace-google-glass-notifications/c6381c2f-f121-4b02-96ad-e418939bef6a/scratchpad/task12-fix2-build.log`.

The only compiler output left in the whole run is, for `:glass:compileDebugJavaWithJavac`
and `:phone:compileDebugJavaWithJavac`:

```
Note: Some input files use or override a deprecated API.
Note: Recompile with -Xlint:deprecation for details.
```

Test totals held steady versus both prior baselines:
- `:wire:test` — 36 tests
- `:glass:testDebugUnitTest` — 31 tests
- `:phone:testDebugUnitTest` — 22 tests

APKs produced and confirmed on disk:
- `glass/build/outputs/apk/debug/glass-debug.apk`
- `phone/build/outputs/apk/debug/phone-debug.apk`

### Warnings: exact counts after the fix (grepped from the clean-build log)

| Line | Occurrences |
|---|---|
| `warning: [options] source value 8 is obsolete and will be removed in a future release` | **0** |
| `warning: [options] target value 8 is obsolete and will be removed in a future release` | **0** |
| `warning: [options] To suppress warnings about obsolete options, use -Xlint:-options` | **0** |
| `Java compiler version 21 has deprecated support for compiling with source/target version 8.` | **0** |
| `Note: Some input files use or override a deprecated API.` | **2** (phone + glass, as expected/accepted) |

**This matches the expected end state exactly:** zero `source value 8` /
`target value 8` / "To suppress warnings about obsolete options" lines, and the
deprecated-API note still present as accepted residue. `-Xlint:-options` did
suppress them — no further escalation to other compiler flags was needed.

### Self-review of this fix

- Compiler-args change touched only `wire/build.gradle.kts`,
  `glass/build.gradle.kts`, `phone/build.gradle.kts` — no other files.
- No Java source files modified.
- `BluetoothAdapter.getDefaultAdapter()` untouched anywhere.
- Source/target level, `options.release`, and toolchain configuration
  unchanged.
- No `-Werror` or other new lint configuration added.
- Round-1 `gradle.properties` property still present and untouched.
- Verified with a genuine `clean` beforehand (0 up-to-date tasks in the main
  run), so the zero-occurrence counts reflect real recompilation, not stale
  output.
- Test counts (36 / 31 / 22) unchanged from both the original Task 12 baseline
  and the round-1 fix baseline — no regression introduced.

### Concern

None outstanding. `-Xlint:-options` fully resolved the residual warnings exactly
as the javac hint suggested; the noted trade-off (it silences the whole
`[options]` category, not only the obsolete-source/target message) is accepted
per the coordinator's framing, since the source/target level is fixed for the
life of the project and unlikely to surface other `[options]` warnings worth
seeing.
