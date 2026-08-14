# Glass Chirp Alert — Design

**Date:** 2026-08-13
**Status:** Approved design, ready for implementation planning
**Author:** Erin Kolp, with Claude
**Extends:** `2026-08-04-glass-notifications-design.md`

---

## 1. Summary

When a notification arrives from an app Erin has marked as chirp-worthy, Glass plays a short rising
tone through its bone conduction transducer at the same moment the interrupt card appears.

Today the prism lighting up is the only signal a notification has arrived. If Glass is worn but not
being looked through — the common case — the card can come and go unnoticed within its seven second
window. This adds a second, non-visual channel for the notifications that warrant one.

The alert is **audible, not tactile**. Section 3 explains why that distinction is forced by the
hardware rather than chosen.

---

## 2. Scope

**In scope:**

- A new notification tier that shows the interrupt card *and* plays a tone.
- Per-app opt-in, expressed through the existing allowlist UI.
- One tone, one volume, no user-facing configuration beyond the per-app choice.

**Explicitly out of scope:**

- Any haptic or vibration alert. Not deferred — impossible on this hardware. See §3.1.
- Per-app or per-tier distinct tones. One sound means one thing: "something you care about
  arrived." Distinguishable-by-app tones require Erin to learn a vocabulary, which is a real cost
  for a benefit not yet felt.
- A chirp cooldown or rate limit. See §9.2.
- Any user-facing volume control on Glass itself. See §6.5.
- Sound for `QUEUE`-tier notifications. Queued items land silently by definition; a sound would
  make the tier meaningless.

---

## 3. Constraints

### 3.1 Glass cannot vibrate, and the bone conduction driver cannot fake it

The originating request was for a *buzz* — something felt rather than heard. A spike on 2026-08-13
established that this is not achievable on this unit:

- **There is no vibration motor.** `/sys/class/timed_output/` is empty, no vibrator sysfs nodes
  exist anywhere on the device, and `/system/lib/hw/vibrator.default.so` is the stock AOSP stub HAL
  with no hardware behind it.
- **The bone conduction transducer cannot substitute for one.** Tones at 60, 90, 120, 160 and
  220 Hz were played through the BCT with Glass worn. All were *audible*; none produced any tactile
  sensation whatsoever. The transducer rolls off below its speech band, which is what it was tuned
  for.

Bone conduction on Glass is a speaker, not a haptic device. Every design below follows from that.

### 3.2 What the transducer does well

The same spike confirmed the audible path is healthy. The BCT is `AUDIO_DEVICE_OUT_SPEAKER` — the
default output — driven through a Glass-specific HAL, `audio.primary.glass_1.so`, at 44100 Hz /
PCM 16-bit. `AudioTrack` playback completes fully, with `playbackHeadPosition` reaching the full
frame count on every burst.

Playback was also verified against a live RFCOMM link: tones played to completion with the link up,
caused no disconnect, and coexisted with a simultaneous interrupt card render.

### 3.3 The existing forward path must not change

As with the charge alert, this feature is additive. An existing `INTERRUPT` notification must behave
exactly as it does today — card, no sound — and `QUEUE` must remain silent and invisible until the
queue is opened. The negative cases are explicitly tested (§8).

---

## 4. Protocol

### 4.1 A third tier

`Tier` gains one value:

```java
INTERRUPT_CHIRP(3)
```

and two predicates, so no call site has to enumerate cases:

```java
/** True for tiers that light up the prism. */
public boolean interrupts() { return this != QUEUE; }

/** True for tiers that also make a sound. */
public boolean chirps() { return this == INTERRUPT_CHIRP; }
```

The predicates are not ceremony. `Tier` already carries a stable wire `code` distinct from its
ordinal precisely because the enum is expected to grow; predicates mean a fourth tier touches `Tier`
and nothing else. `InterruptPolicy` is the only site in the codebase that branches on tier today,
and it becomes `!item.tier.interrupts()`.

### 4.2 `Protocol.VERSION` bumps to 2

The charge alert design (§5.3 of that spec) argued at length that its change should *not* bump the
version, because it was compatible in both directions by construction. This change is the opposite
case, and the contrast is worth stating explicitly so the precedent is not misapplied.

An old Glass build receiving tier code 3 does not skip the item. `SnapshotCodec.decode` throws:

```java
Tier tier = Tier.fromCode(tierCode);
if (tier == null) {
    throw new ProtocolException("unknown tier code " + tierCode + " at item " + i);
}
```

A `ProtocolException` mid-body kills the **entire snapshot**, not the offending item — so one
chirp-tier notification would silently destroy every other notification travelling with it. That is
precisely the "incompatible change to message bodies" the version field exists to catch.

The "ignore unknown frame types" rule in `LinkServerService.dispatch` does not help here. It
operates on frame *types*; this is a malformed frame *body*, one layer down.

| Combination | Behaviour |
|---|---|
| New phone, old Glass | Version mismatch. Glass rejects the frame at `LinkServerService.java:272` and surfaces the mismatch state. Loud and immediate. |
| Old phone, new Glass | Same mismatch, caught at `LinkReader.java:68`. |
| New both | Works. |

**Operational consequence:** both APKs must be reinstalled together. A half-updated pair is a dead
link, not a degraded one. This is the correct failure — a visibly broken link beats notifications
vanishing without explanation — but it must be in the release notes and the README.

### 4.3 `SnapshotCodec` needs no change

The tier is already encoded as a single byte and decoded through `Tier.fromCode`. Code 3 travels the
existing path. No new field, no framing change, no `MAX_*` cap to revisit.

---

## 5. Phone implementation

### 5.1 `AllowlistStore` — no format change

The stored encoding is `"packageName|tierCode"`, where the code is written as an integer and read
back through `Tier.fromCode`. Tier 3 round-trips through the existing format with no migration, and
existing saved rules (codes 1 and 2) continue to decode unchanged.

The malformed-entry guard in `decode` already skips unparseable rows rather than throwing, so even a
hand-corrupted preference cannot stop the service booting. That behaviour is preserved and tested.

### 5.2 `AllowlistActivity` — one more rung on the cycle

The activity is already a per-app tier cycler. The tap sequence gains a state:

| Current | Tap → |
|---|---|
| *not listed* | `QUEUE` |
| `QUEUE` | `INTERRUPT` |
| `INTERRUPT` | `INTERRUPT_CHIRP` |
| `INTERRUPT_CHIRP` | *not listed* |

`describe()` gains a label for the new state — "Interrupts + chirps". Ordering is deliberate:
escalating loudness, with removal at the end of the cycle, matching what is already there.

This is the entire per-app opt-in surface. No new screen, no new toggle, no second axis.

### 5.3 `SbnMapper` and `SnapshotBuilder` are untouched

Neither branches on tier; they resolve it from the allowlist and pass it through. Confirmed by
inspection.

---

## 6. Glass implementation

### 6.1 `ChirpTone` — synthesis, no Android types

```java
static short[] render(int startHz, int endHz, int ms, int sampleRate)
```

A sine sweep with a raised-cosine attack and decay. Two details carry weight:

- **The envelope is not decoration.** A burst starting or ending mid-cycle produces a click, and on
  a transducer pressed against the skull that click is more noticeable than the tone.
- **Phase is accumulated per sample**, not computed from the sample index. Computing phase as
  `2π·f(i)·i/rate` with a varying `f` produces a discontinuous, audibly wrong sweep. This is the one
  place in the feature where a plausible-looking implementation is silently incorrect, and it is why
  this class holds no Android types: it is unit tested on the host.

Defaults, from the tone selected on hardware during the spike:

| Constant | Value |
|---|---|
| Start frequency | 800 Hz |
| End frequency | 2400 Hz |
| Duration | 150 ms |
| Sample rate | 44100 Hz |

### 6.2 `ChirpPlayer` — the Android half

Holds the rendered buffer, synthesized once on first use and cached (~13 KB at the values above).
Creates an `AudioTrack` on `STREAM_NOTIFICATION` per play, writes, plays, releases.

A singleton reached through `GlassNotify.chirp(context)`, mirroring the existing
`GlassNotify.overlay(context)`. One player for the process, for the same reason there is one
overlay.

Public surface is one method:

```java
void playIfNeeded(Tier tier)
```

which no-ops unless `tier.chirps()`. Keeping the tier check inside the player means both call sites
stay one line and the predicate remains the single source of truth.

### 6.3 Where it hooks in

Two existing call sites gain one line beside `overlay.show(interrupt)`:

- `LinkServerService.java:347` — the real path.
- `DebugInjectReceiver.java:91` — the debug path.

