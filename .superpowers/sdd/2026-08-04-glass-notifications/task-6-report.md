# Task 6 Report: Card rendering and the queue screen

## What was implemented

All files exactly as transcribed from the brief, plus the two files the brief's Files header omitted but its steps required:

- `glass/src/main/java/dev/erinlkolp/glassnotify/glass/TouchSample.java` — immutable touch sample (x, y, timeMs), zero Android imports.
- `glass/src/main/java/dev/erinlkolp/glassnotify/glass/Swipe.java` — `NONE/TAP/FORWARD/BACK` enum, zero Android imports.
- `glass/src/main/java/dev/erinlkolp/glassnotify/glass/SwipeDetector.java` — `begin/move/end/cancel`, `SWIPE_MIN_DX`, `HORIZONTAL_DOMINANCE`, `TAP_MAX_MS`; zero Android imports so the decision logic runs on the host JVM.
- `glass/src/main/java/dev/erinlkolp/glassnotify/glass/CardRenderer.java` — `interruptCard`, `queueCard`, `messageCard`; pure black/white, all sizes `TypedValue.COMPLEX_UNIT_DIP`.
- `glass/src/main/java/dev/erinlkolp/glassnotify/glass/Ages.java` (brief Step 6) — `describe(Context, long postedAtMs, long nowMs)` returning short all-caps age strings.
- `glass/src/main/java/dev/erinlkolp/glassnotify/glass/QueueActivity.java` — read-only queue browser; `applyImmersiveFlags()` called from both `onCreate` and `onWindowFocusChanged`; no dismiss, no action firing, no long-press handling.
- `glass/src/main/java/dev/erinlkolp/glassnotify/glass/GlassNotify.java` (brief Step 8) — process-wide singleton holder for `SnapshotStore` and `PeerPin`.
- `glass/src/test/java/dev/erinlkolp/glassnotify/glass/SwipeDetectorTest.java` — 9 tests, transcribed verbatim.
- `glass/src/main/AndroidManifest.xml` — converted the self-closing `<application ... />` into an open/close pair, nested the `QueueActivity` `<activity>` block (MAIN/LAUNCHER intent filter) inside it exactly as given.

No other files were touched. No files outside this list were created.

## What was tested and results

Ran per the brief's TDD order, then the full verification command.

### TDD evidence

**RED** — `./gradlew :glass:testDebugUnitTest --tests '*SwipeDetectorTest*'`

Failed to compile, as expected, because `SwipeDetector`, `Swipe`, and `TouchSample` did not exist yet:

```
error: cannot find symbol
        assertEquals(Swipe.BACK, gesture(400f, 400f - SwipeDetector.SWIPE_MIN_DX - 10f, 100f, 100f, 250L));
                     ^
  symbol:   variable Swipe
  location: class SwipeDetectorTest
...
23 errors
...
FAILURE: Build failed with an exception.
> Task :glass:compileDebugUnitTestJavaWithJavac FAILED
```

