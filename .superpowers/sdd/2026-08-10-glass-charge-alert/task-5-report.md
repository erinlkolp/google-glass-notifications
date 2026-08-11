# Task 5 Report: Read the battery on Glass

## What was implemented

Three files, created exactly as specified in the task-5 brief (verbatim transcription, no edits):

1. `glass/src/test/java/dev/erinlkolp/glassnotify/glass/BatteryReadingTest.java` — 9 JUnit tests covering plain percentage, scale normalisation, rounding, all power-source values, missing plugged extra, missing level, unusable scale (0 and negative), clamping above 100, and full battery.
2. `glass/src/main/java/dev/erinlkolp/glassnotify/glass/BatteryReading.java` — pure-Java (no `android.*` imports) static utility `fromExtras(int level, int scale, int plugged) -> GlassState`. Guards `level < 0 || scale <= 0` by returning `null`; computes `Math.round(level * 100.0d / scale)`, clamps to 0..100; treats `plugged > 0` (not `!= 0`) as on-power.
3. `glass/src/main/java/dev/erinlkolp/glassnotify/glass/BatteryWatcher.java` — `BroadcastReceiver` shell. `Listener` interface with `onBatteryState(GlassState)`; constructor takes and null-checks the listener; `register(Context)` registers for `ACTION_BATTERY_CHANGED` and, since that broadcast is sticky, immediately feeds the returned sticky intent through `onReceive` so `latest()` is populated before `register` returns; `unregister(Context)` swallows `IllegalArgumentException` if already unregistered; `latest()` returns the last published `GlassState` (volatile field) or null; `onReceive` builds a `GlassState` via `BatteryReading.fromExtras` and only stores/publishes it when non-null and not `.equals()` to the previous `latest` — this is the debounce, no timer involved.

`LinkServerService`, the `phone` module, `wire` module, `AndroidManifest.xml`, and `Protocol.VERSION` were not touched.

## Commands run and actual output

### Step 2 — confirm the test fails first

```
./gradlew :glass:testReleaseUnitTest --tests '*BatteryReadingTest*'
```

Result: **FAILED** as expected — `Execution failed for task ':glass:compileReleaseUnitTestJavaWithJavac'` with 14 `cannot find symbol: variable BatteryReading` compilation errors (one per `BatteryReading.fromExtras` call site). This is the expected failure reason: `BatteryReading` did not exist yet.

### Step 4 — after writing `BatteryReading.java`

```
./gradlew :glass:testReleaseUnitTest --tests '*BatteryReadingTest*'
```

Result: `BUILD SUCCESSFUL in 1s`, `23 actionable tasks: 5 executed, 18 up-to-date`. Verified the XML report directly:

```
$ grep -o 'tests="[0-9]*"' glass/build/test-results/testReleaseUnitTest/TEST-*BatteryReadingTest*.xml
tests="9"
```

9 tests, all passing, matching the brief's expectation.

### Step 6 — full build

```
./gradlew test assembleDebug
```

Result: `BUILD SUCCESSFUL in 1s`, `118 actionable tasks: 12 executed, 106 up-to-date`.

Test counts, counted from a single variant per module (`testReleaseUnitTest` for `glass`/`phone`, plain `test` for `wire`, per the brief's counting gotcha about double-counting debug+release):

```
wire  (wire/build/test-results/test):              58
glass (glass/build/test-results/testReleaseUnitTest): 41
phone (phone/build/test-results/testReleaseUnitTest): 42
```

All three match the brief's expected totals exactly: wire 58, glass 41, phone 42.

## Commit

```
git add glass/src/main/java/dev/erinlkolp/glassnotify/glass/BatteryReading.java \
        glass/src/main/java/dev/erinlkolp/glassnotify/glass/BatteryWatcher.java \
        glass/src/test/java/dev/erinlkolp/glassnotify/glass/BatteryReadingTest.java
git commit -m "feat(glass): watch the battery ..."
```

Commit SHA: `e2a43646243115b7324d46bfa5c3ba680bc48ca1`

Note: the repo had a pre-existing untracked `data/` directory unrelated to this task (present before this session started, per `git status` at session start). It was left untouched and not staged — only the three task files were added.

## Surprises / deviations

- No deviations from the brief. All three files were transcribed verbatim as instructed.
- One thing worth flagging (not a deviation, just an observation): `git log -1 --format="%H %s"` initially rendered the hash and subject with no visible space between them in the tool output due to terminal wrapping, but `git rev-parse HEAD` confirms the clean 40-char SHA above.
- The pre-existing untracked `data/` directory in the working tree was not part of this task's scope and was correctly excluded from the commit by staging files individually rather than using `git add -A`/`git add .`.
