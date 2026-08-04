# Glass Notifications — Design

**Date:** 2026-08-04
**Status:** Approved design, ready for implementation planning
**Author:** Erin Kolp, with Claude

---

## 1. Summary

Two Android apps that put notifications from a carried Android phone onto Google Glass.

A **phone app** on an LG V30 (Android 9) observes system notifications, filters them against an
allowlist, assigns each an interrupt tier, and pushes the current notification state to Glass over
classic Bluetooth RFCOMM. A **Glass app** on a Glass Explorer Edition running community AOSP 5.1.1
receives that state and renders it — briefly interrupting for high-priority items, silently queueing
everything else.

Glass is **read-only**: it displays and scrolls, but never acts on a notification.

---

## 2. Hardware context

Both devices were measured, not assumed. The Glass facts come from the retained hardware probe of
2026-07-30 and are treated as structurally stable, since this community AOSP build is the only open
ROM available for the device.

### 2.1 Google Glass Explorer Edition (the display)

| Property | Value |
|---|---|
| ROM | Community AOSP 5.1.1, build LMY49J, `ro.product.device=glass-1` |
| API level | 22 |
| SoC | OMAP4430, 32-bit ARMv7 (`app_process32`) |
| Root | `ro.build.type=eng`, `ro.secure=0` — `adb shell` is already uid 0; SELinux permissive |
| Display | 640×360 physical at density 240 → **320×180 dp** |
| Optics | See-through prism. Pure black / pure white only; mid-tones and gradients wash out |
| Bluetooth | BCM4330. Classic BT healthy (HFP, A2DP, AVRCP). BLE **central only** |
| BT address | `22:22:41:C5:E5:67`, name "Glass 1" — see §5.1, this is *not* the factory address |
| Status bar | Claims `touchableRegion [0,0][640,38]` — steals downward swipes near the top of the pad |

### 2.2 LG V30 / VS996 (the source)

| Property | Value |
|---|---|
| Android | 9 (Pie), API 28, build `PKQ1.190414.001`, device `joan` |
| adb serial | `VS9967edd915b` |
| Role | Carried relay — rides in a bag or pocket, signed into key accounts, no SIM |
| Bluetooth | 5.0, classic RFCOMM. Address `10:F1:F2:EE:90:8F`, name "V30" |

The V30 is a *second* phone. Erin's primary device is an iPhone. This design therefore surfaces
notifications for accounts signed in on the V30 (Signal, Discord, Slack, Gmail, and similar
cross-platform services). It does not surface iMessage, and it surfaces SMS and calls only if a SIM
is later added. This is understood and accepted.

---

## 3. Why not the iPhone directly

Recorded so this is not re-litigated later. The original goal was mirroring iPhone notifications.
That is **not buildable on this hardware**, for a measured reason.

iOS never permits a third-party app to read other apps' notifications, so the only route is **ANCS**
(Apple Notification Center Service) over BLE — the mechanism every smartwatch uses. ANCS requires the
accessory to advertise as a BLE peripheral so the iPhone can discover it, connect, and bond.
Reconnection after a reboot or a range excursion is also accessory-advertises-first.

A capability probe run on-device via `app_process32` returned:

```
getBluetoothLeScanner            = PRESENT   (BLE central / GATT client works)
getBluetoothLeAdvertiser         = NULL
isMultipleAdvertisementSupported = false
isPeripheralModeSupported        = false
```

**The BCM4330 on this ROM cannot act as a BLE peripheral.** It cannot be discovered, and — decisively
— it can never re-connect. A variant where Glass plays central and connects to an advertising iPhone
fails for practical reasons: iOS only advertises connectably while sitting on the Settings → Bluetooth
screen or while a custom iOS app is foregrounded, building that app requires a Mac, and reconnection
remains broken regardless.

The classic-Bluetooth escape hatch is also closed: iOS does not implement MAP (Message Access
Profile), which is why car kits read texts on Android but not iPhone. HFP call events and AVRCP
now-playing metadata are the entire menu, and neither is notifications.

Hence the carried-Android-relay design.

---

## 4. Architecture

One Gradle project, three modules, deliberately echoing the structure that worked for the gesture
launcher (`gesture-core` / `app` / `daemon`).

```
google-glass-notifications/
├── wire/     pure JVM, zero android.* imports — protocol, encoding, framing
├── phone/    Android app, targetSdk 28 — listener, filtering, RFCOMM client
└── glass/    Android app, minSdk/targetSdk 22 — RFCOMM server, overlay, queue
```

### 4.1 `wire`

