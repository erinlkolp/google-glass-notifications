# Glass Chirp Alert Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a per-app opt-in notification tier that plays a short rising tone through the Glass bone conduction transducer alongside the interrupt card.

**Architecture:** A third `Tier` value, `INTERRUPT_CHIRP(3)`, carries the opt-in from the phone's existing allowlist UI across the existing wire format — no new protocol field. On Glass, tone synthesis (`ChirpTone`, pure maths, host-tested) is separated from playback (`ChirpPlayer`, `AudioTrack`), and playback is invoked beside the two existing `overlay.show()` call sites rather than inside the overlay itself.

**Tech Stack:** Java 8 source level, Android API 22 (AOSP 5.1.1), JUnit 4.13.2, Gradle Kotlin DSL. Three modules: `wire` (protocol, no Android types), `phone`, `glass`.

**Spec:** `docs/superpowers/specs/2026-08-13-glass-chirp-alert-design.md`

## Global Constraints

- **Java 8 source/target, pinned for the life of the project** (`sourceCompatibility = JavaVersion.VERSION_1_8`). The Glass device is API 22.
- **No lambdas or method references.** The codebase uses anonymous inner classes throughout (e.g. `new Runnable() { ... }`); match that style.
- **No diamond operator.** The codebase writes explicit type arguments: `new HashMap<String, Tier>()`, not `new HashMap<>()`.
- **The `wire` module must contain no Android imports.** This is enforced by `wire/src/test/java/dev/erinlkolp/glassnotify/wire/NoAndroidImportsTest.java` and will fail the build if violated.
- **Tier wire codes are protocol, not implementation detail.** `INTERRUPT=1`, `QUEUE=2`, and the new `INTERRUPT_CHIRP=3`. Never renumber.
- **Log tag is `"GlassNotify"`** in every module.
- **Audio constants** (spec §6.1): start 800 Hz, end 2400 Hz, duration 150 ms, sample rate 44100 Hz.
- **First-run notification volume index is 5** of a maximum of 7 (spec §6.5).
- Run all tests with `./gradlew test`. Per-module: `./gradlew :wire:test`, `./gradlew :glass:testDebugUnitTest`, `./gradlew :phone:testDebugUnitTest`.

---

### Task 1: `Tier` gains `INTERRUPT_CHIRP` and behaviour predicates

**Files:**
- Modify: `wire/src/main/java/dev/erinlkolp/glassnotify/wire/Tier.java`
- Test: `wire/src/test/java/dev/erinlkolp/glassnotify/wire/TierTest.java`

**Interfaces:**
- Consumes: nothing (first task).
- Produces: `Tier.INTERRUPT_CHIRP` (wire code `3`); `boolean Tier.interrupts()`; `boolean Tier.chirps()`. Every later task depends on these three names.

- [ ] **Step 1: Write the failing tests**

Add to `TierTest.java`. Note the existing `codesAreStableOnTheWire` test must also gain the new assertion:

```java
    @Test
    public void chirpTierHasStableCodeThree() {
        assertEquals(3, Tier.INTERRUPT_CHIRP.code);
    }

    @Test
    public void bothInterruptTiersLightUpThePrism() {
        assertTrue(Tier.INTERRUPT.interrupts());
        assertTrue(Tier.INTERRUPT_CHIRP.interrupts());
        assertFalse(Tier.QUEUE.interrupts());
    }

    @Test
    public void onlyTheChirpTierMakesSound() {
        assertTrue(Tier.INTERRUPT_CHIRP.chirps());
        assertFalse(Tier.INTERRUPT.chirps());
        assertFalse(Tier.QUEUE.chirps());
    }
```

Add the new assertion to the existing `codesAreStableOnTheWire` test body:

```java
        assertEquals(3, Tier.INTERRUPT_CHIRP.code);
```

Add these imports to the top of the file, beside the existing `assertEquals` / `assertNull` imports:

```java
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :wire:test`
Expected: FAIL — compilation error, `cannot find symbol: variable INTERRUPT_CHIRP`.

- [ ] **Step 3: Write minimal implementation**

Replace the enum body of `Tier.java`:

