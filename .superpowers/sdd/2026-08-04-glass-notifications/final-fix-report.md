# Final fix wave — whole-branch review findings

Branch: `feat/glass-notifications`. Eleven commits, `fc13b96..a623441`.

## Verification status up front

Nothing in this wave was run on hardware — no devices are attached. Everything
below is **verified by compilation, the host-JVM unit suite, and inspection
only**.

Specifically:

- **The single-writer restructure (C1 + C2) is unverified at runtime.** Its
  correctness argument is a happens-before argument over four fields, set out
  in full below. It compiles, and the pure-logic parts of the module still
  pass, but nothing here exercises a real RFCOMM socket, a real
  `NotificationListenerService`, or a real disconnect. It needs confirmation at
  hardware bring-up. This is the code path the whole project depends on staying
  alive and it has now been touched a fourth time.
- The `glass` UI changes (IMPORTANT 6, 8) are likewise compile-and-inspect only.
  There is no Robolectric in this project by deliberate choice, so nothing
  drives an `Activity` lifecycle, a `Handler`, or a `View` tree in CI.
- Genuinely covered by tests: the `wire` caps and frame-fitting encode, the
  `SnapshotBuilder` truncation, the swipe dominance band, the frame-length
  boundary.

No test framework was added. No Kotlin, no AndroidX, no post-Java-8 API. `wire`
still has zero `android.*` imports (`NoAndroidImportsTest` still passes).

## Commits

| SHA | Subject |
| --- | --- |
| `1c50b90` | fix(wire): cap key and appLabel, and shrink oversized snapshots to fit |
| `92611b6` | fix(phone): truncate key and appLabel when building the snapshot |
| `5f57d78` | fix(phone): collapse the link service onto a single writer thread |
| `39a1a78` | fix(phone): reset the backoff on a healthy session, not on connect() |
| `652d89e` | fix(glass): back off before retrying after a failed accept() |
| `5618feb` | feat(glass): re-render the queue on arrival and on a timer |
| `bd3c9eb` | fix(glass): make a version mismatch a state, not a Toast |
| `f8fceb5` | fix(glass): log when pinning the peer address fails to persist |
| `d096a7e` | fix(phone): stop the link when notification access goes away, drop a dead permission |
| `b30cdbc` | test(glass): pin the ratio-based swipe dominance rule |
| `a623441` | refactor(phone): name the pending-snapshot reset for what it does |

---

## CRITICAL 1 + 2 — the single-writer restructure

`5f57d78`, in `phone/.../LinkClientService.java`.

### What was wrong

Both defects had one root cause: the socket was shared between the connect path
and the write path, and the write path ran on the main thread.

An earlier fix published the socket to the `socket` field *before*
`attempt.connect()`, so `onDestroy()` could close it and abort a blocking
connect. A later fix routed `send()` through that same field. During the ~10s
connect window a snapshot arriving from `SnapshotBus` therefore reached
`send()`, read a non-null `socket`, and called `getOutputStream()` on a socket
that had not connected — either throwing `IOException`, whose handler called
`closeSocket()` and killed the very connection `connectLoop` was establishing
(and with the 500ms debounce and a chatty stream, repeatedly, so the phone could
never link up); or throwing something unchecked, which `send()` did not catch,
out of a `Handler` runnable on the main thread, taking the process down.

And `SnapshotBus.handler` is bound to `Looper.getMainLooper()`, so that write
was a blocking RFCOMM write on the UI thread — behind a `writeLock` the worker
also held across its own blocking PING, so the main thread could block on lock
acquisition before it even reached the socket. Out of range, an in-flight write
stalls until the ACL supervision timeout: an ANR, and an ANR kill takes
`NotifyListenerService` with it.

### The new shape

**Exactly one thread writes.** The worker thread `glassnotify-link` owns the
socket and is the only caller of `FrameCodec.write` — HELLO, SNAPSHOT and PING
all leave from it, in that one thread, in order.

`writeLock` is **gone**. With one writer there is nothing to serialise; a lock
reappearing here would be the signal that a second writer has crept back in.
`socketLock`, `closeSocket()` and `send()` are gone too (grep-verified absent).

`onSnapshot` — still called on the main thread by the bus — no longer sends.
It takes the `wakeLock` monitor, sets `snapshotPending`, `notifyAll()`s, and
returns. It touches no socket and cannot block on a write.

