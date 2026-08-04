# Task 11 Report: RFCOMM client, backoff, and fast reconnect

## What I implemented

Transcribed the brief exactly, no deviations:

- `phone/src/main/java/dev/erinlkolp/glassnotify/phone/Backoff.java` — pure-Java exponential backoff (`INITIAL_MS = 1_000L`, `MAX_MS = 60_000L`), zero Android imports.
- `phone/src/main/java/dev/erinlkolp/glassnotify/phone/LinkClientService.java` — foreground `Service` owning the RFCOMM connection and reconnect loop: `start(Context)` / `wake(Context)` statics, a worker thread running `connectLoop()`, `pump()` for HELLO + snapshot + PING heartbeats, `onSnapshot()` from `SnapshotBus.Listener`, `findBondedGlass()` (bonded-device name contains "glass", case-insensitive), and `waitFor()` using `wakeLock.wait(ms)`.
- `phone/src/main/java/dev/erinlkolp/glassnotify/phone/AclReceiver.java` — `BroadcastReceiver` for `ACTION_ACL_CONNECTED` that calls `LinkClientService.wake(context)` when the connecting device's name contains "glass".
- `phone/src/test/java/dev/erinlkolp/glassnotify/phone/BackoffTest.java` — 6 tests: `startsShort`, `doublesEachTime`, `capsAtTheCeiling`, `neverOverflows`, `resetReturnsToTheStart`, `ceilingIsSixtySeconds`.
- Modified `phone/src/main/java/dev/erinlkolp/glassnotify/phone/NotifyListenerService.java` — replaced the Task-11 placeholder comment in `onListenerConnected()` with `LinkClientService.start(this);`.
- Modified `phone/src/main/AndroidManifest.xml` — added `<service android:name=".LinkClientService" android:exported="false" />` and `<receiver android:name=".AclReceiver" android:exported="true">` with the `ACL_CONNECTED` intent-filter, inside the existing `<application>` element alongside `NotifyListenerService`.

I verified byte-for-byte that `Backoff.java`, `LinkClientService.java`, `AclReceiver.java`, and `BackoffTest.java` match the brief's code blocks exactly (scripted diff against the brief text — all four reported `EXACT MATCH` / `MATCH`), and confirmed via `git diff` that the manifest and `NotifyListenerService.java` edits are minimal and match Step 6 exactly.

Confirmed all string resources referenced by `LinkClientService` (`app_name`, `channel_link`, `status_connecting`, `status_connected`, `status_no_bluetooth`, `status_not_bonded`) already exist in `phone/src/main/res/values/strings.xml`.

## TDD evidence

**RED** — `./gradlew :phone:testDebugUnitTest --tests '*BackoffTest*'` (run before `Backoff.java` existed):

```
BackoffTest.java:22: error: cannot find symbol
        assertEquals(Backoff.INITIAL_MS, backoff.nextDelayMs());
                     ^
  symbol:   variable Backoff
  location: class BackoffTest
... (11 errors total, one per Backoff reference)
BUILD FAILED in 629ms
```

Expected failure: `Backoff` did not exist yet, so the test module failed to compile. This confirms the test was actually exercising the not-yet-written class rather than passing vacuously.

**GREEN** — after writing `Backoff.java`, `LinkClientService.java`, `AclReceiver.java`, and the manifest/`NotifyListenerService` edits:

```
./gradlew :phone:testDebugUnitTest :phone:assembleDebug
...
> Task :phone:testDebugUnitTest
> Task :phone:mergeProjectDexDebug
> Task :phone:packageDebug
> Task :phone:assembleDebug

BUILD SUCCESSFUL in 1s
38 actionable tasks: 12 executed, 26 up-to-date
```

Test-result XML counts confirm exactly 22 tests, 0 failures, 0 errors across the module:
- `BackoffTest`: 6 tests
- `SnapshotBuilderTest`: 11 tests
- `AllowlistCodecTest`: 5 tests

Matches the brief's Step 7 expectation ("PASS, 22 tests. BUILD SUCCESSFUL.") exactly.

## Files changed

- `phone/src/main/java/dev/erinlkolp/glassnotify/phone/Backoff.java` (new)
- `phone/src/main/java/dev/erinlkolp/glassnotify/phone/LinkClientService.java` (new)
- `phone/src/main/java/dev/erinlkolp/glassnotify/phone/AclReceiver.java` (new)
- `phone/src/test/java/dev/erinlkolp/glassnotify/phone/BackoffTest.java` (new)
- `phone/src/main/java/dev/erinlkolp/glassnotify/phone/NotifyListenerService.java` (modified — one comment block replaced by the `LinkClientService.start(this);` call)
- `phone/src/main/AndroidManifest.xml` (modified — `LinkClientService` service and `AclReceiver` receiver added)