```java
public enum Tier {

    /** Wakes the Glass display briefly. */
    INTERRUPT(1),

    /** Lands silently; visible only when the queue is opened. */
    QUEUE(2),

    /** Wakes the Glass display and plays a short tone. */
    INTERRUPT_CHIRP(3);

    /** Stable on-the-wire code. Not the ordinal — reordering the enum must be safe. */
    public final int code;

    Tier(int code) {
        this.code = code;
    }

    /** True for tiers that light up the prism. */
    public boolean interrupts() {
        return this != QUEUE;
    }

    /** True for tiers that also make a sound. */
    public boolean chirps() {
        return this == INTERRUPT_CHIRP;
    }

    /** Returns null for an unrecognised code; decoders convert that to a ProtocolException. */
    public static Tier fromCode(int code) {
        for (Tier tier : values()) {
            if (tier.code == code) {
                return tier;
            }
        }
        return null;
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :wire:test`
Expected: PASS, including the pre-existing `roundTripsThroughItsCode` (which iterates `values()` and now covers the new tier for free) and `NoAndroidImportsTest`.

- [ ] **Step 5: Commit**

```bash
git add wire/src/main/java/dev/erinlkolp/glassnotify/wire/Tier.java \
        wire/src/test/java/dev/erinlkolp/glassnotify/wire/TierTest.java
git commit -m "feat(wire): add the INTERRUPT_CHIRP tier"
```

---

### Task 2: Bump `Protocol.VERSION` to 2

**Files:**
- Modify: `wire/src/main/java/dev/erinlkolp/glassnotify/wire/Protocol.java:9`
- Test: `wire/src/test/java/dev/erinlkolp/glassnotify/wire/SnapshotCodecTest.java`

**Interfaces:**
- Consumes: `Tier.INTERRUPT_CHIRP` from Task 1.
- Produces: `Protocol.VERSION == 2`. Nothing later reads this directly; both link classes already compare against it.

**Why this bump is required** (spec §4.2): an old Glass build receiving tier code 3 does not skip the item — `SnapshotCodec.decode` throws `ProtocolException`, which destroys the **entire snapshot**. The version check turns that silent data loss into an immediate, visible refusal.

- [ ] **Step 1: Write the failing test**

Add to `SnapshotCodecTest.java`:

```java
    @Test
    public void roundTripsTheChirpTier() throws IOException {
        NotificationItem chirping = new NotificationItem("k1", "Signal", "Jordan Reyes",
                "are you still good for 7pm?", 1000L, Tier.INTERRUPT_CHIRP);
        Snapshot original = new Snapshot(7L, Arrays.asList(chirping));

        Snapshot decoded = SnapshotCodec.decode(SnapshotCodec.encode(original));

        assertEquals(1, decoded.items.size());
        assertEquals(Tier.INTERRUPT_CHIRP, decoded.items.get(0).tier);
    }
```

If `java.util.Arrays` is not already imported in this file, add `import java.util.Arrays;`.

- [ ] **Step 2: Run test to verify it passes already**

Run: `./gradlew :wire:test --tests '*SnapshotCodecTest*'`
Expected: **PASS**. This test is a characterization test, not a red-green test — the tier is already a byte on the wire, so no codec change is needed (spec §4.3). It is worth keeping because it locks that property down: if anyone later changes the tier encoding, this fails.

- [ ] **Step 3: Bump the version**

In `Protocol.java`, change line 9:

```java
    /** Bumped on any incompatible change to framing or message bodies. */
    public static final int VERSION = 2;
```

- [ ] **Step 4: Run the full suite**

Run: `./gradlew test`
Expected: PASS. Both sides read `Protocol.VERSION` from this one constant, so nothing hard-codes `1`.

- [ ] **Step 5: Commit**

```bash
git add wire/src/main/java/dev/erinlkolp/glassnotify/wire/Protocol.java \
        wire/src/test/java/dev/erinlkolp/glassnotify/wire/SnapshotCodecTest.java
git commit -m "feat(wire): bump protocol to version 2 for the chirp tier

An unknown tier code throws mid-body in SnapshotCodec.decode, taking out
the whole snapshot rather than the offending item, so old and new builds
must refuse each other outright."
```

---

### Task 3: `InterruptPolicy` shows a card for chirp-tier items

**Files:**
- Modify: `glass/src/main/java/dev/erinlkolp/glassnotify/glass/InterruptPolicy.java:41`
- Test: `glass/src/test/java/dev/erinlkolp/glassnotify/glass/InterruptPolicyTest.java`

**Interfaces:**
- Consumes: `Tier.interrupts()` from Task 1.
- Produces: no new API. `InterruptPolicy.selectInterrupt(Snapshot, Snapshot)` keeps its signature.