This is the expected failure: the test file references three classes that had not been written yet (Step 3/4 of the brief comes after Step 2's failing run).

**GREEN** — after writing `TouchSample.java`, `Swipe.java`, `SwipeDetector.java`, re-ran the same command:

```
> Task :glass:testDebugUnitTest
BUILD SUCCESSFUL in 980ms
```

### Full verification

`./gradlew :glass:testDebugUnitTest :glass:assembleDebug`

```
> Task :glass:compileDebugJavaWithJavac
Note: .../QueueActivity.java uses or overrides a deprecated API.  (setSystemUiVisibility — expected, matches the brief exactly)
> Task :glass:testDebugUnitTest
> Task :glass:assembleDebug
BUILD SUCCESSFUL in 1s
38 actionable tasks: 11 executed, 27 up-to-date
```

Test count confirmed from `glass/build/test-results/testDebugUnitTest/*.xml`:

- `QueueCursorTest`: 8 tests
- `StalenessTest`: 4 tests
- `SwipeDetectorTest`: 9 tests
- **Total: 21 tests**, matching the brief's Step 10 expectation exactly. All passed. `assembleDebug` succeeded, proving the Android code (including `CardRenderer`, `QueueActivity`, `GlassNotify`, manifest) compiles against API 22/compileSdk 34.

## Files changed

```
 M glass/src/main/AndroidManifest.xml
 A glass/src/main/java/dev/erinlkolp/glassnotify/glass/Ages.java
 A glass/src/main/java/dev/erinlkolp/glassnotify/glass/CardRenderer.java
 A glass/src/main/java/dev/erinlkolp/glassnotify/glass/GlassNotify.java
 A glass/src/main/java/dev/erinlkolp/glassnotify/glass/QueueActivity.java
 A glass/src/main/java/dev/erinlkolp/glassnotify/glass/Swipe.java
 A glass/src/main/java/dev/erinlkolp/glassnotify/glass/SwipeDetector.java
 A glass/src/main/java/dev/erinlkolp/glassnotify/glass/TouchSample.java
 A glass/src/test/java/dev/erinlkolp/glassnotify/glass/SwipeDetectorTest.java
```

Commit: `4787a6b feat(glass): add card rendering and the queue screen` on branch `feat/glass-notifications`. Untracked `data/` was deliberately left alone (not staged, not part of this task).

## Self-review

- **Completeness against the brief**: all 11 steps followed in order (failing test → verified RED → TouchSample/Swipe → SwipeDetector → verified GREEN → CardRenderer → Ages → QueueActivity → GlassNotify → manifest → full test+build → commit). Code in every file is a verbatim transcription of the brief's listings; no renames, no added/removed methods, no logic changes.
- **Every test asserts behaviour**: yes — all 9 `SwipeDetectorTest` cases assert a specific `Swipe` verdict (`TAP`, `NONE`, `FORWARD`, `BACK`) against a concrete gesture, including the cancel-in-progress and end-without-begin edge cases. No test is a no-op or trivially true.
- **Colour audit**: grepped `CardRenderer.java` for any colour reference — only `Color.WHITE` (FG) and `Color.BLACK` (BG) appear as actual colour constants; the only other matches are the word "greys" inside a code comment. No `Color.GRAY`, no hex literals, no alpha blending anywhere in the new code.
- **Android-free swipe logic**: confirmed zero `import` statements of any kind in `TouchSample.java`, `Swipe.java`, and `SwipeDetector.java` — these compile and test on the host JVM only.
- **No androidx, no `var`, no forbidden APIs**: grepped the whole `glass/src/main/java/.../glass/` tree for `androidx.` and `var ` — none found. `TextView.setLetterSpacing` (API 21) is used as explicitly permitted by the brief.
- **Two deliberate choices preserved verbatim**: `applyImmersiveFlags()` is called from both `onCreate` and `onWindowFocusChanged` with the brief's exact comment explaining why; `QueueActivity` has no dismiss, no action firing, no long-press handling — `onTouchEvent` only ever calls `cursor.next()`/`cursor.previous()` via `handle(Swipe)`.
- **Manifest**: the previously self-closing `<application ... />` was converted to an open/close pair with the `<activity>` block nested inside exactly as given, `LAUNCHER`/`MAIN` intent filter preserved.

## Concerns

None. Build and tests are fully green, the diff is scoped exactly to the brief's Files list plus the two explicitly-called-out omissions (`Ages.java`, `GlassNotify.java`), and no unrelated files were staged or modified.

---

## Fix report: cursor staleness against SnapshotStore (post-review, Important finding)

### The bug

`QueueActivity` only called `cursor.setSize(store.items().size())` from `onResume()`. `SnapshotStore.apply()` runs on the background Bluetooth service thread and can swap `current` to a smaller `Snapshot` at any time while the activity is foregrounded. The cursor never learned about the new, smaller size, so a swipe could move `cursor.index()` past the end of the now-shorter list and `render()`'s `items.get(index)` would throw `IndexOutOfBoundsException` — reproducible every time the queue shrinks below the wearer's position while browsing, and a direct violation of design §12.3's clamp requirement (which `QueueCursor.setSize` already implements correctly; the bug was that `QueueActivity` never called it outside `onResume()`).

### The fix

`glass/src/main/java/dev/erinlkolp/glassnotify/glass/QueueActivity.java`:

- `render()` now captures `store.items()` once into a local `items`, then sizes the cursor from that same local (`cursor.setSize(items.size())`) before indexing it. Because `SnapshotStore.current` is `volatile` and `Snapshot.items` is immutable, a single captured reference cannot change underneath the method — this is what makes the sequence race-free, not merely less likely to fail.
- `handle(Swipe)` now re-syncs `cursor.setSize(store.items().size())` before deciding whether `next()`/`previous()` is legal, so navigation is judged against the real, current size rather than a remembered one.
- `refresh()` (called from `onResume()`) was simplified to just call `render()`, since `render()` now does its own sizing. The `onResume()` path itself was kept, per the reviewer's instruction not to remove it.

This matches the fix prescribed in the review exactly.

### Test added

`glass/src/test/java/dev/erinlkolp/glassnotify/glass/QueueCursorTest.java` — added `navigatingRightAfterAShrinkStaysInBounds()`, driving the exact regression scenario from the review: `setSize(5)`, advance to index 4, `setSize(2)`, then `previous()`, asserting the resulting index is within `[0, 1]`. This covers `QueueCursor`'s half of the contract on the host JVM.

`QueueActivity`'s half (that it actually calls `setSize` at the right points before indexing) is **verified by compilation and code inspection only** — this project deliberately has no Robolectric and no Android unit-test runtime, so the activity logic cannot be exercised by a host-JVM test. This needs confirmation during hardware bring-up (drive a swipe immediately after a snapshot shrink and confirm no crash).

### Out of scope, left untouched per instruction

- `SwipeDetector.latest` unused field — deferred Minor finding, not addressed.
- No test added in the band where the ratio and raw dx/dy formulas diverge — deferred Minor finding, not addressed.
- No listener added for live-updating the activity when a snapshot arrives while foregrounded — explicitly Task 8's concern, not addressed here.

### Commands and output

`./gradlew :glass:testDebugUnitTest :glass:assembleDebug`

```
> Task :glass:compileDebugJavaWithJavac
Note: .../QueueActivity.java uses or overrides a deprecated API.  (expected — setSystemUiVisibility)
> Task :glass:testDebugUnitTest
> Task :glass:assembleDebug

BUILD SUCCESSFUL in 1s
38 actionable tasks: 8 executed, 30 up-to-date
```

Test counts confirmed from `glass/build/test-results/testDebugUnitTest/*.xml`:

- `QueueCursorTest`: 9 tests (was 8; added `navigatingRightAfterAShrinkStaysInBounds`), 0 failures
- `StalenessTest`: 4 tests, 0 failures
- `SwipeDetectorTest`: 9 tests, 0 failures
- **Total: 22 tests, all passing.**

Confirmed the new regression test actually ran:

```
$ grep -o 'navigatingRightAfterAShrinkStaysInBounds' glass/build/test-results/testDebugUnitTest/*.xml
glass/build/test-results/testDebugUnitTest/TEST-dev.erinlkolp.glassnotify.glass.QueueCursorTest.xml:navigatingRightAfterAShrinkStaysInBounds
```

### Files changed (this fix)

```
 M glass/src/main/java/dev/erinlkolp/glassnotify/glass/QueueActivity.java
 M glass/src/test/java/dev/erinlkolp/glassnotify/glass/QueueCursorTest.java
```

Commit: `b49edc8 fix(glass): re-derive cursor size from live snapshot state` on branch `feat/glass-notifications`.

Note: `git status` also showed `docs/superpowers/plans/2026-08-04-glass-notifications.md` modified in the working tree with a diff matching this same fix, but that edit was not made by me in this session — I did not touch that file, so it was left out of the commit (only `glass/` paths were staged, consistent with the original task's commit scope). It remains as an unstaged working-tree change.

### Concerns

None new. The `QueueActivity` half of the fix is inspection-verified rather than test-verified, as expected given the project's no-Robolectric constraint — flagging this for hardware bring-up confirmation as instructed.