Commit: `0bd3f62` — "feat(phone): add RFCOMM client, backoff and fast reconnect" (message exactly as specified in the brief's Step 8). Only `phone/` was staged; the untracked `data/` directory was left alone, and no `docs/superpowers/plans/...md` file was touched.

## Self-review against the six deliberate behaviours

1. **Foreground service** — `onCreate()` calls `startForeground(NOTIFICATION_ID, buildNotification(...))` immediately after creating the notification channel. Preserved.
2. **Monitor `wait()`, not `sleep()`** — `waitFor(long ms)` calls `wakeLock.wait(ms)` inside `synchronized (wakeLock)`; `onStartCommand` with the `wake` extra and `onDestroy` both `notifyAll()` on the same lock to cut the wait short. Preserved, not softened to a `Thread.sleep`.
3. **Backoff clamps before assigning** — `nextDelayMs()` computes `doubled = next * 2` into a local, checks `doubled > MAX_MS || doubled < 0` (the overflow guard), and only then assigns to `next`. `neverOverflows` and `capsAtTheCeiling` tests pass over 100 iterations. Preserved.
4. **PING as liveness detection, write failure thrown to retry loop** — `pump()`'s heartbeat loop calls `FrameCodec.write(out, MessageType.PING, new byte[0])` with no try/catch inside `pump()`, so an `IOException` propagates out of `pump()` to the `catch (IOException e)` in `connectLoop()`, tearing down the socket and re-entering backoff. Preserved — did not add a swallow-and-continue.
5. **`cancelDiscovery()` before connecting** — called immediately before `attempt.connect()` in `connectLoop()`. Preserved.
6. **Bonded-device lookup by name containing "glass" (case-insensitive)** — `findBondedGlass()` in `LinkClientService` and the check in `AclReceiver.onReceive()` both use `name.toLowerCase(Locale.US).contains("glass")`, not a hardcoded MAC. Preserved in both places.

No behaviour was softened, renamed, or reordered relative to the brief.

## Concerns

None. Build is green, test count matches exactly, and all created files verified as exact transcriptions of the brief via automated diff rather than eyeballing.

---

## Fix report: concurrency review findings

Review of the initial implementation found one Critical and two Important issues, all in `LinkClientService`'s concurrency surface, present verbatim from the plan. Fixed all three; nothing else touched.

### Finding 1 (Critical): unlocked concurrent frame writes could splice bytes

`pump()` wrote HELLO and PING from the worker thread; `send()` (called from `onSnapshot()`, on the `SnapshotBus` callback thread) wrote SNAPSHOT concurrently. `socketLock` only guarded the *reference read* of the `socket` field, not the `FrameCodec.write(...)` call itself — two threads could interleave bytes on the wire mid-frame. Since this side has no read path, a spliced frame would desync Glass's `FrameCodec` permanently, undetectably.

**Fix:** added a dedicated `writeLock` and a single `writeFrame(BluetoothSocket target, int type, byte[] body)` choke point that calls `FrameCodec.write(target.getOutputStream(), type, body)` inside `synchronized (writeLock)`. Routed the HELLO write in `pump()`, the PING write in `pump()`'s heartbeat loop, and the SNAPSHOT write in `send()` all through it. Kept `socketLock` doing only what it did before — guarding the `socket` field read/write — and did not merge the two locks, per the finding's explicit instruction, since `connectLoop()` needs to swap the field without holding the write lock. The PING write's `IOException` still propagates out of `pump()` uncaught (no try/catch added around it) — that failure remains the only liveness detection this side has.

### Finding 2 (Important): worker could write to Glass after onDestroy()

`socket` was only assigned *after* `attempt.connect()` returned, so a worker blocked inside `connect()` when `onDestroy()` ran was unabortable — `onDestroy()`'s `closeSocket()` had no field to close, and `notifyAll()` cannot unblock a blocking socket connect. When `connect()` eventually returned, the code proceeded straight into `pump()` and sent a live HELLO and snapshot from an already-destroyed service.

**Fix:** moved the `synchronized (socketLock) { socket = attempt; }` publish to *before* `attempt.connect()` is called, so a concurrent `onDestroy()` -> `closeSocket()` now closes the in-flight socket and makes `connect()` throw `IOException` (caught normally by the existing `catch` clause). Added an explicit `if (!running) { return; }` check immediately after `connect()` returns and before `backoff.reset()` / `status()` / `pump()`, so even a successful connect racing against shutdown does not send anything — the `finally` block still runs on that `return`, so the socket is still closed and the field still cleared. All reads/writes of `socket` continue to go through `socketLock` exclusively, so publication between the worker and main threads stays safe under the existing lock discipline (no new field needed).

### Finding 3 (Important): waitFor() could drop a wake-up between backoff and wait()

`waitFor()` was a bare `wakeLock.wait(ms)` with no memory of a pending wake request. `connectLoop()` calls `status(...)` (a `NotificationManager.notify` Binder call — a real window) between computing the next backoff delay and entering `waitFor()`. A `wake()` from `AclReceiver` landing in that window fired `notifyAll()` with nobody waiting yet, and was lost — `backoff.reset()` still ran but only affected the *next* delay, so the phone sat out the full stale (up to 60s) backoff despite Glass already being in range.

**Fix:** added `private boolean wakeRequested;` guarded by `wakeLock`. `onStartCommand`'s wake-extra handling now sets `wakeRequested = true` before `notifyAll()`. `waitFor()` checks the flag first: if already set, it clears it and returns immediately without ever calling `wait()`; otherwise it waits as before and clears the flag afterward (covering the flag being set concurrently during the wait, in which case `wait()` returns via `notifyAll()` and the check is harmless). `onDestroy()`'s existing `notifyAll()` is untouched and still wakes a thread parked in `wait()` promptly — the `running` check immediately following `waitFor()` at each call site is what actually ends the loop, exactly as before.

### Preserved behaviours (all six, re-verified)

1. Foreground service — unchanged (`onCreate()`/`startForeground` untouched).
2. Monitor `wait()`, not `sleep()` — `waitFor()` still calls `wakeLock.wait(ms)`; only added the flag check around it, no polling or sleeping introduced.
3. `Backoff` clamps before assigning — `Backoff.java` untouched by this fix pass.
4. PING write failure allowed to throw to the retry loop — confirmed no try/catch was added around the `writeFrame(connected, MessageType.PING, ...)` call; `pump()` still declares `throws IOException` and the exception still propagates to `connectLoop()`'s existing `catch`.
5. `cancelDiscovery()` before connecting — still called immediately before `attempt.connect()`; only the *publish* of `socket` moved between `cancelDiscovery()` and `connect()`, the discovery-then-connect ordering itself is unchanged.
6. Glass located by bonded-device name containing "glass" (case-insensitive) — `findBondedGlass()` untouched by this fix pass.

### Verification

Ran, in order, after applying all three fixes:

```
$ ./gradlew :phone:testDebugUnitTest :phone:assembleDebug
...
> Task :phone:compileDebugJavaWithJavac
> Task :phone:testDebugUnitTest
> Task :phone:dexBuilderDebug
> Task :phone:mergeProjectDexDebug
> Task :phone:packageDebug
> Task :phone:assembleDebug

BUILD SUCCESSFUL in 1s
38 actionable tasks: 7 executed, 31 up-to-date
```

Test-result XML counts unchanged at exactly 22 tests, 0 failures, 0 errors (`BackoffTest`: 6, `SnapshotBuilderTest`: 11, `AllowlistCodecTest`: 5) — `LinkClientService` and `AclReceiver` remain framework-bound with no Robolectric in this project, so they have no unit-test coverage and none was added, per instruction.

**The concurrency and lifecycle changes (the `writeLock` choke point, the pre-`connect()` socket publish plus post-`connect()` `running` recheck, and the `wakeRequested` flag) are verified by inspection and successful compilation/build only.** They have not been exercised against real hardware or under actual thread contention, and need confirmation at hardware bring-up — in particular, that a real `BluetoothSocket.close()` during a real Bluetooth stack's `connect()` call does in fact throw promptly rather than hanging, and that the `writeLock`/`socketLock` interaction doesn't introduce unexpected latency in practice.

### Files changed (this fix pass)

- `phone/src/main/java/dev/erinlkolp/glassnotify/phone/LinkClientService.java` (modified only — 45 insertions, 9 deletions)

Commit: `567d7f9` — "fix(phone): serialize frame writes, close in-flight connect, keep wakes"

### Not addressed (out of scope per coordinator instruction)

The three Minor findings deferred to final review were left untouched: the unused `worker` field, the fully-qualified `java.util.Locale.US` in `findBondedGlass()`, and `AclReceiver`'s single-arg `getParcelableExtra`.
