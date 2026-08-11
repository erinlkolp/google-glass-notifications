# Glass Charge Alert — Design

**Date:** 2026-08-10
**Status:** Approved design, ready for implementation planning
**Author:** Erin Kolp, with Claude
**Extends:** `2026-08-04-glass-notifications-design.md`

---

## 1. Summary

When Google Glass finishes charging, the carried LG V30 raises a notification saying so.

This is the first Glass → phone traffic in the project. The existing link is strictly one
directional: the phone writes, Glass reads, and nothing goes back. This design adds a single
unsolicited state message in the reverse direction, and nothing else.

The motivating comparison is an Apple Watch telling an iPhone it has finished charging. The
mechanism here is different, but the moment is the same one: knowing the wearable is ready without
picking it up.

---

## 2. Scope

**In scope:**

- Glass reports its battery level and whether it is on power.
- The phone raises a notification when Glass reaches 100% while plugged in.
- The notification clears when Glass is unplugged, re-arming for the next charge.

**Explicitly out of scope:**

- Low-battery warnings. Considered and deferred — the reverse channel will already exist if this
  becomes wanted, but it doubles the policy surface (thresholds, re-arming, not nagging) for a
  problem not yet felt.
- A live battery readout on the phone. The phone receives Glass's level continuously but displays
  it only via the full-charge alert. Turning the foreground notification into a status dashboard is
  a different and larger change to what the app is.
- Any alert reaching Erin's iPhone. The alert lands on the V30 only. See §3.1.
- Any Glass → phone message other than `GLASS_STATE`. See §5.4.

---

## 3. Constraints

### 3.1 The alert lands on the relay phone, not the primary phone

Per the parent spec §2.2, the V30 is a *carried relay* with no SIM; Erin's primary device is an
iPhone. The alert therefore appears on the V30, which is useful when both devices are in the same
place — Glass on a desk charger with the V30 nearby — and useless when the V30 is in a bag.

This was considered and accepted rather than overlooked. Forwarding onward to the iPhone would need
an outbound network dependency (ntfy, Pushover, a Discord webhook) and credentials stored on the
V30, which is a materially larger change than the feature justifies.

### 3.2 The existing forward path must not change

The notification bridge works on real hardware and is in daily use. This feature is **strictly
additive**:

- No change to `Protocol.VERSION`.
- No change to the phone's single-writer discipline.
- No change to the Glass read loop, the snapshot path, or the interrupt overlay.
- No new failure mode that can stop notifications reaching the prism.

Every design decision below that looks over-cautious is answering this constraint.

---

## 4. What counts as "fully charged"

**`batteryLevel >= 100` while `onPower` is true.**

The alternative was Android's `BATTERY_STATUS_FULL` from `EXTRA_STATUS`, which is nominally the
OS's own opinion of "done charging". It was rejected because that value is firmware-specific: some
builds latch it at 100% well before the cell is topped off, others never emit it at all. On a 2013
OMAP4430 running a community ROM, the level-based rule is the one we can predict without measuring
the firmware first.

The practical difference is trickle-charge time at the very top of the curve, which does not matter
for "ready to go".

---

## 5. Protocol

### 5.1 New message type

```
MessageType.GLASS_STATE = 4
```

Types 1–3 (`HELLO`, `SNAPSHOT`, `PING`) are unchanged and remain phone → Glass. Type 4 is
Glass → phone. Direction now carries meaning in this protocol, where before it was implicit;
`MessageType` gains a comment recording that, because it is easy to get wrong later.

### 5.2 `GlassState`

| Field | Type | Meaning |
|---|---|---|
| `batteryLevel` | `int` | 0–100, normalised from `EXTRA_LEVEL`/`EXTRA_SCALE` on Glass |
| `onPower` | `boolean` | plugged into any source (AC or USB) |

The constructor rejects a level outside 0–100, matching the validation already done in
`NotificationItem` and `Hello`.

`GlassStateCodec` encodes a two-byte body — one byte level, one byte flag — through
`DataOutputStream`, like every other codec in `wire`. Two bytes is small enough that the write is
effectively atomic at the RFCOMM layer, which is a minor but welcome property for a message sent
from a thread that must never stall.

`wire` remains pure Java. `NoAndroidImportsTest` covers the new classes automatically.

### 5.3 `Protocol.VERSION` stays at 1

This looks like it should bump. It should not.

The parent spec defines a version bump as an *incompatible* change to framing or message bodies.
This change is compatible in both directions by construction:

| Combination | Behaviour |
|---|---|
| New Glass, old phone | The old phone has no read side, so the frame is never observed. Glass's writer thread may eventually park on a full socket buffer; nothing else is affected, and it clears when the socket closes. |
| Old Glass, new phone | Glass never sends type 4. The phone's reader never sees one. No alert ever fires. |
| New both | Works. |

Bumping `VERSION` would instead make the two builds actively refuse each other, and on Glass that
surfaces as the persistent version-mismatch state in `SnapshotStore` — converting a harmless
silence into a visibly broken link. Strictly worse.