The worker re-reads `SnapshotBus.latest()` rather than receiving a handed-over
value. That is what preserves coalescing for free: `latest()` is already
most-recent-wins, so several snapshots arriving before the worker wakes collapse
into the newest, and there is no queue to grow unbounded.

### The split socket fields

```java
private volatile BluetoothSocket connectingSocket; // pre-connect; teardown-only reader
private volatile BluetoothSocket connectedSocket;  // post-connect; the only writable one
```

- `connectingSocket` is written immediately before the blocking `connect()` and
  read only by `onDestroy()`, which closes it — the only way to make a blocking
  connect return. Nothing writes a frame to it, because it is not connected.
- `connectedSocket` is published only after `connect()` returns and is the only
  socket the write path may touch. `onDestroy()` may close it, which is how a
  write stalled on a dead ACL link is aborted rather than waited out.

### Why the teardown race is closed

`onDestroy()` runs, in order: `running = false;` then
`closeQuietly(connectingSocket)` then `closeQuietly(connectedSocket)`.

The worker runs: `connectingSocket = attempt;` → `attempt.connect();` →
`connectedSocket = attempt; connectingSocket = null;` → `if (!running) return;`
→ `pump(attempt)`.

All four fields are `volatile`, so the write of `running = false` happens-before
any close that follows it, and is visible to any subsequent volatile read.

- If either close lands **after** the worker published a socket, that socket is
  closed and the pending `connect()` or the first write in `pump()` throws
  straight out to the retry loop.
- If both closes land **before** the worker published anything, then
  `running = false` was written before them and is therefore already visible at
  the worker's `if (!running) return;` check, which is placed after the
  publication of `connectedSocket` precisely so it covers every earlier landing
  point.

`connectedSocket` is published *before* `connectingSocket` is cleared, so there
is never an instant where neither field names the live socket and `onDestroy()`
would close nothing.

Prompt teardown otherwise: `running = false` stops both loops, the two closes
abort a blocking connect or a stalled write, and `wakeLock.notifyAll()` wakes a
worker parked in a backoff or an inter-ping wait. The `finally` clears both
fields and closes the socket on every exit path.

### Load-bearing behaviour preserved

- **The PING write's `IOException` still propagates** to the retry loop. There
  is no read side on the phone, so a failed write is the only liveness signal
  there is; it is not caught at the new choke point. The SNAPSHOT write now
  propagates the same way, which is a strict improvement — it used to be
  swallowed into a cross-thread `closeSocket()`.
- The backoff wait is still a monitor `wait()` with the `wakeRequested` flag, so
  `ACTION_ACL_CONNECTED` still cuts it short, including a `wake()` that arrives
  just before the wait is entered.
- `cancelDiscovery()` before connecting — unchanged.
- Foreground service, `IMPORTANCE_MIN` channel, prompt `startForeground` in
  `onCreate` — unchanged.
- Glass found among bonded devices by case-insensitive name containing
  "glass" — unchanged.

### Two waits, deliberately different

`waitForWake(ms)` is the connect-side backoff. It is woken by `wake()` or
teardown and **not** by a pending snapshot — otherwise a chatty notification
stream would set the reconnect cadence.

`awaitWork(ms)` is the connected-side wait. It returns true if a snapshot is
waiting, consuming the flag, and is woken by a snapshot, a wake, or teardown.
Both guard `ms > 0` before `wait(ms)`, because `Object.wait(0)` waits forever —
`awaitWork` is called with a computed remaining-time that can legitimately go
non-positive, at which point it must fall through to the PING rather than park.

The pump loop tracks an absolute `nextPingAt` on `SystemClock.elapsedRealtime()`
rather than sleeping a fixed interval, so an early wake for a snapshot does not
shift the heartbeat.

---

## IMPORTANT 3 — unbounded `key` and `appLabel`

`1c50b90` (wire) and `92611b6` (phone).

**Layer 1.** `Protocol.MAX_KEY_CHARS = 96` and
`Protocol.MAX_APP_LABEL_CHARS = 24`; `SnapshotBuilder.build` now truncates both
through the same null-safe `truncate` used for title and text. 96 covers a real
package name plus a sane tag — the key is only ever compared for equality
between consecutive snapshots, never interpreted. 24 is past the point where a
12dp letter-spaced uppercase label ellipsises on a 320×180dp prism anyway.

