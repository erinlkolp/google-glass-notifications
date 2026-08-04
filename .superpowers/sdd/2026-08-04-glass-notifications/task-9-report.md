# Task 9 Report: phone Module Scaffold and Snapshot Builder

## What Was Implemented

Created the `phone` module scaffold and the `SnapshotBuilder` class that implements all filtering, tiering, truncation, and ordering decisions for notifications before transmission to Glass. No Android framework types are used, making the entire decision system unit-testable on the host JVM.

### Files Created

1. **`phone/build.gradle.kts`**: AGP 8.7.0 application configuration with minSdk=26, targetSdk=28, compileSdk=34. Depends on `:wire` module and JUnit 4.13.2.

2. **`phone/src/main/AndroidManifest.xml`**: Declares Bluetooth, foreground service, boot completion, and battery optimization permissions. Empty application element with app_name label reference.

3. **`phone/src/main/res/values/strings.xml`**: App strings for display name, notification channel name, connection status messages, and setup instruction labels.

4. **`phone/src/main/java/dev/erinlkolp/glassnotify/phone/SourceNotification.java`**: Immutable value type representing a notification as observed on the phone with Android types stripped. Public final fields: `key`, `packageName`, `appLabel`, `title` (nullable), `text` (nullable), `postedAt`, `ongoing`. Constructor validates that key, packageName, and appLabel are non-null.

5. **`phone/src/main/java/dev/erinlkolp/glassnotify/phone/AllowRule.java`**: Immutable value type for allowlist entries with public final fields `packageName` and `tier`. Constructor validates both fields are non-null.

6. **`phone/src/main/java/dev/erinlkolp/glassnotify/phone/SnapshotBuilder.java`**: Static factory class containing the `build(long snapshotId, List<SourceNotification> sources, Map<String, Tier> allowlist)` method that:
   - Filters out ongoing notifications (persistent status like media players, navigation)
   - Filters out any notification not on the allowlist
   - Sorts eligible notifications newest-first using comparison (not subtraction) to handle epoch-milli overflow
   - Applies stable tie-break on key for deterministic ordering
   - Caps the result at `Protocol.MAX_ITEMS` (20), keeping the newest
   - Truncates title to `Protocol.MAX_TITLE_CHARS` (80) and text to `Protocol.MAX_TEXT_CHARS` (240)
   - Normalizes null titles and text to empty strings (NotificationItem forbids nulls)
   - Returns a `Snapshot` with the snapshotId and unmodifiable list of `NotificationItem`s

7. **`phone/src/test/java/dev/erinlkolp/glassnotify/phone/SnapshotBuilderTest.java`**: 11 unit tests covering all filtering, tiering, truncation, ordering, and edge-case behaviors.

## TDD Evidence

### RED: Initial Test Failure

Command: `./gradlew :phone:testDebugUnitTest`

Expected and observed: **26 compilation errors** - `SourceNotification` and `SnapshotBuilder` classes did not exist. Examples of error messages:
```
error: cannot find symbol
    Snapshot snapshot = SnapshotBuilder.build(1L, sources, ...)
                        ^
  symbol:   variable SnapshotBuilder
```

This confirmed the test was written before implementation, as required by TDD.

### GREEN: All Tests Passing

Command: `./gradlew :phone:testDebugUnitTest :phone:assembleDebug`

**Test Results**: 11/11 tests PASSED (0 failures, 0 errors, 0 skipped)
- ordersNewestFirst: PASSED
- leavesShortTextAlone: PASSED
- toleratesNullTitleAndText: PASSED
- carriesTheSnapshotIdThrough: PASSED
- anEmptyAllowlistProducesAnEmptySnapshot: PASSED
- dropsAnythingNotOnTheAllowlist: PASSED
- capsAtTheProtocolLimitKeepingTheNewest: PASSED
- appliesTheTierFromTheAllowlist: PASSED
- dropsOngoingNotifications: PASSED
- truncatesBodyText: PASSED
- truncatesTitle: PASSED

**Build Status**: BUILD SUCCESSFUL in 1s
- All 38 tasks executed successfully
- `:phone:assembleDebug` completed successfully, proving AGP setup and `:wire` dependency resolution work correctly

## Tests and Assertions

Each test asserts specific behavior:

1. **dropsAnythingNotOnTheAllowlist**: Verifies filtering - only allowlisted packages survive
2. **appliesTheTierFromTheAllowlist**: Verifies tier assignment from the allowlist map
3. **ordersNewestFirst**: Verifies descending sort by `postedAt` timestamp
4. **capsAtTheProtocolLimitKeepingTheNewest**: Verifies cap at 20 items keeps the newest
5. **truncatesBodyText**: Verifies text truncation at 240 characters
6. **truncatesTitle**: Verifies title truncation at 80 characters
7. **leavesShortTextAlone**: Verifies no truncation of text under the limit
8. **dropsOngoingNotifications**: Verifies filtering of persistent status (ongoing=true)
9. **toleratesNullTitleAndText**: Verifies null normalization to empty strings
10. **carriesTheSnapshotIdThrough**: Verifies snapshotId passthrough
11. **anEmptyAllowlistProducesAnEmptySnapshot**: Verifies empty allowlist results in empty snapshot

## Commit

Commit: `474deb5` "feat(phone): add module scaffold and the snapshot builder"

The commit includes:
- 7 files created
- 363 insertions
- Full module structure with build configuration, manifest, resources, and implementation classes
- Complete test suite

## Self-Review Findings

### Completeness Against Brief
- [x] All 7 files created as specified
- [x] build.gradle.kts matches brief exactly (dependencies, minSdk, targetSdk, compileSdk, Java 8)
- [x] Manifest and strings.xml match brief exactly
- [x] SourceNotification class implements exact interface with public final fields
- [x] AllowRule class implements exact interface with public final fields
- [x] SnapshotBuilder.build() implements all filtering, sorting, truncation, and capping behaviors
- [x] All 11 tests pass
- [x] Both `:phone:testDebugUnitTest` and `:phone:assembleDebug` successful
- [x] Commit message matches brief
- [x] No Android imports in SnapshotBuilder, SourceNotification, or AllowRule (host JVM testable)

### Behavior Verification
- Filtering: Only allowlisted packages appear in output (test: dropsAnythingNotOnTheAllowlist)
- Ongoing: Persistent status notifications never reach output (test: dropsOngoingNotifications)
- Truncation: Text limited to 240 chars, title to 80 chars (tests: truncatesBodyText, truncatesTitle)
- Ordering: Descending by postedAt with deterministic tie-break (test: ordersNewestFirst)
- Capping: Output limited to 20 items, newest survive (test: capsAtTheProtocolLimitKeepingTheNewest)
- Normalization: Null title/text become empty strings (test: toleratesNullTitleAndText)
- Sort algorithm: Uses comparison, not subtraction, to avoid overflow (verified in code review)
- Tier application: Tier from allowlist map is passed to NotificationItem (test: appliesTheTierFromTheAllowlist)

### Code Quality
- No unnecessary imports
- Null checks in constructors prevent invalid state
- Comments explain the "why" for key decisions (battery optimization, overflow, etc.)
- Consistent style matches wire module
- Immutable value types for thread safety and testability
- Comparator is explicit about newest-first ordering with explanation

## Concerns

None. The implementation:
1. Matches the brief exactly with no omissions or deviations
2. Passes all 11 tests with 100% success rate
3. Compiles to APK successfully with proper AGP configuration
4. Uses only Java 8 features with no modern language constructs
5. Contains no Android framework types, enabling host JVM testing
6. Follows the deliberate design decisions documented in the brief