The "ignore unknown frame types" rule already implemented in `LinkServerService.dispatch` was
written for exactly this situation.

### 5.4 The reverse channel stays one message wide

This supersedes parent spec §7.4, which stated there is no reverse channel at all. Most of that
section's reasoning survives and is restated here, because it is still load-bearing:

- There is still **no acknowledgement**. Full-state snapshots make one meaningless — a lost frame is
  superseded by the next.
- There is still **no request/response**. Glass never asks the phone for anything, and the phone
  never asks Glass for anything. `GLASS_STATE` is unsolicited.
- There is still **no Glass-initiated action**. Glass cannot dismiss, reply to, or act on a
  notification. It remains read-only in every sense that the parent spec meant.

What changed is narrow: Glass may now volunteer its own hardware state. Any future proposal to add a
second reverse message should be treated as a real protocol change and argued on its own merits,
not waved through as "the channel already exists".

---

## 6. Glass implementation

### 6.1 `BatteryWatcher`

Registers `ACTION_BATTERY_CHANGED` at runtime in `LinkServerService.onCreate`, unregisters in
`onDestroy`. Registration must be at runtime: `ACTION_BATTERY_CHANGED` cannot be declared in a
manifest filter. No new permission is required.

It normalises `EXTRA_LEVEL`/`EXTRA_SCALE` to 0–100, reads `EXTRA_PLUGGED`, and publishes the result
to a `volatile GlassState`.

`ACTION_BATTERY_CHANGED` is chatty — it fires on temperature and voltage changes, not only on level
changes. The watcher therefore signals a change **only when the `(batteryLevel, onPower)` tuple
differs from the last published one.** That is the whole debounce; no timer is needed. Glass sitting
at 100% on a charger overnight has a stable tuple and sends nothing.

### 6.2 `StateWriter`

A thread created per connection inside `serve()`, after the MAC pin check passes. It writes the
current state once immediately on connect, then parks on a monitor until the tuple changes, then
writes again.

Sending on connect — not only on change — is what makes the reconnect rule in §7.2 work.

Two invariants:

- **Glass is single-writer.** The accept thread reads and never writes; `StateWriter` writes and
  never reads. The discipline `LinkClientService` documents on the phone now holds symmetrically on
  Glass.
- **A stalled write cannot reach the prism.** The accept thread sits in `FrameCodec.read()` and is
  unaware of the writer. If `StateWriter` blocks indefinitely against an old phone that never
  drains its buffer, snapshots continue to arrive and render exactly as they do today.

Lifecycle reuses machinery that already works. When a session ends, `acceptLoop`'s `finally` closes
the socket, which makes any blocked write throw, which ends the thread. `serve()` joins it with a
short timeout on the way out. A straggler that outlived the join holds only a closed socket and can
do nothing but throw.

---

## 7. Phone implementation

### 7.1 `LinkReader`

A plain Java class — an `InputStream` plus a callback interface, no Android types. It loops on
`FrameCodec.read`, dispatches `GLASS_STATE`, and ignores every other type.

Started inside `pump()` immediately after the HELLO write. It may touch **exactly one thing: the
input stream.** Not `wakeLock`, not `backoff`, not `connectedSocket`, not the output stream. This is
recorded as a doc-comment invariant beside the existing threading documentation in
`LinkClientService`, because the next reader of that file needs to know the single-writer rule
survived this change.

Failure handling is deliberately inert. On `IOException`, `ProtocolException`, or an unrecognised
frame version: **log and exit the thread.** It does not tear the link down, trip a backoff, or
report a status change. The PING loop remains the sole liveness authority, unchanged. The worst
realistic outcome is that the reverse channel goes quiet for one session while notifications
continue to flow.

Being pure Java over a stream, it unit-tests on the JVM with no device.

Delivery hops to the main thread via `Handler`, matching `LinkServerService.applySnapshot`. This is
not strictly required — `status()` already calls `NotificationManager` off-main — but it keeps all
alert state on one thread, so §7.2 needs no locking.

### 7.2 `ChargeAlertPolicy`

A small state machine holding one `boolean shown`:

```
onState(state):
  if (!state.onPower)                    -> shown ? (shown = false, CANCEL) : NONE
  if (state.batteryLevel >= 100 && !shown) -> shown = true, SHOW
  otherwise                              -> NONE
```

That single field carries three behaviours:

- **Reconnect while still full** — `shown` is already true, so nothing fires. This is the chosen
  answer to "Glass reached 100% while the phone was out of range": the phone alerts on reconnect
  *if Glass is still plugged in*, and if Erin already unplugged and left, `onPower` is false and the
  moment has correctly passed. No expiry timer to tune.
- **Notification dismissed by hand, then the link bounces** — still no re-alert, because `shown`
  tracks *we alerted*, not *the notification is visible*. Without this the alert would return on
  every reconnect, which is how an app earns a permanent mute.
- **Unplug re-arms.** CANCEL clears the notification and resets `shown`, so the next charge alerts
  normally.

