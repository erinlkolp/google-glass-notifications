# Task 7 Report: Fake the battery over adb

## What was implemented

Exactly the four edits specified in `task-7-brief.md`, transcribed verbatim:

1. **`glass/src/main/java/dev/erinlkolp/glassnotify/glass/LinkServerService.java`** (modified)
   - Added two private static final String constants `EXTRA_DEBUG_LEVEL` ("debug_level") and
     `EXTRA_DEBUG_PLUGGED` ("debug_plugged") beside the existing `WRITER_JOIN_MS` constant.
   - Added a block at the top of `onStartCommand`, before the `if (!running)` block, that checks
     for the debug extra, builds a `GlassState` via `BatteryReading.fromExtras(...)`, null-checks
     it, logs it, and calls the existing `onBatteryState(fake)` method — routing the injected
     state through the real `StateWriter`/socket path rather than acting directly.

2. **`glass/src/main/java/dev/erinlkolp/glassnotify/glass/DebugBatteryReceiver.java`** (created)
   - `BroadcastReceiver` gated on `BuildConfig.DEBUG` (returns immediately if not a debug build).
   - Reads `level` (int) and `plugged` (boolean) extras off the incoming broadcast intent, forwards
     them as `debug_level` / `debug_plugged` extras on a `startService` call to
     `LinkServerService`.

3. **`glass/src/main/AndroidManifest.xml`** (modified)
   - Registered `.DebugBatteryReceiver` as an exported receiver with an intent-filter for action
     `dev.erinlkolp.glassnotify.DEBUG_BATTERY`, placed directly after the existing
     `.DebugInjectReceiver` entry.

4. **`scripts/fake-battery.sh`** (created, chmod +x)
   - Copies the remote-quoting `remote()` helper pattern from `fake-notify.sh` verbatim (same
     comment referring back to `fake-notify.sh`'s longer explanation), broadcasts
     `dev.erinlkolp.glassnotify.DEBUG_BATTERY` with `--ei level` and `--ez plugged` extras, args
     default to `100` and `true`.

No files outside this list were touched. `Protocol.VERSION`, `phone`, and `wire` were left alone.
No lambdas, `java.util.function`, or `Optional` were used; every `if` (including the null-check on
`fake`) is braced, matching the codebase idiom seen in `DebugInjectReceiver`.

## Commands run and actual output

### chmod + syntax check
```
$ chmod +x scripts/fake-battery.sh
$ ls -la scripts/fake-battery.sh
-rwxrwxr-x 1 ekolp ekolp 1004 Aug 10 17:35 scripts/fake-battery.sh
$ bash -n scripts/fake-battery.sh
bash -n exit: 0
```

### Build
```
$ ./gradlew test assembleDebug
...
BUILD SUCCESSFUL in 2s
118 actionable tasks: 19 executed, 99 up-to-date
```

### Test counts (single variant each, per the "don't double-count" gotcha)
Counted by summing `tests="N"` across each module's `<testsuite>` XML reports, using only one
variant per Android module (`testDebugUnitTest` for `glass` and `phone`; `wire` is a plain Java
module with a single `test` task, no variants):

```
$ grep -h "<testsuite " wire/build/test-results/test/TEST-*.xml | grep -oP 'tests="\K[0-9]+' | awk '{s+=$1} END {print s}'
58

$ find glass/build/test-results -path "*testDebugUnitTest*" -name "TEST-*.xml" | xargs grep -h "<testsuite " | grep -oP 'tests="\K[0-9]+' | awk '{s+=$1} END {print s}'
47

$ find phone/build/test-results -path "*testDebugUnitTest*" -name "TEST-*.xml" | xargs grep -h "<testsuite " | grep -oP 'tests="\K[0-9]+' | awk '{s+=$1} END {print s}'
42

$ grep -h "<testsuite " wire/build/test-results/test/TEST-*.xml glass/build/test-results/testDebugUnitTest/TEST-*.xml phone/build/test-results/testDebugUnitTest/TEST-*.xml | grep -oP 'failures="\K[0-9]+' | awk '{s+=$1} END {print s}'
0
```

Totals: wire 58, glass 47, phone 42, zero failures — exactly matching the expected unchanged
totals from Task 6. This task adds no new unit tests, as expected (it's a debug-only affordance
verified by the build).

## Commit

```
$ git add glass/src/main/java/dev/erinlkolp/glassnotify/glass/DebugBatteryReceiver.java \
        glass/src/main/java/dev/erinlkolp/glassnotify/glass/LinkServerService.java \
        glass/src/main/AndroidManifest.xml \
        scripts/fake-battery.sh
$ git commit -m "feat(glass): inject fake battery state for testing ..."
[feat/glass-charge-alert 5fde0c3] feat(glass): inject fake battery state for testing
 4 files changed, 85 insertions(+)
 create mode 100644 glass/src/main/java/dev/erinlkolp/glassnotify/glass/DebugBatteryReceiver.java
 create mode 100755 scripts/fake-battery.sh
```

Commit SHA: `5fde0c3eaaf08f2d843c84fa4b91556ed0ae7f6a`

`git status` after the commit shows only the pre-existing untracked `data/` directory (unrelated
to this task, present before this task started per the initial git status snapshot) — nothing else
outstanding.

Script executability confirmed both by the `ls -la` output above (`-rwxrwxr-x`) and by the git
commit's mode bits (`create mode 100755 scripts/fake-battery.sh`).

## Surprises / deviations

None. The brief's code blocks were transcribed exactly as given — verified by diffing the actual
edits against the brief's Step 1 and Step 3 snippets, which matched character-for-character. No
scope, naming, or logic decisions were needed beyond what the brief specified verbatim. The script
was not run against a physical device, per the instructions (hardware verification is Task 9).