**Layer 2.** `SnapshotCodec.encodeWithinFrame(Snapshot)` encodes and, if the
body exceeds `FrameCodec.MAX_BODY_BYTES`, drops items from the tail — the oldest,
since items are newest-first — until it fits. `LinkClientService.writeSnapshot`
is the single send path and uses it, so it protects HELLO-time and steady-state
sends alike. `FrameCodec.MAX_BODY_BYTES` was added so the budget is stated
rather than implied by a private `HEADER_AFTER_LENGTH`.

The old failure was not merely lossy: `FrameCodec.write` threw, the phone
dropped the link, and the reconnect handshake re-sent the identical snapshot —
a loop that never self-heals, presenting as "phone says Connected, Glass shows
nothing". Losing the tail of the queue beats that.

`encodeWithinFrame` still lets a snapshot over `MAX_ITEMS` throw. That cap is
violated only by our own code (`SnapshotBuilder` enforces it), unlike the byte
ceiling which is driven by external strings, so the belt-and-braces guard in
`encode` is left intact rather than silently papered over.

**Test widened.** `aFullSnapshotFitsComfortablyInOneFrame` now builds
`MAX_ITEMS` items with *every* field at its cap via a shared `worstCase(char)`
helper, instead of `"key-" + i` and `"Signal"`. The ASCII worst case is asserted
under 16KB (it was previously asserted under 8KB, which the real worst case
exceeds — the old bound was an artifact of the unrealistic fixture) and within
`MAX_BODY_BYTES`. Added alongside it: a CJK variant, since `writeUTF` spends
three bytes on a BMP character outside Latin-1 and the caps are in chars while
the limit is in bytes; and three `encodeWithinFrame` tests covering
leave-alone, drop-oldest, and a single item too big to send at all.

---

## IMPORTANT 4 — backoff reset on connect rather than on a healthy session

`39a1a78`. `backoff.reset()` moved out of `connectLoop` and into `pump`, gated
on `HEALTHY_SESSION_PINGS = 1` successful PINGs.

Glass has two paths that accept the connection and drop it immediately — an
unpinned MAC and a protocol version mismatch — and the phone read both as
success. The result was connect → reset → HELLO → Glass closes → PING fails →
~1s backoff → repeat, at full duty cycle forever: the exponential backoff never
engaged in exactly the two situations it exists for.

A PING that completes without an `IOException` means Glass held the link for at
least `PING_INTERVAL_MS`, which neither refusal path does. The constant's
Javadoc records why a bare `connect()` is not proof of a working session. One
ping is enough — a higher threshold would only slow recovery from a genuine
dropout.

---

## IMPORTANT 5 — `accept()` failure had no backoff

`652d89e`, `glass/.../LinkServerService.java`. The listen failure slept 5s but
an `accept()` that threw fell through the `finally` straight back to the top of
the loop — a hot loop of listen/accept/close plus a log line per iteration, on
the device with the smallest battery in the system. Reachable during a Bluetooth
adapter toggle, where the `isEnabled()` check passes and `accept()` then fails.

An `acceptFailed` flag now triggers `sleepQuietly(RETRY_DELAY_MS)` **after** the
`finally`, so the sockets are already closed while we wait. Placing it in the
`catch` would have held them open across the sleep. A normal `serve()` return —
the ordinary disconnect — does not set the flag and still re-arms the listener
immediately, so reconnect latency is unchanged.

Kept a plain sleep; the phone's `Backoff` is not imported into `glass`. The 5s
literal is now the named `RETRY_DELAY_MS`, shared by all three sites.

---

## IMPORTANT 6 — queue never live-updated, staleness marker never appeared

`5618feb`.

`SnapshotStore` gained a one-subscriber `Listener` (a plain field — no
broadcast, no library; both ends are in the same process). `apply()` calls
`notifyChanged()`, which hops to the main thread, because `applySnapshot` runs
on the accept thread and the listener redraws a view tree.

`QueueActivity` implements it, registers in `onResume`, clears in `onPause`.
That is what stops the process-singleton store from leaking the activity. The
posted `Runnable` is an anonymous inner class of `SnapshotStore`, so it captures
the store and not the activity, and it **re-reads** the listener field on
delivery rather than capturing it, so an activity that paused between post and
delivery is not called.