**Why this matters:** without it the feature ships as "it beeps but no card appears" — `selectInterrupt` currently skips anything that is not exactly `Tier.INTERRUPT`, so a chirp-tier item would be filtered out before it ever reaches the overlay.

- [ ] **Step 1: Write the failing tests**

Add to `InterruptPolicyTest.java`. The file already has `item(...)`, `snapshot(...)` and `empty()` helpers — reuse them:

```java
    @Test
    public void aNewChirpTierItemAlsoInterrupts() {
        NotificationItem incoming = item("a", Tier.INTERRUPT_CHIRP, 100L);

        assertEquals(incoming, InterruptPolicy.selectInterrupt(empty(), snapshot(incoming)));
    }

    @Test
    public void aStormOfMixedTiersCollapsesToTheNewest() {
        NotificationItem older = item("a", Tier.INTERRUPT, 100L);
        NotificationItem newest = item("b", Tier.INTERRUPT_CHIRP, 300L);
        NotificationItem middle = item("c", Tier.INTERRUPT, 200L);

        assertEquals(newest,
                InterruptPolicy.selectInterrupt(empty(), snapshot(older, newest, middle)));
    }

    @Test
    public void queuedItemsStillNeverInterrupt() {
        NotificationItem incoming = item("a", Tier.QUEUE, 100L);

        assertNull(InterruptPolicy.selectInterrupt(empty(), snapshot(incoming)));
    }
```

- [ ] **Step 2: Run tests to verify the first two fail**

Run: `./gradlew :glass:testDebugUnitTest --tests '*InterruptPolicyTest*'`
Expected: `aNewChirpTierItemAlsoInterrupts` FAILS with `expected:<...> but was:<null>`. `queuedItemsStillNeverInterrupt` passes already — it is the negative case guarding the change.

- [ ] **Step 3: Write minimal implementation**

In `InterruptPolicy.java`, change the tier check inside the loop:

```java
            if (!item.tier.interrupts()) {
                continue;
            }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :glass:testDebugUnitTest`
Expected: PASS, all of them.

- [ ] **Step 5: Commit**

```bash
git add glass/src/main/java/dev/erinlkolp/glassnotify/glass/InterruptPolicy.java \
        glass/src/test/java/dev/erinlkolp/glassnotify/glass/InterruptPolicyTest.java
git commit -m "feat(glass): show an interrupt card for chirp-tier items"
```

---

### Task 4: Phone allowlist stores and cycles the chirp tier

**Files:**
- Modify: `phone/src/main/java/dev/erinlkolp/glassnotify/phone/AllowlistActivity.java` (the `cycle` and `describe` methods)
- Test: `phone/src/test/java/dev/erinlkolp/glassnotify/phone/AllowlistCodecTest.java`

**Interfaces:**
- Consumes: `Tier.INTERRUPT_CHIRP` from Task 1.
- Produces: no new API. `AllowlistStore` keeps its existing `rules()` / `put()` / `remove()` signatures and its `"packageName|tierCode"` storage format.

**Note:** `AllowlistStore` itself needs **no change**. It encodes `tier.code` as an integer and decodes through `Tier.fromCode`, so code 3 round-trips through the existing format and existing saved rules keep working (spec §5.1). The tests below prove that rather than assuming it.

**Watch out:** `describe()` currently ends with a ternary that returns `"Queued silently"` for *anything* that is not `INTERRUPT`. Left alone it would mislabel a chirping app as silent — the exact opposite of the truth.

- [ ] **Step 1: Write the failing tests**

Add to `AllowlistCodecTest.java`:

```java
    @Test
    public void roundTripsTheChirpTier() {
        Map<String, Tier> rules = new HashMap<String, Tier>();
        rules.put("com.discord", Tier.INTERRUPT_CHIRP);

        Map<String, Tier> decoded = AllowlistStore.decode(AllowlistStore.encode(rules));

        assertEquals(1, decoded.size());
        assertEquals(Tier.INTERRUPT_CHIRP, decoded.get("com.discord"));
    }

    @Test
    public void rulesSavedBeforeTheChirpTierExistedStillDecode() {
        // Hand-written in the on-disk format, as an older build would have left
        // it: rules saved against protocol version 1 must survive the upgrade.
        Set<String> legacy = new HashSet<String>();
        legacy.add("org.thoughtcrime.securesms|1");
        legacy.add("com.slack|2");

        Map<String, Tier> decoded = AllowlistStore.decode(legacy);

        assertEquals(2, decoded.size());
        assertEquals(Tier.INTERRUPT, decoded.get("org.thoughtcrime.securesms"));
        assertEquals(Tier.QUEUE, decoded.get("com.slack"));
    }
```