Owns the message model, `DataOutputStream`/`DataInputStream` encoding, and stream framing. This is
the `gesture-core` of this project: all the fiddly logic in one place, tested at host speed with no
device and no emulator.

Both apps compile against it, so the two ends **cannot drift out of agreement** — a protocol change
that breaks one end fails the other's build.

### 4.2 `phone`

- `NotificationListenerService` observing posts and removals
- Allowlist / tier configuration UI
- Foreground service owning the RFCOMM client socket and reconnect backoff
- Snapshot builder mapping current notification state to a `wire` message

### 4.3 `glass`

- Service owning the RFCOMM server socket
- Disk-cached copy of the last snapshot
- `TYPE_SYSTEM_ALERT` overlay renderer for interrupts
- Activity for browsing the queue, with touchpad paging

### 4.4 No AndroidX, in either app

`android.useAndroidX` is a **project-wide** Gradle property and cannot vary per module. The Glass
build deliberately runs `useAndroidX=false` with plain framework `Activity`/`View`, and that must not
change.

Rather than split into two Gradle projects and lose the shared `wire` module, the **phone app is also
written against plain framework APIs**. This costs nothing: `NotificationListenerService`,
`BluetoothAdapter`, foreground services, notification channels, and a `ListView` settings screen are
all framework classes on Oreo and later. Nothing in this project wants AndroidX.

Per-module SDK configuration still differs — `glass` at minSdk/targetSdk 22, `phone` at targetSdk 28,
both on compileSdk 34 — which is ordinary per-module config and unaffected by the project-wide flag.

### 4.5 Android 9 implications for `phone`

- `FOREGROUND_SERVICE` permission in the manifest (required at targetSdk 28)
- A notification channel for the persistent service notification
- Battery-optimization exemption requested on first run, so App Standby Buckets and Doze do not sever
  the link overnight
- `BLUETOOTH` / `BLUETOOTH_ADMIN` remain **install-time** permissions below API 31 — no runtime grant
- Connecting to an **already-bonded** device by MAC avoids discovery, and therefore avoids the
  location permission that BT scanning would otherwise require
- Pie's non-SDK interface restrictions do not apply — `NotificationListenerService` is public SDK

---

## 5. Transport and connection topology

**Classic Bluetooth RFCOMM.** The phone is carried and there is no assumption of a network, so Wi-Fi
is not viable as the primary path. RFCOMM also avoids the advertising problem entirely: either side
may initiate, and reconnection is symmetric.

**Glass is the server; the phone is the client and owns retry.** Reconnection means a backoff loop
running indefinitely, which belongs on the device with the larger battery and a foreground service
already required. Glass simply blocks in `accept()`, which costs nothing.

### 5.1 Glass's Bluetooth address is generated, not factory

Verified 2026-08-04. `ro.bt.bdaddr_path` points at `/data/misc/bluedroid/bdaddr`, **which does not
exist**. Bluedroid therefore generated a random locally-administered address (`0x22` first octet has
the locally-administered bit set) and persisted it:

```
persist.service.bdroid.bdaddr = 22:22:41:c5:e5:67   <- in use
ro.boot.bdaddr                = f8:8f:ca:12:ff:c9   <- factory, unused
```

Because `persist.*` properties are written to `/data/property/`, the address is stable across
reboots, so MAC pinning (§11.1) and the phone's `ACTION_ACL_CONNECTED` filter (§10.2) are both sound.

**But a `/data` wipe or ROM reflash will produce a new random address.** The pinning in §11.1 must
therefore have a reset path, or a reflash locks the wearer out of their own device.

---

## 6. State model — full-state snapshots

Every time notification state changes, the phone sends the **entire current queue**. Glass discards
what it had and renders the new list.

**Rationale.** The message is idempotent, so a duplicate or replayed frame is harmless. There are no
deltas, sequence numbers, resync handshakes, or divergence. Reconnection is free — the phone sends a
snapshot on connect, the same code path as every other update.

The rejected alternative was delta events (`ADD`/`REMOVE`/`UPDATE`). Deltas use fewer bytes and scale
to a larger backlog, but introduce the bug class that eats projects like this: a dropped `REMOVE`
during a flaky moment leaves a ghost notification on Glass forever, with no self-healing path. Making
that honest needs sequence numbers and reconciliation — substantial machinery for a queue of twenty
items. The constraint here is not bandwidth; it is correctness under flaky Bluetooth, and snapshots
solve that by construction.

**Queue cap: 20 items.** A snapshot is then roughly 3KB, which is negligible over RFCOMM.

