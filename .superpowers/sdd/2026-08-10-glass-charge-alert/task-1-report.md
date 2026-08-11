# Task 1 Report: The wire message

## Status: DONE

## What was implemented

Transcribed verbatim from `task-1-brief.md`:

- `wire/src/main/java/dev/erinlkolp/glassnotify/wire/MessageType.java` — modified to add
  `public static final int GLASS_STATE = 4;` with the direction-of-travel Javadoc.
- `wire/src/main/java/dev/erinlkolp/glassnotify/wire/GlassState.java` — new immutable value
  type: `public final int batteryLevel`, `public final boolean onPower`, constructor validates
  `0 <= batteryLevel <= 100` and throws `IllegalArgumentException` otherwise, plus
  `equals`/`hashCode`/`toString`.
- `wire/src/main/java/dev/erinlkolp/glassnotify/wire/GlassStateCodec.java` — new codec with
  `encode(GlassState) -> byte[]` (2-byte body: unsigned byte level, boolean onPower) and
  `decode(byte[]) -> GlassState`, which raises `ProtocolException` (not
  `IllegalArgumentException`) when the level byte exceeds 100.
- `wire/src/test/java/dev/erinlkolp/glassnotify/wire/GlassStateTest.java` — new, 7 tests.
- `wire/src/test/java/dev/erinlkolp/glassnotify/wire/GlassStateCodecTest.java` — new, 6 tests.

No other files were touched. `Protocol.VERSION` was not modified. No changes to
`SnapshotCodec`, `Snapshot`, `NotificationItem`, `Hello`, `HelloCodec`, `FrameCodec`, `Frame`,
`Tier`, or `Protocol`. No `android.*` imports were introduced. Java 8 style preserved
(no lambdas, all `if` bodies braced).

## TDD sequence and actual command output

### Step 2 — run tests before implementation, confirm expected failure

Command: `./gradlew :wire:test --tests '*GlassState*'`

Result: **FAILED**, as expected — compilation error, `cannot find symbol: class GlassState`
(35 errors total across `GlassStateTest.java`), task `:wire:compileTestJava` failed. This
confirms the tests fail for the correct reason (missing production classes), not a typo in
the test files.

### Step 6 — run focused tests after implementation

Command: `./gradlew :wire:test --tests '*GlassState*'`

Result: `BUILD SUCCESSFUL in 1s`, 3 actionable tasks: 3 executed.

Verified via the JUnit XML reports directly (since Gradle didn't print a summary line):

```
wire/build/test-results/test/TEST-dev.erinlkolp.glassnotify.wire.GlassStateTest.xml
  tests="7" skipped="0" failures="0" errors="0"
wire/build/test-results/test/TEST-dev.erinlkolp.glassnotify.wire.GlassStateCodecTest.xml
  tests="6" skipped="0" failures="0" errors="0"
```

7 + 6 = **13 tests, all passing** — matches the brief's expected count exactly.

### Step 7 — full suite regression check

Command: `./gradlew test`

Result: `BUILD SUCCESSFUL in 1s`, 86 actionable tasks: 10 executed, 76 up-to-date.

Per-module counts, one build variant only (per the counting gotcha in the task
instructions — summing both `testDebugUnitTest` and `testReleaseUnitTest` for `glass`/`phone`
would double-count):

```
wire  (wire/build/test-results/test/TEST-*.xml):                       58 tests
glass (glass/build/test-results/testDebugUnitTest/TEST-*.xml):         32 tests
phone (phone/build/test-results/testDebugUnitTest/TEST-*.xml):         25 tests
```

All three module result sets had `failures="0" errors="0"` in every `TEST-*.xml` file (checked
with a `grep -L` for files *not* matching that pattern — none found). `wire` grew from 45 to 58
(+13, exactly the new GlassState/GlassStateCodec tests). `glass` (32) and `phone` (25) are
unchanged, confirming zero regression.

## Commit

SHA: `7bcb3064436d6dea2b494c875d5ea62e3319c95b`

Message (exact text from the brief, used verbatim):

```
feat(wire): add the GLASS_STATE message

The first Glass -> phone message in the protocol. Carries battery level
and whether Glass is on power, in a two-byte body.

Protocol.VERSION deliberately does not move. Unknown frame types are
already ignored on both sides, so an old build paired with a new one goes
quiet rather than refusing the link - which is what a version bump would
do instead.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
```

Only the five files listed in the brief's Step 8 were staged and committed:
`GlassState.java`, `GlassStateCodec.java`, `MessageType.java`, `GlassStateTest.java`,
`GlassStateCodecTest.java`. 5 files changed, 242 insertions(+), 1 deletion(-).

## Surprises / deviations

- The repository had a pre-existing untracked `data/` directory at the start of the session
  (visible in `git status` both before and after this task, per the initial git snapshot). It
  is unrelated to this task and was left untouched — not staged, not committed.
- `./gradlew test` and `./gradlew :wire:test` do not print a per-suite pass/fail count to
  stdout by default (only `BUILD SUCCESSFUL`/`BUILD FAILED`); test counts were confirmed by
  reading the generated `TEST-*.xml` JUnit reports directly, consistent with the "counting
  tests" gotcha called out in the task instructions.
- No other deviations. The brief's source was transcribed exactly as given — no renames,
  reformatting, or additions.