Staleness has no event to hang off, so the activity also re-renders every
`REFRESH_INTERVAL_MS = 5_000L` while foregrounded — a `Handler.postDelayed`
loop started in `onResume` and `removeCallbacks`'d in `onPause`, well under the
30s `STALE_AFTER_MS`. This is the part that matters: staleness is the mechanism
guarding against presenting hours-old notifications as current (spec §7.3,
§11), and with the marker only sampled at render time it was effectively dead.

`render()` still captures `store.items()` once into a local and sizes the cursor
from that same local, so the cursor/store desync stays fixed.

The store's `Handler` is created lazily on the first non-null `setListener`, so
`SnapshotStore` remains constructible in a host JVM test. `new Handler(Looper.getMainLooper())`
is used rather than the no-arg constructor, which is deprecated at compileSdk 34.

---

## IMPORTANT 8 — version mismatch as a state

`bd3c9eb`. Spec §7.1 requires an explicit state; a ~3.5s `Toast` on a
see-through prism is one the wearer is very likely looking away from, and a
mismatch that goes unseen is indistinguishable from the app being broken.

`SnapshotStore.versionMismatch` is set by `LinkServerService.serve` when a
foreign protocol version arrives, and cleared in the HELLO dispatch case — a
HELLO that got past the version check *is* the successful handshake. The setter
routes through `notifyChanged()`, so the change re-renders immediately via the
IMPORTANT 6 listener.

`QueueActivity.render()` shows `messageCard(getString(R.string.version_mismatch))`
in preference to the queue, since whatever is cached came from a phone this
build cannot talk to. The branch sits *after* `items` is captured and the cursor
sized, so the cursor invariant is unaffected. `showMessage` and the `Toast`
import are gone.

In memory rather than on disk, deliberately: it describes the phone currently on
the other end of the link, not anything about this device.

---

## Minor fixes

- **`f8fceb5` — `PeerPin` ignored `commit()`.** A silent persistence failure
  means nothing is ever pinned, so `isAllowed()` returns true for every device
  forever: the security control fails **open**, previously with no signal at
  all. Both `pinIfUnset` and `clear` now log a warning on a false return,
  matching `SnapshotStore.persist`.
- **`d096a7e` — dead `RECEIVE_BOOT_COMPLETED`.** No boot receiver exists in the
  `phone` module; boot coverage comes from the system rebinding
  `NotifyListenerService`. Permission dropped, with a manifest comment recording
  why so it does not get re-added.
- **`d096a7e` — `onListenerDisconnected`.** Revoking notification access left
  the link service running as a foreground service, pinging Glass every 10s with
  a frozen snapshot. Now overridden; calls the new `LinkClientService.stop`.
- **`b30cdbc` — swipe dominance regression test.** `dx=60, dy=55` sits in the
  band where the two candidate rules disagree: a naive `absDx > absDy` accepts,
  the documented ratio rule rejects because `60 < 55 * 1.2`. Also falls outside
  the tap window (`absDx` is not `< SWIPE_MIN_DX`), so the expected verdict is
  unambiguously `NONE`.
- **`1c50b90` — frame boundary test.** `acceptsAFrameOfExactlyTheMaximumLength`
  writes a body of exactly `MAX_BODY_BYTES`, asserts the encoded length is
  `4 + MAX_FRAME_BYTES`, and round-trips it. Paired with a one-byte-over
  negative case. This is precisely the frame `encodeWithinFrame` aims at when it
  shrinks, so an off-by-one would be invisible in normal traffic and fatal there.
- **`a623441`** — `takePendingSnapshot` → `clearPendingSnapshot`; the old name
  read as if it returned the snapshot.

## Out of scope, untouched as instructed

`BluetoothAdapter.getDefaultAdapter()` (7 call sites, accepted ruling); no reset
UI for the MAC pin; no `ACTION_STATE_CHANGED` receivers; the 18 minor findings
the review judged fine to carry.

---

## Test results

```
$ ./gradlew clean
BUILD SUCCESSFUL

$ ./gradlew :wire:test :glass:testDebugUnitTest :phone:testDebugUnitTest \
            :glass:assembleDebug :phone:assembleDebug
BUILD SUCCESSFUL in 2s
77 actionable tasks: 77 executed
```