Glass caches the last snapshot to app-private storage, so a Bluetooth dropout or service restart
still leaves prior notifications readable.

---

## 7. Wire protocol

### 7.1 Framing

RFCOMM is a byte stream with no message boundaries, so framing is mandatory. This is the most common
source of "works on the bench, corrupts in the field" Bluetooth bugs.

```
uint32  length      big-endian; counts every byte after this field
uint8   version     protocol version
uint8   type        HELLO | SNAPSHOT | PING
...     body
```

- Frames declaring more than **64KB are rejected without allocating**, so a corrupted length field
  cannot become an OOM.
- A version mismatch makes Glass display an explicit "phone app out of date" state. Loud failure
  beats mysterious silence.

### 7.2 Encoding: `DataOutputStream`, not JSON

Android ships its **own reimplementation** of `org.json`, so host-side unit tests using the Maven
artifact would exercise a different implementation than the one that actually runs on either device.
`java.io.DataOutputStream` is identical on host and Android, needs no dependency, and has no
string-escaping edge cases. The exact code the tests prove is the code that ships.

A `debugString()` on each message provides readable logcat output during bring-up, recovering JSON's
debuggability without its problems.

### 7.3 Messages

**`HELLO`** — phone → Glass on connect. Protocol version and phone identity. Fails fast on mismatch.

**`SNAPSHOT`** — snapshot id plus up to 20 items, ordered newest-first *by the phone*. Glass renders
the list as given and holds no opinion about ordering.

| Field | Notes |
|---|---|
| `key` | `StatusBarNotification.getKey()` — stable identity across updates |
| `appLabel` | Resolved to text on the phone. No icons — see §9.1 |
| `title` | Truncated |
| `text` | Truncated to ~240 characters |
| `postedAt` | Epoch millis |
| `tier` | `INTERRUPT` or `QUEUE` |

Truncation happens **on the phone, before transmission**. At 320×180dp only a couple hundred
characters are renderable, so sending more wastes radio time and lets a pathological notification
exhaust Glass's memory. It also keeps every string well under `writeUTF`'s 64KB ceiling.

**`PING`** — periodic, phone → Glass. RFCOMM sockets can half-die silently with neither end noticing.
A heartbeat lets the phone detect a dead socket and begin backoff promptly, and lets Glass mark its
cached snapshot stale rather than presenting hours-old notifications as current. Starting values:
`PING` every 10s, stale after ~30s of silence.

### 7.4 No reverse channel

There is no `ACK` and no Glass → phone traffic. With full-state snapshots there is nothing to
acknowledge: a lost frame is superseded by the next snapshot.

---

## 8. Tiering and filtering

**All filtering and tier assignment happens on the phone.** The V30 has a real screen to configure an
allowlist on; Glass does not. The phone evaluates each notification, tags it `INTERRUPT` or `QUEUE`,
and Glass obeys.

This also means filtered-out notifications never reach the radio, which is the single largest lever
on battery life for both devices.

- **`INTERRUPT`** — a short user-defined allowlist. Wakes the Glass display for ~5 seconds.
- **`QUEUE`** — everything else. Lands silently, visible only when the queue is opened.

---

## 9. Glass display

Renders at 640×360 physical pixels, 320×180 dp. **Black is transparent on the prism** — the wearer
sees white glyphs over the world, not a black rectangle.

### 9.1 Palette and typography

Pure `#000` and pure `#fff` only. No icons, no grey, no gradients — mid-tones wash out to
invisibility on see-through optics. App identity is conveyed as a **text label**, which is more
legible at this size than an icon would be.

Sizes are specified in **dp, not sp**: the layout is fixed and must not reflow under a user font-scale
setting.

### 9.2 Interrupt card

Glanceable headline. Optimised to be read in under a second without focusing.

| Element | Size | Placement |
|---|---|---|
| Sender | ~27dp bold | Centre, dominant |
| Message | ~16dp | Below sender, hard-truncated to ~40 characters |
| App label | ~12dp letterspaced caps | Bottom-left |

Displayed ~5 seconds, then dismissed automatically. Duration is a starting value to be tuned on
hardware.

**Consequence, accepted deliberately:** an interrupt tells you *who* and only gestures at *what*.
The queue is therefore where reading actually happens, and is designed for reading comfort rather
than density.

### 9.3 Queue view

One notification per screen.

| Element | Size | Placement |
|---|---|---|
| App label | ~12dp caps | Top-left |
| Position (`3 / 7`) | ~12dp | Top-right |
| Sender | ~20dp bold | Below header |
| Body | ~15dp | Full message, comfortable line height |
| Age (`14 MIN AGO`) | ~12dp caps | Bottom-left |