Known edge, accepted: if the phone app restarts while Glass sits plugged at 100%, `shown` starts
false and one additional alert fires. Persisting the flag to disk was rejected as
disproportionate — a fresh session arguably *should* re-announce.

No Android types, so it unit-tests on the JVM alongside `Backoff` and `InterruptPolicy`.

### 7.3 The notification

A **new channel**, `glass_charge`, at `IMPORTANCE_DEFAULT` — audible, but not a heads-up interrupt.
The existing `glass_link` channel remains `IMPORTANCE_MIN` and is not modified; the ongoing
foreground status notification behaves exactly as it does today. A new notification ID (`2`) is
used, since `1` belongs to the foreground service.

Content: title "Glass is charged", text "100% — ready to go". Not ongoing, and no content intent —
there is nothing for a tap to launch. It is dismissed by swiping, or cleared automatically
(`NotificationManager.cancel`) when Glass reports it has come off power.

**Open implementation detail:** the small icon. Existing code uses
`android.R.drawable.stat_sys_data_bluetooth`. A battery glyph is preferable, but platform drawable
names in that area vary across API levels. The implementer must verify a chosen name compiles
against `compileSdk 34` and add a small vector under `phone/src/main/res` if none is suitable. This
is a build-time check, not a design question.

---

## 8. Testing

New JVM tests, all runnable without hardware:

| Test | Covers |
|---|---|
| `GlassStateCodecTest` | roundtrip, boundary levels, rejects out-of-range |
| `ChargeAlertPolicyTest` | reach 100, repeat state, unplug, replug, reconnect-while-full, 99% |
| `LinkReaderTest` | dispatch, fragmented stream, unknown type ignored, bad version exits cleanly, truncated stream exits cleanly |

**The regression gate is the existing 102-test suite staying green.** It covers the forward path.
Because this change is purely additive, any failure there means something was broken and work stops
until it is understood.

Baseline verified on 2026-08-10, immediately before this design was committed: `./gradlew test`
passes with 102 distinct tests — wire 45, glass 32, phone 25.

Note when counting: `./gradlew test` runs the Android modules' unit tests once per build variant, so
`glass` and `phone` each produce both a `testDebugUnitTest` and a `testReleaseUnitTest` result
directory. Summing every `TEST-*.xml` therefore reads 159, not 102. Count one variant only.

### 8.1 What the suite cannot cover

Neither real Bluetooth nor real battery hardware. Hardware verification is required for:

1. Glass reaching 100% with the phone connected → alert appears on the V30.
2. Glass reaching 100% with the phone out of range, then coming back into range while still
   plugged → alert appears on reconnect.
3. Unplugging Glass → notification clears.
4. Replugging and reaching 100% again → alert fires again.
5. Notifications continue reaching the prism throughout all of the above.

### 8.2 Debug injection

Charging Glass to 100% takes over an hour, which makes the loop above impractical to run honestly.
Mirroring the existing `DebugInjectReceiver` and `scripts/fake-notify.sh`, this design adds a debug
receiver that injects a fake `GlassState` and a `scripts/fake-battery.sh` to drive it over adb.

This is treated as part of the feature, not an optional extra: without it, hardware verification is
tedious enough that it will likely be skipped or faked.

---

## 9. Documentation to correct

Four existing statements become false and must be corrected in the same change:

| Location | Current text |
|---|---|
| `README.md:28` | "there is no reverse channel at all" |
| `README.md:191` | "The three message types" |
| `README.md:205` | "no reverse channel and no acknowledgement… Glass never sends anything back" |
| Parent spec §7.4 | "No reverse channel" |

Parent spec §7.4 is **rewritten, not deleted** — its reasoning about acknowledgements and deltas
still holds. It should point at §5.4 of this document for the one exception.

README §13 ("Tuned values") gains the full-charge threshold once verified on hardware.

---

## 10. Rejected alternatives

| Alternative | Why rejected |
|---|---|
| Forward the alert to the iPhone (ntfy / Pushover / Discord) | Adds an outbound network dependency and stored credentials to the V30. Disproportionate to the feature. |
| Show the "charged" card on the Glass prism itself | You would have to pick Glass up and look through it to see the alert, which defeats the purpose. |
| Phone polls `available()` in the existing worker loop instead of a reader thread | Saves one thread, but `BluetoothSocket.available()` is unreliable across stacks and it puts reads into the thread that must never block. Trades a well-understood thread for a subtle bug. |
| Capability flag in `HELLO` gating whether Glass sends at all | About ten lines, and makes a mixed install provably inert rather than merely harmless. Rejected because both APKs are installed together per README §7, and the un-gated failure mode is already benign (§5.3). Cheap to add later if a mixed install ever becomes real. |
| `BATTERY_STATUS_FULL` as the trigger | Firmware-specific and unverified on this ROM. See §4. |
| Bump `Protocol.VERSION` to 2 | Turns a harmless silence into a mutually refused link. See §5.3. |
| Persist `shown` across app restarts | Disproportionate to a once-per-restart duplicate alert. See §7.2. |
