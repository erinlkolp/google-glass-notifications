# Task 5 Report: Snapshot Store and Peer Pinning

## What Was Implemented

Implemented three files as specified in the task brief:

1. **SnapshotStore.java** — Manages notification snapshot caching:
   - Holds current snapshot in memory with `current()` method
   - Mirrors snapshots to disk via `persist()` for resilience across Bluetooth dropouts and service restarts
   - Implements staleness detection via static `isStale(long, long)` rule (30-second silence threshold)
   - Provides `load()` to restore cached snapshots (deliberately does NOT mark contact, keeping restored data stale)
   - Logs and swallows failed cache writes to prevent losing live connection
   - Uses `SystemClock.elapsedRealtime()` for time tracking

2. **PeerPin.java** — Trust-on-first-use device pinning:
   - Pins the first device's Bluetooth address; refuses connections from others
   - `isAllowed()` returns true for no-yet-pinned state or matching address (case-insensitive)
   - `pinIfUnset()` records address if unpinned; does nothing otherwise
   - Uses `.commit()` not `.apply()` to ensure durability before connection proceeds
   - Provides `clear()` for reset path (spec section 11.1)

3. **StalenessTest.java** — Unit test for staleness rule:
   - Tests fresh contact (not stale)
   - Tests threshold crossing (stale after 30 seconds)
   - Tests clock-backwards edge case (not treated as stale)
   - Tests sentinel value `NEVER` (always stale until contact made)

## Testing and Results

### TDD Evidence

**RED Phase:** Test run before implementation:
```bash
./gradlew :glass:testDebugUnitTest --tests '*StalenessTest*'
```
Failed with:
```
error: cannot find symbol
  symbol:   variable SnapshotStore
  location: class StalenessTest
```
This was expected — `SnapshotStore` class did not exist yet.

**GREEN Phase:** Test run after implementation:
```bash
./gradlew :glass:testDebugUnitTest --rerun-tasks
```
Result:
```
BUILD SUCCESSFUL in 1s
```

**Test Results:**
- StalenessTest: 4 tests, 0 failures, 0 errors
  - `aClockThatWentBackwardsIsNotTreatedAsStale` ✓
  - `goesStaleAfterTheThreshold` ✓
  - `freshContactIsNotStale` ✓
  - `neverContactedIsStale` ✓
- QueueCursorTest: 8 tests (from Task 4, unchanged) ✓
- **Total: 12 tests passing**

### Android Compilation Check

```bash
./gradlew :glass:assembleDebug
```
Result:
```
BUILD SUCCESSFUL in 926ms
33 actionable tasks: 3 executed, 30 up-to-date
```

The Android-touching code (uses `android.os.SystemClock`, `android.util.Log`, `android.content.SharedPreferences`) compiles without errors. All API calls are within the ceiling of API 22 (minSdk/targetSdk for the glass module).

## Files Changed

Created (committed):
- `/home/ekolp/workspace/google-glass-notifications/glass/src/main/java/dev/erinlkolp/glassnotify/glass/SnapshotStore.java` (147 lines)
- `/home/ekolp/workspace/google-glass-notifications/glass/src/main/java/dev/erinlkolp/glassnotify/glass/PeerPin.java` (57 lines)
- `/home/ekolp/workspace/google-glass-notifications/glass/src/test/java/dev/erinlkolp/glassnotify/glass/StalenessTest.java` (54 lines)

Commit: `98483f5` — `feat(glass): add snapshot cache and trust-on-first-use peer pin`

## Self-Review Findings

### Completeness Against Brief
- ✓ All three files created with exact code from brief
- ✓ No naming changes, no "improvements"
- ✓ All public methods and constants match interface spec
- ✓ TDD order followed: test first, then RED, then implementation, then GREEN
- ✓ Commit message matches brief exactly
- ✓ No new manifest components registered (as required)

### Test Assertions
All four test methods assert behavior correctly:

1. **freshContactIsNotStale**: Asserts `isStale()` returns `false` for same-time contact and recent contact (5s apart). Tests the non-stale happy path.

2. **goesStaleAfterTheThreshold**: Asserts `isStale()` returns `false` at threshold - 1ms and `true` at threshold and beyond. Verifies the exact boundary.

3. **aClockThatWentBackwardsIsNotTreatedAsStale**: Asserts backward clock (10_000 to 9_000) returns `false`. Defensive against clock jitter.

4. **neverContactedIsStale**: Asserts the sentinel value `NEVER` always returns `true` regardless of current time. Ensures stale-on-startup behavior.

### Implementation Notes
- Staleness rule is split correctly: static form (pure, testable) and instance form (calls `SystemClock.elapsedRealtime()`). The static form is what the test exercises.
- `load()` correctly does NOT call `markContact()` — restored cached data must read as stale until phone reconnects.
- Failed cache writes are logged at WARN level and swallowed, not propagated.
- `PeerPin` uses `.commit()` for durability (not `.apply()`), ensuring pin is durable before connection proceeds.
- `isAllowed()` uses case-insensitive comparison for Bluetooth addresses (standard practice).

### No Concerns

The implementation:
- Follows all global constraints (Java 8 bytecode, no Kotlin, no AndroidX, API 22 ceiling)
- Uses only standard Android framework types
- Has no compile warnings beyond the Java 21 deprecation warning for Java 8 source/target (pre-existing)
- Passes all tests without modification
- All imports are from correct packages (no mock frameworks, no external libs)

## Summary

Task 5 is complete. The snapshot cache and peer pinning implementations are in place, all 12 tests pass, and the code compiles successfully to APK. The staleness rule is comprehensively tested with edge cases (threshold boundary, clock backward, sentinel value). Both `SnapshotStore` and `PeerPin` will be used by Tasks 8 (RFCOMM server) and beyond.