Parsed from `*/build/test-results/**/*.xml`:

| module | tests | failures | errors | baseline |
| --- | --- | --- | --- | --- |
| wire | 42 | 0 | 0 | 36 |
| glass | 32 | 0 | 0 | 31 |
| phone | 25 | 0 | 0 | 22 |
| **total** | **99** | **0** | **0** | **89** |

Ten new tests. Both APKs produced:
`glass/build/outputs/apk/debug/glass-debug.apk`,
`phone/build/outputs/apk/debug/phone-debug.apk`.

### On the deprecation notes

A clean build now prints the `deprecated API` note twice, once per Android
module, where the baseline reported one. This is **not a new deprecation** — it
is the difference between a clean build (all files compiled, so the note is the
generic "Some input files…") and the incremental build the baseline was measured
on (only `LinkClientService` recompiled, so the note named it).

Recompiled with `-Xlint:deprecation` to enumerate every site. All of them are
pre-existing and accepted: `getDefaultAdapter()` in `LinkServerService`,
`LinkClientService`, `SetupActivity`; the API-22-era `TYPE_SYSTEM_ALERT`,
`FLAG_FULLSCREEN`, `SCREEN_BRIGHT_WAKE_LOCK`, `ACQUIRE_CAUSES_WAKEUP` in
`InterruptOverlay`; the `SYSTEM_UI_FLAG_*` constants and
`setSystemUiVisibility` in `QueueActivity.applyImmersiveFlags`; and
`getParcelableExtra` in `AclReceiver`. None is in code this wave wrote — every
one is in a method left untouched, at a shifted line number. The temporary
`-Xlint:deprecation` was reverted.

## Working tree

`git status` shows only the untracked `data/`. Nothing outside `wire/`,
`glass/` and `phone/` was staged; no branch was created, switched, merged,
pushed or rebased; `git clean` was never run.

## What still needs hardware

1. **The single-writer restructure.** Confirm at bring-up: a snapshot arriving
   during the connect window no longer aborts the connect; going out of range
   mid-write does not ANR the phone; `onDestroy` tears down promptly from both
   a blocking connect and a stalled write.
2. **IMPORTANT 4.** Confirm the backoff actually escalates against an unpinned
   Glass and against a version-mismatched pair, instead of retrying at ~1s.
3. **IMPORTANT 6.** Confirm the "Not connected" marker appears within ~35s of
   the phone going quiet with the queue open, and that an arriving snapshot
   redraws with the cursor at the end of the list.
4. **IMPORTANT 8.** Confirm the mismatch card persists and that a good handshake
   clears it.
5. **IMPORTANT 5.** Toggle Bluetooth on Glass and confirm the accept loop idles
   instead of spinning.

---

# Re-review wave 2

One Important defect found in this wave's own single-writer fix, plus the two
Low findings that were left open alongside it.

## CORRECTION to "Two waits, deliberately different"

The section above says of `waitForWake(ms)`:

> It is woken by `wake()` or teardown and **not** by a pending snapshot.

**That claim was wrong.** It described the intent, not the code. `onSnapshot`
signals `wakeLock.notifyAll()`, and `waitForWake` parked on that same monitor
with a bare `wait(ms)` and no condition loop. `Object.wait(long)` returns
identically on a timeout and on a notify and reports no reason, so the old code
could not tell them apart — it fell straight through to `wakeRequested = false`
and returned.

The consequence was a duty-cycle failure, the same class as the
`backoff.reset()`-on-connect finding, moved from the reset path to the wait
path. Glass out of range, backoff escalated to 60s, worker parked in
`waitForWake(60_000)`; a notification arrives, `SnapshotBus` delivers after its
500ms debounce, `onSnapshot` signals, and `waitForWake` returns immediately.
`connectLoop` goes straight round to another RFCOMM connect. So **every
notification post or removal forced a reconnect attempt**, `backoff.nextDelayMs()`
was computed and then discarded, and the exponential backoff never engaged at
all: notification traffic set the retry cadence. The `findBondedGlass() == null`
branch was hit identically, so an unbonded phone re-enumerated bonded devices
over Binder on every single notification.