Swipe forward/back pages through the queue — the same idiom already in muscle memory from the gesture
launcher's next/previous-app gesture, so no new learning is required.

Rejected alternatives: a four-item scannable list (truncates every line to ~34 characters, sacrificing
the reading comfort the interrupt card depends on) and a position-dot strip (costs a line of message
text; can be added later if position is genuinely hard to track).

### 9.4 Status bar interference

The StatusBar window claims `touchableRegion [0,0][640,38]`, so downward swipes near the top of the
pad open the notification shade instead of reaching the app. Mitigation, already proven in the gesture
launcher:

`SYSTEM_UI_FLAG_IMMERSIVE_STICKY` together with `FULLSCREEN | HIDE_NAVIGATION | LAYOUT_STABLE`, set in
`onCreate` **and re-applied in `onWindowFocusChanged`**. `SYSTEM_UI_FLAG_LOW_PROFILE` does *not*
achieve this — it only dims navigation icons.

Text is additionally kept clear of the top 38px band regardless.

---

## 10. Lifecycle

### 10.1 Glass

A `BOOT_COMPLETED` receiver starts a service that blocks on `BluetoothServerSocket.accept()` on a
background thread. This is considerably simpler than the launcher's boot problem, which needed the
init-hook technique because it ran a root `app_process` daemon; this is an ordinary app UID, so the
normal receiver suffices.

On accept, the service reads frames in a loop. On any `IOException` it closes and returns directly to
`accept()`.

Interrupts are drawn as a `TYPE_SYSTEM_ALERT` overlay — an install-time permission on API 22, so **no
root is required** — with a `SCREEN_BRIGHT | ACQUIRE_CAUSES_WAKEUP` wakelock held for the display
duration and released after.

**Interrupt storms are collapsed, not queued.** If a second interrupt arrives while one is showing, it
replaces it and resets the timer. Otherwise a chatty group thread pins the display on for a minute and
drains the battery.

The queue is browsed by launching the app from the existing gesture launcher, which lists it
automatically. A global gesture is **deliberately out of scope**: it would couple this project to the
gesture launcher's root daemon, and two independent apps are worth more than the convenience. It
remains a small follow-up if wanted.

### 10.2 Phone

The `NotificationListenerService` is bound and revived by the system automatically. A separate
foreground service owns the RFCOMM client socket and an exponential reconnect backoff capped around
60 seconds.

The service also listens for `ACTION_ACL_CONNECTED` matching Glass's MAC and reconnects immediately on
that signal, so walking back into range does not mean waiting out a full backoff interval.

Notification changes are **debounced ~500ms** before a snapshot is sent. A single group message can
fire several `onNotificationPosted` callbacks in a burst; coalescing them is the second major battery
lever alongside phone-side filtering.

---

## 11. Failure handling

| Situation | Behaviour |
|---|---|
| Bluetooth off, either side | Watch `ACTION_STATE_CHANGED` and idle; do not spin a retry loop |
| Devices not bonded | Phone shows a setup screen. Programmatic pairing is never attempted |
| Corrupt frame / bad length | Close the socket and reconnect. Never attempt mid-stream resync |
| Protocol version mismatch | Glass displays "phone app out of date" |
| No `PING` for ~30s | Glass keeps showing cached items, marked stale |
| Glass reboots | Receiver restarts the service; phone's backoff reconnects and sends a snapshot |
| Snapshot shrinks under the reader | Queue index clamps (see §12.3) |

### 11.1 Connection security

Glass's server socket would otherwise accept a connection from anything in range that knows the UUID.
Since Glass has no configuration UI, connections are pinned **trust-on-first-use**: the first device
to connect has its MAC persisted, and every later connection is checked against it and rejected on
mismatch. This prevents a stranger in range pushing arbitrary text into the wearer's field of view.

**A reset path is mandatory, not optional.** Per §5.1, Glass's own address is regenerated on a `/data`
wipe, and a replacement phone would present a different MAC as well. Without a way to clear the pin,
either event permanently breaks the pairing. `adb shell pm clear <package>` is sufficient and needs no
UI, but it must be documented rather than left as folklore.

---

## 12. Testing

### 12.1 `wire` — host JVM, carries the weight

Round-trip encode/decode is the straightforward half. The half that matters is **framing under
adversarial reads**:

- the same frame split at every byte boundary
- random chunk sizes
- two frames arriving in a single read
- one frame split across three reads
- a stream truncated mid-header
- a length field claiming 2GB

