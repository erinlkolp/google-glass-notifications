# Task 10 Report: Notification listener and allowlist storage

## What was implemented

Transcribed exactly from `task-10-brief.md`, plus the specified `AllowRule.java` deletion:

- **Created** `phone/src/main/java/dev/erinlkolp/glassnotify/phone/AllowlistStore.java`
  Persists the allowlist in a `SharedPreferences` string set of `"packageName|tierCode"`.
  Public API: `rules()`, `put(String, Tier)`, `remove(String)`. Package-private static
  `encode(Map<String, Tier>)` / `decode(Set<String>)` do the pure encoding/decoding, reachable
  by the test in the same package without widening the public surface.
- **Created** `phone/src/main/java/dev/erinlkolp/glassnotify/phone/SnapshotBus.java`
  Singleton holding the `latest()` `Snapshot`, `publish(Snapshot)`, `setListener(Listener)`, and
  a 500ms (`DEBOUNCE_MS`) debounced delivery via a main-thread `Handler`.
- **Created** `phone/src/main/java/dev/erinlkolp/glassnotify/phone/SbnMapper.java`
  The sole place `StatusBarNotification` is touched; maps to `SourceNotification`, falling back
  to big-text extra when the normal text extra is absent, and to the package name when the
  app label can't be resolved.
