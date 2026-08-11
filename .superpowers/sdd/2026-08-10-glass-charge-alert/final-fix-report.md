# Final whole-branch review — fix report

Branch: `feat/glass-charge-alert`
Commit: `2af476662584adbcbc43b0346f52518f157c2594`

All five findings from the final review were fixed, independently, in the order given. One
commit, `git commit` message above.

---

## Finding 1 — reverse-channel failure aborted the forward session

**File:** `glass/src/main/java/dev/erinlkolp/glassnotify/glass/LinkServerService.java`, `serve()`

**Before:** on `socket.getOutputStream()` throwing `IOException`, `serve()` logged and `return`ed
immediately — before the read loop ever ran. The phone had already shown "Connected", so an entire
session of forward notifications never reached the prism, repeating on every reconnect if the
condition persisted. Violated spec §3.2 ("No new failure mode that can stop notifications reaching
the prism").

```java
try {
    writer = new StateWriter(socket.getOutputStream(), batteryWatcher.latest());
} catch (IOException e) {
    Log.w(TAG, "no output stream for the reverse channel", e);
    return;
}
```

**After:** `writer`/`writerThread` are declared `null` up front. On failure, log and fall through —
no early return. The writer-thread creation and `stateWriter` publish are now guarded by
`if (writer != null)`, so a failed session simply runs the read loop with no reverse channel;
`stateWriter` is never published, and `onBatteryState` already no-ops when `stateWriter == null`.
The `finally` teardown is null-guarded the same way (`if (writer != null) { writer.stop(); ...join...}`),
so there's nothing to stop or join when the reverse channel never existed for that session.

The read loop, version-mismatch handling, `PeerPin` check, and `dispatch` call are byte-for-byte
identical to before — confirmed by inspection, not just diff — per the constraint against
restructuring `serve()` beyond what findings 1/2 require.

---

## Finding 2 — publish-after-start dropped a state change (the important one)

Same file, same method.

**Before:**
```java
writerThread = new Thread(writer, "glassnotify-state");
writerThread.start();
stateWriter = writer;
```
`stateWriter = writer` ran *after* `writerThread.start()`. Failure scenario: Glass at 99% on
power, phone reconnects, writer seeded with (99, true), battery ticks to 100 before `stateWriter`
is published. `onBatteryState` (main thread) reads `stateWriter == null` in that window and drops
the state. `BatteryWatcher` only re-broadcasts on change, so once (100, true) is the stable reading
nothing resends it — no alert for the whole session, and recovery needs an actual link drop, which
could be hours away.

**After:**
```java
if (writer != null) {
    writerThread = new Thread(writer, "glassnotify-state");
    // Publish before start(), not after: onBatteryState (main
    // thread) and this thread race to see stateWriter. If start()
    // ran first, a battery tick landing in that window would find
    // stateWriter still null and drop the update. BatteryWatcher
    // only re-broadcasts on change (its debounce), so a dropped
    // update is not merely late - once the reading has settled,
    // nothing ever resends it, and the session goes without an
    // alert until the link happens to drop and reconnect, possibly
    // hours later. offer() is thread-safe and a not-yet-started
    // thread simply reads the newer pending value on its first pass
    // through the loop, so publishing first costs nothing and closes
    // the window.
    stateWriter = writer;
    writerThread.start();
}
```
`stateWriter = writer` now runs strictly before `writerThread.start()`. Safe because `offer()` is
thread-safe and a thread that hasn't started yet just reads whatever `pending` is on its first pass
through its own loop — there's no cost to publishing early, only a closed race window. Comment
explains the "why" in the codebase's existing style, explicitly naming the debounce as what turns
a merely-dropped update into a permanently missed one.

---

## Finding 3 — `setAutoCancel(true)` was inert

**File:** `phone/src/main/java/dev/erinlkolp/glassnotify/phone/ChargeAlerter.java`

**Before:** `.setAutoCancel(true)` with no `.setContentIntent(...)` — auto-cancel only fires when
the content intent launches, so tapping did nothing and the flag never took effect. Spec §7.3
falsely claimed "auto-cancels on tap".

**After:** removed the inert call (no content intent added — out of scope, this feature doesn't
need one). Replaced with a comment explaining why, in the file's existing voice. Spec §7.3 in
`docs/superpowers/specs/2026-08-10-glass-charge-alert-design.md` corrected to:

> Content: title "Glass is charged", text "100% — ready to go". Not ongoing, and no content intent
> — there is nothing for a tap to launch. It is dismissed by swiping, or cleared automatically
> (`NotificationManager.cancel`) when Glass reports it has come off power.

Verified against `ChargeAlertPolicy.onState()`: `!state.onPower && shown` returns `CANCEL`, which
is exactly the "cleared when unplugged" path described.

---

## Finding 4 — README test counts and hardware-status claims

**File:** `README.md`

1. Line ~487: `"102 unit tests pass (45 wire, 32 glass, 25 phone)"` → `"147 unit tests pass (58 in
   wire, 47 in glass, 42 in phone)"`. Confirmed by test run (below).
2. Line ~636, "Known limitations": `"Nothing has been tested on real hardware yet"` → rewritten as
   `"Hardware verification was completed on 2026-08-10, against both devices (Glass Explorer
   Edition and the LG V30)"`, keeping the existing detail about what was exercised (RFCOMM
   concurrency, single-writer handling, connect/destroy race, and — since this branch is what's
   under review — the new `GLASS_STATE` reverse channel), now stated as verified by running the
   devices against each other rather than only by code review.
3. Line ~602, "Tuned values" intro: `"None of these have been tuned on real hardware yet"` softened
   to `"Most of these have not been tuned on real hardware yet"`, with a new sentence calling out
   `ChargeAlertPolicy.FULL_LEVEL` as the exception, hardware-validated 2026-08-10. The `FULL_LEVEL`
   table row's Status column also updated: `"Fixed."` → `"Fixed, and validated on hardware
   2026-08-10."`

**Where the battery-status finding was recorded, and why:** as a new sub-bullet directly under the
rewritten "Hardware verification was completed" bullet in **Known limitations and parked items**.
That section is explicitly the place in this README for "stated plainly so nothing above is
mistaken for more settled than it is" — hardware surprises and residual caveats, in the same voice
as the existing `BootReceiver`/`DebugInjectReceiver`/`AclReceiver` notes. I considered the Tuned
values section instead (it's right next to the `FULL_LEVEL` row) but the finding is really about
what hardware verification *discovered*, not about the constant's value — so it belongs with the
other "here's what real hardware taught us" notes, and I cross-linked it from the Tuned values
intro paragraph so a reader looking at `FULL_LEVEL` finds it either way. Full text recorded:

> On this ROM, Glass reports `status: 2` (`BATTERY_STATUS_CHARGING`) even at level 100 — it never
> reports `status: 5` (`BATTERY_STATUS_FULL`). Verified via `adb shell dumpsys battery` during this
> pass. Had the charge-alert design triggered on `BATTERY_STATUS_FULL` instead of battery level,
> the alert would never have fired at all on this hardware. This validates the level-based trigger
> chosen in the charge-alert design, §4.

---

## Finding 5 — parked-items list omitted the new debug receiver

**File:** `README.md`, "Known limitations and parked items" → "Deliberately parked, not bugs"

Confirmed `DebugBatteryReceiver` (`glass/src/main/java/dev/erinlkolp/glassnotify/glass/DebugBatteryReceiver.java`)
has the identical shape to `DebugInjectReceiver`: `android:exported="true"` in
`glass/src/main/AndroidManifest.xml`, guarded only by `BuildConfig.DEBUG` at runtime. Added to the
**existing** `DebugInjectReceiver` bullet (no new bullet created):

> `DebugInjectReceiver` is `exported="true"` on an unprotected broadcast action in debug builds, so
> any other app on the same Glass unit could trigger fake interrupts. Debug-build-only, low risk,
> not fixed. `DebugBatteryReceiver` has the identical shape (`exported="true"`, guarded only by
> `BuildConfig.DEBUG`) and the same acceptance applies.

---

## Constraints honored

- `Protocol.VERSION` untouched (still `1`).
- `wire`, `LinkReader`, `LinkClientService`, `StateWriter`, `BatteryWatcher`, `BatteryReading` — not
  touched.
- `serve()`'s read loop, version-mismatch handling, `PeerPin` check, and `dispatch` call are
  byte-for-byte identical to the pre-fix version.
- No lambdas, no `java.util.function`, no `Optional` — plain `if`/anonymous `Runnable`, matching
  the rest of the file. Every `if` braced.
- Glass remains single-writer; the accept thread never writes (unchanged — `writerThread` is the
  only thread touching the socket's output stream, same as before).
- No `adb` commands run; devices untouched.

---

## Test run

```
./gradlew test assembleDebug
```
Result: **BUILD SUCCESSFUL** (118 actionable tasks: 22 executed, 96 up-to-date).

Counted the **debug** variant only (per the gotcha — `glass`/`phone` each also produce a
`testReleaseUnitTest` results directory that would double-count if summed):

| Module | Results dir | Tests | Failures | Errors |
|---|---|---|---|---|
| `wire` | `wire/build/test-results/test` | 58 | 0 | 0 |
| `glass` | `glass/build/test-results/testDebugUnitTest` | 47 | 0 | 0 |
| `phone` | `phone/build/test-results/testDebugUnitTest` | 42 | 0 | 0 |
| **Total** | | **147** | **0** | **0** |

Matches the corrected README figures exactly.

---

## Commit

```
2af476662584adbcbc43b0346f52518f157c2594
fix: don't drop notifications or charge alerts on reverse-channel races
```
4 files changed: `README.md`, `docs/superpowers/specs/2026-08-10-glass-charge-alert-design.md`,
`glass/.../LinkServerService.java`, `phone/.../ChargeAlerter.java`. 70 insertions, 25 deletions.

## Note

An untracked `data/` directory was present in the working tree at task start (per `git status` in
the initial context) and was left alone — unrelated to this review and not part of any finding.