- [ ] **Step 2: Run tests to verify they pass**

Run: `./gradlew :phone:testDebugUnitTest --tests '*AllowlistCodecTest*'`
Expected: **PASS** — both are characterization tests proving the format needs no migration. If `rulesSavedBeforeTheChirpTierExistedStillDecode` fails, stop: something changed the storage format and the spec's no-migration claim is wrong.

- [ ] **Step 3: Extend the UI cycle**

In `AllowlistActivity.java`, replace the `cycle` method:

```java
    private void cycle(String packageName) {
        Map<String, Tier> rules = store.rules();
        Tier current = rules.get(packageName);
        if (current == null) {
            store.put(packageName, Tier.QUEUE);
        } else if (current == Tier.QUEUE) {
            store.put(packageName, Tier.INTERRUPT);
        } else if (current == Tier.INTERRUPT) {
            store.put(packageName, Tier.INTERRUPT_CHIRP);
        } else {
            store.remove(packageName);
        }
    }
```

and replace `describe` — the ternary must become an explicit three-way, or a chirping app reads as "Queued silently":

```java
    private static String describe(Tier tier) {
        if (tier == null) {
            return "Not shown";
        }
        if (tier == Tier.INTERRUPT_CHIRP) {
            return "Interrupts + chirps";
        }
        return tier == Tier.INTERRUPT ? "Interrupts" : "Queued silently";
    }
```

- [ ] **Step 4: Verify it compiles and the suite is green**

Run: `./gradlew test :phone:assembleDebug`
Expected: PASS and BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add phone/src/main/java/dev/erinlkolp/glassnotify/phone/AllowlistActivity.java \
        phone/src/test/java/dev/erinlkolp/glassnotify/phone/AllowlistCodecTest.java
git commit -m "feat(phone): let an app be marked as interrupting and chirping"
```

---

### Task 5: `ChirpTone` renders the alert as PCM

**Files:**
- Create: `glass/src/main/java/dev/erinlkolp/glassnotify/glass/ChirpTone.java`
- Test: `glass/src/test/java/dev/erinlkolp/glassnotify/glass/ChirpToneTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `ChirpTone.render(int startHz, int endHz, int ms, int sampleRate)` returning `short[]`; `ChirpTone.renderDefault()` returning `short[]`; and the public constants `ChirpTone.SAMPLE_RATE` (44100), `ChirpTone.START_HZ` (800), `ChirpTone.END_HZ` (2400), `ChirpTone.DURATION_MS` (150). Task 6 uses `renderDefault()`, `SAMPLE_RATE` and `DURATION_MS`.

This class holds **no Android types** so the maths runs on the host JVM. Two details carry weight (spec §6.1): the raised-cosine envelope prevents a start/end click, which on a transducer against the skull is more noticeable than the tone itself; and phase is **accumulated per sample** rather than computed from the sample index, because `2π·f(i)·i/rate` with a varying `f` produces a discontinuous, audibly wrong sweep.

- [ ] **Step 1: Write the failing tests**

Create `glass/src/test/java/dev/erinlkolp/glassnotify/glass/ChirpToneTest.java`:

```java
package dev.erinlkolp.glassnotify.glass;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ChirpToneTest {

    /** Counts positive-going zero crossings, which is proportional to frequency. */
    private static int upwardCrossings(short[] pcm, int from, int to) {
        int crossings = 0;
        for (int i = from + 1; i < to; i++) {
            if (pcm[i - 1] <= 0 && pcm[i] > 0) {
                crossings++;
            }
        }
        return crossings;
    }

    @Test
    public void frameCountMatchesTheRequestedDuration() {
        assertEquals(6615, ChirpTone.render(800, 2400, 150, 44100).length);
        assertEquals(44100, ChirpTone.render(800, 2400, 1000, 44100).length);
    }

    @Test
    public void theEnvelopeSilencesBothEdges() {
        // A burst that starts or ends mid-cycle clicks, and against the skull
        // the click is more noticeable than the tone.
        short[] pcm = ChirpTone.renderDefault();

        assertEquals(0, pcm[0]);
        assertEquals(0, pcm[pcm.length - 1]);
    }

    @Test
    public void theToneReachesUsefulAmplitudeWithoutClipping() {
        short[] pcm = ChirpTone.renderDefault();

        int peak = 0;
        for (int i = 0; i < pcm.length; i++) {
            peak = Math.max(peak, Math.abs(pcm[i]));
        }

        assertTrue("peak " + peak + " should be loud enough to hear",
                peak > (int) (0.8 * Short.MAX_VALUE));
        assertTrue("peak " + peak + " must not clip", peak <= Short.MAX_VALUE);
    }

    @Test
    public void theSweepRisesRatherThanJumping() {
        // Proves the phase accumulator sweeps continuously: the back half of a
        // rising chirp must contain more cycles than the front half.
        short[] pcm = ChirpTone.renderDefault();
        int half = pcm.length / 2;

        int front = upwardCrossings(pcm, 0, half);
        int back = upwardCrossings(pcm, half, pcm.length);

        assertTrue("front " + front + " should have fewer cycles than back " + back,
                back > front);
    }

    @Test
    public void aVeryShortBurstDoesNotDivideByZeroInTheRampGuard() {
        short[] pcm = ChirpTone.render(800, 2400, 1, 44100);

        assertEquals(44, pcm.length);
    }

    @Test
    public void aZeroLengthRequestYieldsNoSamplesRatherThanThrowing() {
        assertEquals(0, ChirpTone.render(800, 2400, 0, 44100).length);
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :glass:testDebugUnitTest --tests '*ChirpToneTest*'`
Expected: FAIL — compilation error, `cannot find symbol: class ChirpTone`.

- [ ] **Step 3: Write the implementation**

Create `glass/src/main/java/dev/erinlkolp/glassnotify/glass/ChirpTone.java`:

```java
package dev.erinlkolp.glassnotify.glass;

/**
 * Renders the alert tone as PCM.
 *
 * Free of Android types, so the maths is unit tested on the host - which
 * matters more here than it looks, because a plausible implementation of the
 * sweep is silently wrong. See render().
 */
public final class ChirpTone {

    public static final int SAMPLE_RATE = 44100;
    public static final int START_HZ = 800;
    public static final int END_HZ = 2400;
    public static final int DURATION_MS = 150;

    private ChirpTone() {
    }

    /** The tone as tuned on hardware. Spec section 6.1. */
    public static short[] renderDefault() {
        return render(START_HZ, END_HZ, DURATION_MS, SAMPLE_RATE);
    }

    /**
     * A sine sweep from startHz to endHz with a raised-cosine attack and decay.
     *
     * Phase is accumulated per sample rather than computed as 2*PI*f(i)*i/rate,
     * which looks equivalent and is not: with a varying f, the latter jumps
     * rather than sweeps.
     */
    public static short[] render(int startHz, int endHz, int ms, int sampleRate) {
        int frames = (int) ((long) sampleRate * ms / 1000L);
        if (frames <= 0) {
            return new short[0];
        }

        short[] pcm = new short[frames];

        // Guarded against zero so a burst shorter than the nominal ramp still
        // renders instead of dividing by zero.
        int ramp = Math.max(1, Math.min(frames / 4, sampleRate / 200));
        double phase = 0.0;

        for (int i = 0; i < frames; i++) {
            double progress = frames == 1 ? 0.0 : (double) i / (frames - 1);
            double frequency = startHz + (endHz - startHz) * progress;
            phase += 2.0 * Math.PI * frequency / sampleRate;

            double envelope = 1.0;
            if (i < ramp) {
                envelope = 0.5 * (1.0 - Math.cos(Math.PI * i / ramp));
            } else if (i >= frames - ramp) {
                envelope = 0.5 * (1.0 - Math.cos(Math.PI * (frames - 1 - i) / ramp));
            }

            pcm[i] = (short) (Math.sin(phase) * envelope * Short.MAX_VALUE);
        }

        return pcm;
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :glass:testDebugUnitTest`
Expected: PASS, all six new tests plus the existing suite.

- [ ] **Step 5: Commit**

```bash
git add glass/src/main/java/dev/erinlkolp/glassnotify/glass/ChirpTone.java \
        glass/src/test/java/dev/erinlkolp/glassnotify/glass/ChirpToneTest.java
git commit -m "feat(glass): render the chirp tone as PCM"
```

---

### Task 6: `ChirpPlayer` plays the tone, and `GlassNotify` holds it

