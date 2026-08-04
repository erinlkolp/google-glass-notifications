# Task 1 Report: Project scaffold and `wire` model

## Status: DONE

## What I implemented

Exactly the brief's Steps 1-9, verbatim:

1. Copied the Gradle wrapper from `/home/ekolp/workspace/google-glass-gesture-launcher` (`gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties`). Confirmed `distributionUrl` is `gradle-8.9-bin.zip` as expected.
2. Root build files: `settings.gradle.kts` (3 modules: `wire`, `glass`, `phone`), `build.gradle.kts` (AGP 8.7.0, apply false), `gradle.properties` (jvmargs, `android.useAndroidX=false`, parallel builds).
3. `wire/build.gradle.kts`: `java-library` plugin, Java 8 source/target compatibility, `options.release.set(8)` on all `JavaCompile` tasks, JUnit 4.13.2 test dependency.
4. `wire` module source (`wire/src/main/java/dev/erinlkolp/glassnotify/wire/`): `Protocol.java`, `MessageType.java`, `ProtocolException.java`, `Tier.java`, `NotificationItem.java`, `Snapshot.java`, `Hello.java` — all copied verbatim from the brief, including the fixed service UUID `7d9313f0-110b-4d84-8daa-10389eba6b55` and all numeric constants (VERSION=1, MAX_FRAME_BYTES=65536, MAX_ITEMS=20, MAX_TEXT_CHARS=240, MAX_TITLE_CHARS=80).
5. `wire` module tests (`wire/src/test/java/dev/erinlkolp/glassnotify/wire/`): `TierTest.java`, `NotificationItemTest.java`, `SnapshotTest.java` — copied verbatim from the brief.

## TDD evidence

**RED** — `./gradlew :wire:test --console=plain` before any main sources existed:

```
> Task :wire:compileTestJava
...
wire/src/test/java/dev/erinlkolp/glassnotify/wire/TierTest.java:14: error: package Tier does not exist
        assertEquals(1, Tier.INTERRUPT.code);
...
30 errors
3 warnings

FAILURE: Build failed with an exception.
* What went wrong:
Execution failed for task ':wire:compileTestJava'.
> Compilation failed; see the compiler error output for details.
```

Expected: the tests reference `Tier`, `NotificationItem`, and `Snapshot`, none of which existed yet, so `compileTestJava` fails with "cannot find symbol" / "package X does not exist" for each. This matches the brief's Step 4 expectation exactly (compilation errors, not runtime test failures).

**GREEN** — after writing `Protocol.java`, `MessageType.java`, `ProtocolException.java`, `Tier.java`, `NotificationItem.java`, `Snapshot.java`, `Hello.java`, ran `./gradlew clean :wire:test --console=plain`:

```
> Task :wire:compileJava
> Task :wire:processResources NO-SOURCE
> Task :wire:classes
> Task :wire:compileTestJava
> Task :wire:processTestResources NO-SOURCE
> Task :wire:testClasses
> Task :wire:test

BUILD SUCCESSFUL in 1s
4 actionable tasks: 4 executed
```

Confirmed test counts from the generated JUnit XML reports (`wire/build/test-results/test/TEST-*.xml`):
- `TierTest`: `tests="3" failures="0" errors="0"`
- `NotificationItemTest`: `tests="4" failures="0" errors="0"`
- `SnapshotTest`: `tests="4" failures="0" errors="0"`

Total: **11 tests, 11 passed, 0 failures** — matches the brief's Step 8 expectation exactly.

The only compiler warnings seen throughout were the standard `[options] source value 8 is obsolete` / `target value 8 is obsolete` notices from javac on JDK 21 (JDK 21 no longer treats Java 8 as a fully current target but still fully supports compiling to it via `--release 8`) — no errors, no android-related warnings.

## Verification performed

- `grep -rn "android\." wire/src` → no matches. Confirms the `wire` module has zero `android.*` imports as required.
- `./gradlew clean :wire:test --console=plain` (clean full build, not incremental) → `BUILD SUCCESSFUL`, same 11/11 pass result.
- `git status` before staging confirmed `data/` and `.claude/` remained untracked/untouched; only the brief's named paths were `git add`-ed.

## Files changed (commit `c1046ac`)

```
build.gradle.kts
gradle.properties
gradle/wrapper/gradle-wrapper.jar
gradle/wrapper/gradle-wrapper.properties
gradlew
gradlew.bat
settings.gradle.kts
wire/build.gradle.kts
wire/src/main/java/dev/erinlkolp/glassnotify/wire/Hello.java
wire/src/main/java/dev/erinlkolp/glassnotify/wire/MessageType.java
wire/src/main/java/dev/erinlkolp/glassnotify/wire/NotificationItem.java
wire/src/main/java/dev/erinlkolp/glassnotify/wire/Protocol.java
wire/src/main/java/dev/erinlkolp/glassnotify/wire/ProtocolException.java
wire/src/main/java/dev/erinlkolp/glassnotify/wire/Snapshot.java
wire/src/main/java/dev/erinlkolp/glassnotify/wire/Tier.java
wire/src/test/java/dev/erinlkolp/glassnotify/wire/NotificationItemTest.java
wire/src/test/java/dev/erinlkolp/glassnotify/wire/SnapshotTest.java
wire/src/test/java/dev/erinlkolp/glassnotify/wire/TierTest.java
```

18 files changed, 746 insertions.

## Self-review

- **Completeness against brief**: every file listed in the brief's "Files" section exists with the exact path and exact content given. No files were added beyond what the brief specified (no `.gitignore` additions, no extra helper classes, no README).
- **Naming**: matches the brief's interface contract exactly — `Protocol.VERSION/MAX_FRAME_BYTES/MAX_ITEMS/MAX_TEXT_CHARS/MAX_TITLE_CHARS/SERVICE_UUID/SERVICE_NAME`, `MessageType.HELLO/SNAPSHOT/PING`, `Tier.INTERRUPT/QUEUE` with `.code` and `.fromCode()`, `NotificationItem`/`Snapshot`/`Hello` constructors and public final fields all present and correctly typed.
- **YAGNI**: no extra methods, builders, or serialization logic were added beyond what the brief specifies — those are explicitly later tasks (framing, message bodies). `Hello.java` has no accompanying test in this task per the brief, and I did not invent one.
- **Tests verify real behaviour, not the implementation**: `TierTest` asserts the wire codes are the fixed protocol values (1, 2), not just "whatever the enum returns" — this would catch an accidental reordering-via-ordinal bug. `NotificationItemTest` checks value equality and null-rejection on required fields, not just field assignment. `SnapshotTest` specifically tests defensive copying (mutating the caller's source list after construction and confirming it doesn't leak in) and immutability of the returned list — both are real behavioral guarantees, not restatements of "the constructor exists."
- No use of `git add -A`; only the exact paths named in the brief's commit step were staged. `data/` and `.claude/` untouched.

## Concerns

None. The task went exactly as scripted in the brief with no ambiguity encountered beyond the three points already pre-resolved (wrapper copy, JDK 21 compiling to release 8 — which worked without issue, and test count — which matched 11 exactly).