Stream-reassembly bugs survive bench testing and surface in the field. All of the above are testable
with no hardware.

### 12.2 Keep Android types at the edges

`StatusBarNotification` cannot be constructed in a host test, so the phone module maps it to a plain
data object immediately. Allowlist matching, tier assignment, truncation, ordering, and the 20-item
cap then operate on plain data and are tested directly.

### 12.3 Glass queue state

Pure logic, and it has one genuinely nasty case that must be tested first: **the reader is on item 5
of 7 when a snapshot arrives containing 3 items.** The index must clamp gracefully rather than throw.
Full-state snapshots make this routine rather than rare.

### 12.4 Fake feed

A debug-only broadcast receiver injects a snapshot from a file via `adb`, so the entire Glass UI —
interrupt card, queue paging, stale state, empty state — is developable and demoable with no phone
involved. This decouples build order.

### 12.5 Hardware-testing warnings

Carried forward from the gesture launcher's gotchas, and **not optional**:

- `adb shell input tap/swipe` injects *below* the window manager, bypassing touchable regions
  entirely, and cannot inject multitouch at all. On the previous project it produced 40 green tests
  while two real bugs were live. **Real-finger testing on hardware by Erin is mandatory** for anything
  touch-related. Automated tests do not cover it, and no one should read them as if they do.
- Clear `/data/dalvik-cache` when testing failure paths, or ART will silently serve a stale dex and
  mask the behaviour under observation.

### 12.6 Toolchain gotchas that will recur

- Install `openjdk-N-jdk-headless`, not the JRE package — the JRE lacks `lib/ct.sym`, which breaks
  Gradle's `options.release = 8` with a misleading error.
- d8 8.2.2-dev (build-tools 34.0.0) NPEs on any enum compiled by JDK 21 javac. The protocol uses a
  `tier` enum, so **this will be hit.** Workaround: strip the `MethodParameters` attribute from jar
  copies before invoking d8. Alternatively represent `tier` as an int constant in `wire` to sidestep
  it entirely — to be decided during planning.
- The device shell lacks `head`, `which`, `pidof`, and `sed`. Pipe to the host instead.

---

## 13. Out of scope

Explicitly deferred, and not to be added without a further design pass:

- **Any action from Glass** — dismissing, replying, firing notification actions. Glass is read-only.
  Actions are `PendingIntent`s needing marshalling plus a selection UI at 320×180dp; that is a phase
  of its own.
- **Notification icons or images.** Ruled out by the optics, not by effort.
- **A global "open queue" gesture.** Would couple this project to the gesture launcher's root daemon.
- **Wi-Fi transport.** The device is carried; there is no network assumption.
- **iPhone as a source.** See §3 — not achievable on this hardware.

---

## 14. Open questions

None blocking. To confirm during implementation:

1. **The two devices are not yet bonded.** `dumpsys bluetooth_manager` on the V30 shows an empty
   bonded-devices list. Pairing is a manual, one-time setup step — §11 states the apps never attempt
   programmatic pairing — and must happen before any end-to-end test.
2. **Interrupt display duration** (~5s), **`PING` interval** (10s) and **staleness threshold** (~30s)
   are starting values to be tuned on hardware.
3. **Allowlist configuration UI** — the interaction detail is left to the implementation plan. The
   data model (per-app, with tier) is fixed by §7.3 and §8. Note the phone's setup screen can detect
   whether access has been granted by reading `settings get secure enabled_notification_listeners`,
   a colon-separated list of `package/class` entries.
4. **The `tier` enum will trigger the d8 NPE** described in §12.6, since `wire` is dexed into both
   apps. Decide during planning between the `MethodParameters`-stripping workaround and representing
   `tier` as an int constant.

### 14.1 Resolved during design

- **V30 adb access.** Now working: serial `VS9967edd915b`, API 28 confirmed. The obstacle was the USB
  debugging toggle, not USB mode — cycling Charging/MTP/PTP changes the product ID (`62ce`, `62c1`,
  `62c9`) without ever publishing the ADB interface. Watch for interface class 255 / subclass 66 /
  protocol 1, not for a PID change. The final `unauthorized` state cleared after `adb kill-server &&
  adb start-server` with the phone unlocked; the RSA prompt is suppressed on the lock screen.
- **udev.** Not needed. `/etc/udev/rules.d/51-android.rules` matches only Google's `18d1`, but the LG
  node came up `root plugdev` with a `uaccess` ACL from a systemd default, and adb connected fine.