**Files:**
- Create: `glass/src/main/java/dev/erinlkolp/glassnotify/glass/ChirpPlayer.java`
- Modify: `glass/src/main/java/dev/erinlkolp/glassnotify/glass/GlassNotify.java`

**Interfaces:**
- Consumes: `ChirpTone.renderDefault()`, `ChirpTone.SAMPLE_RATE`, `ChirpTone.DURATION_MS` from Task 5; `Tier.chirps()` from Task 1.
- Produces: `ChirpPlayer.playIfNeeded(Tier tier)` (void, safe on any thread, safe with a null tier), and `GlassNotify.chirp(Context context)` returning `ChirpPlayer`. Task 7 calls exactly these two.

**No unit tests here.** `AudioTrack` is hardware; a mock would only prove the mock works (spec §8.1). This task is verified by compiling, and by the device steps in Task 7.

- [ ] **Step 1: Write `ChirpPlayer`**

Create `glass/src/main/java/dev/erinlkolp/glassnotify/glass/ChirpPlayer.java`:

```java
package dev.erinlkolp.glassnotify.glass;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.util.Log;

import dev.erinlkolp.glassnotify.wire.Tier;

/**
 * Plays the chirp through the bone conduction transducer.
 *
 * Glass has no vibration motor and the transducer produces no tactile
 * sensation below its speech band, so this alert is heard rather than felt.
 * Spec section 3.1.
 *
 * Every failure path here is silent and non-fatal: an audio fault must never
 * take down the link service or stop the interrupt card from showing.
 */
public final class ChirpPlayer {

    private static final String TAG = "GlassNotify";

    /** Written once per install, then never again, so a later manual change sticks. */
    static final String KEY_VOLUME_INITIALIZED = "chirp_volume_initialized";

    /**
     * Of a maximum of 7. The device ships at 7, so this is a deliberate step
     * down from the full-scale tones auditioned on hardware. Spec section 6.5.
     */
    static final int INITIAL_VOLUME_INDEX = 5;

    private final AudioManager audioManager;
    private final short[] pcm;

    public ChirpPlayer(Context context, SharedPreferences prefs) {
        this.audioManager = (AudioManager) context.getApplicationContext()
                .getSystemService(Context.AUDIO_SERVICE);
        this.pcm = ChirpTone.renderDefault();
        initializeVolumeOnce(prefs);
    }

    /**
     * Plays the chirp if this tier calls for one. Returns immediately; playback
     * runs on its own thread, because AudioTrack.write() blocks and both
     * callers are on the main thread with a card to draw.
     */
    public void playIfNeeded(Tier tier) {
        if (tier == null || !tier.chirps()) {
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                play();
            }
        }, "glassnotify-chirp").start();
    }

    private void play() {
        AudioTrack track = null;
        try {
            track = new AudioTrack(AudioManager.STREAM_NOTIFICATION, ChirpTone.SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    pcm.length * 2, AudioTrack.MODE_STATIC);

            if (track.getState() == AudioTrack.STATE_UNINITIALIZED) {
                Log.w(TAG, "chirp: AudioTrack would not initialize");
                return;
            }

            track.write(pcm, 0, pcm.length);
            track.play();

            // Releasing a MODE_STATIC track mid-playback truncates the tone, so
            // wait out its length plus a margin before letting finally run.
            Thread.sleep(ChirpTone.DURATION_MS + 100L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            Log.w(TAG, "chirp: playback failed", e);
        } finally {
            if (track != null) {
                track.release();
            }
        }
    }

    /**
     * The Glass volume keys are unmapped on this AOSP build, so without this
     * there is no way to set the level at all. Written once and then left
     * alone, so "adb shell settings put system volume_notification N" sticks.
     */
    private void initializeVolumeOnce(SharedPreferences prefs) {
        if (prefs.getBoolean(KEY_VOLUME_INITIALIZED, false)) {
            return;
        }
        try {
            audioManager.setStreamVolume(
                    AudioManager.STREAM_NOTIFICATION, INITIAL_VOLUME_INDEX, 0);
            Log.i(TAG, "chirp: set initial notification volume to " + INITIAL_VOLUME_INDEX);
        } catch (RuntimeException e) {
            Log.w(TAG, "chirp: could not set initial notification volume", e);
        }
        prefs.edit().putBoolean(KEY_VOLUME_INITIALIZED, true).apply();
    }
}
```

- [ ] **Step 2: Add the singleton accessor**

In `GlassNotify.java`, add the field beside the existing ones:

```java
    private static ChirpPlayer chirp;
```