`awaitWork` was genuinely never affected, and that asymmetry is why this was
easy to miss when writing the section above. `pump` holds an absolute
`nextPingAt` on `SystemClock.elapsedRealtime()` and re-enters `awaitWork` with
whatever time is left, so a spurious return there costs one idle pass round the
loop and nothing else. The claim that was true of `awaitWork` — that a stray
wakeup is harmless — was written down for `waitForWake`, where it was not.

## IMPORTANT — `onSnapshot`'s `notifyAll()` truncated the connect backoff

`LinkClientService.waitForWake` now runs a deadline loop:

```java
long deadline = SystemClock.elapsedRealtime() + ms;
synchronized (wakeLock) {
    while (running && !wakeRequested) {
        long remaining = deadline - SystemClock.elapsedRealtime();
        if (remaining <= 0) {
            break;
        }
        try {
            wakeLock.wait(remaining);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            break;
        }
    }
    wakeRequested = false;
}
```

The shared monitor was kept rather than split into a second one. A separate
`snapshotPending` monitor would work, but it makes `onDestroy` responsible for
notifying two monitors to stay prompt, and the correctness of teardown would
then rest on nobody later adding a third wait. The condition loop fixes the
defect without adding that obligation.

**How it tells a real wake from a spurious one.** It does not try to. That is
the point: the return value of `wait()` carries no information, so the loop
ignores what woke it and re-tests state it can trust. Two things end the wait
early, and both are latched booleans guarded by the same monitor that the
`notifyAll()` is issued under:

- `wakeRequested` — set only by the `wake()` path in `onStartCommand`.
- `!running` — set only by `onDestroy`.

`onSnapshot` sets neither. It sets `snapshotPending`, which this loop does not
read, so its `notifyAll()` wakes the worker, the worker recomputes `remaining`,
finds time still owed, and parks again. The wait is therefore bounded below by
the requested duration for every wakeup that is not a genuine wake or a
teardown — including JVM-spurious ones, which the old code was also exposed to.

**How a real `wake()` is still immediate.** `AclReceiver` → `wake()` →
`onStartCommand` takes `wakeLock`, sets `wakeRequested = true`, and calls
`notifyAll()`. The parked worker cannot be inside `wait()` holding the monitor,
so it reacquires, re-tests `!wakeRequested`, and exits the loop *without*
consulting `remaining` at all — the deadline is only reached on the path where
the condition still holds. There is no added latency and no polling interval.
The pre-wait race is still covered too: because `wakeRequested` is a latched
flag and the `while` tests it *before* the first `wait()`, a `wake()` that lands
while `status()` is busy on its Binder call to `NotificationManager` is seen on
entry and the wait is skipped entirely. That was the reason the flag existed and
it still does that job.

**Monotonic clock.** `SystemClock.elapsedRealtime()`, not
`System.currentTimeMillis()`. `elapsedRealtime` counts since boot including
sleep and is not settable, so an NITZ update or a user clock change mid-wait can
neither push the deadline away nor collapse `remaining` to zero. Using the wall
clock here would have traded a truncated backoff for a hung one.

**The other invariants still hold.**

- `onDestroy` still wakes a parked worker promptly. It clears `running` before
  `notifyAll()`, and `running` is re-tested at the top of the loop, so teardown
  does not sit out a 60s wait. Unchanged, and now load-bearing in one more place.
- `onSnapshot` still cannot block. It is untouched: two field writes and a
  `notifyAll()` inside the monitor, no I/O. Nothing else holds `wakeLock` across
  anything that can stall — `waitForWake` and `awaitWork` release it inside
  `wait()`, and `clearPendingSnapshot` is a single assignment. The RFCOMM writes
  in `pump` are outside the monitor entirely.
- A snapshot arriving while the worker runs is still never lost, only coalesced.
  The clear-before-read ordering in `pump` (`clearPendingSnapshot()` then
  `SnapshotBus.get().latest()`) is unchanged, as is `awaitWork` reading and
  clearing `snapshotPending` under the lock before `pump` re-reads `latest()`.
  A snapshot landing in that gap re-raises the flag and is sent on the next pass.

## LOW — `wakeRequested` was not consumed inside a session

`awaitWork` now clears `wakeRequested` alongside `snapshotPending`.

