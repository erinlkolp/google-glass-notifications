# Glass Notifications

Two Android apps that put notifications from a carried Android phone onto Google Glass Explorer
Edition over classic Bluetooth RFCOMM. This README is written to stand alone: it should be enough
to rebuild, reflash, and debug the project without opening the design spec or the implementation
plan. Those documents still exist for historical reasoning (`docs/superpowers/specs/2026-08-04-glass-notifications-design.md`
and `docs/superpowers/plans/2026-08-04-glass-notifications.md`), and are linked below where they
add something this file does not repeat.

---

## 1. What it is, and what it is not

This mirrors a **second, carried Android phone** onto Glass. It is not an iPhone bridge, and it
will never become one — see [section 2](#2-why-not-the-iphone) for the measured reason why. The
phone (an LG V30 with no SIM) rides in a bag or pocket, signed into the same cross-platform
accounts as everything else: Signal, Discord, Slack, Gmail, and similar. Its notifications are
filtered and tiered on the phone, then pushed to Glass, which displays them and nothing else.

Concretely:

- **Will appear on Glass:** anything the V30 receives a system notification for and that is
  allowlisted — Signal messages, Discord pings, Slack DMs, calendar reminders, and so on.
- **Will never appear:** iMessage. iOS does not let any accessory read it, on any hardware.
- **Will only appear if a SIM is later added to the V30:** SMS and phone calls. Today the V30 has
  no SIM, so neither exists to forward.
- **Glass is read-only.** It displays and scrolls a queue. It never dismisses, replies to, or acts
  on a notification. The one thing it does send back is its own battery state, so the phone can tell
  you when Glass has finished charging — see [section 5](#5-how-the-protocol-works).

Full design rationale lives in the spec, `docs/superpowers/specs/2026-08-04-glass-notifications-design.md`,
sections 1–2.

---

## 2. Why not the iPhone

This project's original goal was mirroring notifications from Erin's actual phone, an iPhone. That
turned out not to be buildable on this Glass hardware, and the reason is worth keeping on record so
it is never re-investigated from scratch.

iOS does not let any third-party app read another app's notifications directly. The only route in
is **ANCS** (Apple Notification Center Service) over Bluetooth LE — the same mechanism every
smartwatch uses. ANCS requires the accessory (Glass, here) to **advertise as a BLE peripheral** so
the iPhone can discover it, connect, and bond. Critically, reconnection after a reboot or after
walking out of range is also accessory-advertises-first — the iPhone will not proactively dial out
to reconnect.

A capability probe run on-device via `app_process32` on 2026-07-30 returned:

```
getBluetoothLeScanner            = PRESENT   (BLE central / GATT client works)
getBluetoothLeAdvertiser         = NULL
isMultipleAdvertisementSupported = false
isPeripheralModeSupported        = false
```

**The BCM4330 Bluetooth chip on this ROM cannot act as a BLE peripheral at all.** It can be a BLE
*central* (it can scan and connect out to other BLE devices), but it can never advertise, which
means it can neither be discovered by the iPhone in the first place nor reconnect to it afterward.
An accessory that can only advertise-and-be-found half the time is not a usable ANCS accessory —
the same mechanism is required for both first pairing and every reconnect, so the missing half
breaks the whole thing.

Two escape hatches were checked and both are closed:

- **Reverse the roles (Glass as BLE central, connecting to an advertising iPhone).** iOS only
  advertises connectably while the Settings → Bluetooth screen is open or while a custom iOS app is
  in the foreground. Building that companion app requires a Mac and an Apple developer account, and
  even then, reconnection after the app is backgrounded is still broken.
- **Classic Bluetooth instead of BLE.** Car head units read SMS on Android but not on iPhone for
  exactly this reason: iOS does not implement MAP (Message Access Profile). The only classic
  profiles iOS exposes are HFP (call state) and AVRCP (now-playing metadata) — neither carries
  notifications.

Hence the carried-Android-relay design this project actually ships. Full writeup: spec §3.

---

## 3. Hardware

Both devices were physically measured, not assumed. Treat this table as ground truth over anything
in the spec or plan if they ever disagree — this file is the one to update after any hardware
change.

| | Glass | LG V30 |
|---|---|---|
| adb serial | `0123456789ABCDEF` | `VS9967edd915b` |
| Bluetooth MAC | `22:22:41:C5:E5:67` | `10:F1:F2:EE:90:8F` |
| Bluetooth name | `Glass 1` | `V30` |
| Android / API | Community AOSP 5.1.1 (build `LMY49J`), **API 22** | Android 9 (Pie, build `PKQ1.190414.001`), **API 28** |
| Role | RFCOMM **server**, display only | RFCOMM **client**, owns reconnection |
| Display | 640×360 physical, density 240 → **320×180 dp** | (not relevant — carried, screen off) |

**Glass's Bluetooth MAC is generated, not the factory address, and this matters operationally.**
`ro.bt.bdaddr_path` on this ROM points at `/data/misc/bluedroid/bdaddr`, which does not exist, so
Bluedroid generated a random locally-administered address (`22:22:41:C5:E5:67` — the `0x22` first
octet has the locally-administered bit set) and persisted it under `persist.service.bdroid.bdaddr`.
The real factory address, `f8:8f:ca:12:ff:c9` (readable from `ro.boot.bdaddr`), is not in use and
never will be under this ROM.

Because it lives in a `persist.*` property (written to `/data/property/`), the address is stable
across ordinary reboots. **A `/data` wipe or a ROM reflash regenerates it.** When that happens:

- The existing Bluetooth *bond* between Glass and the V30 breaks (the OS-level pairing is keyed to
  the old address) and must be redone from [section 8](#8-first-run-setup) step 1.
- Glass's own trust-on-first-use pin (`PeerPin`, [section 12](#12-recovery)) still holds the *old*
  V30 address as "the peer," which no longer matters once the bond is gone, but the pin should be
  cleared anyway as part of the same recovery so a stale entry is never sitting around.

Design detail: spec §5.1.

---

## 4. Architecture

One Gradle project, three modules:

```
google-glass-notifications/
├── wire/     pure JVM, zero android.* imports — protocol, encoding, framing
├── glass/    Android app, minSdk=targetSdk=22 — RFCOMM server, overlay, queue
└── phone/    Android app, minSdk=26, targetSdk=28 — listener, filtering, RFCOMM client
```

### `wire`

A plain `java-library` module — not an Android module, and it must stay that way. It owns the
entire wire protocol: the message model (`NotificationItem`, `Snapshot`, `Hello`), `DataOutputStream`
/`DataInputStream` encoding, and length-prefixed stream framing. Both `glass` and `phone` compile
against it as a project dependency, so the two ends of the connection **cannot silently drift apart**
— a protocol change that breaks decoding on one side fails that side's build immediately, at compile
time, rather than surfacing as a garbled frame months later on hardware.

The zero-Android-imports rule is not just tidiness. `wire` is where the fiddliest, most
bug-prone logic in the whole project lives — byte-level framing under adversarial reads — and being
pure JVM means it runs at full host-JVM speed with plain JUnit, no emulator, no Robolectric, no
device. `NoAndroidImportsTest` (`wire/src/test/java/dev/erinlkolp/glassnotify/wire/NoAndroidImportsTest.java`)
makes this an enforced test rather than a convention someone can forget.

### `phone`

Everything that requires judgment happens here, because the V30 has a screen to configure it on and
Glass does not. `NotifyListenerService` observes the system's notification stream; `SnapshotBuilder`
(pure logic, no Android types) filters against the allowlist, assigns each notification a tier,
truncates fields to the wire limits, sorts, and caps at 20 items; `LinkClientService` is a
foreground service that owns the RFCOMM **client** socket and the entire reconnect/backoff loop.
Filtering happens here — not on Glass — for two reasons: Glass has no input surface to build a
configuration UI with, and keeping the filtering decision off Glass means anything filtered out
never touches the radio, which is the single biggest lever on both devices' battery life.

### `glass`

Deliberately dumb. `LinkServerService` blocks on `BluetoothServerSocket.accept()` and, once
connected, does nothing but read frames, apply them to `SnapshotStore`, and render. Glass never
evaluates allowlists, never decides what to show — it shows exactly what the phone tells it to,
tier and all. The RFCOMM server role is on this side deliberately: reconnection needs a loop that
runs indefinitely, and that belongs on the device with the bigger battery and a foreground service
already running, not on Glass.

Design detail: spec §4.

---

## 5. How the protocol works

RFCOMM is a raw byte stream with no message boundaries of its own, so framing is mandatory — this
is the classic source of "works on the bench, corrupts in the field" Bluetooth bugs, and it is
where most of `wire`'s test suite lives.

### Frame layout

```
uint32  length      big-endian; counts every byte AFTER this field (version + type + body)
uint8   version     protocol version (currently 2)
uint8   type        1 = HELLO, 2 = SNAPSHOT, 3 = PING
...     body        type-specific payload
```

Implemented in `wire/src/main/java/dev/erinlkolp/glassnotify/wire/FrameCodec.java`. A frame
declaring a length over 64KB (`Protocol.MAX_FRAME_BYTES`) is rejected **before any buffer is
allocated**, so a corrupted length field cannot be turned into an `OutOfMemoryError`. A version the
receiver does not recognise is not rejected outright — it is surfaced up so Glass can show an
explicit "phone app out of date" state instead of a generic stream error.

`Protocol.VERSION` bumped from 1 to 2 for the `INTERRUPT_CHIRP` tier: an old Glass build cannot
decode tier code 3, and a `ProtocolException` mid-snapshot would silently destroy every other
notification travelling with it, not just the chirp-tier one. That is exactly the incompatible
change the version field exists to catch. The consequence is operational, not just internal: both
APKs must be reinstalled together after this change. A half-updated pair is a dead link, not a
degraded one — the mismatch is caught immediately and loudly (see the troubleshooting table below)
rather than quietly dropping notifications.

Encoding uses plain `java.io.DataOutputStream`/`DataInputStream`, not JSON. Android ships its own
reimplementation of `org.json` that differs from the one available to a host JVM test, which would
mean the test suite exercises different code than what actually runs on-device. `DataOutputStream`
is bit-for-bit identical on host and Android, needs no dependency, and has no string-escaping edge
cases to get wrong.

### The four message types

- **`HELLO`** — sent once, phone → Glass, immediately after connecting. Carries the phone's
  Bluetooth device name and address. Fails fast on a version mismatch (see above).
- **`SNAPSHOT`** — the entire current notification queue: a monotonically increasing snapshot id,
  then up to 20 items (`Protocol.MAX_ITEMS`), each with `key`, `appLabel`, `title`, `text`,
  `postedAt` (epoch millis), and `tier` (`INTERRUPT`, `QUEUE`, or `INTERRUPT_CHIRP`). Ordered
  newest-first by the phone; Glass renders the list as given and has no opinion about ordering.
- **`PING`** — sent every 10 seconds by the phone (`LinkClientService.PING_INTERVAL_MS`), empty
  body. RFCOMM sockets can half-die with neither end noticing — the heartbeat is what lets the
  phone detect a dead socket and start backing off promptly, and lets Glass mark its cached
  snapshot stale after `SnapshotStore.STALE_AFTER_MS` (30s) of silence rather than showing hours-old
  notifications as current.
- **`GLASS_STATE`** — Glass → phone, unsolicited. Glass's own battery level and whether it is plugged
  in. Sent when a connection opens and whenever the level or power state actually changes. This is the
  only message that travels this direction, and the phone acts on exactly one thing in it: reaching
  100% while on power, which raises a "Glass is charged" notification.

There is deliberately **no acknowledgement**. With full-state snapshots there is nothing to
acknowledge: a lost frame is superseded by the next one.

The reverse channel is exactly one message wide, and should stay that way. Glass volunteers its
battery state and nothing else — it never asks the phone for anything, never confirms receipt, and
never acts on a notification. Adding a second Glass → phone message is a real protocol change and
should be argued on its own merits, not waved through because a channel already exists.

### Why whole snapshots, not deltas (`ADD`/`REMOVE`/`UPDATE`)

Every time notification state changes on the phone, it sends the **entire current queue**, and
Glass simply replaces whatever it was holding. This is more bytes per update than a delta scheme
would need, but the queue is capped at 20 items and a full snapshot comes to roughly 3KB (worst
case, measured under test, is under 16KB — see `SnapshotCodecTest.aFullSnapshotFitsComfortablyInOneFrame`)
— trivial over RFCOMM. In exchange, the message becomes **idempotent**: a duplicate frame, a replayed
frame, or a frame that arrives on reconnect are all harmless, because each one simply overwrites
state rather than needing to be reconciled against what came before. Deltas would need sequence
numbers and a resync handshake to be correct under a flaky radio link, and a single dropped `REMOVE`
would leave a ghost notification on Glass forever with no self-healing path. For a queue this small,
correctness-by-construction was judged more valuable than the bytes saved.

Design detail: spec §6–7.

---

## 6. Prerequisites

- **JDK 21, specifically the `openjdk-21-jdk-headless` package — not a JRE-only package.**
  ```bash
  sudo apt install openjdk-21-jdk-headless
  ```
  The JRE-only package lacks `lib/ct.sym`, and Gradle's `options.release.set(8)` (used to compile
  `wire` for Java 8 bytecode, since Glass's API 22 runtime requires it) fails with a misleading
  error against a JRE-only install. This project was built and tested against
  `openjdk 21.0.11 2026-04-21`.
- **Android SDK, with `ANDROID_HOME` (or `ANDROID_SDK_ROOT`) set.** e.g.:
  ```bash
  export ANDROID_HOME="$HOME/Android/Sdk"
  ```
  `compileSdk = 34` for both app modules; `platform-tools` (for `adb`) must be on `PATH` or
  referenced by full path.
- **udev, if the V30 is not recognised over USB on a fresh machine.** The stock Android udev rules
  file (`/etc/udev/rules.d/51-android.rules`) matches only Google's USB vendor ID `18d1` — LG's
  vendor ID is **`1004`**, and is not covered by that file. On the machine this project was built
  on, no custom rule was actually needed: the LG device node came up owned `root:plugdev` with a
  `uaccess` ACL from a systemd default, and `adb` connected without any udev change. If a different
  machine's `adb devices` cannot see the V30 at all (not even as `unauthorized`), add a rule for
  vendor `1004`:
  ```bash
  echo 'SUBSYSTEM=="usb", ATTR{idVendor}=="1004", MODE="0664", GROUP="plugdev", TAG+="uaccess"' \
    | sudo tee /etc/udev/rules.d/52-lg.rules
  sudo udevadm control --reload-rules && sudo udevadm trigger
  ```
  This is a *different* failure mode from the phone showing up but `unauthorized` — see the
  troubleshooting table for that case, which is about USB debugging, not udev.

---

## 7. Build and install

From the repo root:

```bash
# Run every module's unit tests (wire, glass, phone)
./gradlew test

# Build both APKs
./gradlew :glass:assembleDebug :phone:assembleDebug
```

`./gradlew :glass:installDebug` / `:phone:installDebug` exist and work when exactly **one** device
is attached, but with both Glass and the V30 plugged in at once (the normal case for this project),
AGP has no reliable way to pick between them from those tasks alone. Install with `adb` directly
instead, targeting each APK at its own serial — this is the path actually used during development:

```bash
adb -s 0123456789ABCDEF install -r glass/build/outputs/apk/debug/glass-debug.apk
adb -s VS9967edd915b   install -r phone/build/outputs/apk/debug/phone-debug.apk
```

To build and run only `wire`'s tests (fast, no device needed, no AGP):

```bash
./gradlew :wire:test
```

Uninstalling either app cleanly (useful before a fresh install, see [Recovery](#12-recovery)):

```bash
adb -s 0123456789ABCDEF shell pm uninstall dev.erinlkolp.glassnotify.glass
adb -s VS9967edd915b   shell pm uninstall dev.erinlkolp.glassnotify.phone
```

---

## 8. First-run setup

Do these **in order**. Every step includes the `adb` command to verify it actually took — several
of these fail silently in the UI.

**1. Pair the two devices.** This is manual and one-time. The apps never attempt programmatic
pairing (spec §11 — connecting to an *already-bonded* device by MAC avoids Bluetooth discovery and
therefore avoids needing the location permission scanning would otherwise require).

On the V30: **Settings → Connected devices → Bluetooth → pair with "Glass 1"**. Confirm any PIN
prompt on both sides. Then verify from the phone side:

```bash
adb -s VS9967edd915b shell dumpsys bluetooth_manager | grep -iA4 "Bonded devices"
```

Expected: `22:22:41:C5:E5:67` appears in the list.

**2. Grant notification access to the phone app.** Open the phone app (`SetupActivity`) and tap
"Grant notification access," which opens the system notification-listener settings screen; enable
"Glass Notifications" there. Verify:

```bash
adb -s VS9967edd915b shell settings get secure enabled_notification_listeners
```

Expected: the colon-separated output contains
`dev.erinlkolp.glassnotify.phone/dev.erinlkolp.glassnotify.phone.NotifyListenerService`.

**3. Grant the battery optimization exemption.** In the same setup screen, tap "Allow running in
the background." Without this, Android 9's Doze/App Standby will suspend the link overnight. Verify:

```bash
adb -s VS9967edd915b shell dumpsys deviceidle whitelist | grep dev.erinlkolp.glassnotify.phone
```

Expected: a line listing the phone app's package.

**4. Configure the allowlist.** Still in the phone app, open "Choose which apps to show"
(`AllowlistActivity`) and set at least one app to `INTERRUPT` and at least one to `QUEUE`. Set a
third to `INTERRUPT_CHIRP` if you want to test the chirp sound on this pass — tapping an app cycles
it through all three tiers before returning to "not shown." Pick something you can trigger on demand
for testing. There is no `adb` verification step for this one — it is stored in `SharedPreferences`
on the phone and has no external side effect until a notification actually fires.

Once all four are done, `SetupActivity.onResume()` starts `LinkClientService` automatically as soon
as notification access is detected — there is no separate "connect" button.

### 8.1 Moving to a different phone

The steps above assume a first-time setup on the V30. Swapping in a different phone adds two
gotchas, and **both fail silently** — nothing errors, the setup screen looks fine, and no
notification ever arrives.

**Gotcha 1: Glass is pinned to the old phone's MAC.**

`PeerPin` is trust-on-first-use (§12, spec §11.1): the first device to connect is remembered, and
every later connection is checked against it and refused on mismatch. A new phone gets rejected
before a single frame is read.

The reason this is so confusing in practice: **the new phone will say "Connected to Glass" anyway.**
`LinkClientService` sets that status the moment TCP-level `connect()` succeeds, before any data is
exchanged, and the reverse channel carries no such signal (spec §7.4 — it is one message wide and
carries battery state only) — so the phone genuinely cannot learn
it was rejected. Only Glass knows:

```bash
adb -s 0123456789ABCDEF logcat -s GlassNotify   # look for "refusing connection from unpinned device"
```

Clear the pin, then **launch the Glass app once** — `pm clear` also returns the package to Android's
stopped state, and stopped packages receive no broadcasts, so the boot receiver stays dormant until
something launches it:

```bash
adb -s 0123456789ABCDEF shell pm clear dev.erinlkolp.glassnotify.glass
adb -s 0123456789ABCDEF shell am start -n dev.erinlkolp.glassnotify.glass/.QueueActivity
```

This also wipes the cached queue, which is disposable — the phone replaces it wholesale on the next
snapshot anyway.

**Gotcha 2: Android 13+ blocks notification access for sideloaded apps.**

Since Android 13, an app installed from outside an app store is placed under **"Restricted
settings"**, and the OS specifically greys out the two most powerful toggles — Accessibility and
**Notification Listener access**. That second one is the permission this entire project depends on.

The toggle refuses with a "Restricted setting" message and no obvious remedy. The unlock is buried
in an overflow menu:

> **Settings → Apps → Glass Notifications → ⋮ (top right) → Allow restricted settings**

Do this **before** attempting step 2 above, or you will conclude the app is broken.

This did not exist on Android 9, so it never came up on the V30.

**Full migration order**

1. Sideload `phone-debug.apk` (allow installs from unknown sources)
2. **Allow restricted settings** on the app — gotcha 2
3. Grant notification access (step 2 above)
4. Grant the battery exemption (step 3 above)
5. Pair the new phone with Glass (step 1 above)
6. **Clear Glass's pin and relaunch the Glass app** — gotcha 1
7. Configure the allowlist (step 4 above)
8. Test with something you can trigger on demand

**If the new phone is your primary rather than a carried relay**

Two things change, and neither is a code change:

- **SMS and calls start appearing**, because the source phone finally has your SIM. That is new
  surface that never existed on the V30, and it probably deserves `INTERRUPT` while most other
  things do not.
- **The allowlist matters much more.** On a relay phone it only ever saw the accounts you had
  bothered signing into, which was an accidental filter. On a primary, *everything* routes through
  it. Start deliberately narrow and add, rather than starting broad and subtracting — the failure
  mode of a too-broad allowlist is marketing email waking the prism every few minutes, which is
  exactly what the tiered design exists to prevent.

**On modern Android generally**

The APK should install and run untouched: `targetSdk 28` keeps it on legacy Bluetooth permission
behaviour, so there is no runtime `BLUETOOTH_CONNECT` flow to implement, and Android 14+ only blocks
installing apps targeting below API 23. See "Known limitations and parked items" for what breaks the
day `targetSdk` is raised.

The real risk is not the API level, it is **OEM battery management**. Samsung and Xiaomi are far more
aggressive at killing background services than stock Android, and Samsung keeps a separate "Sleeping
apps" list that the battery-optimisation exemption does not cover. Motorola and Pixel are close to
stock and generally need nothing beyond step 3.

---

## 9. Developing the Glass UI without the phone

`scripts/fake-notify.sh` broadcasts a synthetic notification straight into the Glass app via
`DebugInjectReceiver`, so the entire Glass UI — interrupt card, queue paging, stale state, empty
state — can be built and demoed without the phone app existing or being nearby at all. This only
works on a **debug** build (`DebugInjectReceiver` checks `BuildConfig.DEBUG` and no-ops otherwise).

```bash
# Uses GLASS_SERIAL env var if set, otherwise defaults to 0123456789ABCDEF
export GLASS_SERIAL=0123456789ABCDEF

# A QUEUE-tier item (default tier if omitted)
scripts/fake-notify.sh "Signal" "Jordan Reyes" "are you still good for 7pm?"

# An INTERRUPT-tier item — this one wakes the display
scripts/fake-notify.sh "Discord" "#general" "new message from Sam" INTERRUPT

# An INTERRUPT_CHIRP-tier item — wakes the display and plays the chirp tone
scripts/fake-notify.sh "Slack" "#eng" "deploy finished" INTERRUPT_CHIRP

# Clear the injected queue back to empty
scripts/fake-notify.sh --clear
```

Injected items are additive and newest-first, mirroring what the real phone app sends: each
invocation prepends a new item onto whatever the fake queue already holds, up to the 20-item cap.
Under the hood this is just:

```bash
adb -s "$GLASS_SERIAL" shell am broadcast \
  -a dev.erinlkolp.glassnotify.DEBUG_INJECT \
  --es app "Signal" --es title "Jordan Reyes" \
  --es text "are you still good for 7pm?" --es tier QUEUE
```

Note `DebugInjectReceiver` is `exported="true"` on an unprotected action in debug builds — any
other app installed on the same Glass unit could also trigger fake interrupts. Accepted as a known,
low-risk residual (see [Known limitations](#known-limitations-and-parked-items) below) since this
only ever affects debug builds on a device Erin controls.

---

## 10. Testing

```bash
./gradlew test              # all three modules
./gradlew :wire:test        # protocol only, fastest
./gradlew :glass:testDebugUnitTest
./gradlew :phone:testDebugUnitTest
```

As of the last full run: **162 unit tests pass** (62 in `wire`, 56 in `glass`, 44 in `phone`), and
both APKs build cleanly except for one accepted, deliberately-unfixed deprecation note (see
[Known limitations](#known-limitations-and-parked-items)).

What the suite actually covers:

- **`wire`** carries the real weight. Round-trip encode/decode is the easy half; the half that
  matters is framing under adversarial reads — the same frame split at every possible byte
  boundary, two frames arriving in one buffer, one frame split across three reads, a stream
  truncated mid-header and mid-body, and a corrupted length field claiming 2GB. These are exactly
  the "works on the bench, corrupts in the field" bugs RFCOMM invites.
- **`glass`** and **`phone`** keep Android types (`StatusBarNotification`, `View`, `BluetoothSocket`)
  confined to thin edge classes (`SbnMapper`, `QueueActivity`, `LinkServerService`/`LinkClientService`)
  so everything with actual decision logic — allowlist matching, tiering, truncation, the queue
  cursor's clamping behaviour when a snapshot shrinks under the reader — is plain-JVM-testable.

### What the suite does *not* and cannot cover

**Real-finger touch testing on the actual hardware is mandatory and not automatable.**
`adb shell input tap`/`swipe` injects events **below the window manager**, bypassing touchable
regions entirely, and cannot inject multitouch at all. On the predecessor project (the gesture
launcher), this produced 40 green automated tests while two real, user-visible bugs were live on
the device the whole time. Nothing about this project's test suite should be read as covering:

- Whether paging forward/back through the queue actually stops at both ends on real touch input
  (as opposed to the unit-tested `QueueCursor` logic, which does).
- Whether a swipe near the top of the touchpad correctly does *not* open the notification shade
  (the `IMMERSIVE_STICKY` mitigation, spec §9.4) — this can only be confirmed with a finger.
- Whether a horizontal swipe reliably registers as a swipe and a vertical drag reliably does not
  page (`SwipeDetector`'s thresholds — see [Tuned values](#13-tuned-values)).
- Whether the interrupt card is actually legible outdoors or against a bright background — a
  property of the see-through optics that has no software proxy at all.

Concurrency and lifecycle correctness in `LinkServerService` and `LinkClientService` (the
single-writer socket handling, the connect/destroy race handling, the backoff/wake interaction) has
been **verified by code inspection and reconstructed happens-before reasoning during review, not by
running on hardware** — see [Known limitations](#known-limitations-and-parked-items).

---

## 11. Troubleshooting

| Symptom | Cause | What to do |
|---|---|---|
| **Nothing appears on Glass at all** | Most commonly: devices not bonded, notification access not granted, or the allowlist has nothing configured. | Walk through [First-run setup](#8-first-run-setup) again — each step has a verification command; find which one actually failed rather than guessing. |
| **Interrupts appear fine while the Glass UI is awake, but with the display asleep and locked the screen wakes to the lock screen and goes straight back off, showing no card** | The overlay is rendering *underneath* the keyguard. Fixed as of `a383c3e`; if you still see it, the installed APK predates that commit. Root cause: `TYPE_SYSTEM_ALERT` sits at window layer `101000`, below `KeyguardScrim` (`131000`) and the `StatusBar` that draws the keyguard on 5.x (`151000`). Note `FLAG_SHOW_WHEN_LOCKED` does **not** fix this — it controls whether the keyguard force-*hides* a window, and here the keyguard is stacked above rather than hiding anything. | Reinstall the current Glass APK. To confirm the layering yourself: `adb -s 0123456789ABCDEF shell dumpsys window windows \| grep -E "Window #\|mBaseLayer="` while an interrupt is showing — the `glassnotify.glass` overlay should read `mBaseLayer=221000`, above the StatusBar's `151000`. |
| **Glass shows "Not connected" (stale queue)** | No `PING` received for 30s (`SnapshotStore.STALE_AFTER_MS`). The link died and the phone hasn't reconnected yet, or Bluetooth is off on one side. | Check `adb -s VS9967edd915b shell dumpsys bluetooth_manager \| grep -iE "enabled"` on both devices. If both are on and bonded, give the phone's backoff up to 60s (`Backoff.MAX_MS`) to retry, or force it with the app's "wake" path by reopening the phone app. |
| **Glass shows "Phone app out of date"** | `LinkServerService` read a frame whose `version` field does not match `Protocol.VERSION` (currently `2`). This means the two APKs were built from different, incompatible commits of `wire`. | Rebuild and reinstall **both** APKs from the same checkout — `wire` is shared, but an old APK on one side does not get the new protocol automatically. |
| **Glass refuses the connection after a reflash** | The wipe regenerated Glass's Bluetooth MAC (§3), which breaks the OS-level bond. Separately, Glass's own `PeerPin` trust-on-first-use pin may still reference the phone's old identity from before the reflash. | Re-pair the two devices from scratch (step 1 of [First-run setup](#8-first-run-setup)), then clear the pin as in [Recovery](#12-recovery) so the next connection re-pins cleanly. |
| **The phone's persistent notification says "Connected to Glass," but nothing shows on Glass and the queue stays stale** | An **unpinned MAC is refused silently.** `LinkServerService.serve()` checks `PeerPin.isAllowed()` immediately on accept; if it fails, Glass logs `"refusing connection from unpinned device …"` and returns — closing the socket — without ever sending anything back. The phone side (`LinkClientService`) sets its "Connected" status the instant TCP-level `connect()` succeeds, **before** any data has actually been exchanged, and has no read side at all, so it can never learn the connection was rejected. | `adb -s 0123456789ABCDEF logcat -s GlassNotify` and look for "refusing connection from unpinned device." If present, clear Glass's pin (see [Recovery](#12-recovery)) and reconnect — the next connection attempt will pin the current phone address. |
| **V30 not visible to `adb`, or stuck `unauthorized`** | This is almost always the **USB debugging toggle**, not the USB connection mode (Charging/MTP/PTP) — those are orthogonal settings on this device. Cycling the USB mode changes the advertised product ID (`62ce`, `62c1`, `62c9` were all observed) but never publishes the ADB interface if debugging is off. Watch for **USB interface class `255` / subclass `66` / protocol `1`** (the ADB interface) in `lsusb -v`, not for a specific product ID. | Confirm Developer Options → USB debugging is on. If it shows `unauthorized`, run `adb kill-server && adb start-server` **with the phone screen unlocked** (see next row) and re-check. |
| **The RSA authorization prompt never appears on the V30** | The prompt is suppressed while the phone's screen is locked. | Unlock the phone, then `adb kill-server && adb start-server`, or simply unplug/replug the USB cable while unlocked. |

Every filter above assumes the `GlassNotify` logcat tag, which every relevant class on both sides
uses:

```bash
adb -s 0123456789ABCDEF logcat -s GlassNotify   # Glass side
adb -s VS9967edd915b   logcat -s GlassNotify   # phone side
```

---

## 12. Recovery

**Clear Glass's pinned peer MAC** (trust-on-first-use — see [section 5](#5-how-the-protocol-works)
and spec §11.1). Needed after a `/data` wipe, a ROM reflash, or replacing the phone with one that
has a different Bluetooth address:

```bash
adb -s 0123456789ABCDEF shell pm clear dev.erinlkolp.glassnotify.glass
```

This clears **all** app state, not just the pin — `SharedPreferences` (`glassnotify.xml`, holding
the pin) and the cached snapshot file (`snapshot.bin`) both live under the app's private storage and
both get wiped. That is fine: the cached snapshot is just the last thing shown, and it will be
replaced by a fresh one on the next connection. To inspect what is currently pinned without clearing
it:

```bash
adb -s 0123456789ABCDEF shell run-as dev.erinlkolp.glassnotify.glass \
  cat shared_prefs/glassnotify.xml
```

**Clear `/data/dalvik-cache`** when testing failure paths or after side-loading a new build over an
old one and seeing behaviour that does not match the source. ART can silently keep serving a stale
compiled dex and mask the very behaviour under investigation:

```bash
adb -s 0123456789ABCDEF shell rm -rf /data/dalvik-cache/*
adb -s 0123456789ABCDEF reboot
```

(This device's `adb shell` is already uid 0 and SELinux is permissive on this community ROM —
`ro.build.type=eng`, `ro.secure=0` — so no `su` is needed for either of these.)

**Force a full reinstall of either app** (e.g. after a build that will not start cleanly):

```bash
adb -s 0123456789ABCDEF shell pm uninstall dev.erinlkolp.glassnotify.glass
./gradlew :glass:assembleDebug
adb -s 0123456789ABCDEF install glass/build/outputs/apk/debug/glass-debug.apk
```

**Glass's `BootReceiver` will not fire after a fresh install** until the app has been launched at
least once by hand. Android leaves a newly-installed package in the "stopped" state, and stopped
packages receive no broadcasts — including `BOOT_COMPLETED` — until something explicitly launches
them. After installing (or reinstalling) the Glass app, open it once from the launcher before
relying on boot-time auto-start; only after that will a subsequent reboot bring the link service up
on its own.

---

## 13. Tuned values

**Most of these have not been tuned on real hardware yet — see [Known limitations](#known-limitations-and-parked-items).**
Every value below is the value actually compiled into the last build, read directly from source
rather than from any planning document, but the ones marked "starting value" were chosen by
reasoning about the display and the radio, not by measurement on the device, and should be revisited
once real bring-up happens. `ChargeAlertPolicy.FULL_LEVEL` is the exception: it was exercised
against both devices during hardware verification on 2026-08-10 (see the row below, and
[Known limitations](#known-limitations-and-parked-items) for what that verification found).

| Constant | Value | Where | Status |
|---|---|---|---|
| `Protocol.VERSION` | `2` | `wire/src/main/java/dev/erinlkolp/glassnotify/wire/Protocol.java` | Fixed protocol constant. |
| `Protocol.MAX_FRAME_BYTES` | `65536` (64 × 1024) | same | Fixed protocol constant. |
| `Protocol.MAX_ITEMS` | `20` | same | Fixed protocol constant. |
| `Protocol.MAX_TEXT_CHARS` | `240` | same | Fixed protocol constant. |
| `Protocol.MAX_TITLE_CHARS` | `80` | same | Fixed protocol constant. |
| `Protocol.MAX_KEY_CHARS` | `96` | same | Fixed protocol constant, added to close a frame-size overflow path. |
| `Protocol.MAX_APP_LABEL_CHARS` | `24` | same | Fixed protocol constant. |
| `InterruptOverlay.DISPLAY_MS` | `7000` (7s) | `glass/src/main/java/dev/erinlkolp/glassnotify/glass/InterruptOverlay.java` | **Starting value, tune on hardware.** How long an interrupt card stays up before auto-dismissing. Raised from the original 5s — the card read as too brief on hardware. |
| `ChirpTone.START_HZ` | `800` (Hz) | `glass/src/main/java/dev/erinlkolp/glassnotify/glass/ChirpTone.java` | **Starting value, tune on hardware.** Sweep start frequency. Chosen by ear from several candidate tones during the 2026-08-13 spike, all played at full stream volume (7 of 7). |
| `ChirpTone.END_HZ` | `2400` (Hz) | same | **Starting value, tune on hardware.** Sweep end frequency, chosen the same way. Lower this first if the chirp proves audible to bystanders — see [Known limitations](#known-limitations-and-parked-items) — leakage worsens with frequency. |
| `ChirpTone.DURATION_MS` | `150` (ms) | same | **Starting value, tune on hardware.** Sweep duration, chosen the same way; short enough that even an audible leak reads as a click rather than a recognisable alert. |
| `ChirpPlayer.INITIAL_VOLUME_INDEX` | `5` (of 7 max) | `glass/src/main/java/dev/erinlkolp/glassnotify/glass/ChirpPlayer.java` | **Confirmed in use, 2026-08-14.** `STREAM_NOTIFICATION` level written once per install. Chosen blind — every candidate tone in the spike was auditioned at the device's maximum of 7, so 5 was a deliberate step down that nobody had heard — and then confirmed right after a day of real notifications on the head. Do not move it without a reason. If the chirp proves audible to bystanders, lower `ChirpTone.END_HZ` first; this is the second lever, not the first. |
| `LinkClientService.PING_INTERVAL_MS` | `10000` (10s) | `phone/src/main/java/dev/erinlkolp/glassnotify/phone/LinkClientService.java` | **Starting value, tune on hardware.** How often the phone sends a heartbeat. |
| `SnapshotStore.STALE_AFTER_MS` | `30000` (30s) | `glass/src/main/java/dev/erinlkolp/glassnotify/glass/SnapshotStore.java` | **Starting value, tune on hardware.** Silence beyond this marks Glass's cached queue stale. Chosen as 3× the ping interval. |
| `SwipeDetector.SWIPE_MIN_DX` | `60f` dp | `glass/src/main/java/dev/erinlkolp/glassnotify/glass/SwipeDetector.java` | **Starting value, tune on hardware.** Minimum horizontal travel to register as a swipe rather than a tap. Explicitly called out in the plan as "chosen on reasoning, not measurement." |
| `SwipeDetector.HORIZONTAL_DOMINANCE` | `1.2f` | same | **Starting value, tune on hardware.** How much larger `|dx|` must be than `|dy|` for a gesture to count as horizontal. |
| `SwipeDetector.TAP_MAX_MS` | `400` ms | same | **Starting value, tune on hardware.** Maximum duration for a gesture with small travel to count as a tap. |
| `QueueActivity.REFRESH_INTERVAL_MS` | `5000` (5s) | `glass/src/main/java/dev/erinlkolp/glassnotify/glass/QueueActivity.java` | Fixed re-render poll interval while the queue screen is foregrounded, kept comfortably under the 30s staleness threshold so a stale marker cannot sit unseen for long. |
| `Backoff.INITIAL_MS` | `1000` (1s) | `phone/src/main/java/dev/erinlkolp/glassnotify/phone/Backoff.java` | Fixed. First retry delay after a connection failure. |
| `Backoff.MAX_MS` | `60000` (60s) | same | Fixed. Reconnect backoff ceiling; doubles from `INITIAL_MS` up to this cap. |
| `ChargeAlertPolicy.FULL_LEVEL` | `100` (100%) | `phone/src/main/java/dev/erinlkolp/glassnotify/phone/ChargeAlertPolicy.java` | Fixed, and validated on hardware 2026-08-10. Battery level, while on power, at which the phone raises the "Glass is charged" notification. |
| `LinkServerService.RETRY_DELAY_MS` | `5000` (5s) | `glass/src/main/java/dev/erinlkolp/glassnotify/glass/LinkServerService.java` | Fixed. Delay before retrying `listenUsingRfcomm` after a failure (not the accept loop itself, which has no backoff — see known limitations). |
| `SnapshotBus.DEBOUNCE_MS` | `500` (500ms) | `phone/src/main/java/dev/erinlkolp/glassnotify/phone/SnapshotBus.java` | Fixed. Coalescing window for bursty `onNotificationPosted` callbacks (e.g. a group message) before a snapshot is built and sent. |

---

## Known limitations and parked items

Stated plainly so nothing above is mistaken for more settled than it is:

- **Hardware verification was completed on 2026-08-10**, against both devices (Glass Explorer
  Edition and the LG V30). All 162 unit tests pass and both APKs build cleanly. The RFCOMM
  concurrency (single-writer socket handling in `LinkClientService`, the accept-loop lifecycle in
  `LinkServerService`, the connect/destroy race handling, and the new `GLASS_STATE` reverse channel)
  was exercised by running the two devices against each other, not only by code review.
  - **On this ROM, Glass reports `status: 2` (`BATTERY_STATUS_CHARGING`) even at level 100 — it
    never reports `status: 5` (`BATTERY_STATUS_FULL`).** Verified via `adb shell dumpsys battery`
    during this pass. Had the charge-alert design triggered on `BATTERY_STATUS_FULL` instead of
    battery level, the alert would never have fired at all on this hardware. This validates the
    level-based trigger chosen in the charge-alert design, §4 (see also
    [Tuned values](#13-tuned-values) → `ChargeAlertPolicy.FULL_LEVEL`).
- **Whether the chirp is audible to people nearby has not been tested.** Bone conduction
  transducers leak, and leakage worsens with frequency — the sweep tops out at 2400 Hz. Testing
  this needs a second listener and one was not available, so this is untested rather than verified
  quiet. If it proves audible in use, lower `ChirpTone.END_HZ` first, then
  `ChirpPlayer.INITIAL_VOLUME_INDEX` (see [Tuned values](#13-tuned-values)). Audibility to the
  *wearer* is a separate question and is now settled: the chirp was used for a day of real
  notifications on 2026-08-14 at level 5 and works well. That says nothing about leakage, which
  still needs a second listener.
- **Touch behaviour has no automated coverage and cannot get any**, per the `adb shell input`
  limitation described in [Testing](#10-testing). This is not a gap to be closed later with more
  unit tests — it structurally cannot be closed that way.
- **`BootReceiver` needs one manual launch after every fresh install**, per the note in
  [Recovery](#12-recovery). This is normal Android behaviour (stopped-package broadcast
  suppression), not a bug, but it will look like a bug the first time a rebuild doesn't survive a
  reboot.
- **Deliberately parked, not bugs:**
  - `DebugInjectReceiver` is `exported="true"` on an unprotected broadcast action in debug builds,
    so any other app on the same Glass unit could trigger fake interrupts. Debug-build-only, low
    risk, not fixed. `DebugBatteryReceiver` has the identical shape (`exported="true"`, guarded only
    by `BuildConfig.DEBUG`) and the same acceptance applies.
  - `LinkServerService`'s `accept()` failure path has no backoff (only `listenUsingRfcomm` failures
    sleep, via `RETRY_DELAY_MS`), so a persistent hardware-level accept failure could hot-loop on
    Glass's small battery.
  - `AclReceiver` (phone side) calls `backoff.reset()` on every `ACTION_ACL_CONNECTED`, which can
    make the exponential backoff sawtooth rather than climb smoothly if the ACL bounces repeatedly.
    It can also resurrect `LinkClientService` after notification access has been revoked.
  - `SwipeDetector`'s ratio-based decision (`HORIZONTAL_DOMINANCE`) has no unit test in the band
    where the ratio and a naive raw-`dx`-vs-`dy` comparison would disagree, so a regression away
    from "ratio, not raw" would currently pass the suite.
  - A handful of other minor, low-risk items are recorded in
    `.superpowers/sdd/2026-08-04-glass-notifications/progress.md` if a fuller list is ever needed;
    none were judged worth carrying here.

---

## Reference documents

- Design spec: `docs/superpowers/specs/2026-08-04-glass-notifications-design.md` — the full
  rationale behind every decision summarized above, plus the sections not needed day-to-day
  (rejected alternatives, out-of-scope items, the complete failure-handling table).
- Implementation plan: `docs/superpowers/plans/2026-08-04-glass-notifications.md` — the task-by-task
  build history, including every file created and why.
- Ledger: `.superpowers/sdd/2026-08-04-glass-notifications/progress.md` — the complete defect and
  review history across all 13 tasks, including everything summarized in
  [Known limitations](#known-limitations-and-parked-items) above.