and add this method after `overlay(Context)`:

```java
    /**
     * One player for the whole process, for the same reason there is one
     * overlay. Constructing it renders the tone and, on the first run after
     * install, sets the notification volume.
     */
    public static synchronized ChirpPlayer chirp(Context context) {
        if (chirp == null) {
            Context app = context.getApplicationContext();
            chirp = new ChirpPlayer(app, app.getSharedPreferences(PREFS, Context.MODE_PRIVATE));
        }
        return chirp;
    }
```

`PREFS` is the existing `"glassnotify"` constant already declared at the top of the class — reuse it, do not add a second preferences file.

- [ ] **Step 3: Verify it compiles and nothing regressed**

Run: `./gradlew test :glass:assembleDebug`
Expected: PASS and BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add glass/src/main/java/dev/erinlkolp/glassnotify/glass/ChirpPlayer.java \
        glass/src/main/java/dev/erinlkolp/glassnotify/glass/GlassNotify.java
git commit -m "feat(glass): play the chirp through the bone conduction driver"
```

---

### Task 7: Wire the chirp into both interrupt paths, and verify on hardware

**Files:**
- Modify: `glass/src/main/java/dev/erinlkolp/glassnotify/glass/LinkServerService.java` (inside `applySnapshot`, around line 347)
- Modify: `glass/src/main/java/dev/erinlkolp/glassnotify/glass/DebugInjectReceiver.java` (inside `notifyUi`, around line 91)

**Interfaces:**
- Consumes: `GlassNotify.chirp(Context)` and `ChirpPlayer.playIfNeeded(Tier)` from Task 6.
- Produces: nothing new.

**Why two call sites and not one inside `InterruptOverlay.show()`** (spec §6.3): `InterruptOverlay` is windowing. Folding an audio device into it would mean neither the card nor the sound could be tested or changed alone, and a `WindowManager` failure and an `AudioTrack` failure would share a fate. There are exactly two sites, one of which is debug-only.

- [ ] **Step 1: Add the call in the real path**

In `LinkServerService.java`, inside the `Runnable` in `applySnapshot`:

```java
                if (interrupt != null) {
                    GlassNotify.chirp(LinkServerService.this).playIfNeeded(interrupt.tier);
                    overlay.show(interrupt);
                }
```

`LinkServerService.this` is needed rather than `this` because the code sits inside an anonymous `Runnable`.

- [ ] **Step 2: Add the call in the debug path**

In `DebugInjectReceiver.java`, inside the `Runnable` in `notifyUi`:

```java
                NotificationItem interrupt = InterruptPolicy.selectInterrupt(previous, next);
                if (interrupt != null) {
                    GlassNotify.chirp(context).playIfNeeded(interrupt.tier);
                    overlay.show(interrupt);
                }
```

`context` is already a `final` parameter of `notifyUi`, so it is usable here as-is.

- [ ] **Step 3: Build and install both APKs**

The protocol bump means a half-updated pair is a dead link, so both must go on together:

```bash
./gradlew :glass:assembleDebug :phone:assembleDebug
adb -s 0123456789ABCDEF install -r glass/build/outputs/apk/debug/glass-debug.apk
adb -s VS9967edd915b install -r phone/build/outputs/apk/debug/phone-debug.apk
```

- [ ] **Step 4: Verify the chirp fires, and that the negative cases stay silent**

Start the Glass app so the link server is running, then, wearing Glass:

```bash
# Should show a card AND chirp
scripts/fake-notify.sh "Signal" "Jordan Reyes" "test" INTERRUPT_CHIRP

# Should show a card and stay SILENT
scripts/fake-notify.sh "Signal" "Jordan Reyes" "test" INTERRUPT

# Should do neither - visible only when the queue is opened
scripts/fake-notify.sh "Signal" "Jordan Reyes" "test" QUEUE
```

The negative cases are the ones that catch a botched predicate. Check the log for the volume write, which should appear exactly once on the first run after install:

```bash
adb -s 0123456789ABCDEF logcat -d -s GlassNotify | grep chirp
```

Expected: `chirp: set initial notification volume to 5` on the first interrupt after install, and never again — including after a service restart.

- [ ] **Step 5: Verify end to end from the phone**

On the phone, open the allowlist screen and tap an app until it reads **"Interrupts + chirps"**. Have that app post a real notification. Glass should show the card and chirp.

Confirm the tap cycle runs Not shown → Queued silently → Interrupts → Interrupts + chirps → Not shown.

- [ ] **Step 6: Commit**

```bash
git add glass/src/main/java/dev/erinlkolp/glassnotify/glass/LinkServerService.java \
        glass/src/main/java/dev/erinlkolp/glassnotify/glass/DebugInjectReceiver.java