The pre-single-writer `waitFor` consumed the flag; the split into two waits
dropped that on the connected side. So a `wake()` arriving during a live session
had nothing to do — there is no backoff to cut short while connected — but left
the flag set, and the *first* `waitForWake` after that session ended returned
instantly on a stale wake, skipping one whole backoff interval. The flag now
means one thing on both sides: nobody has acted on this wake yet.

This is separate from `AclReceiver` calling `backoff.reset()` on every
`ACL_CONNECTED`, which remains parked for bring-up.

## LOW — `encodeWithinFrame`'s first `encode()` was unguarded

`SnapshotCodec.encodeWithinFrame` now runs every attempt through the loop,
including the first, catching `ProtocolException` (over `MAX_ITEMS`) and
`UTFDataFormatException` (a single field past `writeUTF`'s own 65535-byte
ceiling) as well as testing the encoded length.

Both are `IOException`s. Escaping this method would have put them at
`writeFrame`, which drops the link — and the reconnect handshake re-sends the
identical snapshot, which is precisely the unrecoverable loop the degradation
path exists to prevent, presenting as "phone says Connected, Glass shows
nothing". The remedy for all three failure modes is the same: drop the oldest
item and try again.

This supersedes the note in IMPORTANT 3 above that "`encodeWithinFrame` still
lets a snapshot over `MAX_ITEMS` throw". It no longer does — it degrades to
`MAX_ITEMS`. `encode` itself is unchanged and still throws, so the belt-and-braces
check on the raw encoder is intact; it is only the frame-fitting wrapper that
now absorbs it. `SnapshotBuilder` caps every field and the item count, so none
of this is reachable today, but a second layer with a hole in it is not a second
layer.

The `items.isEmpty()` exit throws rather than falling through. An empty snapshot
is a fixed ten bytes with no strings to overflow, so it always encodes and always
fits and that branch is unreachable; it is there so the loop provably terminates
instead of indexing off the end of an empty list.

## Tests

`+3`, all host-JVM in `wire`, no new dependency:

- `encodeWithinFrameDegradesRatherThanThrowingOverTheItemCap` — `MAX_ITEMS + 5`
  items now degrade to `MAX_ITEMS`, newest kept, instead of throwing.
- `encodeWithinFrameDegradesRatherThanThrowingOnAnUnencodableField` — a 70,000
  character field on the older of two items; the newer survives. This one cannot
  be caught by the length check at all, since `writeUTF` throws before any
  oversized `byte[]` exists.
- `encodeWithinFrameDropsToEmptyWhenTheNewestItemIsUnencodable` — degradation
  runs to empty when nothing is salvageable.

**No test for the `waitForWake` deadline loop, deliberately.** Stating it
plainly rather than working around it: the logic is `SystemClock.elapsedRealtime`
plus monitor `wait`/`notify` across two threads, and `phone` does not set
`returnDefaultValues`, so `SystemClock` throws "not mocked" under the stock
unit-test runner. There is no pure helper worth extracting — `deadline - now` is
not the part that was wrong; the condition loop around `wait()` is, and that
cannot be separated from the monitor. Testing it properly needs Robolectric or a
mocking framework, and this project has neither and must not gain one. It is
verified by reading and belongs on the bring-up list.

Test totals: **102** (wire 45, glass 32, phone 25), 0 failures, both APKs build.

## Not in scope this wave

Left untouched, as ruled: `AclReceiver` calling `backoff.reset()` on every
`ACL_CONNECTED` (parked); `AclReceiver` being able to restart the link service
after notification access is revoked (parked); the teardown window where a
connect started after `onDestroy` sampled the socket fields is not aborted
(pre-existing, correctness unaffected); `QueueActivity` re-rendering
unconditionally every 5s (parked); surrogate-pair splitting in
`SnapshotBuilder.truncate` (cosmetic); `BluetoothAdapter.getDefaultAdapter()`,
the MAC-pin reset UI and `ACTION_STATE_CHANGED` receivers (accepted).

## Add to bring-up

Confirm the backoff now actually escalates against an out-of-range Glass **while
notifications are arriving on the phone**. That is the exact condition the old
code failed under and the reason it was invisible in a quiet-phone test: the
reconnect interval must follow `Backoff`, not the notification rate. Confirm in
the same run that walking Glass back into range still reconnects immediately via
`ACL_CONNECTED`, so the deadline loop has not slowed a real wake.