```java
GlassNotify.chirp(context).playIfNeeded(interrupt.tier);
```

**The chirp deliberately does not live inside `InterruptOverlay.show()`.** That class is windowing;
folding an audio device into it would mean neither the card nor the sound could be tested or changed
independently, and a `WindowManager` failure and an `AudioTrack` failure would share a fate. The
cost is two call sites instead of one. There are exactly two, one of which is debug-only, and a
coordinating class to unify them earns its keep only if a third appears.

### 6.4 Threading

Playback runs on a background thread. `AudioTrack.write()` on a `MODE_STATIC` track blocks, both
call sites are on the main thread, and the interrupt card must never wait on the speaker. The link
reader thread must never wait on it either.

Ordering between card and sound is not synchronized and does not need to be — a few milliseconds of
skew between a card appearing and a tone starting is imperceptible.

### 6.5 Volume

The chirp plays on `STREAM_NOTIFICATION` at full scale. Level is controlled by the stream, not by
scaling the PCM.

The first time `ChirpPlayer` is constructed after install, Glass sets `STREAM_NOTIFICATION` to
**5 of 7** via `AudioManager.setStreamVolume`, guarded by a boolean in Glass-side SharedPreferences
so the write happens exactly once for the life of the install and never overwrites a later manual
adjustment. Construction is the right moment because it is reached from both the real and debug
paths, and only when a chirp is actually about to be needed.

Two facts force this design:

- **The volume keys are unmapped on this AOSP build.** `KEYCODE_VOLUME_UP` does nothing. Without a
  programmatic write there is no way to set the level at all.
- **The device ships at maximum.** `dumpsys audio` reports `STREAM_NOTIFICATION` as
  `2 (speaker): 7` against `Max: 7`. Every tone auditioned during the spike was full-scale, so 5 is
  a deliberate step down from what was heard and approved.

Retuning afterwards needs no rebuild:

```sh
adb -s 0123456789ABCDEF shell settings put system volume_notification 5
```

This is a persistent global write to the device, accepted because Glass is single-purpose and this
app is effectively its only sound source.

### 6.6 Failure is silent, never fatal

If `AudioTrack` fails to initialize, `ChirpPlayer` logs a warning and returns. The card still shows.
The track is released on every path, including failure.

An audio fault must not be able to take down the link service — the same principle as the existing
`catch (RuntimeException)` around `windowManager.addView`, which exists so a windowing failure
cannot kill the connection.

---

## 7. What the user sees

1. Erin marks an app as "Interrupts + chirps" in the phone's allowlist screen.
2. That app posts a notification.
3. The phone tiers it `INTERRUPT_CHIRP` and sends it in the next snapshot.
4. Glass shows the interrupt card for seven seconds and plays one 150 ms rising chirp.

Apps left at `INTERRUPT` behave exactly as they do today.

---

## 8. Testing

Host unit tests, alongside the existing `glass/src/test` suite:

- **`ChirpToneTest`** — the only component with real math.
  - Frame count equals `sampleRate × ms / 1000`.
  - No sample clips past `Short.MAX_VALUE`.
  - First and last samples sit near zero, proving the envelope suppresses the click.
  - Zero-crossing intervals shrink monotonically across a rising sweep, proving the phase
    accumulator sweeps continuously rather than jumping (§6.1).
  - A 1 ms burst does not divide by zero in the ramp guard.
- **`TierTest`** — `fromCode(3)` resolves; `fromCode(4)` returns null; `interrupts()` and `chirps()`
  are correct for all three values. Guards the code-vs-ordinal invariant.
- **`InterruptPolicyTest`** (extend) — a chirp-tier item is selected as an interrupt, and
  storm-collapse still picks the newest across *mixed* tiers. Without the first of these, the
  feature ships as "it beeps but no card appears".
- **`AllowlistStoreTest`** (extend) — chirp tier round-trips, and legacy rows with codes 1 and 2
  still decode.
- **`SnapshotCodecTest`** (extend) — round-trip carrying the new tier.

### 8.1 What the suite cannot cover

`AudioTrack` is hardware. Mocking it would prove only that the mock works. Everything from the
`AudioTrack` boundary outward — that the tone is audible, at a reasonable level, through the
transducer — is device verification, in this order:

1. Install **both** APKs. The version bump makes a half-updated pair a dead link (§4.2).
2. `scripts/fake-notify.sh "Signal" "Jordan Reyes" "test" INTERRUPT_CHIRP` → card **and** chirp.
   The script passes the tier through unvalidated and `DebugInjectReceiver` resolves it by name via
   `Tier.valueOf`, so neither needs changing.
3. `INTERRUPT` still shows a card **silently**; `QUEUE` still does neither. These negative cases
   catch a botched predicate.
4. End-to-end: mark a real app as chirp-tier on the phone and have it notify.
5. Confirm the first-run volume write landed at 5, and that a later manual `settings put` survives a
   service restart.

---

## 9. Known risks

### 9.1 Bystander audibility is untested

Whether someone standing next to Erin can hear the chirp was never measured. Bone conduction
transducers leak, and leakage worsens at higher frequencies — the sweep tops out at 2400 Hz, well
into the range where leakage is plausible.

This is a deliberate acceptance, not an oversight. A leakage test needs a second person in the room
and none was available, so it was not attempted rather than attempted and passed — the distinction
matters if this ships and later turns out to be audible.

Two things reduce the risk. The shipped level is 5 of 7, a step down from the full-scale tones
auditioned during the spike. And the chirp is 150 ms, short enough that even an audible leak reads
as a click rather than a recognisable alert.

If it does prove audible in use, the fix is the constants in §6.1 — lowering the 2400 Hz ceiling is
the first lever, since leakage worsens with frequency — or the first-run level in §6.5. No
structural change is implied either way.

### 9.2 No chirp cooldown

`InterruptPolicy` already collapses each snapshot to a single winner, so a chatty thread cannot
produce more than one chirp per snapshot. Back-to-back snapshots could still chirp in quick
succession.

Rate limiting is deliberately not built. It adds policy — a window length, whether the card still
shows when the sound is suppressed, whether the suppressed one is remembered — for a problem that
may not exist in practice. If it becomes annoying, it is a small, well-isolated addition to
`ChirpPlayer`.

---

## 10. Documentation to correct

The README describes a two-tier system in several places. All must be updated:

| Location | Correction |
|---|---|
| Line 198 | Snapshot field description: tier is `INTERRUPT`, `QUEUE`, **or `INTERRUPT_CHIRP`**. |
| Line 344 | Setup instructions naming the tiers to assign during first-run testing. |
| Lines 450-454 | `fake-notify.sh` usage examples; add a chirp-tier example. |
| Line 619 | Tuning-constants table; add the `ChirpTone` frequency/duration constants and the first-run volume level, both marked as tuned-on-hardware starting values. |
| Protocol section | `Protocol.VERSION` is now 2, and both APKs must be reinstalled together. |

`scripts/fake-notify.sh` needs no code change; its usage comment on line 5 should gain a chirp
example.

---

## 11. Rejected alternatives

### 11.1 A `chirp` boolean on `NotificationItem`

Sound as an independent axis: any tier could chirp. Better modelling in the abstract, and rejected
as premature. It requires a new wire field in `SnapshotCodec`, a third component in the
`AllowlistStore` encoding (with a real migration for existing rules), and a genuinely new UI
affordance — the cycler becomes a cycler *plus* a toggle. All to express combinations, such as a
silent-but-chirping queue item, that have no described use.

The version bump is required either way, so it buys no compatibility either.

### 11.2 A Glass-side opt-in list keyed by `appLabel`

The cheapest option: no wire change, no phone change, no version bump. Glass keeps its own opt-in
set and matches on the `appLabel` already present in every item.

Rejected on two counts. It splits notification policy across two devices when the architecture
deliberately centralizes it on the phone — `Tier`'s own documentation reads "Decided on the phone."
And `appLabel` is a *display* string, truncated to `MAX_APP_LABEL_CHARS` (24), resolved from the
app's own manifest; using it as an identity key is fragile in a way `packageName` is not.

### 11.3 Chirping every `INTERRUPT`

Considered first, and the simplest possible design — no new tier, no protocol change, no version
bump. Rejected because it removes a choice rather than adding one: every notification already worth
lighting up the prism would also be worth making noise about, with no way to keep an app visible but
silent. The per-app distinction is the point of the feature.

### 11.4 A vibration motor alert

Not rejected on design grounds. Impossible; see §3.1.