git commit -m "feat(glass): chirp when a chirp-tier notification arrives"
```

---

### Task 8: Correct the documentation

**Files:**
- Modify: `README.md` (five locations, listed below)
- Modify: `scripts/fake-notify.sh:5` (usage comment only)

**Interfaces:**
- Consumes: everything above.
- Produces: nothing.

The README describes a two-tier system throughout. `scripts/fake-notify.sh` needs **no code change** — it passes `$4` through unvalidated and `DebugInjectReceiver` resolves it by name via `Tier.valueOf` — only its usage comment is out of date.

- [ ] **Step 1: Update every stale reference**

| Location | Change |
|---|---|
| `README.md:198` | Snapshot field description: tier is `INTERRUPT`, `QUEUE`, **or `INTERRUPT_CHIRP`**. |
| `README.md:344` | First-run setup: mention setting an app to `INTERRUPT_CHIRP` to test the sound. |
| `README.md:450-454` | `fake-notify.sh` examples: add an `INTERRUPT_CHIRP` example beside the existing two. |
| `README.md:619` | Tuning-constants table: add `ChirpTone.START_HZ` / `END_HZ` / `DURATION_MS` and `ChirpPlayer.INITIAL_VOLUME_INDEX`, each marked as a starting value tuned on hardware, with the same file/line format the existing rows use. |
| README protocol section | `Protocol.VERSION` is now **2**, and both APKs must be reinstalled together — a half-updated pair is a dead link, not a degraded one. |
| `scripts/fake-notify.sh:5` | Usage comment: add an `INTERRUPT_CHIRP` example. |

- [ ] **Step 2: Record what is not verified**

Add to the README's known-limitations or open-questions section, in the same voice as the existing entries:

```markdown
- Whether the chirp is audible to people nearby. Bone conduction transducers
  leak, and leakage worsens with frequency — the sweep tops out at 2400 Hz.
  Testing this needs a second listener and it has not been done, so this is
  untested rather than verified quiet. If it proves audible in use, lower
  `ChirpTone.END_HZ` first, then `ChirpPlayer.INITIAL_VOLUME_INDEX`.
```

- [ ] **Step 3: Verify the whole suite one last time**

Run: `./gradlew test`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add README.md scripts/fake-notify.sh
git commit -m "docs: document the chirp tier and the version 2 protocol"
```

---

## Self-Review

**Spec coverage:**

| Spec section | Task |
|---|---|
| §4.1 new tier + predicates | Task 1 |
| §4.2 `VERSION` → 2 | Task 2 |
| §4.3 no codec change | Task 2 (characterization test) |
| §5.1 allowlist format unchanged | Task 4 (legacy-decode test) |
| §5.2 UI cycle + `describe()` | Task 4 |
| §5.3 `SbnMapper`/`SnapshotBuilder` untouched | No task needed — nothing to change |
| §6.1 `ChirpTone` | Task 5 |
| §6.2 `ChirpPlayer` | Task 6 |
| §6.3 call sites | Task 7 |
| §6.4 threading | Task 6 (background thread in `playIfNeeded`) |
| §6.5 volume | Task 6 (`initializeVolumeOnce`) |
| §6.6 silent failure | Task 6 (try/catch/finally) |
| §7 user-visible flow | Task 7 Step 5 |
| §8 testing | Tasks 1-5, device steps in Task 7 |
| §9 known risks | Task 8 Step 2 |
| §10 documentation | Task 8 |
| §3.3 existing paths unchanged | Task 3 (`queuedItemsStillNeverInterrupt`), Task 7 Step 4 negative cases |

No gaps.

**Type consistency:** `Tier.interrupts()` / `Tier.chirps()` (Task 1) are used with those exact names in Tasks 3, 4 and 6. `ChirpTone.renderDefault()`, `SAMPLE_RATE` and `DURATION_MS` (Task 5) are used with those names in Task 6. `GlassNotify.chirp(Context)` and `playIfNeeded(Tier)` (Task 6) are used with those names in Task 7. Consistent throughout.

**Placeholder scan:** no TBDs, no "handle errors appropriately", no "similar to Task N". Every code step carries its actual content.