- **Created** `phone/src/main/java/dev/erinlkolp/glassnotify/phone/NotifyListenerService.java`
  Extends `NotificationListenerService`. `onListenerConnected`, `onNotificationPosted`, and
  `onNotificationRemoved` all call a private `republish()` that rebuilds the entire snapshot
  from `getActiveNotifications()` and publishes it to `SnapshotBus`. No reference to
  `LinkClientService` (Task 11's job) — the module compiles standalone.
- **Created** `phone/src/main/java/dev/erinlkolp/glassnotify/phone/GlassNotifyPrefs.java`
  Tiny constants holder: `NAME = "glassnotify"`.
- **Modified** `phone/src/main/AndroidManifest.xml`
  Converted the self-closing `<application ... />` into an open/close pair and added the
  `<service>` declaration for `NotifyListenerService` with the
  `BIND_NOTIFICATION_LISTENER_SERVICE` permission and the standard intent-filter action.
- **Test:** `phone/src/test/java/dev/erinlkolp/glassnotify/phone/AllowlistCodecTest.java`
  5 tests covering round-trip encode/decode, empty map, malformed-entry skipping, null
  tolerance, and separator-anchoring (`lastIndexOf`) with dotted package names.
- **Deleted** `phone/src/main/java/dev/erinlkolp/glassnotify/phone/AllowRule.java`
  Confirmed via `grep -rn "AllowRule" --include="*.java" .` that nothing outside the file
  itself referenced it. `SnapshotBuilder.build` takes a `Map<String, Tier>` and
  `AllowlistStore.rules()` returns that same shape, so `AllowRule` had no consumer.

All six Java source blocks (test + 5 production files) were verified byte-for-byte identical
to the brief's code blocks via a scripted diff (see Self-review below). The XML `<service>`
snippet was likewise diffed against the brief's fenced block and matches exactly.

## Testing

**RED** — `./gradlew :phone:testDebugUnitTest --tests '*AllowlistCodecTest*'`
Failed as expected, before any production code existed:
```
/home/ekolp/workspace/google-glass-notifications/phone/src/test/java/dev/erinlkolp/glassnotify/phone/AllowlistCodecTest.java:23: error: cannot find symbol
        Map<String, Tier> decoded = AllowlistStore.decode(AllowlistStore.encode(rules));
                                    ^
  symbol:   variable AllowlistStore
  location: class AllowlistCodecTest
...
8 errors
FAILURE: Build failed with an exception.
> Task ':phone:compileDebugUnitTestJavaWithJavac'.
> Compilation failed; see the compiler error output for details.
```
This is the expected failure mode per the brief (Step 2): `AllowlistStore` does not exist yet.

**GREEN** — after writing `AllowlistStore.java`, `SnapshotBus.java`, `SbnMapper.java`,
`NotifyListenerService.java`, `GlassNotifyPrefs.java`, and the manifest change:

`./gradlew :phone:testDebugUnitTest`
```
> Task :phone:compileDebugUnitTestJavaWithJavac
...
> Task :phone:testDebugUnitTest

BUILD SUCCESSFUL in 1s
22 actionable tasks: 9 executed, 13 up-to-date
```
Test result XML confirms counts: `SnapshotBuilderTest` 11 tests (from Task 9, unchanged),
`AllowlistCodecTest` 5 tests — 16 total, matching the brief's Step 9 expectation exactly. All
0 failures, 0 errors.

`./gradlew :phone:assembleDebug`
```
> Task :phone:packageDebug
> Task :phone:assembleDebug

BUILD SUCCESSFUL in 692ms
33 actionable tasks: 3 executed, 30 up-to-date
```
The module compiles and assembles standalone with no forward reference to `LinkClientService`.

## Files changed

- `phone/src/main/java/dev/erinlkolp/glassnotify/phone/AllowlistStore.java` (new)
- `phone/src/main/java/dev/erinlkolp/glassnotify/phone/SnapshotBus.java` (new)
- `phone/src/main/java/dev/erinlkolp/glassnotify/phone/SbnMapper.java` (new)
- `phone/src/main/java/dev/erinlkolp/glassnotify/phone/NotifyListenerService.java` (new)
- `phone/src/main/java/dev/erinlkolp/glassnotify/phone/GlassNotifyPrefs.java` (new)
- `phone/src/main/AndroidManifest.xml` (modified — application opened, service registered)
- `phone/src/test/java/dev/erinlkolp/glassnotify/phone/AllowlistCodecTest.java` (new)
- `phone/src/main/java/dev/erinlkolp/glassnotify/phone/AllowRule.java` (deleted — dead code,
  superseded by `AllowlistStore`'s `Map<String, Tier>`)

Commit: `be30cb5` on `feat/glass-notifications` —
"feat(phone): add notification listener, allowlist and snapshot bus" (includes a note on the
`AllowRule.java` deletion in the commit body, as required).

Only `phone/` paths were staged (`git add phone/`); the untracked `data/` directory was left
alone, as instructed.

## Self-review

- **Completeness against the brief**: all 10 steps executed — failing test written, confirmed
  RED with the expected error, all four production classes plus the prefs holder written, the
  manifest converted and the service registered, tests confirmed GREEN at the exact expected
  count (16), `assembleDebug` confirmed green, and the commit made with the brief's message
  plus the added deletion note.
- **Transcription accuracy**: I extracted the brief's six fenced `java` code blocks and the one
  `xml` block with a script and diffed them character-for-character against the files I wrote —
  all six Java files and the XML snippet are exact matches, zero diff output.
- **Every test asserts behaviour**: `AllowlistCodecTest`'s five tests each end in a real
  assertion (`assertEquals`/`assertTrue`) tied to a specific encode/decode behaviour (round
  trip, empty map, malformed-entry skip, null tolerance, separator anchoring) — none are
  smoke tests that merely check "no exception thrown."
- **`AllowRule` deletion verified safe**: grepped the whole tree for `AllowRule` before
  deleting; the only hits were inside the file itself. `SnapshotBuilder.build`'s signature
  (`Map<String, Tier> allowlist`) and `AllowlistStore.rules()`'s return type
  (`Map<String, Tier>`) confirm there was never a slot for `AllowRule` to plug into.
- **Constraints honored**: no Kotlin, no AndroidX, Java 8 source/target (build already
  configured that way and compiled clean, aside from the pre-existing javac 21 deprecation
  warning about targeting 8, which is environmental and outside this task's scope), no `var`,
  `encode`/`decode` are package-private static as required, and `NotifyListenerService` has no
  forward reference to `LinkClientService`.

## Concerns

None. The task is complete and matches the brief exactly, with the one deletion called out as
instructed.

---

## Fix report: review finding (Important) — SnapshotBus concurrency

### Finding

Review flagged that `SnapshotBus.pending` was a plain `boolean` mutated by both `publish()` and
`deliver.run()` with no `volatile` and no synchronization, and — worse — that the check-then-act
in `publish()` (`if (!pending) { pending = true; handler.postDelayed(...); }`) was not atomic
even if `pending` were made volatile. Two racing callers could both observe `pending == false`
and double-schedule `deliver`, defeating the 500ms coalescing. This was masked only by an
undocumented, unenforced main-thread-only calling convention, which Task 11 is about to break
by wiring in a second component.

### Fix applied

`phone/src/main/java/dev/erinlkolp/glassnotify/phone/SnapshotBus.java`:

- `publish()` now guards the check-then-act with `synchronized (this)`: it checks and sets
  `pending` inside the lock, and returns early if already pending. `handler.postDelayed(...)` is
  called *outside* the synchronized block, deliberately, per the review's own guidance (no need
  to hold a lock across a framework call; `pending` already guarantees only one scheduling
  wins). Added a javadoc line stating `publish()` is safe to call from any thread.
- `deliver.run()` now clears `pending` inside `synchronized (SnapshotBus.this) { pending =
  false; }` as the very first thing it does, still before invoking the listener — so an
  exception thrown out of `onSnapshot` cannot wedge the bus permanently.
- Preserved exactly, as instructed: `deliver.run()` still re-reads the `listener` field
  (`Listener target = listener;`) at execution time rather than capturing it at schedule time,
  so `setListener(null)` during teardown remains a clean no-op. `latest` and `listener` remain
  `volatile`, read outside the lock, unchanged.
- Nothing else touched: 500ms `DEBOUNCE_MS`, full-snapshot rebuild behaviour, skip-not-throw
  decoding, and `onListenerConnected` placement are all unchanged. The deferred Minor finding
  (`NotifyListenerService.republish()` re-decoding SharedPreferences on every callback) was left
  alone as instructed.

### Verification

`SnapshotBus` depends on `android.os.Handler`/`Looper`, has no host-JVM unit test, and this
project deliberately carries no Robolectric — no test framework was added. Verification is by
inspection and successful compilation/assembly only, as instructed.

`./gradlew :phone:testDebugUnitTest`
```
> Task :phone:compileDebugJavaWithJavac
...
> Task :phone:testDebugUnitTest

BUILD SUCCESSFUL in 979ms
22 actionable tasks: 4 executed, 18 up-to-date
```
Test result XML unchanged: `AllowlistCodecTest` 5 tests, `SnapshotBuilderTest` 11 tests — 16
total, 0 failures, 0 errors (no test touches `SnapshotBus`, so the count is unaffected by the
fix, confirming no regression in the covered surface).

`./gradlew :phone:assembleDebug`
```
> Task :phone:packageDebug
> Task :phone:assembleDebug

BUILD SUCCESSFUL in 650ms
33 actionable tasks: 3 executed, 30 up-to-date
```

### Files changed (this fix)

- `phone/src/main/java/dev/erinlkolp/glassnotify/phone/SnapshotBus.java` (modified)

Commit: `174070e` on `feat/glass-notifications` — "fix(phone): make SnapshotBus.publish safe to
call from any thread".

Note: `docs/superpowers/plans/2026-08-04-glass-notifications.md` appeared modified in the
working tree (reflecting this same corrected `SnapshotBus` code) but was not staged or
committed by me — it wasn't named in the brief's commit step and isn't mine to commit.

### Concerns

None. The fix matches the required patch exactly, both preserved behaviours were checked by
reading the resulting file, and both covering checks pass.
